import json
import sys
import os
import types
from io import StringIO

# Global context to hold current task state, used by monkeypatches
_current_task_context = {
    'complex_args': {},
    'connection_java': None,
    'become_context_java': None,
    'environment_java': None
}

def bind_task(complex_args, connection_java, become_context_java, environment_java):
    """Updates the current task context for the monkeypatches to use."""
    _current_task_context['complex_args'] = complex_args
    _current_task_context['connection_java'] = connection_java
    _current_task_context['become_context_java'] = become_context_java
    _current_task_context['environment_java'] = environment_java

def setup_sys_path(site_packages):
    """Adds Java-provided site-packages to sys.path."""
    if not site_packages:
        return
    for p in site_packages:
        if p not in sys.path:
            sys.path.append(p)

def setup_env(env_vars):
    """Injects Java-provided environment variables into os.environ."""
    if not env_vars:
        return
    for k, v in dict(env_vars).items():
        os.environ[str(k)] = str(v)

def mock_problematic_modules():
    """Mocks modules that cause issues in GraalPy or are missing in certain environments."""
    if getattr(sys, '_ansible_bridge_mocks_applied', False):
        return

    # Aggressively mock native/problematic modules
    for mname in ['cryptography', 'cryptography.hazmat', 'cryptography.hazmat.bindings', '_cffi_backend', 'yaml._yaml', 'selinux', 'markupsafe._speedups']:
        sys.modules[mname] = None

    # Mock Display to avoid circular imports
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

    # Mock missing system modules
    import collections
    passwd = collections.namedtuple('passwd', ['pw_name', 'pw_passwd', 'pw_uid', 'pw_gid', 'pw_gecos', 'pw_dir', 'pw_shell'])
    group = collections.namedtuple('group', ['gr_name', 'gr_passwd', 'gr_gid', 'gr_mem'])

    if 'grp' not in sys.modules:
        m = types.ModuleType('grp')
        def mocked_grp_call(*args, **kwargs): return group('root', 'x', 0, [])
        m.getgrnam = m.getgrgid = mocked_grp_call
        sys.modules['grp'] = m
    if 'pwd' not in sys.modules:
        m = types.ModuleType('pwd')
        def mocked_pwd_call(*args, **kwargs): return passwd('root', 'x', 0, 0, 'root', '/root', '/bin/bash')
        m.getpwnam = m.getpwuid = mocked_pwd_call
        sys.modules['pwd'] = m
    if 'termios' not in sys.modules or sys.modules['termios'] is None:
        m = types.ModuleType('termios')
        m.TCSAFLUSH = 1
        def mocked_tcgetattr(fd, *args, **kwargs): return [0,0,0,0, ' ', ' ', []]
        def mocked_tcsetattr(fd, opt, mode, *args, **kwargs): return None
        m.tcgetattr = mocked_tcgetattr
        m.tcsetattr = mocked_tcsetattr
        sys.modules['termios'] = m
    if 'syslog' not in sys.modules:
        m = types.ModuleType('syslog')
        def mocked_syslog_call(*args, **kwargs): return None
        m.openlog = m.syslog = m.closelog = m.setlogmask = mocked_syslog_call
        # Add common syslog constants
        m.LOG_PID = 0x01
        m.LOG_CONS = 0x02
        m.LOG_NDELAY = 0x08
        m.LOG_NOWAIT = 0x10
        m.LOG_PERROR = 0x20
        m.LOG_KERN = 0
        m.LOG_USER = 1 << 3
        m.LOG_MAIL = 2 << 3
        m.LOG_DAEMON = 3 << 3
        m.LOG_AUTH = 4 << 3
        m.LOG_SYSLOG = 5 << 3
        m.LOG_LPR = 6 << 3
        m.LOG_NEWS = 7 << 3
        m.LOG_UUCP = 8 << 3
        m.LOG_CRON = 9 << 3
        m.LOG_AUTHPRIV = 10 << 3
        m.LOG_FTP = 11 << 3
        m.LOG_LOCAL0 = 16 << 3
        m.LOG_LOCAL1 = 17 << 3
        m.LOG_LOCAL2 = 18 << 3
        m.LOG_LOCAL3 = 19 << 3
        m.LOG_LOCAL4 = 20 << 3
        m.LOG_LOCAL5 = 21 << 3
        m.LOG_LOCAL6 = 22 << 3
        m.LOG_LOCAL7 = 23 << 3
        m.LOG_EMERG = 0
        m.LOG_ALERT = 1
        m.LOG_CRIT = 2
        m.LOG_ERR = 3
        m.LOG_WARNING = 4
        m.LOG_NOTICE = 5
        m.LOG_INFO = 6
        m.LOG_DEBUG = 7
        sys.modules['syslog'] = m

    sys._ansible_bridge_mocks_applied = True

def patch_ansible():
    """Applies monkeypatches to Ansible core classes and utilities."""
    if getattr(sys, '_ansible_bridge_patched', False):
        return

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

    # distro info
    def mocked_distro_id(*args, **kwargs): return 'debian'
    def mocked_distro_version(*args, **kwargs): return '12'
    ansible.module_utils.distro.id = mocked_distro_id
    ansible.module_utils.distro.version = mocked_distro_version
    def mocked_get_bin_path(arg=None, *args, **kwargs):
        return '/usr/bin/' + arg if arg else None
    ansible.module_utils.common.process.get_bin_path = mocked_get_bin_path

    # JSON handling
    if not hasattr(json, '_graal_ansible_patched'):
        class AnsibleEncoder(json.JSONEncoder):
            def default(self, o):
                if isinstance(o, (set, frozenset)): return list(o)
                if isinstance(o, range): return list(o)
                return str(o)

        _original_json_dumps = json.dumps
        def mocked_json_dumps(obj, **kwargs):
            if 'cls' not in kwargs:
                kwargs['cls'] = AnsibleEncoder
            return _original_json_dumps(obj, **kwargs)
        json.dumps = mocked_json_dumps
        json._graal_ansible_patched = True

    # AnsibleModule patching
    def mocked_basic_load_params(*args, **kwargs):
        return (_current_task_context['complex_args'], 'main')
    ansible.module_utils.basic._load_params = mocked_basic_load_params
    ansible.module_utils.basic._ANSIBLE_PROFILE = 'modern'

    def mocked_load_params(self, *args, **kwargs):
        self.params = _current_task_context['complex_args']
    ansible.module_utils.basic.AnsibleModule._load_params = mocked_load_params

    def mocked_check_locale(self, *args, **kwargs): return None
    ansible.module_utils.basic.AnsibleModule._check_locale = mocked_check_locale

    def mocked_run_command(self, args, **kwargs):
        conn = _current_task_context['connection_java']
        if conn:
            command = " ".join(args) if isinstance(args, list) else args
            env = dict(_current_task_context['environment_java']) if _current_task_context['environment_java'] is not None else None
            res = conn.execCommand(command, _current_task_context['become_context_java'], env)
            return (res.exitCode(), res.stdout(), res.stderr())
        return (0, '', '')

    ansible.module_utils.basic.AnsibleModule.run_command = mocked_run_command
    def mocked_mod_get_bin_path(self, arg=None, *args, **kwargs):
        return '/usr/bin/' + arg if arg else None
    ansible.module_utils.basic.AnsibleModule.get_bin_path = mocked_mod_get_bin_path

    def mocked_record_module_result(self, o, *args, **kwargs):
        print(json.dumps(o))
    ansible.module_utils.basic.AnsibleModule._record_module_result = mocked_record_module_result

    sys._ansible_bridge_patched = True

def execute_module(module_name, complex_args, module_code=None):
    """
    Executes an Ansible module.
    If module_code is provided, it executes that code (mock mode).
    Otherwise, it finds and executes the module by name (actual mode).
    """
    import __main__

    # Setup __main__ attributes
    __main__._module_fqn = f"ansible.builtin.{module_name}"
    __main__.complex_args = complex_args
    __main__._modlib_path = None

    old_stdout = sys.stdout
    sys.stdout = mystdout = StringIO()
    try:
        if module_code:
            # Mock mode
            exec(module_code, {'complex_args': complex_args, 'ansible_module_results': {}, '__name__': '__main__'})
        else:
            from ansible.plugins.loader import module_loader
            # Actual mode
            path = module_loader.find_plugin(module_name)
            if not path:
                # Try with ansible.builtin. prefix if not found
                if not module_name.startswith('ansible.builtin.'):
                    path = module_loader.find_plugin(f"ansible.builtin.{module_name}")

            if not path:
                return json.dumps({'failed': True, 'msg': f'Module {module_name} not found'})

            with open(path, 'rb') as f:
                code_text = f.read()
                # lineinfile might have encoding issues or other things that cause ShouldNotReachHere in GraalPy
                # Let's try to decode it explicitly if it's bytes
                if isinstance(code_text, bytes):
                    code_text = code_text.decode('utf-8')
                code = compile(code_text, path, 'exec')
            try:
                exec(code, {'__name__': '__main__', '__file__': path, '__package__': 'ansible.modules'})
            except SystemExit:
                pass
        return mystdout.getvalue()
    except Exception as e:
        import traceback
        return json.dumps({'failed': True, 'msg': f'Execution error: {str(e)}', 'traceback': traceback.format_exc()})
    finally:
        sys.stdout = old_stdout

# Register this module so it can be imported as 'ansible_bridge'
import sys
import types
bridge_mod = types.ModuleType('ansible_bridge')
bridge_mod.__dict__.update(globals())
# Ensure functions are bound to the new module's globals if needed,
# but for simple function calls this update is often enough.
sys.modules['ansible_bridge'] = bridge_mod
