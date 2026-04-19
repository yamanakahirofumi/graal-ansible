import json
import sys
import os
import types
import re
from io import StringIO
from typing import Any, Dict, List, Optional, Tuple, Union, Set, Type, TYPE_CHECKING, Iterable

if TYPE_CHECKING:
    # These are injected by GraalVM context in TaskExecutor.java
    os_java: Any
    AnsibleModuleJava: Any
    task_executor_java: Any

# Fail early if necessary mocks are not available in GraalPy context
if 'os_java' not in globals():
    raise ImportError("os_java mock not found. Ensure TaskExecutor correctly injects PythonOSMock.")
if 'AnsibleModuleJava' not in globals():
    raise ImportError("AnsibleModuleJava factory not found. Ensure TaskExecutor correctly injects PythonAnsibleModuleMock.Factory.")

def _raise_file_not_found(msg: str) -> None:
    # This helper is necessary because Java cannot directly 'raise' a Python exception.
    # By calling this function, Java triggers a Python-level raise statement,
    # ensuring the exception is correctly caught as a FileNotFoundError in Python.
    raise FileNotFoundError(msg)

# Initialize Java-based os mock with Python classes
os_java.setPythonClasses(os.stat_result, _raise_file_not_found)

def _to_java_str(p: Any) -> Optional[str]:
    if p is None: return None
    if isinstance(p, bytes):
        try: return p.decode('utf-8')
        except: return p.decode('latin-1')
    return str(p)

# Override os functions to use Java-based mocks
os.makedirs = lambda name, mode=0o777, exist_ok=False: os_java.makedirs(_to_java_str(name), mode, exist_ok)
os.mkdir = lambda path, mode=0o777: os_java.mkdir(_to_java_str(path), mode)
os.path.exists = lambda path: os_java.exists(_to_java_str(path))
os.stat = lambda path, *args, **kwargs: os_java.statPython(_to_java_str(path), *args)
os.geteuid = os_java.geteuid
os.getuid = os_java.getuid
os.getegid = os_java.getegid
os.getgid = os_java.getgid
os.chown = lambda path, uid, gid: os_java.chown(_to_java_str(path), uid, gid)
os.lchown = lambda path, uid, gid: os_java.lchown(_to_java_str(path), uid, gid)
os.lchmod = lambda path, mode: os_java.lchmod(_to_java_str(path), mode)
os.setegid = os_java.setegid
os.seteuid = os_java.seteuid
os.setgid = os_java.setgid
os.setuid = os_java.setuid

# Global context to hold current task state
_current_task_context: Dict[str, Any] = {
    'complex_args': {},
    'connection_java': None,
    'become_context_java': None,
    'environment_java': None
}

def _normalize_path(p: Any) -> str:
    return str(os_java.normalizePath(_to_java_str(p)))

def _deep_convert(obj: Any) -> Any:
    if obj is None: return None
    if isinstance(obj, (str, bytes)): return _normalize_path(obj)
    if isinstance(obj, (int, float, bool)): return obj

    if hasattr(obj, 'getClass'):
        from java.util import Map, List as JavaList, Set as JavaSet
        if isinstance(obj, Map):
            return {str(k): _deep_convert(v) for k, v in obj.items()}
        if isinstance(obj, (JavaList, JavaSet)):
            return [_deep_convert(i) for i in obj]

        class_name = obj.getClass().getName()
        if class_name == 'java.lang.String': return _normalize_path(str(obj))
        if class_name == 'java.lang.Boolean': return bool(obj)
        if class_name in ('java.lang.Integer', 'java.lang.Long', 'java.lang.Short', 'java.lang.Byte'): return int(obj)
        if class_name in ('java.lang.Float', 'java.lang.Double'): return float(obj)
        if 'java.nio.file.Path' in class_name: return _normalize_path(str(obj.toString()))
        if 'java.io.File' in class_name: return _normalize_path(str(obj.getAbsolutePath()))

        if 'Proxy' in class_name or 'com.sun.proxy' in class_name:
            if hasattr(obj, 'substring'): return _normalize_path(str(obj))
            if hasattr(obj, 'toString'): return _normalize_path(str(obj.toString()))

        try: return str(obj)
        except: return obj

    if isinstance(obj, dict):
        return {str(k): _deep_convert(v) for k, v in obj.items()}
    if isinstance(obj, (list, tuple, set, frozenset)):
        return [_deep_convert(i) for i in obj]
    return obj

def bind_task(complex_args: Any, connection_java: Any, become_context_java: Any, environment_java: Any) -> None:
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

def setup_sys_path(site_packages: Optional[List[str]]) -> None:
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

def setup_env(env_vars: Optional[Dict[str, Any]]) -> None:
    if env_vars:
        for k, v in dict(env_vars).items(): os.environ[str(k)] = str(v)

# --- Mock Classes ---

class MockLoader:
    def __init__(self) -> None:
        self.path_finder: Any = None
        self._basedir: str = os.getcwd()
    def get_basedir(self) -> str:
        return self._basedir
    def set_basedir(self, basedir: str) -> None:
        self._basedir = basedir
    def get_real_file(self, file_path: str, decrypt: bool = True) -> str:
        return file_path
    def get_text_file_contents(self, file_path: str, loader: Any = None) -> Tuple[str, bool]:
        fp = _normalize_path(file_path)
        if fp and os.path.exists(fp):
            with open(fp, 'r', encoding='utf-8', errors='surrogateescape') as f:
                return f.read(), True
        return "", False
    def cleanup_tmp_file(self, *args: Any, **kwargs: Any) -> None:
        pass
    def path_dwim(self, path: str) -> str:
        return _normalize_path(path)
    def load_from_file(self, file_path: str, *args: Any, **kwargs: Any) -> Optional[Any]:
        fp = _normalize_path(file_path)
        if fp and os.path.exists(fp):
            with open(fp, 'r', encoding='utf-8') as f:
                import yaml
                return yaml.safe_load(f)
        return None

class MockShell:
    def __init__(self) -> None:
        import tempfile
        self.tmpdir: str = tempfile.gettempdir()
    def path_has_trailing_slash(self, path: Union[str, List[str]]) -> bool:
        if isinstance(path, list):
            if len(path) > 0: path = path[0]
            else: return False
        return str(path).endswith('/') or str(path).endswith('\\')
    def join_path(self, *args: Any) -> str:
        cleaned_args = []
        for a in args:
            if isinstance(a, list):
                if len(a) > 0: a = a[0]
                else: a = ""
            cleaned_args.append(str(a))
        return os.path.join(*cleaned_args)
    def expand_user(self, path: str, *args: Any, **kwargs: Any) -> str:
        return path

class Display:
    verbosity: int = 10
    def __init__(self, *args: Any, **kwargs: Any) -> None:
        self.verbosity = 10
        self.columns = 79
        self.color = False
    def display(self, *args: Any, **kwargs: Any) -> None: pass
    def debug(self, *args: Any, **kwargs: Any) -> None: pass
    def verbose(self, *args: Any, **kwargs: Any) -> None: pass
    def warning(self, *args: Any, **kwargs: Any) -> None: pass
    def error(self, *args: Any, **kwargs: Any) -> None: pass
    def deprecated(self, *args: Any, **kwargs: Any) -> None: pass
    def v(self, *args: Any, **kwargs: Any) -> None: pass
    def vv(self, *args: Any, **kwargs: Any) -> None: pass
    def vvv(self, *args: Any, **kwargs: Any) -> None: pass
    def vvvv(self, *args, **kwargs: Any) -> None: pass
    def vvvvv(self, *args: Any, **kwargs: Any) -> None: pass
    def vvvvvv(self, *args: Any, **kwargs: Any) -> None: pass

class PlayContext:
    def __init__(self) -> None:
        self.verbosity = 10
        self.check_mode = False
        self.diff = False

class ActionBase:
    def __init__(self, task: 'Task', connection: Any, play_context: 'PlayContext', loader: 'MockLoader', templar: 'Templar', shared_loader_obj: Any) -> None:
        self._task, self._connection, self._play_context = task, connection, play_context
        self._loader, self._templar = loader, templar
        self._shared_loader_obj = shared_loader_obj
        self._display = self.display = sys.modules.get('ansible.utils.display', types.SimpleNamespace(display=Display())).display
        self._supports_check_mode = True
        self._supports_async = False
    def run(self, tmp: Optional[str] = None, task_vars: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
        return {'changed': False, 'failed': False}
    def validate_argument_spec(self, argument_spec: Dict[str, Any], *args: Any, **kwargs: Any) -> Tuple[Any, Dict[str, Any]]:
        res = {}
        input_args = self._task.args or {}
        for k, v in argument_spec.items():
            if k in input_args: res[k] = input_args[k]
            elif isinstance(v, dict) and 'default' in v: res[k] = v['default']
            elif isinstance(v, dict) and v.get('required'): res[k] = None
            else: res[k] = None
        return types.SimpleNamespace(error=None, warning=None), res
    def _execute_module(self, module_name: Optional[str] = None, module_args: Optional[Dict[str, Any]] = None, tmp: Optional[str] = None, task_vars: Optional[Dict[str, Any]] = None, *args: Any, **kwargs: Any) -> Dict[str, Any]:
        m_name = module_name or self._task.action
        m_args = module_args or self._task.args
        if 'task_executor_java' in globals():
            res = task_executor_java.execute_from_python(m_name, m_args, task_vars or {})
            if res is not None:
                try:
                    r_dict = {str(k): v for k, v in res.items()}
                except:
                    try:
                        r_dict = {str(k): v for k, v in res.data().items()}
                        r_dict['failed'] = not res.success()
                        r_dict['changed'] = res.changed()
                    except:
                        s = str(res)
                        if 'data=' in s:
                            r_dict = {'changed': 'changed=true' in s.lower()}
                        else:
                            r_dict = {'failed': True, 'msg': 'Failed to bridge module result'}

                if 'changed' not in r_dict: r_dict['changed'] = True
                return r_dict
            return {'changed': True}
        return {'failed': True, 'msg': 'task_executor_java not available'}
    def _remove_tmp_path(self, *args: Any, **kwargs: Any) -> None: pass
    def _find_needle(self, name: str, needle: str, *args: Any, **kwargs: Any) -> str:
        if needle and 'task_executor_java' in globals():
            res = task_executor_java.resolveLocalPath(needle)
            if res: return _normalize_path(res)
        return _normalize_path(needle)
    def _remote_expand_user(self, path: str, *args: Any, **kwargs: Any) -> str: return path
    def _execute_remote_stat(self, path: str, all_vars: Any, follow: bool = False, *args: Any, **kwargs: Any) -> Dict[str, Any]:
        import hashlib
        p = _normalize_path(path)
        conn = _current_task_context.get('connection_java')
        if conn:
            is_remote = False
            try:
                cn = conn.getClass().getName()
                if 'Ssh' in cn: is_remote = True
            except: pass
            if is_remote:
                try:
                    res = conn.execCommand(f"test -d \"{p}\" && echo DIR || (test -f \"{p}\" && echo FILE || echo NO)",
                                         _current_task_context.get('become_context_java'), None)
                    stdout = ""
                    try: stdout = str(res.stdout())
                    except:
                        try: stdout = str(res.stdout)
                        except:
                            s = str(res); m = re.search(r'stdout=(.*?), stderr=', s, re.DOTALL)
                            if m: stdout = m.group(1)
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
    def _transfer_file(self, local_path: str, remote_path: str) -> str:
        conn = _current_task_context['connection_java']
        lp, rp = _normalize_path(local_path), _normalize_path(remote_path)
        if conn:
            from java.nio.file import Paths
            conn.putFile(Paths.get(str(lp)), str(rp))
        import hashlib
        with open(lp, 'rb') as f:
            return hashlib.sha1(f.read()).hexdigest()
    def _fixup_perms2(self, *args: Any, **kwargs: Any) -> None: pass

class Task:
    def __init__(self) -> None:
        self.action: Optional[str] = None
        self.args: Dict[str, Any] = {}
        self.async_val: int = 0
        self._origin = types.SimpleNamespace(path=None)
        self.collections: List[str] = []
        self.tags: List[str] = []
        self.implicit: bool = False
        self.resolved_action: Optional[str] = None
        self._parent: Any = None
        self.diff: bool = False
        self.check_mode: bool = False
        self.no_log: bool = False
        self.delegate_to: Optional[str] = None
        self.delegate_facts: bool = False
        self.environment: Dict[str, Any] = {}
        self._role: Any = None
        self._original_basename: Optional[str] = None
    def get_name(self) -> str: return "mock_task"
    def copy(self) -> 'Task':
        new_task = Task()
        new_task.action = self.action
        new_task.args = (self.args or {}).copy()
        new_task.async_val = self.async_val
        return new_task

class Templar:
    def __init__(self, loader: Optional[Any] = None, variables: Optional[Dict[str, Any]] = None) -> None:
        self._engine = type('Eng', (), {
            'tvars': variables or {},
            'extend': lambda *a, **kw: self._engine,
            'evaluate_expression': lambda expr, *a, **kw: self._engine.tvars.get(expr, expr)
        })
        self.available_variables: Dict[str, Any] = variables or {}
        self.environment: Dict[str, Any] = {}
    def template(self, msg: Any, *args: Any, **kwargs: Any) -> Any:
        if msg is None: return None
        if isinstance(msg, (tuple, list)): msg = msg[0]
        if not isinstance(msg, str): return msg

        try:
            from jinja2 import Template
            t = Template(msg)
            return t.render(**self.available_variables)
        except Exception:
            import re
            def repl(match: Any) -> str:
                var_name = match.group(1).strip()
                return str(self.available_variables.get(var_name, match.group(0)))
            return re.sub(r'\{\{\s*(.*?)\s*\}\}', repl, msg)
    def evaluate_conditional(self, conditional: Any, *args: Any, **kwargs: Any) -> bool:
        try:
            return bool(eval(str(conditional), {}, self.available_variables))
        except:
            return False
    def copy_with_new_env(self, *args: Any, **kwargs: Any) -> 'Templar': return self

class AnsibleModule:
    def __init__(self, argument_spec: Dict[str, Any], *args: Any, **kwargs: Any) -> None:
        input_args = _current_task_context['complex_args'] or {}
        spec_conv = _deep_convert(argument_spec)
        kwargs_conv = _deep_convert(kwargs)

        self._java_mock = AnsibleModuleJava.create(
            spec_conv, input_args, kwargs_conv,
            _current_task_context.get('connection_java'),
            _current_task_context.get('become_context_java'),
            _current_task_context.get('environment_java'),
            print, sys.exit
        )
        self.params: Dict[str, Any] = _deep_convert(self._java_mock.getParams())
        self.check_mode: bool = bool(self._java_mock.getCheck_mode())
        self._debug: bool = bool(self._java_mock.get_debug())
        self._diff: bool = bool(self._java_mock.get_diff())
        self.boolean = self.boolean_value

    def boolean_value(self, x: Any) -> bool:
        return bool(self._java_mock.boolean_value(x))

    @property
    def tmpdir(self) -> str:
        return str(self._java_mock.getTmpdir())

    def exit_json(self, **kwargs: Any) -> None:
        self._java_mock.exit_json(_deep_convert(kwargs))

    def fail_json(self, **kwargs: Any) -> None:
        self._java_mock.fail_json(_deep_convert(kwargs))

    def run_command(self, args: Union[str, List[str]], **kwargs: Any) -> Tuple[int, str, str]:
        res = self._java_mock.run_command(args)
        return (int(res[0]), str(res[1]), str(res[2]))

    def get_bin_path(self, arg: str, required: bool = False, opt_dirs: Optional[List[str]] = None) -> Optional[str]:
        return _to_java_str(self._java_mock.get_bin_path(_to_java_str(arg), required, [_to_java_str(d) for d in (opt_dirs or [])]))

    def sha1(self, path: str) -> str: return str(self._java_mock.sha1(_to_java_str(path)))
    def md5(self, path: str) -> str: return str(self._java_mock.md5(_to_java_str(path)))
    def sha256(self, path: str) -> str: return str(self._java_mock.sha256(_to_java_str(path)))

    def atomic_move(self, src: str, dest: str, unsafe_writes: bool = False, **kwargs: Any) -> None:
        self._java_mock.atomic_move(_to_java_str(src), _to_java_str(dest))

    def debug(self, msg: str) -> None: self._java_mock.debug(_to_java_str(msg))
    def warn(self, msg: str) -> None: self._java_mock.warn(_to_java_str(msg))
    def deprecate(self, msg: str, version: Optional[str] = None, date: Optional[str] = None, collection_name: Optional[str] = None) -> None:
        self._java_mock.deprecate(_to_java_str(msg), _to_java_str(version), _to_java_str(date), _to_java_str(collection_name))

    def digest_from_file(self, filename: str, algorithm: str) -> str:
        return str(self._java_mock.digest_from_file(_to_java_str(filename), _to_java_str(algorithm)))

    def get_file_attributes(self, path: str) -> Dict[str, Any]:
        return _deep_convert(self._java_mock.get_file_attributes(_to_java_str(path)))

    def load_file_common_arguments(self, params: Dict[str, Any], path: Optional[str] = None) -> Dict[str, Any]:
        return _deep_convert(self._java_mock.load_file_common_arguments(params, _to_java_str(path)))

    def set_fs_attributes_if_different(self, file_args: Dict[str, Any], changed: bool, diff: Optional[Any] = None, expand: bool = True) -> bool:
        self._java_mock.set_fs_attributes_if_different(file_args, changed)
        return changed
    def set_file_attributes_if_different(self, file_args: Dict[str, Any], changed: bool, diff: Optional[Any] = None, expand: bool = True) -> bool:
        self._java_mock.set_file_attributes_if_different(file_args, changed)
        return changed

    def makedirs_safe(self, path: str, mode: Optional[int] = None) -> None:
        self._java_mock.makedirs_safe(_to_java_str(path), mode)

# --- Mock Application ---

def apply_mocks() -> None:
    mocks_applied = getattr(sys, '_ansible_bridge_mocks_applied', False)

    def create_mock(mname: str, attributes: Optional[Dict[str, Any]] = None, is_package: bool = True) -> types.ModuleType:
        if mname in sys.modules:
            m = sys.modules[mname]
        else:
            m = types.ModuleType(mname)
            sys.modules[mname] = m
        if is_package:
            if not hasattr(m, '__path__') or not isinstance(m.__path__, list):
                m.__path__ = []
            if not hasattr(m, '__file__') or not m.__file__:
                placeholder_file = os.path.join(os.getcwd(), mname.replace('.', '/'), '__init__.py')
                setattr(m, '__file__', placeholder_file)
                m.__dict__['__file__'] = placeholder_file
        if attributes:
            for k, v in attributes.items(): setattr(m, k, v)
        return m

    # 1. Native & System Mocks
    create_mock('_posixsubprocess', {'fork_exec': lambda *a, **kw: 0, 'cloexec_pipe': lambda: (0, 0)}, False)
    create_mock('fcntl', {'fcntl': lambda *a, **kw: 0, 'ioctl': lambda *a, **kw: 0, 'flock': lambda *a, **kw: 0, 'lockf': lambda *a, **kw: 0}, False)
    create_mock('resource', {'getrlimit': lambda *a, **kw: (1024, 1024), 'RLIMIT_NOFILE': 7}, False)
    for m in ['cryptography', 'yaml._yaml', 'markupsafe._speedups', 'selinux']: create_mock(m)
    create_mock('termios', {'TCSAFLUSH': 1, 'tcgetattr': lambda *a, **kw: [0,0,0,0, ' ', ' ', []], 'tcsetattr': lambda *a, **kw: None})
    create_mock('syslog', {'openlog': lambda *a, **kw: None, 'syslog': lambda *a, **kw: None, 'closelog': lambda *a, **kw: None, 'setlogmask': lambda *a, **kw: None})
    create_mock('markupsafe', {
        'escape': lambda s, *a, **kw: s, 'soft_str': str, 'soft_unicode': str, 'Markup': str,
        'EscapeFormatter': type('EF', (), {})
    })

    # 2. Display & PlayContext
    import tempfile
    create_mock('ansible')
    constants = create_mock('ansible.constants', {
        'DEFAULT_REMOTE_TMP': '/tmp',
        'DEFAULT_LOCAL_TMP': tempfile.gettempdir(),
        'DEFAULT_KEEP_REMOTE_FILES': False,
        '_ACTION_SETUP': ['ansible.builtin.setup', 'ansible.legacy.setup', 'setup']
    })
    config_mock = create_mock('ansible.config', {
        'ConfigManager': type('CM', (), {'get_config_value': lambda *a, **kw: None}),
        'get_config_value': lambda k, *a, **kw: ['ansible.legacy.setup'] if k == 'FACTS_MODULES' else None
    })
    setattr(constants, 'config', config_mock)
    create_mock('ansible.config.manager', {'ConfigManager': type('CM', (), {'get_config_value': lambda *a, **kw: None}), 'ensure_type': lambda x, t: x})
    create_mock('ansible.utils')
    create_mock('ansible.utils.display', {'Display': Display, 'display': Display(), 'PlayContext': PlayContext})

    def real_checksum(path: str, *args: Any, **kwargs: Any) -> Optional[str]:
        import hashlib
        p = _normalize_path(path)
        if not os.path.exists(p): return None
        with open(p, 'rb') as f: return hashlib.sha1(f.read()).hexdigest()
    def real_checksum_s(s: Union[str, bytes], *args: Any, **kwargs: Any) -> str:
        import hashlib
        if isinstance(s, str): s = s.encode('utf-8')
        return hashlib.sha1(s).hexdigest()

    def real_md5(path: str, *args: Any, **kwargs: Any) -> Optional[str]:
        import hashlib
        p = _normalize_path(path)
        if not os.path.exists(p): return None
        with open(p, 'rb') as f: return hashlib.md5(f.read()).hexdigest()

    create_mock('ansible.utils.hashing', {
        'checksum_s': real_checksum_s,
        'checksum': real_checksum,
        'secure_hash': real_checksum,
        'secure_hash_s': real_checksum_s,
        'md5': real_md5
    })

    # 3. Utils
    def mock_makedirs_safe(path: str, mode: Optional[int] = None, *args: Any, **kwargs: Any) -> None:
        p = _normalize_path(path)
        if not os.path.exists(p):
            try: os.makedirs(p, mode=mode if mode is not None else 0o777)
            except: pass

    def mock_is_subpath(path: str, base: str, *args: Any, **kwargs: Any) -> bool:
        p, b = _normalize_path(path), _normalize_path(base)
        return os.path.abspath(p).startswith(os.path.abspath(b))

    create_mock('ansible.utils.path', {
        'unquote': lambda s, *a, **kw: s, 'cleanup_tmp_file': lambda s, *a, **kw: None,
        'makedirs_safe': mock_makedirs_safe, 'unfrackpath': _normalize_path,
        'get_real_file': lambda s, *a, **kw: s,
        'is_subpath': mock_is_subpath
    })
    create_mock('ansible.utils.fqcn', {'add_internal_fqcns': lambda *a, **kw: None})
    create_mock('ansible.utils.plugin_docs', {'get_versioned_doclink': lambda *a, **kw: 'http://docs.ansible.com'})
    create_mock('ansible.utils.collection_loader', is_package=True)
    create_mock('ansible.utils.collection_loader._collection_finder', {
        '_get_collection_metadata': lambda *a, **kw: {},
        '_nested_dict_get': lambda *a, **kw: None
    })
    create_mock('ansible.utils.vars', {
        'isidentifier': lambda s, *a, **kw: True, 'validate_variable_name': lambda s, *a, **kw: True,
        'merge_hash': lambda a, b: dict(a, **(b or {})),
        'combine_vars': lambda a, b, *args, **kwargs: {**a, **b}
    })

    # 4. Errors
    class AnsibleError(Exception):
        def __init__(self, message: str = "", obj: Any = None, show_content: bool = True, suppress_extended_error: bool = False, orig_exception: Optional[Exception] = None, **kwargs: Any) -> None:
            super().__init__(message)
            for k, v in kwargs.items(): setattr(self, k, v)
    class AnsibleValueOmittedError(AnsibleError): pass
    class AnsibleActionFail(AnsibleError): pass
    class AnsibleActionSkip(AnsibleError): pass
    class AnsibleTemplateError(AnsibleError): pass
    class AnsibleFileNotFound(AnsibleError): pass
    class AnsibleConnectionFailure(AnsibleError): pass
    class AnsibleParserError(AnsibleError): pass
    class AnsiblePromptInterrupt(AnsibleError): pass
    class AnsiblePromptNoninteractive(AnsibleError): pass

    create_mock('ansible.errors', {
        'AnsibleError': AnsibleError,
        'AnsibleValueOmittedError': AnsibleValueOmittedError,
        'AnsibleActionFail': AnsibleActionFail,
        'AnsibleActionSkip': AnsibleActionSkip,
        'AnsibleTemplateError': AnsibleTemplateError,
        'AnsibleFileNotFound': AnsibleFileNotFound,
        'AnsibleConnectionFailure': AnsibleConnectionFailure,
        'AnsibleParserError': AnsibleParserError,
        'AnsiblePromptInterrupt': AnsiblePromptInterrupt,
        'AnsiblePromptNoninteractive': AnsiblePromptNoninteractive
    })

    # 5. Plugins & Loader
    create_mock('ansible.plugins', {'AnsiblePlugin': type('AnsiblePlugin', (), {})})
    create_mock('ansible.plugins.action', {'ActionBase': ActionBase})

    action_loader_obj = types.SimpleNamespace()
    action_loader_obj.action_loader = action_loader_obj
    action_loader_obj.module_loader = type('ML', (), {'find_plugin': lambda name: None})()
    action_loader_obj.module_utils_loader = type('MUL', (), {'find_plugin': lambda name: None})()
    action_loader_obj.ps_module_utils_loader = type('PSML', (), {'find_plugin': lambda name: None})()
    def action_loader_get(name: str, *args: Any, **kwargs: Any) -> Any:
        import ansible_bridge
        return ansible_bridge._create_action_plugin(
            name, kwargs.get('task'), kwargs.get('connection'),
            kwargs.get('play_context'), kwargs.get('loader'),
            kwargs.get('templar'), kwargs.get('shared_loader_obj')
        )
    action_loader_obj.get = action_loader_get

    create_mock('ansible.plugins.loader', {
        'action_loader': action_loader_obj,
        'module_loader': action_loader_obj.module_loader,
        'module_utils_loader': action_loader_obj.module_utils_loader,
        'ps_module_utils_loader': action_loader_obj.ps_module_utils_loader
    })

    # 6. Playbook
    create_mock('ansible.playbook')
    create_mock('ansible.playbook.task', {'Task': Task})
    create_mock('ansible.playbook.play_context', {'PlayContext': PlayContext})

    # 7. Templating
    create_mock('ansible.template', {'Templar': Templar, 'trust_as_template': lambda x: x})

    create_mock('ansible._internal', {
        'get_controller_serialize_map': lambda: {}
    })
    create_mock('ansible._internal._locking')
    create_mock('ansible._internal._errors')
    create_mock('ansible._internal._errors._error_utils', {
        'result_dict_from_captured_errors': lambda *a, **kw: {}
    })
    ansiballz = create_mock('ansible._internal._ansiballz')
    # Mock __file__ to avoid FileNotFoundError when executor tries to read _wrapper.py relative to it
    if hasattr(ansiballz, '__file__'):
        wrapper_path = os.path.join(os.path.dirname(ansiballz.__file__), '_wrapper.py')
        if not os.path.exists(wrapper_path):
            with open(wrapper_path, 'w') as f: f.write("# mock wrapper")
    create_mock('ansible._internal._ansiballz._builder')
    swe = type('SWE', (Exception,), {'is_tagged_on': staticmethod(lambda x: False)})
    create_mock('ansible._internal._datatag', {'SourceWasEncrypted': swe})
    create_mock('ansible._internal._datatag._tags', {'SourceWasEncrypted': swe, 'Origin': type('Origin', (), {}), 'TrustedAsTemplate': type('TAT', (), {})})
    create_mock('ansible._internal._datatag._utils')
    create_mock('ansible._internal._templating', {
        '_template_vars': types.SimpleNamespace(generate_ansible_template_vars=lambda *a, **kw: {}),
        'get_text_file_contents': lambda x, *a, **kw: (open(_normalize_path(x), 'r').read() if x and os.path.exists(_normalize_path(x)) else "mock_content", True)
    })
    for mname in ['ansible._internal._templating._engine', 'ansible._internal._templating._jinja_bits', 'ansible._internal._templating._jinja_common', 'ansible._internal._templating._utils', 'ansible._internal._templating._marker_behaviors']:
        m = create_mock(mname)
        if mname.endswith('_engine'):
            m.TemplateEngine = type('TE', (), {})
            m.TemplateOptions = type('TO', (), {})
        elif mname.endswith('_jinja_common'):
            m.UndefinedMarker = type('UM', (), {})
            m.TruncationMarker = type('TM', (), {})
        elif mname.endswith('_utils'):
            m.Omit = type('Omit', (), {})
        elif mname.endswith('_marker_behaviors'):
            m.ReplacingMarkerBehavior = type('RMB', (), {'emit_warnings': lambda *a: None})
            m.RoutingMarkerBehavior = type('RoMB', (), {'__init__': lambda *a, **kw: None})

    # 8. Module Utils
    for mname in ['ansible', 'ansible.module_utils', 'ansible.module_utils.common', 'ansible.module_utils.compat', 'ansible.module_utils._internal', 'ansible.module_utils.parsing', 'ansible.plugins', 'ansible.plugins.action']:
        attrs = {}
        if mname == 'ansible.module_utils._internal':
            attrs['get_controller_serialize_map'] = lambda: {}
        create_mock(mname, attributes=attrs, is_package=True)

    if mocks_applied: return

    create_mock('ansible.module_utils.common.sentinel', {'Sentinel': type('Sentinel', (), {})})
    def mock_get_distribution() -> str:
        import platform
        try: sys_type = platform.system()
        except: sys_type = 'Linux'
        if sys_type == 'Windows': return 'Windows'
        if sys_type == 'Darwin': return 'MacOS'
        return 'Linux'

    create_mock('ansible.module_utils.common.sys_info', {
        'get_distribution': mock_get_distribution,
        'get_distribution_version': lambda: 'Any',
        'get_distribution_codename': lambda: 'Any',
        'get_platform_subclass': lambda cls: cls
    })
    create_mock('ansible.module_utils.compat.version', {'LooseVersion': str, 'StrictVersion': str})
    create_mock('ansible.module_utils.parsing.convert_bool', {
        'convert_bool': lambda x, *a, **kw: str(x).lower() in ('yes', 'true', 't', '1'),
        'boolean': lambda x, *a, **kw: str(x).lower() in ('yes', 'true', 't', '1'),
        'BOOLEANS_TRUE': frozenset(['y', 'yes', 'on', '1', 'true', 't', 1, True]),
        'BOOLEANS_FALSE': frozenset(['n', 'no', 'off', '0', 'false', 'f', 0, False])
    })
    FILE_COMMON_ARGUMENTS = {
        'path': dict(type='str', aliases=['dest', 'name']),
        'mode': dict(type='raw'),
        'owner': dict(type='str'),
        'group': dict(type='str'),
        'seuser': dict(type='str'),
        'serole': dict(type='str'),
        'setype': dict(type='str'),
        'selevel': dict(type='str'),
        'attributes': dict(type='str', aliases=['attr']),
        'unsafe_writes': dict(type='bool', default=False),
    }
    def mock_get_bin_path(arg: str, required: bool = False, opt_dirs: Optional[List[str]] = None) -> str:
        if arg == 'python': return sys.executable
        return arg
    def mock_load_params() -> Tuple[Dict[str, Any], str]:
        args = _current_task_context['complex_args']
        if not args: return {}, 'main'
        return args, 'main'

    create_mock('ansible.module_utils.basic', {
        'AnsibleModule': AnsibleModule,
        '_load_params': mock_load_params,
        '_ANSIBLE_PROFILE': 'modern',
        'FILE_COMMON_ARGUMENTS': FILE_COMMON_ARGUMENTS,
        'missing_required_lib': lambda *a, **kw: None,
        'sanitize_keys': lambda x, *a, **kw: x,
        'get_bin_path': mock_get_bin_path,
        'get_distribution': mock_get_distribution,
        'is_executable': lambda x: True
    })

    # 9. Password/Group System Mocks
    import collections
    passwd, group = collections.namedtuple('passwd', ['pw_name', 'pw_passwd', 'pw_uid', 'pw_gid', 'pw_gecos', 'pw_dir', 'pw_shell']), collections.namedtuple('group', ['gr_name', 'gr_passwd', 'gr_gid', 'gr_mem'])

    def mock_getgrnam(name: str) -> Any:
        if name == 'root': return group('root', 'x', 0, [])
        return group(str(name), 'x', 1001, [])

    def mock_getpwnam(name: str) -> Any:
        if name == 'root': return passwd('root', 'x', 0, 0, 'root', '/root', '/bin/bash')
        return passwd(str(name), 'x', 1001, 1001, str(name), f'/home/{name}', '/bin/bash')

    create_mock('grp', {'getgrnam': mock_getgrnam, 'getgrgid': lambda *a, **kw: group('root', 'x', 0, []), 'getgrall': lambda: []}, False)
    create_mock('pwd', {'getpwnam': mock_getpwnam, 'getpwuid': lambda *a, **kw: passwd('root', 'x', 0, 0, 'root', '/root', '/bin/bash'), 'getpwall': lambda: []}, False)

    create_mock('syslog', {
        'openlog': lambda *a, **kw: None, 'syslog': lambda *a, **kw: None, 'closelog': lambda *a, **kw: None, 'setlogmask': lambda *a, **kw: None,
        'LOG_NOTICE': 5, 'LOG_INFO': 6, 'LOG_DEBUG': 7, 'LOG_ERR': 3, 'LOG_WARNING': 4
    }, False)

    # 10. JSON handling
    if not hasattr(json, '_graal_ansible_patched'):
        class AnsibleEncoder(json.JSONEncoder):
            def default(self, o: Any) -> Any:
                if isinstance(o, bytes):
                    try: return o.decode('utf-8')
                    except: return o.decode('latin-1')
                if isinstance(o, (set, frozenset, range)): return list(o)
                if isinstance(o, Exception):
                    return {'failed': True, 'msg': str(o), 'exception': str(o)}
                try:
                    if hasattr(o, '__iter__') and not isinstance(o, (str, bytes)):
                        if hasattr(o, 'keys'): return dict(o)
                        if hasattr(o, 'size') and hasattr(o, 'get'):
                            try: return [o.get(i) for i in range(o.size())]
                            except: pass
                        return list(o)
                except Exception: pass
                return str(o)
        _orig_dumps = json.dumps
        json.dumps = lambda obj, **kw: _orig_dumps(obj, **(dict({'cls': AnsibleEncoder}, **kw)))
        json._graal_ansible_patched = True

    sys._ansible_bridge_mocks_applied = True

def _create_action_plugin(action_name: str, task: Any, connection: Any, play_context: Any, loader: Any, templar: Any, shared_loader_obj: Any) -> Any:
    import importlib.util
    import __main__
    base_name = action_name
    if base_name.startswith('ansible.builtin.'): base_name = base_name[16:]
    elif base_name.startswith('ansible.legacy.'): base_name = base_name[15:]

    path = None
    site_pkgs = globals().get('site_packages_java')
    if site_pkgs:
        for p in site_pkgs:
            cand = os.path.join(_normalize_path(p), 'ansible/plugins/action', base_name + '.py')
            if os.path.exists(cand): path = cand; break

    if not path:
        for p in sys.path:
            cand = os.path.join(p, 'ansible/plugins/action', base_name + '.py')
            if os.path.exists(cand): path = cand; break

    if not path:
        class ModuleAction(ActionBase):
            def run(self, tmp: Optional[str] = None, task_vars: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
                return self._execute_module(module_name=action_name, module_args=self._task.args, task_vars=task_vars)
        return ModuleAction(task, connection, play_context, loader, templar, shared_loader_obj)

    fqcn = "ansible.plugins.action." + base_name
    if fqcn in sys.modules:
        mod = sys.modules[fqcn]
    else:
        spec = importlib.util.spec_from_file_location(fqcn, path)
        if spec and spec.loader:
            mod = importlib.util.module_from_spec(spec)
            sys.modules[spec.name] = mod
            spec.loader.exec_module(mod)
        else:
            raise ImportError(f"Could not load action plugin {action_name}")

    l = loader or MockLoader()

    c = connection
    if not hasattr(c, '_shell') or not hasattr(c._shell, 'path_has_trailing_slash'):
        class Proxy:
            def __init__(self, obj: Any) -> None:
                self._obj, self._shell = obj, MockShell()
                self.become = False
            def __getattr__(self, name: str) -> Any:
                if name == 'become': return self.become
                return getattr(self._obj, name)
            def fetch_file(self, remote_path: str, local_path: str) -> None:
                rp, lp = _normalize_path(remote_path), _normalize_path(local_path)
                from java.nio.file import Paths
                self._obj.fetchFile(str(rp), Paths.get(str(lp)))
        c = Proxy(connection)

    return mod.ActionModule(task, c, play_context, l, templar, shared_loader_obj)

def execute_module(module_name: str, complex_args: Dict[str, Any], module_code: Optional[str] = None) -> str:
    import __main__
    setattr(__main__, '_module_fqn', f"ansible.builtin.{module_name}")
    setattr(__main__, 'complex_args', complex_args)
    old_stdout, sys.stdout = sys.stdout, StringIO()
    try:
        if module_code:
            exec(module_code, {'complex_args': complex_args, 'ansible_module_results': {}, '__name__': '__main__'})
        else:
            base_name = module_name
            if base_name.startswith('ansible.builtin.'): base_name = base_name[16:]
            elif base_name.startswith('ansible.legacy.'): base_name = base_name[15:]
            path = None
            for p in sys.path:
                cand = os.path.join(p, 'ansible/modules', base_name + '.py')
                if os.path.exists(cand): path = cand; break
            if not path: return json.dumps({'failed': True, 'msg': f'Module {module_name} not found'})
            with open(path, 'rb') as f:
                code = compile(f.read(), path, 'exec')
                exec(code, {'__name__': '__main__', '__file__': path, '__package__': 'ansible.modules'})
        return sys.stdout.getvalue()
    except SystemExit:
        return sys.stdout.getvalue()
    except Exception as e:
        import traceback
        return json.dumps({'failed': True, 'msg': str(e), 'traceback': traceback.format_exc()})
    finally:
        sys.stdout = old_stdout

def initialize(site_packages: Optional[List[str]] = None, env_vars: Optional[Dict[str, Any]] = None, complex_args: Optional[Dict[str, Any]] = None, connection_java: Any = None, become_context_java: Any = None) -> None:
    apply_mocks()
    setup_sys_path(site_packages)
    setup_env(env_vars)
    bind_task(complex_args or {}, connection_java, become_context_java, env_vars)

# Early initialization
apply_mocks()

# Self-registration
bridge_mod = types.ModuleType('ansible_bridge')
bridge_mod.__dict__.update(globals())
sys.modules['ansible_bridge'] = bridge_mod
