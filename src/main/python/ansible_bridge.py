import json
import sys
import os
import types
from io import StringIO

# Early mocks for native modules to avoid ApiInitException in GraalPy
def early_mocks():
    for mname in ['_posixsubprocess', 'fcntl', 'resource', 'cryptography', 'yaml._yaml', 'markupsafe._speedups', 'selinux']:
        if mname not in sys.modules or sys.modules[mname] is None:
            m = types.ModuleType(mname)
            if mname == '_posixsubprocess':
                m.fork_exec = lambda *a: 0
                m.cloexec_pipe = lambda: (0, 0)
            elif mname == 'fcntl':
                m.fcntl = m.ioctl = m.flock = m.lockf = lambda *a: 0
            elif mname == 'resource':
                m.getrlimit = lambda *a: (1024, 1024)
                m.RLIMIT_NOFILE = 7
            sys.modules[mname] = m

early_mocks()

# Global context to hold current task state
_current_task_context = {
    'complex_args': {},
    'connection_java': None,
    'become_context_java': None,
    'environment_java': None
}

def bind_task(complex_args, connection_java, become_context_java, environment_java):
    _current_task_context['complex_args'] = complex_args
    _current_task_context['connection_java'] = connection_java
    _current_task_context['become_context_java'] = become_context_java
    _current_task_context['environment_java'] = environment_java

def setup_sys_path(site_packages):
    if not site_packages: return
    for p in site_packages:
        if p not in sys.path: sys.path.append(str(p))

def setup_env(env_vars):
    if not env_vars: return
    for k, v in dict(env_vars).items(): os.environ[str(k)] = str(v)

def mock_problematic_modules():
    if getattr(sys, '_ansible_bridge_mocks_applied', False): return

    # markupsafe
    if 'markupsafe' not in sys.modules or sys.modules['markupsafe'] is None:
        m = types.ModuleType('markupsafe')
        m.escape = lambda s: s
        m.soft_str = m.soft_unicode = m.Markup = str
        class EscapeFormatter: pass
        m.EscapeFormatter = EscapeFormatter
        sys.modules['markupsafe'] = m

    # Display
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

    # System modules
    import collections
    passwd = collections.namedtuple('passwd', ['pw_name', 'pw_passwd', 'pw_uid', 'pw_gid', 'pw_gecos', 'pw_dir', 'pw_shell'])
    group = collections.namedtuple('group', ['gr_name', 'gr_passwd', 'gr_gid', 'gr_mem'])

    for mname, nt in [('grp', group), ('pwd', passwd)]:
        if mname not in sys.modules or sys.modules[mname] is None:
            m = types.ModuleType(mname)
            if mname == 'grp':
                m.getgrnam = m.getgrgid = lambda *args: nt('root', 'x', 0, [])
            else:
                m.getpwnam = m.getpwuid = lambda *args: nt('root', 'x', 0, 0, 'root', '/root', '/bin/bash')
            m.getgrall = m.getpwall = lambda: []
            sys.modules[mname] = m

    if 'termios' not in sys.modules:
        m = types.ModuleType('termios')
        m.TCSAFLUSH = 1
        m.tcgetattr = lambda *args: [0,0,0,0, ' ', ' ', []]
        m.tcsetattr = lambda *args: None
        sys.modules['termios'] = m
    if 'syslog' not in sys.modules:
        m = types.ModuleType('syslog')
        m.openlog = m.syslog = m.closelog = m.setlogmask = lambda *args: None
        sys.modules['syslog'] = m

    # Lightweight mocks for core classes to bypass heavy imports in Action Plugins
    if 'ansible.plugins.action' not in sys.modules:
        action_mod = types.ModuleType('ansible.plugins.action')
        class ActionBase:
            def __init__(self, task, connection, play_context, loader, templar, shared_loader_obj):
                self._task, self._connection, self._play_context = task, connection, play_context
                self._loader, self._templar = loader, templar
                from ansible.utils.display import display
                self._display = display
            def run(self, tmp=None, task_vars=None): return {}
            def validate_argument_spec(self, *args, **kwargs): return None, self._task.args
            def _execute_module(self, *args, **kwargs): return {}
            def _remove_tmp_path(self, *args, **kwargs): pass
        action_mod.ActionBase = ActionBase
        sys.modules['ansible.plugins.action'] = action_mod

    if 'ansible.playbook.task' not in sys.modules:
        task_mod = types.ModuleType('ansible.playbook.task')
        class Task:
            def __init__(self): self.action, self.args, self.async_val = None, {}, 0
        task_mod.Task = Task
        sys.modules['ansible.playbook.task'] = task_mod

    if 'ansible.playbook.play_context' not in sys.modules:
        pc_mod = types.ModuleType('ansible.playbook.play_context')
        class PlayContext:
            def __init__(self): self.check_mode = False
        pc_mod.PlayContext = PlayContext
        sys.modules['ansible.playbook.play_context'] = pc_mod

    if 'ansible.template' not in sys.modules:
        template_mod = types.ModuleType('ansible.template')
        class Engine:
            def __init__(self, tvars): self.tvars = tvars
            def extend(self, *args, **kwargs): return self
            def evaluate_expression(self, expr, *args, **kwargs): return self.tvars.get(expr, expr)
        class Templar:
            def __init__(self, loader=None, variables=None): self._engine = Engine(variables or {})
            def template(self, msg, *args, **kwargs): return msg
        template_mod.Templar = Templar
        sys.modules['ansible.template'] = template_mod

    sys._ansible_bridge_mocks_applied = True

def patch_ansible():
    if getattr(sys, '_ansible_bridge_patched', False): return
    try:
        import ansible.module_utils.basic
    except ImportError:
        # Try to make module_utils available even if basic is missing
        if 'ansible.module_utils' not in sys.modules:
             sys.modules['ansible.module_utils'] = types.ModuleType('ansible.module_utils')
        return

    # Ensure basic is attached to module_utils
    import ansible.module_utils
    if not hasattr(ansible.module_utils, 'basic'):
        ansible.module_utils.basic = sys.modules['ansible.module_utils.basic']

    # JSON handling with foreign object support
    if not hasattr(json, '_graal_ansible_patched'):
        class AnsibleEncoder(json.JSONEncoder):
            def default(self, o):
                if isinstance(o, (set, frozenset, range)): return list(o)
                try:
                    if hasattr(o, '__iter__') and not isinstance(o, (str, bytes)):
                        if hasattr(o, 'keys'): return dict(o)
                        return list(o)
                except: pass
                return str(o)
        _original_json_dumps = json.dumps
        def mocked_json_dumps(obj, **kwargs):
            if 'cls' not in kwargs and 'default' not in kwargs: kwargs['cls'] = AnsibleEncoder
            return _original_json_dumps(obj, **kwargs)
        json.dumps = mocked_json_dumps
        json._graal_ansible_patched = True

    # Patch AnsibleModule
    ansible.module_utils.basic._load_params = lambda: (_current_task_context['complex_args'], 'main')
    def mocked_load_params(self, *args, **kwargs): self.params = _current_task_context['complex_args']
    ansible.module_utils.basic.AnsibleModule._load_params = mocked_load_params

    def mocked_run_command(self, args, **kwargs):
        conn = _current_task_context['connection_java']
        if conn:
            command = " ".join(args) if isinstance(args, list) else args
            env = dict(_current_task_context['environment_java']) if _current_task_context['environment_java'] is not None else None
            res = conn.execCommand(command, _current_task_context['become_context_java'], env)
            return (res.exitCode(), res.stdout(), res.stderr())
        return (0, '', '')
    ansible.module_utils.basic.AnsibleModule.run_command = mocked_run_command

    sys._ansible_bridge_patched = True

def execute_module(module_name, complex_args, module_code=None):
    import __main__
    __main__._module_fqn = f"ansible.builtin.{module_name}"
    __main__.complex_args = complex_args
    old_stdout = sys.stdout
    sys.stdout = mystdout = StringIO()
    try:
        if module_code:
            exec(module_code, {'complex_args': complex_args, 'ansible_module_results': {}, '__name__': '__main__'})
        else:
            from ansible.plugins.loader import module_loader
            path = module_loader.find_plugin(module_name)
            if not path: return json.dumps({'failed': True, 'msg': f'Module {module_name} not found'})
            with open(path, 'rb') as f:
                code = compile(f.read(), path, 'exec')
            try:
                exec(code, {'__name__': '__main__', '__file__': path, '__package__': 'ansible.modules'})
            except SystemExit: pass
        return mystdout.getvalue()
    except Exception as e:
        import traceback
        return json.dumps({'failed': True, 'msg': str(e), 'traceback': traceback.format_exc()})
    finally:
        sys.stdout = old_stdout

def initialize(site_packages=None, env_vars=None, complex_args=None, connection_java=None, become_context_java=None):
    setup_sys_path(site_packages)
    setup_env(env_vars)
    mock_problematic_modules()
    bind_task(complex_args or {}, connection_java, become_context_java, env_vars)
    patch_ansible()

# Register this module
bridge_mod = types.ModuleType('ansible_bridge')
bridge_mod.__dict__.update(globals())
sys.modules['ansible_bridge'] = bridge_mod
