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

if not hasattr(sys, '_ansible_launcher_initialized'):
    sys._ansible_launcher_initialized = True
else:
    # Already initialized globally, but we still need to run the module
    pass

try:
    # Aggressively mock native/problematic modules before any imports
    # Setting to None triggers ImportError, which is better for many libraries
    for mname in ['cryptography', 'cryptography.hazmat', 'cryptography.hazmat.bindings', '_cffi_backend', 'yaml._yaml', 'selinux']:
        sys.modules[mname] = None

    # Mock Display to avoid circular imports in GraalPy
    if 'ansible.utils.display' not in sys.modules:
        display_mod = types.ModuleType('ansible.utils.display')
        class Display:
            def __init__(self, *args, **kwargs):
                self.verbosity = 0
                self.columns = 79
                self.color = False
            def display(self, *args, **kwargs): pass
            def debug(self, *args, **kwargs): pass
            def verbose(self, *args, **kwargs): pass
            def warning(self, *args, **kwargs): pass
            def error(self, *args, **kwargs): pass
            def deprecated(self, *args, **kwargs): pass
        display_mod.Display = Display
        display_mod.display = Display()
        sys.modules['ansible.utils.display'] = display_mod

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
    if 'syslog' not in sys.modules:
        m = types.ModuleType('syslog')
        for k, v in {'LOG_PID': 1, 'LOG_CONS': 2, 'LOG_NDELAY': 8, 'LOG_NOWAIT': 16, 'LOG_PERROR': 32,
                     'LOG_KERN': 0, 'LOG_USER': 8, 'LOG_MAIL': 16, 'LOG_DAEMON': 24, 'LOG_AUTH': 32,
                     'LOG_SYSLOG': 40, 'LOG_LPR': 48, 'LOG_NEWS': 56, 'LOG_UUCP': 64, 'LOG_CRON': 72,
                     'LOG_AUTHPRIV': 80, 'LOG_FTP': 88, 'LOG_EMERG': 0, 'LOG_ALERT': 1, 'LOG_CRIT': 2,
                     'LOG_ERR': 3, 'LOG_WARNING': 4, 'LOG_NOTICE': 5, 'LOG_INFO': 6, 'LOG_DEBUG': 7}.items():
            setattr(m, k, v)
        m.openlog = lambda *args, **kwargs: None
        m.syslog = lambda *args, **kwargs: None
        m.closelog = lambda *args, **kwargs: None
        m.setlogmask = lambda *args, **kwargs: 0
        sys.modules['syslog'] = m

    from ansible.plugins.loader import module_loader
    import ansible.module_utils.basic
    # Explicitly ensure basic is available on module_utils for some GraalPy versions
    if not hasattr(ansible.module_utils, 'basic'):
        ansible.module_utils.basic = sys.modules['ansible.module_utils.basic']
    if not hasattr(ansible.module_utils.basic, 'AnsibleModule'):
        # Force reload if partially initialized
        import importlib
        importlib.reload(ansible.module_utils.basic)

    import ansible.module_utils.distro
    import ansible.module_utils.common.process

    # Monkeypatch to avoid system interaction
    ansible.module_utils.distro.id = lambda *args, **kwargs: 'debian'
    ansible.module_utils.distro.version = lambda *args, **kwargs: '12'
    ansible.module_utils.common.process.get_bin_path = lambda *args, **kwargs: '/usr/bin/' + args[0] if args else None

    # Monkeypatch JSON to handle non-serializable objects (like sets from setup module)
    import json
    if not hasattr(json, '_graal_ansible_patched'):
        class AnsibleEncoder(json.JSONEncoder):
            def default(self, o):
                if isinstance(o, (set, frozenset)):
                    return list(o)
                if isinstance(o, range):
                    return list(o)
                return str(o)

        _original_json_dumps = json.dumps
        def mocked_json_dumps(obj, **kwargs):
            if 'cls' not in kwargs:
                kwargs['cls'] = AnsibleEncoder
            return _original_json_dumps(obj, **kwargs)
        json.dumps = mocked_json_dumps
        json._graal_ansible_patched = True

    # Monkeypatch globally before instantiation
    ansible.module_utils.basic._load_params = lambda *args, **kwargs: (complex_args, 'main')
    def mocked_load_params(self, *args, **kwargs):
        self.params = complex_args
    ansible.module_utils.basic.AnsibleModule._load_params = mocked_load_params
    ansible.module_utils.basic.AnsibleModule._check_locale = lambda self, *args, **kwargs: None

    def mocked_run_command(self, args, **kwargs):
        # connection_java is the Java Connection object
        if connection_java:
            if isinstance(args, list):
                command = " ".join(args)
            else:
                command = args
            # Execute via the provided connection (SSH or Local)
            res = connection_java.execCommand(command, become_context_java)
            return (res.exitCode(), res.stdout(), res.stderr())
        return (0, '', '') # Fallback

    ansible.module_utils.basic.AnsibleModule.run_command = mocked_run_command
    ansible.module_utils.basic.AnsibleModule.get_bin_path = lambda self, *args, **kwargs: '/usr/bin/' + args[0] if args else None

    def mocked_record_module_result(self, o):
        sys._ansible_module_result = o
        print(json.dumps(o))
    ansible.module_utils.basic.AnsibleModule._record_module_result = mocked_record_module_result

    def run_module():
        sys._ansible_module_result = None
        path = module_loader.find_plugin(module_name)
        if not path:
            return json.dumps({'failed': True, 'msg': f'Module {module_name} not found'})

        # Inject necessary attributes for modules that import __main__
        import __main__
        if '.' in module_name:
            __main__._module_fqn = module_name
        else:
            __main__._module_fqn = f"ansible.builtin.{module_name}"
        __main__.complex_args = complex_args
        __main__._modlib_path = None

        # Capture stdout
        from io import StringIO
        old_stdout = sys.stdout
        sys.stdout = mystdout = StringIO()
        try:
            with open(path, 'rb') as f:
                code = compile(f.read(), path, 'exec')
            try:
                # Set __package__ to support relative imports in some modules (like setup)
                exec(code, {'__name__': '__main__', '__file__': path, '__package__': 'ansible.modules'})
            except SystemExit:
                pass
            except Exception as e:
                import traceback
                return json.dumps({'failed': True, 'msg': f'Execution error: {str(e)}', 'traceback': traceback.format_exc()})

            if hasattr(sys, '_ansible_module_result') and sys._ansible_module_result is not None:
                return json.dumps(sys._ansible_module_result)
            return mystdout.getvalue()
        finally:
            sys.stdout = old_stdout
    result = run_module()
except ImportError as e:
    result = json.dumps({'failed': True, 'msg': f'Import error: {str(e)}'})
