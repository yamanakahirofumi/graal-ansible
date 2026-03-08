import json
import sys
import os
import types

# Setup sys.path from site_packages_java
for p in site_packages_java:
    if p not in sys.path:
        sys.path.insert(0, p)

# Convert Java Map to native Python dict
complex_args = dict(complex_args_java) if complex_args_java is not None else {}

def mock_module(name, attrs):
    if name not in sys.modules:
        m = types.ModuleType(name)
        for a, v in attrs.items():
            setattr(m, a, v)
        sys.modules[name] = m
    else:
        m = sys.modules[name]
        if m is not None:
            for a, v in attrs.items():
                if not hasattr(m, a):
                    setattr(m, a, v)
    return sys.modules[name]

try:
    # Clean up partially initialized ansible modules from previous runs in the same shared context
    for mname in list(sys.modules.keys()):
        if mname.startswith('ansible'):
            m = sys.modules[mname]
            if m is not None and hasattr(m, '__spec__') and getattr(m.__spec__, '_initializing', False):
                 del sys.modules[mname]

    # Aggressively mock native/problematic modules
    for mname in ['cryptography', 'cryptography.hazmat', 'cryptography.hazmat.bindings', '_cffi_backend', 'yaml._yaml', 'selinux']:
        sys.modules[mname] = None

    # Helper for mocking system modules
    import collections
    passwd = collections.namedtuple('passwd', ['pw_name', 'pw_passwd', 'pw_uid', 'pw_gid', 'pw_gecos', 'pw_dir', 'pw_shell'])
    group = collections.namedtuple('group', ['gr_name', 'gr_passwd', 'gr_gid', 'gr_mem'])

    mock_module('grp', {
        'getgrnam': lambda x: group('root', 'x', 0, []),
        'getgrgid': lambda x: group('root', 'x', 0, [])
    })
    mock_module('pwd', {
        'getpwnam': lambda x: passwd('root', 'x', 0, 0, 'root', '/root', '/bin/bash'),
        'getpwuid': lambda x: passwd('root', 'x', 0, 0, 'root', '/root', '/bin/bash')
    })
    if not hasattr(os, 'geteuid'):
        os.geteuid = lambda: 0

    syslog_attrs = {
        'syslog': lambda *args, **kwargs: None,
        'openlog': lambda *args, **kwargs: None,
        'closelog': lambda *args, **kwargs: None,
        'setlogmask': lambda *args, **kwargs: None,
    }
    for c in ['LOG_PID', 'LOG_CONS', 'LOG_NDELAY', 'LOG_NOWAIT', 'LOG_PERROR',
              'LOG_KERN', 'LOG_USER', 'LOG_MAIL', 'LOG_DAEMON', 'LOG_AUTH', 'LOG_SYSLOG', 'LOG_LPR', 'LOG_NEWS', 'LOG_UUCP', 'LOG_CRON', 'LOG_AUTHPRIV', 'LOG_FTP',
              'LOG_LOCAL0', 'LOG_LOCAL1', 'LOG_LOCAL2', 'LOG_LOCAL3', 'LOG_LOCAL4', 'LOG_LOCAL5', 'LOG_LOCAL6', 'LOG_LOCAL7',
              'LOG_EMERG', 'LOG_ALERT', 'LOG_CRIT', 'LOG_ERR', 'LOG_WARNING', 'LOG_NOTICE', 'LOG_INFO', 'LOG_DEBUG']:
        syslog_attrs[c] = 1
    mock_module('syslog', syslog_attrs)

    mock_module('fcntl', {
        'fcntl': lambda *args: None,
        'ioctl': lambda *args: None,
        'flock': lambda *args: None,
    })

    termios_attrs = {
        'TCSAFLUSH': 1,
        'tcgetattr': lambda fd: [0,0,0,0, ' ', ' ', []],
        'tcsetattr': lambda fd, opt, mode: None,
    }
    mock_module('termios', termios_attrs)

    # Mock ansible.utils.display.Display BEFORE any ansible imports
    display_mod = mock_module('ansible.utils.display', {})
    class Display:
        def __init__(self, *args, **kwargs): pass
        def display(self, *args, **kwargs): pass
        def debug(self, *args, **kwargs): pass
        def verbose(self, *args, **kwargs): pass
        def error(self, *args, **kwargs): pass
        def warning(self, *args, **kwargs): pass
        def system_warning(self, *args, **kwargs): pass
        def deprecated(self, *args, **kwargs): pass
    display_mod.Display = Display

    # Import and monkeypatch basic module utils FIRST
    import ansible.module_utils.basic as basic_mod

    basic_mod._load_params = lambda: (complex_args, 'main')
    def mocked_load_params(self):
        self.params = complex_args

    if hasattr(basic_mod, 'AnsibleModule'):
        basic_mod.AnsibleModule._load_params = mocked_load_params
        basic_mod.AnsibleModule._check_locale = lambda self: None
        basic_mod.AnsibleModule.run_command = lambda self, *args, **kwargs: (0, '', '')
        basic_mod.AnsibleModule.get_bin_path = lambda self, *args, **kwargs: '/usr/bin/' + args[0] if args else None
        def mocked_record_module_result(self, o):
            sys._ansible_module_result = json.dumps(o)
        basic_mod.AnsibleModule._record_module_result = mocked_record_module_result

    # Other monkeypatches
    import ansible.module_utils.distro as distro_mod
    distro_mod.id = lambda: 'debian'
    distro_mod.version = lambda: '12'

    import ansible.module_utils.common.process as process_mod
    process_mod.get_bin_path = lambda *args, **kwargs: '/usr/bin/' + args[0] if args else None

    # Now import module_loader
    from ansible.plugins.loader import module_loader

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
                mod_globals = {
                    '__name__': '__main__',
                    '__file__': path,
                    '__package__': 'ansible.modules',
                    '_module_fqn': f'ansible.modules.{module_name}'
                }
                exec(code, mod_globals)
            except SystemExit:
                pass
            except Exception as e:
                import traceback
                return json.dumps({'failed': True, 'msg': f'Execution error: {str(e)}', 'traceback': traceback.format_exc()})

            return getattr(sys, '_ansible_module_result', None) or mystdout.getvalue()
        finally:
            sys.stdout = old_stdout
    result = run_module()
except Exception as e:
    import traceback
    result = json.dumps({'failed': True, 'msg': f'Launcher error: {str(e)}', 'traceback': traceback.format_exc()})
