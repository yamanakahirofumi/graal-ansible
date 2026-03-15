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
    for mname in ['cryptography', 'cryptography.hazmat', 'cryptography.hazmat.bindings', '_cffi_backend', 'yaml._yaml', 'selinux']:
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
        m.openlog = m.syslog = m.closelog = m.setlogmask = lambda *args, **kwargs: None
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
    ansible.module_utils.distro.id = lambda: 'debian'
    ansible.module_utils.distro.version = lambda: '12'
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
    ansible.module_utils.basic._load_params = lambda: (_current_task_context['complex_args'], 'main')
    def mocked_load_params(self):
        self.params = _current_task_context['complex_args']
    ansible.module_utils.basic.AnsibleModule._load_params = mocked_load_params
    ansible.module_utils.basic.AnsibleModule._check_locale = lambda self: None

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
    ansible.module_utils.basic.AnsibleModule._record_module_result = lambda self, o: print(json.dumps(o))

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
                return json.dumps({'failed': True, 'msg': f'Module {module_name} not found'})

            with open(path, 'rb') as f:
                code = compile(f.read(), path, 'exec')
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
