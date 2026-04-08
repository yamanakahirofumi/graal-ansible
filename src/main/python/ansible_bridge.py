import json
import sys
import os
import types
import re
from io import StringIO

# Global context to hold current task state
_current_task_context = {
    'complex_args': {},
    'connection_java': None,
    'become_context_java': None,
    'environment_java': None
}

_last_module_result = None

def _normalize_path(p):
    if p is None: return None
    if isinstance(p, bytes):
        try: s = p.decode('utf-8')
        except: s = p.decode('latin-1', errors='replace')
    elif isinstance(p, str):
        s = p
    else:
        try: s = str(p)
        except: return p

    # Remove leading slashes from Windows absolute paths (e.g. /C:\... -> C:\...)
    temp_s = s
    while True:
        if len(temp_s) > 3 and temp_s[0] in ('/', '\\') and temp_s[2] == ':' and temp_s[1].isalpha():
            temp_s = temp_s[1:]
        elif len(temp_s) > 2 and temp_s[0] in ('/', '\\') and temp_s[1] in ('/', '\\') and temp_s[2] != '\\':
            if len(temp_s) > 3 and temp_s[3] == ':': # Case like //C:
                temp_s = temp_s[1:]
            else:
                break
        else:
            break

    if os.name == 'nt' and ':' in temp_s:
        temp_s = temp_s.replace('/', '\\')

    return temp_s

# Override os functions to automatically normalize paths
_orig_os_makedirs = os.makedirs
def _mock_os_makedirs(name, mode=0o777, exist_ok=False):
    return _orig_os_makedirs(_normalize_path(name), mode, exist_ok)
os.makedirs = _mock_os_makedirs

_orig_os_mkdir = os.mkdir
def _mock_os_mkdir(path, mode=0o777):
    return _orig_os_mkdir(_normalize_path(path), mode)
os.mkdir = _mock_os_mkdir

_orig_os_path_exists = os.path.exists
def _mock_os_path_exists(path):
    return _orig_os_path_exists(_normalize_path(path))
os.path.exists = _mock_os_path_exists

_orig_os_stat = os.stat
def _mock_os_stat(path, *args, **kwargs):
    return _orig_os_stat(_normalize_path(path), *args, **kwargs)
os.stat = _mock_os_stat

def _deep_convert(obj):
    if obj is None: return None
    if isinstance(obj, bool): return obj
    if isinstance(obj, (int, float)): return obj
    if isinstance(obj, (str, bytes)): return _normalize_path(obj)

    # Check for Java objects
    if hasattr(obj, 'getClass'):
        cls_name = obj.getClass().getName()
        if cls_name == 'java.lang.String': return str(obj)
        if cls_name == 'java.lang.Boolean': return bool(obj)
        if 'Integer' in cls_name or 'Long' in cls_name: return int(obj)
        if 'Float' in cls_name or 'Double' in cls_name: return float(obj)

        from java.util import Map, List, Set
        if isinstance(obj, Map):
            res = {}
            try:
                # Use toArray() to avoid iterator issues with some proxies
                for entry in obj.entrySet().toArray():
                    res[str(entry.getKey())] = _deep_convert(entry.getValue())
                return res
            except:
                try:
                    for k in obj.keySet():
                        res[str(k)] = _deep_convert(obj.get(k))
                    return res
                except: return str(obj)
        if isinstance(obj, (List, Set)):
            res = []
            try:
                for i in obj.toArray():
                    res.append(_deep_convert(i))
                return res
            except: return str(obj)

        if 'Path' in cls_name or 'File' in cls_name:
            return _normalize_path(str(obj.toString()))

        if 'TaskResult' in cls_name:
            try:
                data = _deep_convert(obj.data())
                data['failed'] = not obj.success()
                data['changed'] = obj.changed()
                return data
            except: pass

        try: return str(obj)
        except: return obj

    # Regular Python collections
    if isinstance(obj, dict):
        return {str(k): _deep_convert(v) for k, v in obj.items()}
    if isinstance(obj, (list, tuple, set, frozenset)):
        return [_deep_convert(i) for i in obj]
    return obj

def bind_task(complex_args, connection_java, become_context_java, environment_java):
    args = complex_args
    if hasattr(complex_args, 'getClass') and 'Map' in complex_args.getClass().getName():
        from java.util import HashMap
        args = HashMap(complex_args)
    converted_args = _deep_convert(args)
    _current_task_context.update({
        'complex_args': converted_args,
        'connection_java': connection_java,
        'become_context_java': become_context_java,
        'environment_java': environment_java
    })

def setup_sys_path(site_packages):
    if site_packages:
        for p in site_packages:
            p_str = _normalize_path(p)
            if p_str not in sys.path: sys.path.append(p_str)
            for mname in ['ansible', 'ansible.module_utils', 'ansible.module_utils.common', 'ansible.module_utils.compat', 'ansible.module_utils._internal', 'ansible.module_utils.parsing', 'ansible.plugins', 'ansible.plugins.action']:
                if mname in sys.modules:
                    m = sys.modules[mname]
                    if hasattr(m, '__path__') and isinstance(m.__path__, list):
                        rel_path = mname.replace('.', '/')
                        cand = os.path.join(p_str, rel_path)
                        if os.path.exists(cand):
                            if cand not in m.__path__:
                                m.__path__.insert(0, cand)
                            target_file = os.path.join(cand, '__init__.py')
                            if not hasattr(m, '__file__') or not m.__file__ or not os.path.exists(str(m.__file__)):
                                try:
                                    m.__file__ = target_file
                                    m.__dict__['__file__'] = target_file
                                except: pass

def setup_env(env_vars):
    if env_vars:
        for k, v in dict(env_vars).items(): os.environ[str(k)] = str(v)

# --- Mock Classes ---

class MockLoader:
    def __init__(self):
        self.path_finder = None
        self._basedir = os.getcwd()
    def get_basedir(self): return self._basedir
    def set_basedir(self, basedir): self._basedir = basedir
    def get_real_file(self, file_path, decrypt=True): return file_path
    def get_text_file_contents(self, file_path, loader=None):
        fp = _normalize_path(file_path)
        if fp and os.path.exists(fp):
            with open(fp, 'r', encoding='utf-8', errors='surrogateescape') as f:
                return f.read(), True
        return "", False
    def cleanup_tmp_file(self, *args, **kwargs): pass
    def path_dwim(self, path): return _normalize_path(path)
    def load_from_file(self, file_path, *args, **kwargs):
        fp = _normalize_path(file_path)
        if fp and os.path.exists(fp):
            with open(fp, 'r', encoding='utf-8') as f:
                import yaml
                return yaml.safe_load(f)
        return None

class MockShell:
    def __init__(self):
        import tempfile
        self.tmpdir = tempfile.gettempdir()
    def path_has_trailing_slash(self, path): return path.endswith('/') or path.endswith('\\')
    def join_path(self, *args): return os.path.join(*args)
    def expand_user(self, path, *args, **kwargs): return path

class Display:
    def __init__(self, *args, **kwargs):
        self.verbosity = 10
        self.columns = 79
        self.color = False
    def display(self, *args, **kwargs): pass
    def debug(self, *args, **kwargs): pass
    def verbose(self, *args, **kwargs): pass
    def warning(self, *args, **kwargs): pass
    def error(self, *args, **kwargs): pass
    def deprecated(self, *args, **kwargs): pass
    def vvvv(self, *args, **kwargs): pass
Display.verbosity = 10

class PlayContext:
    def __init__(self):
        self.verbosity = 10
        self.check_mode = False
        self.diff = False

class ActionBase:
    def __init__(self, task, connection, play_context, loader, templar, shared_loader_obj):
        self._task, self._connection, self._play_context = task, connection, play_context
        self._loader, self._templar = loader, templar
        self._shared_loader_obj = shared_loader_obj
        self._display = self.display = sys.modules.get('ansible.utils.display', types.SimpleNamespace(display=Display())).display
        self._supports_check_mode = True
        self._supports_async = False
    def run(self, tmp=None, task_vars=None): return {'changed': False, 'failed': False}
    def validate_argument_spec(self, argument_spec, *args, **kwargs):
        res = {}
        input_args = self._task.args or {}
        for k, v in argument_spec.items():
            if k in input_args: res[k] = input_args[k]
            elif isinstance(v, dict) and 'default' in v: res[k] = v['default']
            else: res[k] = None
        return types.SimpleNamespace(error=None, warning=None), res
    def _execute_module(self, module_name=None, module_args=None, tmp=None, task_vars=None, *args, **kwargs):
        m_name = module_name or self._task.action
        m_args = module_args or self._task.args
        if 'task_executor_java' in globals():
            res = task_executor_java.execute_from_python(m_name, m_args, task_vars or {})
            if res is not None:
                r_dict = _deep_convert(res)
                if not isinstance(r_dict, dict): r_dict = {'failed': True, 'msg': 'Module result not a dict'}
                if r_dict.get('failed') and 'exception' not in r_dict:
                    r_dict['exception'] = r_dict.get('msg', 'Module execution failed')
                if 'changed' not in r_dict: r_dict['changed'] = True
                return r_dict
        return {'failed': True, 'msg': 'task_executor_java not available'}
    def _remove_tmp_path(self, *args, **kwargs): pass
    def _find_needle(self, name, needle, *args, **kwargs):
        if needle and 'task_executor_java' in globals():
            res = task_executor_java.resolveLocalPath(needle)
            if res: return _normalize_path(res)
        return _normalize_path(needle)
    def _remote_expand_user(self, path, *args, **kwargs): return path
    def _execute_remote_stat(self, path, all_vars, follow=False, *args, **kwargs):
        import hashlib
        p = _normalize_path(path)
        conn = _current_task_context.get('connection_java')
        if conn:
            try:
                res = conn.execCommand(f"test -d \"{p}\" && echo DIR || (test -f \"{p}\" && echo FILE || echo NO)", _current_task_context.get('become_context_java'), None)
                stdout = str(res.stdout())
                if 'DIR' in stdout: return {'exists': True, 'checksum': None, 'isdir': True, 'isreg': False, 'islnk': False}
                elif 'FILE' in stdout: return {'exists': True, 'checksum': None, 'isdir': False, 'isreg': True, 'islnk': False}
                elif 'NO' in stdout: return {'exists': False, 'checksum': None, 'isdir': False, 'isreg': False, 'islnk': False}
            except: pass
        if os.path.exists(p):
            csum = None
            try:
                with open(p, 'rb') as f: csum = hashlib.sha1(f.read()).hexdigest()
            except: pass
            return {'exists': True, 'checksum': csum, 'isdir': os.path.isdir(p), 'isreg': os.path.isfile(p), 'islnk': os.path.islink(p)}
        return {'exists': False, 'checksum': None, 'isdir': False, 'isreg': False, 'islnk': False}
    def _transfer_file(self, local_path, remote_path):
        conn = _current_task_context['connection_java']
        lp, rp = _normalize_path(local_path), _normalize_path(remote_path)
        if conn:
            from java.nio.file import Paths
            conn.putFile(Paths.get(str(lp)), str(rp))
        import hashlib
        return hashlib.sha1(open(lp, 'rb').read()).hexdigest()
    def _fixup_perms2(self, *args, **kwargs): pass

class Task:
    def __init__(self):
        self.action, self.args, self.async_val = None, {}, 0
        self._origin = types.SimpleNamespace(path=None)
        self.collections, self.tags = [], []
        self.implicit = False
        self.resolved_action = None
        self._parent = types.SimpleNamespace(_play=types.SimpleNamespace(_action_groups={}))
        self.diff, self.check_mode, self.no_log = False, False, False
        self.delegate_to, self.delegate_facts = None, False
        self.environment, self.module_defaults = {}, {}
        self._role, self._original_basename = None, None
    def get_name(self): return "mock_task"
    def copy(self):
        new_task = Task()
        new_task.action, new_task.args, new_task.async_val = self.action, (self.args or {}).copy(), self.async_val
        return new_task

class Templar:
    def __init__(self, loader=None, variables=None):
        self._engine = type('Eng', (), {
            'tvars': variables or {},
            'extend': lambda *a, **kw: self._engine,
            'evaluate_expression': lambda expr, *a, **kw: self._engine.tvars.get(expr, expr),
            'resolve_to_container': lambda x: x
        })
        self.available_variables = variables or {}
        self.environment = {}
    def template(self, msg, *args, **kwargs):
        if msg is None: return None
        if isinstance(msg, (tuple, list)): msg = msg[0]
        if hasattr(msg, 'getClass') and msg.getClass().getName() == 'java.lang.String': msg = str(msg)
        if not isinstance(msg, str): return msg
        try:
            from jinja2 import Template
            t = Template(msg)
            return t.render(**self.available_variables)
        except:
            import re
            def repl(match):
                var_name = match.group(1).strip()
                return str(self.available_variables.get(var_name, match.group(0)))
            return re.sub(r'\{\{\s*(.*?)\s*\}\}', repl, msg)
    def resolve_to_container(self, x): return x
    def evaluate_conditional(self, conditional, *args, **kwargs):
        try: return eval(str(conditional), {}, self.available_variables)
        except: return False
    def copy_with_new_env(self, *args, **kwargs): return self

class AnsibleModule:
    def __init__(self, argument_spec, *args, **kwargs):
        self.params, self.aliases, self._stored_file_args = {}, {}, {}
        input_args = _current_task_context['complex_args'] or {}
        effective_spec = argument_spec.copy() if argument_spec else {}
        for k, v in effective_spec.items():
            if isinstance(v, dict) and 'aliases' in v:
                for alias in v['aliases']: self.aliases[alias] = k
        for k, v in effective_spec.items():
            self.params[k] = v.get('default') if isinstance(v, dict) else None
        for k, v in input_args.items():
            target_key = self.aliases.get(k, k)
            self.params[target_key] = v
        if 'path' in self.params and self.params['path'] is None:
            self.params['path'] = self.params.get('dest') or self.params.get('name')
        self.check_mode = self._debug = self._diff = False
        self.boolean = lambda x, *a, **kw: str(x).lower() in ('yes', 'true', 't', '1', 'on', 'y')
    @property
    def tmpdir(self):
        import tempfile
        return tempfile.gettempdir()
    def exit_json(self, **kwargs):
        global _last_module_result
        if 'changed' not in kwargs: kwargs['changed'] = False
        for k, v in self._stored_file_args.items():
            if k not in kwargs and v is not None: kwargs[k] = v
        _last_module_result = kwargs
        print(json.dumps(kwargs))
        sys.exit(0)
    def fail_json(self, **kwargs):
        global _last_module_result
        kwargs['failed'] = True
        _last_module_result = kwargs
        print(json.dumps(kwargs))
        sys.exit(1)
    def debug(self, msg): pass
    def log(self, msg, **kwargs): pass
    def warn(self, msg): pass
    def deprecate(self, msg, version=None, **kwargs): pass
    def run_command(self, args, **kwargs):
        if isinstance(args, list) and len(args) >= 2 and str(args[0]) == 'getent':
            db, key = str(args[1]), str(args[2]) if len(args) > 2 else None
            if db == 'passwd': return 0, "root:x:0:0:root:/root:/bin/bash\n", ""
            if db == 'group': return 0, "root:x:0:\n", ""
        conn = _current_task_context['connection_java']
        if conn:
            cmd_str = " ".join(args) if isinstance(args, list) else args
            res = conn.execCommand(cmd_str, _current_task_context['become_context_java'], None)
            try: return (int(res.exitCode()), str(res.stdout()), str(res.stderr()))
            except: return (-1, "", str(res))
        return (1, '', 'No connection')
    def get_bin_path(self, arg, *a, **kw): return arg
    def sha1(self, path):
        import hashlib
        try: return hashlib.sha1(open(_normalize_path(path), 'rb').read()).hexdigest()
        except: return None
    def atomic_move(self, src, dest, **kwargs):
        import shutil
        shutil.move(_normalize_path(src), _normalize_path(dest))
    def load_file_common_arguments(self, params, path=None):
        actual_path = path or params.get('path') or params.get('dest') or params.get('name')
        return {'path': _normalize_path(actual_path)}
    def set_fs_attributes_if_different(self, file_args, changed, **kwargs):
        if file_args: self._stored_file_args.update(file_args)
        return changed
    def set_file_attributes_if_different(self, file_args, changed, **kwargs):
        if file_args: self._stored_file_args.update(file_args)
        return changed

def apply_mocks():
    mocks_applied = getattr(sys, '_ansible_bridge_mocks_applied', False)

    def create_mock(mname, attributes=None, is_package=True):
        m = sys.modules.get(mname)
        if not m:
            m = types.ModuleType(mname)
            sys.modules[mname] = m
        if is_package:
            if not hasattr(m, '__path__'): m.__path__ = []
            if not hasattr(m, '__file__'): m.__file__ = os.path.join(os.getcwd(), mname.replace('.', '/'), '__init__.py')
        if attributes:
            for k, v in attributes.items(): setattr(m, k, v)
        return m

    if not hasattr(os, 'geteuid'): os.geteuid = lambda: 0
    if not hasattr(os, 'getuid'): os.getuid = lambda: 0
    for f in ['chown', 'lchown', 'lchmod', 'setegid', 'seteuid', 'setgid', 'setuid']:
        if not hasattr(os, f): setattr(os, f, lambda *a, **kw: None)

    create_mock('_posixsubprocess', {'fork_exec': lambda *a, **kw: 0, 'cloexec_pipe': lambda: (0, 0)}, False)
    create_mock('fcntl', {'fcntl': lambda *a, **kw: 0, 'ioctl': lambda *a, **kw: 0, 'flock': lambda *a, **kw: 0, 'lockf': lambda *a, **kw: 0}, False)
    create_mock('resource', {'getrlimit': lambda *a, **kw: (1024, 1024), 'RLIMIT_NOFILE': 7}, False)
    for m in ['cryptography', 'yaml._yaml', 'markupsafe._speedups', 'selinux']: create_mock(m)
    create_mock('termios', {'TCSAFLUSH': 1, 'tcgetattr': lambda *a, **kw: [0,0,0,0, ' ', ' ', []], 'tcsetattr': lambda *a, **kw: None})
    create_mock('syslog', {'openlog': lambda *a, **kw: None, 'syslog': lambda *a, **kw: None, 'LOG_NOTICE': 5, 'LOG_INFO': 6}, False)
    create_mock('markupsafe', {'escape': lambda s, *a, **kw: s, 'soft_str': str, 'Markup': str})

    # Mock date_time facts to avoid %s issue with strftime
    dt_mod = create_mock('ansible.module_utils.facts.system.date_time')
    class MockDateTimeFactCollector:
        name = 'date_time'
        _fact_ids = set(['date_time'])
        _platform = 'Generic'
        required_facts = set()
        @classmethod
        def platform_match(cls, platform_info): return cls
        def __init__(self, *args, **kwargs):
            self.fact_ids = set([self.name])
            self.fact_ids.update(self._fact_ids)
            self.namespace = kwargs.get('namespace')
        def collect_with_namespace(self, module=None, collected_facts=None):
            facts_dict = self.collect(module=module, collected_facts=collected_facts)
            if self.namespace:
                res = {}
                for k, v in facts_dict.items(): res[self.namespace.prefix + k] = v
                return res
            return facts_dict
        def collect(self, module=None, collected_facts=None):
            import datetime
            now = datetime.datetime.now()
            return {
                'date_time': {
                    'year': now.strftime('%Y'), 'month': now.strftime('%m'), 'weekday': now.strftime('%A'),
                    'weekday_number': now.strftime('%w'), 'week_number': now.strftime('%W'), 'day': now.strftime('%d'),
                    'hour': now.strftime('%H'), 'minute': now.strftime('%M'), 'second': now.strftime('%S'),
                    'epoch': str(int(now.timestamp())), 'epoch_int': str(int(now.timestamp())),
                    'date': now.strftime('%Y-%m-%d'), 'time': now.strftime('%H:%M:%S'),
                    'iso8601_micro': now.isoformat() + 'Z', 'iso8601': now.strftime('%Y-%m-%dT%H:%M:%SZ'),
                    'iso8601_basic': now.strftime('%Y%m%dT%H%M%S%f'), 'tz': 'UTC', 'tz_offset': '+0000'
                }
            }
    dt_mod.DateTimeFactCollector = MockDateTimeFactCollector

    import tempfile
    create_mock('ansible.constants', {
        'DEFAULT_REMOTE_TMP': '/tmp', 'DEFAULT_LOCAL_TMP': tempfile.gettempdir(), 'DEFAULT_KEEP_REMOTE_FILES': False,
        'config': type('Config', (), {'get_config_value': lambda *a, **kw: ['setup']}),
        '_ACTION_SETUP': frozenset(['setup', 'gather_facts'])
    })
    create_mock('ansible.config.manager', {'ConfigManager': type('CM', (), {'get_config_value': lambda *a, **kw: None}), 'ensure_type': lambda x, t: x})
    create_mock('ansible.utils.display', {'Display': Display, 'display': Display(), 'PlayContext': PlayContext})
    create_mock('ansible.utils.plugin_docs', {'get_versioned_doclink': lambda x: ""})
    create_mock('ansible.utils.collection_loader', is_package=True)
    create_mock('ansible.utils.collection_loader._collection_finder', {'_get_collection_metadata': lambda *a: None, '_nested_dict_get': lambda *a: None})
    create_mock('ansible.utils.hashing', {'checksum': lambda p: None, 'secure_hash': lambda p: None})
    create_mock('ansible.utils.path', {'makedirs_safe': lambda p, **kw: os.makedirs(_normalize_path(p), exist_ok=True), 'unfrackpath': _normalize_path, 'is_subpath': lambda p, b: True})

    def mock_merge_hash(a, b, **kw):
        res = a.copy() if a else {}
        if b: res.update(b)
        return res
    create_mock('ansible.utils.vars', {'isidentifier': lambda s: True, 'validate_variable_name': lambda s: True, 'merge_hash': mock_merge_hash, 'combine_vars': lambda a, b, **kw: {**a, **b}})

    create_mock('ansible.errors', {
        'AnsibleError': Exception, 'AnsibleActionFail': type('AAF', (Exception,), {}), 'AnsibleTemplateError': type('ATE', (Exception,), {}),
        'AnsibleAssertionError': type('AAE', (Exception,), {}), 'AnsibleFileNotFound': type('AFNF', (Exception,), {}), 'AnsibleParserError': type('APE', (Exception,), {})
    })

    create_mock('ansible.plugins', {'AnsiblePlugin': type('AnsiblePlugin', (), {})})
    create_mock('ansible.plugins.action', {'ActionBase': ActionBase})

    mod_loader = type('ML', (), {
        'find_plugin': lambda name: None,
        'find_plugin_with_context': lambda *a, **kw: type('Ctxt', (), {'resolved_path': '/mock/path', 'resolved_fqcn': 'ansible.builtin.setup'})
    })
    shared_loader_obj = types.SimpleNamespace(action_loader=types.SimpleNamespace(), module_loader=mod_loader)
    shared_loader_obj.action_loader.get = lambda name, **kw: _create_action_plugin(name, kw.get('task'), kw.get('connection'), kw.get('play_context'), kw.get('loader'), kw.get('templar'), shared_loader_obj)
    shared_loader_obj.module_loader = mod_loader

    create_mock('ansible.plugins.loader', {'action_loader': shared_loader_obj, 'module_loader': mod_loader, 'ps_module_utils_loader': type('PML', (), {}), 'module_utils_loader': type('MUL', (), {'find_plugin': lambda n, **kw: None})})
    create_mock('ansible.playbook.task', {'Task': Task})
    create_mock('ansible.playbook.play_context', {'PlayContext': PlayContext})
    create_mock('ansible.template', {'Templar': Templar, 'trust_as_template': lambda x: x})
    create_mock('ansible._internal._locking')
    create_mock('ansible._internal._ansiballz', {'_builder': type('B', (), {})})
    create_mock('ansible._internal._errors', {'_error_utils': type('EU', (), {'result_dict_from_captured_errors': lambda **kw: {}})})
    create_mock('ansible._internal._datatag', {'SourceWasEncrypted': Exception, '_utils': type('U', (), {})})
    create_mock('ansible._internal._datatag._tags', {'SourceWasEncrypted': Exception, 'Origin': type('Origin', (), {}), 'VaultedValue': type('VaultedValue', (), {}), 'TrustedAsTemplate': type('TrustedAsTemplate', (), {})})
    lc = type('LC', (), {'_AnsibleLazyTemplateDict': type('ALTD', (), {}), '_AnsibleLazyTemplateList': type('ALTL', (), {})})
    create_mock('ansible._internal._templating', {'_template_vars': types.SimpleNamespace(generate_ansible_template_vars=lambda *a, **kw: {}), 'get_text_file_contents': lambda x, **kw: ("mock", True), '_lazy_containers': lc})
    for mname in ['ansible._internal._templating._engine', 'ansible._internal._templating._jinja_bits', 'ansible._internal._templating._jinja_common', 'ansible._internal._templating._utils', 'ansible._internal._templating._marker_behaviors']:
        m = create_mock(mname)
        if mname.endswith('_engine'): m.TemplateEngine, m.TemplateOptions = type('TE', (), {}), type('TO', (), {})
        elif mname.endswith('_jinja_common'): m.UndefinedMarker, m.TruncationMarker = type('UM', (), {}), type('TM', (), {})
        elif mname.endswith('_utils'): m.Omit = type('Omit', (), {})
        elif mname.endswith('_marker_behaviors'): m.ReplacingMarkerBehavior, m.RoutingMarkerBehavior = type('RMB', (), {'emit_warnings': lambda *a: None}), type('RoMB', (), {'__init__': lambda *a, **kw: None})

    for mname in ['ansible', 'ansible.module_utils', 'ansible.module_utils.common', 'ansible.module_utils.compat', 'ansible.module_utils._internal', 'ansible.module_utils.parsing', 'ansible.plugins', 'ansible.plugins.action']:
        create_mock(mname, is_package=True)

    if mocks_applied: return
    create_mock('ansible.module_utils.common.sys_info', {'get_distribution': lambda: 'Linux', 'get_distribution_version': lambda: 'Any', 'get_distribution_codename': lambda: 'Any', 'get_platform_subclass': lambda cls: cls})
    create_mock('ansible.module_utils.compat.version', {'LooseVersion': str, 'StrictVersion': str})
    create_mock('ansible.module_utils.parsing.convert_bool', {'convert_bool': lambda x: str(x).lower() in ('yes', 'true', 't', '1'), 'boolean': lambda x: str(x).lower() in ('yes', 'true', 't', '1'), 'BOOLEANS_TRUE': frozenset(['y', 'yes', 'on', '1', 'true', 't', 1, True]), 'BOOLEANS_FALSE': frozenset(['n', 'no', 'off', '0', 'false', 'f', 0, False])})
    create_mock('ansible.module_utils.basic', {'AnsibleModule': AnsibleModule, '_load_params': lambda: (_current_task_context['complex_args'] or {}, 'main'), 'FILE_COMMON_ARGUMENTS': {}, 'missing_required_lib': lambda *a, **kw: None, 'get_bin_path': lambda arg, *a, **kw: arg, 'is_executable': lambda x: True, 'debug': lambda *a: None, 'sys_info': types.SimpleNamespace(get_distribution=lambda: 'Linux', get_distribution_version=lambda: 'Any', get_distribution_codename=lambda: 'Any')})
    create_mock('grp', {'getgrnam': lambda n: type('G', (), {'gr_gid': 0, 'gr_name': str(n), 'gr_mem': []})(), 'getgrgid': lambda *a: type('G', (), {'gr_name': 'root', 'gr_gid': 0, 'gr_mem': []})(), 'getgrall': lambda: []}, False)
    create_mock('pwd', {'getpwnam': lambda n: type('P', (), {'pw_uid': 0, 'pw_gid': 0, 'pw_dir': '/root', 'pw_shell': '/bin/bash', 'pw_name': str(n), 'pw_gecos': ''})(), 'getpwuid': lambda *a: type('P', (), {'pw_name': 'root', 'pw_uid': 0, 'pw_gid': 0, 'pw_dir': '/root', 'pw_shell': '/bin/bash', 'pw_gecos': ''})(), 'getpwall': lambda: []}, False)

    if not hasattr(json, '_graal_ansible_patched'):
        class AnsibleEncoder(json.JSONEncoder):
            def default(self, o):
                if isinstance(o, bytes): return o.decode('utf-8', errors='replace')
                if isinstance(o, (set, frozenset, range)): return list(o)
                if isinstance(o, Exception): return {'failed': True, 'msg': str(o)}
                try:
                    if hasattr(o, 'keys'): return {str(k): v for k, v in o.items()}
                    if hasattr(o, '__iter__') and not isinstance(o, (str, bytes)): return list(o)
                except: pass
                return str(o)
        _orig_dumps = json.dumps
        def _safe_dumps(obj, **kw):
            if isinstance(obj, dict): obj = {str(k): v for k, v in obj.items()}
            return _orig_dumps(obj, **(dict({'cls': AnsibleEncoder}, **kw)))
        json.dumps = _safe_dumps
        json._graal_ansible_patched = True
    sys._ansible_bridge_mocks_applied = True

def _create_action_plugin(action_name, task, connection, play_context, loader, templar, shared_loader_obj):
    import importlib.util
    base_name = action_name.split('.')[-1]
    path = None
    site_pkgs = globals().get('site_packages_java')
    search_paths = (site_pkgs if site_pkgs else []) + sys.path
    for p in search_paths:
        cand = os.path.join(_normalize_path(p), 'ansible/plugins/action', base_name + '.py')
        if os.path.exists(cand): path = cand; break
    if not path:
        class ModuleAction(ActionBase):
            def run(self, tmp=None, task_vars=None): return self._execute_module(module_name=action_name, module_args=self._task.args, task_vars=task_vars)
        return ModuleAction(task, connection, play_context, loader, templar, shared_loader_obj)
    fqcn = "ansible.plugins.action." + base_name
    if fqcn in sys.modules: mod = sys.modules[fqcn]
    else:
        spec = importlib.util.spec_from_file_location(fqcn, path)
        mod = importlib.util.module_from_spec(spec)
        sys.modules[spec.name] = mod
        spec.loader.exec_module(mod)
    l, c = loader or MockLoader(), connection
    if not hasattr(c, '_shell'):
        class Proxy:
            def __init__(self, obj): self._obj, self._shell = obj, MockShell()
            def __getattr__(self, name): return getattr(self._obj, name)
            def fetch_file(self, rp, lp):
                from java.nio.file import Paths
                self._obj.fetchFile(_normalize_path(rp), Paths.get(_normalize_path(lp)))
        c = Proxy(connection)
    return mod.ActionModule(task, c, play_context, l, templar, shared_loader_obj)

def execute_module(module_name, complex_args, module_code=None):
    global _last_module_result
    _last_module_result = None
    import __main__
    __main__._module_fqn = f"ansible.builtin.{module_name}"
    __main__.complex_args = complex_args
    old_stdout, sys.stdout = sys.stdout, StringIO()
    try:
        if module_code:
            exec(module_code, {'complex_args': complex_args, 'ansible_module_results': {}, '__name__': '__main__'})
        else:
            base_name = module_name.split('.')[-1]
            path = None
            for p in sys.path:
                cand = os.path.join(p, 'ansible/modules', base_name + '.py')
                if os.path.exists(cand): path = cand; break
            if not path: return json.dumps({'failed': True, 'msg': f'Module {module_name} not found'})
            with open(path, 'rb') as f:
                code = compile(f.read(), path, 'exec')
                exec(code, {'__name__': '__main__', '__file__': path, '__package__': 'ansible.modules'})

        if _last_module_result:
            return json.dumps(_last_module_result)
        return sys.stdout.getvalue()
    except SystemExit:
        if _last_module_result:
            return json.dumps(_last_module_result)
        return sys.stdout.getvalue()
    except Exception as e:
        import traceback
        return json.dumps({'failed': True, 'msg': str(e), 'traceback': traceback.format_exc()})
    finally:
        sys.stdout = old_stdout

def initialize(site_packages=None, env_vars=None, complex_args=None, connection_java=None, become_context_java=None):
    apply_mocks()
    setup_sys_path(site_packages)
    setup_env(env_vars)
    bind_task(complex_args or {}, connection_java, become_context_java, env_vars)

apply_mocks()
bridge_mod = types.ModuleType('ansible_bridge')
bridge_mod.__dict__.update(globals())
sys.modules['ansible_bridge'] = bridge_mod
