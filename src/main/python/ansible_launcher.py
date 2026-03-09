import json
import sys
import os
import types

# Setup sys.path from site_packages_java
for p in site_packages_java:
    if p not in sys.path:
        sys.path.append(p)

# Convert Java Map to native Python dict
complex_args = dict(complex_args_java) if complex_args_java is not None else {}

try:
    # Aggressively mock native/problematic modules before any imports
    # Setting to None triggers ImportError, which is better for many libraries
    for mname in ['cryptography', 'cryptography.hazmat', 'cryptography.hazmat.bindings', '_cffi_backend', 'yaml._yaml', 'selinux']:
        sys.modules[mname] = None

    # Mock missing system modules as actual modules
    import collections
    passwd = collections.namedtuple('passwd', ['pw_name', 'pw_passwd', 'pw_uid', 'pw_gid', 'pw_gecos', 'pw_dir', 'pw_shell'])
    group = collections.namedtuple('group', ['gr_name', 'gr_passwd', 'gr_gid', 'gr_mem'])
    if 'grp' not in sys.modules:
        m = types.ModuleType('grp')
        m.getgrnam = m.getgrgid = lambda x: group('root', 'x', 0, [])
        sys.modules['grp'] = m
    if 'pwd' not in sys.modules:
        m = types.ModuleType('pwd')
        m.getpwnam = m.getpwuid = lambda x: passwd('root', 'x', 0, 0, 'root', '/root', '/bin/bash')
        sys.modules['pwd'] = m
    if 'termios' not in sys.modules or sys.modules['termios'] is None:
        m = types.ModuleType('termios')
        m.TCSAFLUSH = 1
        m.tcgetattr = lambda fd: [0,0,0,0, ' ', ' ', []]
        m.tcsetattr = lambda fd, opt, mode: None
        sys.modules['termios'] = m

    # Mock ansible.utils.display.Display to avoid circular imports
    display_mod = types.ModuleType('ansible.utils.display')
    class Display:
        def __init__(self, *args, **kwargs): pass
        def display(self, *args, **kwargs): pass
        def debug(self, *args, **kwargs): pass
        def verbose(self, *args, **kwargs): pass
        def deprecated(self, *args, **kwargs): pass
        def warning(self, *args, **kwargs): pass
        def error(self, *args, **kwargs): pass
    display_mod.Display = Display
    sys.modules['ansible.utils.display'] = display_mod

    from ansible.plugins.loader import module_loader
    import ansible.module_utils.basic
    import ansible.module_utils.distro
    import ansible.module_utils.common.process

    # Monkeypatch to avoid system interaction
    ansible.module_utils.distro.id = lambda: 'debian'
    ansible.module_utils.distro.version = lambda: '12'
    ansible.module_utils.common.process.get_bin_path = lambda *args, **kwargs: '/usr/bin/' + args[0] if args else None

    # Monkeypatch globally before instantiation
    ansible.module_utils.basic._load_params = lambda: (complex_args, 'main')
    def mocked_load_params(self):
        self.params = complex_args
    ansible.module_utils.basic.AnsibleModule._load_params = mocked_load_params
    ansible.module_utils.basic.AnsibleModule._check_locale = lambda self: None
    ansible.module_utils.basic.AnsibleModule.run_command = lambda self, *args, **kwargs: (0, '', '')
    ansible.module_utils.basic.AnsibleModule.get_bin_path = lambda self, *args, **kwargs: '/usr/bin/' + args[0] if args else None

    # Custom JSON encoder to handle sets and other non-serializable objects
    class AnsibleEncoder(json.JSONEncoder):
        def default(self, obj):
            if isinstance(obj, (set, frozenset, range)):
                return list(obj)
            return super().default(obj)

    # Monkeypatch json.dumps globally to handle non-serializable objects
    orig_dumps = json.dumps
    def wrapped_dumps(obj, **kwargs):
        if 'cls' not in kwargs:
            kwargs['cls'] = AnsibleEncoder
        return orig_dumps(obj, **kwargs)
    json.dumps = wrapped_dumps

    # Store result in a known location for reliable extraction
    sys._ansible_module_result = None
    def record_result(self, o):
        sys._ansible_module_result = o
        print(json.dumps(o, cls=AnsibleEncoder))
    ansible.module_utils.basic.AnsibleModule._record_module_result = record_result

    def run_module():
        path = module_loader.find_plugin(module_name)
        if not path:
            return json.dumps({'failed': True, 'msg': f'Module {module_name} not found'})
        # Capture stdout
        from io import StringIO
        old_stdout = sys.stdout
        sys.stdout = mystdout = StringIO()
        try:
            with open(path, 'rb') as f:
                code = compile(f.read(), path, 'exec')
            try:
                # Set __package__ to 'ansible.modules' to support relative imports (e.g., in 'setup' module)
                exec(code, {'__name__': '__main__', '__file__': path, '__package__': 'ansible.modules'})
            except SystemExit:
                pass
            except Exception as e:
                import traceback
                return json.dumps({'failed': True, 'msg': f'Execution error: {str(e)}', 'traceback': traceback.format_exc()})

            # Prefer recorded result over stdout capture if available
            if getattr(sys, '_ansible_module_result', None) is not None:
                return json.dumps(sys._ansible_module_result, cls=AnsibleEncoder)
            return mystdout.getvalue()
        finally:
            sys.stdout = old_stdout
    result = run_module()
except ImportError as e:
    result = json.dumps({'failed': True, 'msg': f'Import error: {str(e)}'})
