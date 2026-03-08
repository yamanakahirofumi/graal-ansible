import json
import sys
import os
import types

# 1. Early path setup
if 'site_packages_java' in globals():
    for p in site_packages_java:
        if p not in sys.path:
            sys.path.insert(0, p)

# 2. Early System Mocks (before any ansible imports)
def mock_module(name, attrs=None):
    if name not in sys.modules:
        m = types.ModuleType(name)
        sys.modules[name] = m
    else:
        m = sys.modules[name]
    if m is not None and attrs:
        for k, v in attrs.items():
            if not hasattr(m, k): setattr(m, k, v)
    return m

# Mock Display early
mock_module('ansible.utils.display', {
    'Display': type('Display', (), {k: (lambda *a, **kw: None) for k in ['__init__', 'display', 'debug', 'verbose', 'error', 'warning', 'system_warning', 'deprecated']})
})

# Mock syslog comprehensively
syslog_attrs = {k: 1 for k in ['LOG_PID', 'LOG_CONS', 'LOG_NDELAY', 'LOG_NOWAIT', 'LOG_PERROR', 'LOG_KERN', 'LOG_USER', 'LOG_MAIL', 'LOG_DAEMON', 'LOG_AUTH', 'LOG_SYSLOG', 'LOG_LPR', 'LOG_NEWS', 'LOG_UUCP', 'LOG_CRON', 'LOG_AUTHPRIV', 'LOG_FTP', 'LOG_LOCAL0', 'LOG_LOCAL1', 'LOG_LOCAL2', 'LOG_LOCAL3', 'LOG_LOCAL4', 'LOG_LOCAL5', 'LOG_LOCAL6', 'LOG_LOCAL7', 'LOG_EMERG', 'LOG_ALERT', 'LOG_CRIT', 'LOG_ERR', 'LOG_WARNING', 'LOG_NOTICE', 'LOG_INFO', 'LOG_DEBUG']}
syslog_attrs.update({k: (lambda *a, **kw: None) for k in ['syslog', 'openlog', 'closelog', 'setlogmask']})
mock_module('syslog', syslog_attrs)

# Mock other OS-specific modules
mock_module('fcntl', {k: (lambda *a, **kw: None) for k in ['fcntl', 'ioctl', 'flock']})
mock_module('termios', {'TCSAFLUSH': 1, 'tcgetattr': lambda fd: [0,0,0,0, ' ', ' ', []], 'tcsetattr': lambda fd, opt, mode: None})

import collections
passwd = collections.namedtuple('passwd', ['pw_name', 'pw_passwd', 'pw_uid', 'pw_gid', 'pw_gecos', 'pw_dir', 'pw_shell'])
group = collections.namedtuple('group', ['gr_name', 'gr_passwd', 'gr_gid', 'gr_mem'])
mock_module('grp', {'getgrnam': lambda x: group('root', 'x', 0, []), 'getgrgid': lambda x: group('root', 'x', 0, [])})
mock_module('pwd', {'getpwnam': lambda x: passwd('root', 'x', 0, 0, 'root', '/root', '/bin/bash'), 'getpwuid': lambda x: passwd('root', 'x', 0, 0, 'root', '/root', '/bin/bash')})

if not hasattr(os, 'geteuid'): os.geteuid = lambda: 0

# 3. JSON Encoder for Ansible-specific types
class AnsibleEncoder(json.JSONEncoder):
    def default(self, obj):
        if isinstance(obj, (set, range)): return list(obj)
        try: return super().default(obj)
        except TypeError: return str(obj)

# 4. Import and Monkeypatch Ansible
try:
    import ansible.module_utils.basic as basic
    import ansible.module_utils.distro as distro
    import ansible.module_utils.common.process as process
    import ansible.module_utils

    # Ensure modules are available as attributes
    setattr(ansible.module_utils, 'basic', basic)
    setattr(ansible.module_utils, 'distro', distro)

    distro.id = lambda: 'debian'
    distro.version = lambda: '12'
    process.get_bin_path = lambda *args, **kwargs: '/usr/bin/' + args[0] if args else None

    # Setup module arguments
    complex_args = dict(complex_args_java) if complex_args_java else {}

    # Global _load_params mock
    basic._load_params = lambda: (complex_args, 'main')

    # Class-level monkeypatching of AnsibleModule
    def mocked_load_params(self):
        self.params = complex_args
        return (complex_args, 'main')
    basic.AnsibleModule._load_params = mocked_load_params
    basic.AnsibleModule._check_locale = lambda self: None
    basic.AnsibleModule.run_command = lambda self, *args, **kwargs: (0, '', '')
    basic.AnsibleModule.get_bin_path = lambda self, *args, **kwargs: '/usr/bin/' + args[0] if args else None

    def mocked_record_module_result(self, o):
        sys._ansible_module_result = json.dumps(o, cls=AnsibleEncoder)
    basic.AnsibleModule._record_module_result = mocked_record_module_result

    from ansible.plugins.loader import module_loader

    def run_module():
        if 'module_name' not in globals():
            return json.dumps({'failed': True, 'msg': 'module_name not provided to launcher'})

        path = module_loader.find_plugin(module_name)
        if not path:
            return json.dumps({'failed': True, 'msg': f'Module {module_name} not found'})

        from io import StringIO
        old_stdout, sys.stdout = sys.stdout, StringIO()
        try:
            with open(path, 'rb') as f:
                code = compile(f.read(), path, 'exec')
            try:
                sys._ansible_module_result = None
                # Set globals for module execution
                mod_globals = {
                    '__name__': '__main__',
                    '__file__': path,
                    '__package__': 'ansible.modules',
                    '_module_fqn': f'ansible.modules.{module_name}'
                }
                # Inject metadata into __main__ for modules that import it
                main_mod = sys.modules['__main__']
                for k, v in mod_globals.items():
                    setattr(main_mod, k, v)

                exec(code, mod_globals)
                return getattr(sys, '_ansible_module_result', None) or sys.stdout.getvalue()
            except SystemExit:
                return getattr(sys, '_ansible_module_result', None) or sys.stdout.getvalue()
            except Exception as e:
                import traceback
                return json.dumps({'failed': True, 'msg': f'Execution error: {str(e)}', 'traceback': traceback.format_exc()})
        finally:
            sys.stdout = old_stdout

    result = run_module()
except Exception as e:
    import traceback
    result = json.dumps({'failed': True, 'msg': f'Launcher error: {str(e)}', 'traceback': traceback.format_exc()})
