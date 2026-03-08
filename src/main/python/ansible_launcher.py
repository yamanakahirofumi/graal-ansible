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
    ansible.module_utils.basic.AnsibleModule._record_module_result = lambda self, o: print(json.dumps(o))

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
                exec(code, {'__name__': '__main__', '__file__': path})
            except SystemExit:
                pass
            except Exception as e:
                import traceback
                return json.dumps({'failed': True, 'msg': f'Execution error: {str(e)}', 'traceback': traceback.format_exc()})
            return mystdout.getvalue()
        finally:
            sys.stdout = old_stdout
    result = run_module()
except ImportError as e:
    result = json.dumps({'failed': True, 'msg': f'Import error: {str(e)}'})
