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
    if not hasattr(os, 'geteuid'):
        os.geteuid = lambda: 0
    if 'syslog' not in sys.modules:
        m = types.ModuleType('syslog')
        m.syslog = m.openlog = m.closelog = m.setlogmask = lambda *args, **kwargs: None
        m.LOG_PID = m.LOG_CONS = m.LOG_NDELAY = m.LOG_NOWAIT = m.LOG_PERROR = 1
        m.LOG_KERN = m.LOG_USER = m.LOG_MAIL = m.LOG_DAEMON = m.LOG_AUTH = m.LOG_SYSLOG = m.LOG_LPR = m.LOG_NEWS = m.LOG_UUCP = m.LOG_CRON = m.LOG_AUTHPRIV = m.LOG_FTP = 1
        m.LOG_LOCAL0 = m.LOG_LOCAL1 = m.LOG_LOCAL2 = m.LOG_LOCAL3 = m.LOG_LOCAL4 = m.LOG_LOCAL5 = m.LOG_LOCAL6 = m.LOG_LOCAL7 = 1
        m.LOG_EMERG = m.LOG_ALERT = m.LOG_CRIT = m.LOG_ERR = m.LOG_WARNING = m.LOG_NOTICE = m.LOG_INFO = m.LOG_DEBUG = 1
        sys.modules['syslog'] = m
    if 'fcntl' not in sys.modules:
        m = types.ModuleType('fcntl')
        m.fcntl = m.ioctl = m.flock = lambda *args: None
        sys.modules['fcntl'] = m
    if 'termios' not in sys.modules or sys.modules['termios'] is None:
        m = types.ModuleType('termios')
        m.TCSAFLUSH = 1
        m.tcgetattr = lambda fd: [0,0,0,0, ' ', ' ', []]
        m.tcsetattr = lambda fd, opt, mode: None
        sys.modules['termios'] = m

    # Mock ansible.utils.display.Display to avoid circular imports
    if 'ansible.utils.display' not in sys.modules:
        m = types.ModuleType('ansible.utils.display')
        class Display:
            def __init__(self, *args, **kwargs): pass
            def display(self, *args, **kwargs): pass
            def debug(self, *args, **kwargs): pass
            def verbose(self, *args, **kwargs): pass
            def error(self, *args, **kwargs): pass
            def warning(self, *args, **kwargs): pass
            def system_warning(self, *args, **kwargs): pass
            def deprecated(self, *args, **kwargs): pass
        m.Display = Display
        sys.modules['ansible.utils.display'] = m

    from ansible.plugins.loader import module_loader
    import ansible.module_utils
    import ansible.module_utils.basic
    import ansible.module_utils.distro
    import ansible.module_utils.common.process

    basic_mod = sys.modules['ansible.module_utils.basic']
    # Ensure attributes are set on the parent module for modules that use 'import ansible.module_utils.basic'
    setattr(ansible.module_utils, 'basic', basic_mod)
    setattr(ansible.module_utils, 'distro', sys.modules['ansible.module_utils.distro'])
    setattr(ansible.module_utils, 'common', sys.modules['ansible.module_utils.common'])
    distro_mod = sys.modules['ansible.module_utils.distro']
    process_mod = sys.modules['ansible.module_utils.common.process']

    # Monkeypatch to avoid system interaction
    distro_mod.id = lambda: 'debian'
    distro_mod.version = lambda: '12'
    process_mod.get_bin_path = lambda *args, **kwargs: '/usr/bin/' + args[0] if args else None

    # Monkeypatch globally before instantiation
    basic_mod._load_params = lambda: (complex_args, 'main')
    def mocked_load_params(self):
        self.params = complex_args
    basic_mod.AnsibleModule._load_params = mocked_load_params
    basic_mod.AnsibleModule._check_locale = lambda self: None
    basic_mod.AnsibleModule.run_command = lambda self, *args, **kwargs: (0, '', '')
    basic_mod.AnsibleModule.get_bin_path = lambda self, *args, **kwargs: '/usr/bin/' + args[0] if args else None

    def mocked_record_module_result(self, o):
        sys._ansible_module_result = json.dumps(o)
        print(sys._ansible_module_result)
    basic_mod.AnsibleModule._record_module_result = mocked_record_module_result

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
                sys._ansible_module_result = None
                exec(code, {'__name__': '__main__', '__file__': path})
            except SystemExit:
                pass
            except Exception as e:
                import traceback
                return json.dumps({'failed': True, 'msg': f'Execution error: {str(e)}', 'traceback': traceback.format_exc()})

            return getattr(sys, '_ansible_module_result', None) or mystdout.getvalue()
        finally:
            sys.stdout = old_stdout
    result = run_module()
except ImportError as e:
    result = json.dumps({'failed': True, 'msg': f'Import error: {str(e)}'})
