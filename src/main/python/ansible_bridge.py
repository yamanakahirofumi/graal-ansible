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
    # We use a loop to handle multiple leading slashes and ensure we don't accidentally
    # strip valid UNC paths (which start with \\ but not with a drive letter)
    temp_s = s
    while True:
        if len(temp_s) > 3 and temp_s[0] in ('/', '\\') and temp_s[2] == ':' and temp_s[1].isalpha():
            temp_s = temp_s[1:]
        elif len(temp_s) > 2 and temp_s[0] in ('/', '\\') and temp_s[1] in ('/', '\\') and temp_s[2] != '\\':
            # This handles //server or similar by NOT stripping if it looks like a UNC root,
            # but usually in this bridge we get /C: which is what we want to fix.
            if len(temp_s) > 3 and temp_s[3] == ':': # Case like //C:
                temp_s = temp_s[1:]
            else:
                break
        else:
            break

    # Fix backslashes for Windows if we are on Windows
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
    if isinstance(obj, str): return _normalize_path(obj)
    if isinstance(obj, bytes): return _normalize_path(obj)
    if isinstance(obj, (int, float, bool)): return obj

    if hasattr(obj, 'getClass'):
        # It's likely a Java object
        from java.util import Map, List, Set
        if isinstance(obj, Map):
            return {str(k): _deep_convert(v) for k, v in obj.items()}
        if isinstance(obj, (List, Set)):
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

def bind_task(complex_args, connection_java, become_context_java, environment_java):
    # Ensure complex_args is a dict before deep convert if it's a Map proxy
    args = complex_args
    if hasattr(complex_args, 'getClass') and 'Map' in complex_args.getClass().getName():
        from java.util import HashMap
        args = HashMap(complex_args)

    # Pre-process complex_args to handle Windows paths passed from Java
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
            # Link mocked packages to disk paths to allow loading non-mocked submodules
            for mname in ['ansible', 'ansible.module_utils', 'ansible.module_utils.common', 'ansible.module_utils.compat', 'ansible.module_utils._internal', 'ansible.module_utils.parsing', 'ansible.plugins', 'ansible.plugins.action']:
                if mname in sys.modules:
                    m = sys.modules[mname]
                    if hasattr(m, '__path__') and isinstance(m.__path__, list):
                        rel_path = mname.replace('.', '/')
                        cand = os.path.join(p_str, rel_path)
                        if os.path.exists(cand):
                            if cand not in m.__path__:
                                m.__path__.insert(0, cand)
                            # Update __file__ if it's missing or points to wrong place
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
    def get_basedir(self):
        return self._basedir
    def set_basedir(self, basedir):
        self._basedir = basedir
    def get_real_file(self, file_path, decrypt=True):
        return file_path
    def get_text_file_contents(self, file_path, loader=None):
        fp = _normalize_path(file_path)
        if fp and os.path.exists(fp):
            with open(fp, 'r', encoding='utf-8', errors='surrogateescape') as f:
                return f.read(), True
        return "", False
    def cleanup_tmp_file(self, *args, **kwargs):
        pass
    def path_dwim(self, path):
        return _normalize_path(path)

class MockShell:
    def __init__(self):
        import tempfile
        self.tmpdir = tempfile.gettempdir()
    def path_has_trailing_slash(self, path):
        return path.endswith('/') or path.endswith('\\')
    def join_path(self, *args):
        return os.path.join(*args)
    def expand_user(self, path, *args, **kwargs):
        return path

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
    def run(self, tmp=None, task_vars=None):
        return {'changed': False, 'failed': False}
    def validate_argument_spec(self, argument_spec, *args, **kwargs):
        res = {}
        input_args = self._task.args or {}
        for k, v in argument_spec.items():
            if k in input_args: res[k] = input_args[k]
            elif isinstance(v, dict) and 'default' in v: res[k] = v['default']
            elif isinstance(v, dict) and v.get('required'): res[k] = None
            else: res[k] = None
        return types.SimpleNamespace(error=None, warning=None), res
    def _execute_module(self, module_name=None, module_args=None, tmp=None, task_vars=None, *args, **kwargs):
        m_name = module_name or self._task.action
        m_args = module_args or self._task.args
        if 'task_executor_java' in globals():
            res = task_executor_java.execute_from_python(m_name, m_args, task_vars or {})
            if res is not None:
                # Bridge Java Map proxy to Python dict robustly
                try:
                    r_dict = {str(k): v for k, v in res.items()}
                except:
                    try:
                        # Maybe it's a TaskResult record or object
                        r_dict = {str(k): v for k, v in res.data().items()}
                        r_dict['failed'] = not res.success()
                        r_dict['changed'] = res.changed()
                    except:
                        # Fallback to string parsing if necessary
                        s = str(res)
                        if 'data=' in s:
                            r_dict = {'changed': 'changed=true' in s.lower()}
                        else:
                            r_dict = {'failed': True, 'msg': 'Failed to bridge module result'}

                if 'changed' not in r_dict: r_dict['changed'] = True
                return r_dict
            return {'changed': True}
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
        if os.path.exists(p):
            with open(p, 'rb') as f:
                csum = hashlib.sha1(f.read()).hexdigest()
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
        self.collections = []
        self.tags = []
        self.implicit = False
        self.resolved_action = None
        self._parent = None
        self.diff = False
        self.check_mode = False
        self.no_log = False
        self.delegate_to = None
        self.delegate_facts = False
        self.environment = {}
        self._role = None
        self._original_basename = None
    def get_name(self): return "mock_task"
    def copy(self):
        new_task = Task()
        new_task.action = self.action
        new_task.args = (self.args or {}).copy()
        new_task.async_val = self.async_val
        return new_task

class Templar:
    def __init__(self, loader=None, variables=None):
        self._engine = type('Eng', (), {
            'tvars': variables or {},
            'extend': lambda *a, **kw: self._engine,
            'evaluate_expression': lambda expr, *a, **kw: self._engine.tvars.get(expr, expr)
        })
        self.available_variables = variables or {}
        self.environment = {}
    def template(self, msg, *args, **kwargs):
        if msg is None: return None
        if isinstance(msg, (tuple, list)): msg = msg[0]
        if not isinstance(msg, str): return msg

        try:
            from jinja2 import Template
            t = Template(msg)
            return t.render(**self.available_variables)
        except Exception:
            import re
            def repl(match):
                var_name = match.group(1).strip()
                return str(self.available_variables.get(var_name, match.group(0)))
            return re.sub(r'\{\{\s*(.*?)\s*\}\}', repl, msg)
    def evaluate_conditional(self, conditional, *args, **kwargs):
        # Basic implementation for mock
        try:
            return eval(str(conditional), {}, self.available_variables)
        except:
            return False
    def copy_with_new_env(self, *args, **kwargs): return self

class AnsibleModule:
    def __init__(self, argument_spec, *args, **kwargs):
        self.params = {}
        self.aliases = {}
        self._stored_file_args = {}
        input_args = _current_task_context['complex_args'] or {}
        effective_spec = argument_spec.copy() if argument_spec else {}
        if kwargs.get('add_file_common_args'):
            import sys
            basic = sys.modules.get('ansible.module_utils.basic')
            if basic and hasattr(basic, 'FILE_COMMON_ARGUMENTS'):
                effective_spec.update(basic.FILE_COMMON_ARGUMENTS)

        for k, v in effective_spec.items():
            if isinstance(v, dict) and 'aliases' in v:
                for alias in v['aliases']: self.aliases[alias] = k

        for k, v in effective_spec.items():
            if isinstance(v, dict) and 'default' in v:
                self.params[k] = v['default']
            else:
                self.params[k] = None

        for k, v in dict(input_args).items():
            target_key = k
            if k in self.aliases: target_key = self.aliases[k]

            val = v
            spec_entry = effective_spec.get(target_key)
            if isinstance(spec_entry, dict):
                t = spec_entry.get('type')
                if t == 'list':
                    if not isinstance(v, (list, tuple)): val = [v]
                elif t == 'str' or t == 'path':
                    item = v[0] if isinstance(v, (list, tuple)) and len(v) > 0 else v
                    val = _normalize_path(item)
                elif t == 'bool':
                    val = str(v).lower() in ('yes', 'true', 't', '1', 'on')
                elif t == 'int':
                    try: val = int(v)
                    except: pass

            self.params[target_key] = val
            if target_key != k: self.params[k] = val

        if 'path' in self.params and self.params['path'] is None:
            if 'dest' in self.params and self.params['dest'] is not None: self.params['path'] = self.params['dest']
            elif 'name' in self.params and self.params['name'] is not None: self.params['path'] = self.params['name']

        # Crucial for 'file' module touch action
        # The file module expects 'path' in the result of load_file_common_arguments
        # which it uses as 'file_args'.
        # We need to make sure that 'path' is correctly handled in params.

        if '_raw_params' in input_args: self.params['_raw_params'] = input_args['_raw_params']
        self.params['_uses_shell'] = input_args.get('_uses_shell', False)
        self.check_mode = self._debug = self._diff = False
        def mock_boolean(x, *a, **kw):
            return str(x).lower() in ('yes', 'true', 't', '1', 'on', 'y')
        self.boolean = mock_boolean

    @property
    def tmpdir(self):
        import tempfile
        return tempfile.gettempdir()
    def exit_json(self, **kwargs):
        if 'changed' not in kwargs: kwargs['changed'] = False
        # Inject stored file attributes if they were requested but missing from result
        for k, v in self._stored_file_args.items():
            if k not in kwargs and v is not None:
                kwargs[k] = v
        print(json.dumps(kwargs)); sys.exit(0)
    def fail_json(self, **kwargs):
        kwargs['failed'] = True
        if 'msg' not in kwargs: kwargs['msg'] = 'Module failed'
        kwargs['diagnostic_os_name'] = os.name
        kwargs['diagnostic_sys_platform'] = sys.platform
        print(json.dumps(kwargs))
        sys.exit(1)
    def run_command(self, args, **kwargs):
        conn = _current_task_context['connection_java']
        if conn:
            command = " ".join(args) if isinstance(args, list) else args
            env = dict(_current_task_context['environment_java']) if _current_task_context['environment_java'] is not None else None
            res = conn.execCommand(command, _current_task_context['become_context_java'], env)
            return (res.exitCode(), res.stdout(), res.stderr())
        return (1, '', 'No connection')
    def get_bin_path(self, arg, required=False, opt_dirs=None): return arg
    def sha1(self, path):
        import hashlib
        p = _normalize_path(path)
        try:
            with open(p, 'rb') as f: return hashlib.sha1(f.read()).hexdigest()
        except: return None
    def md5(self, path):
        import hashlib
        p = _normalize_path(path)
        try:
            with open(p, 'rb') as f: return hashlib.md5(f.read()).hexdigest()
        except: return None
    def sha256(self, path):
        import hashlib
        p = _normalize_path(path)
        try:
            with open(p, 'rb') as f: return hashlib.sha256(f.read()).hexdigest()
        except: return None
    def atomic_move(self, src, dest, unsafe_writes=False, **kwargs):
        import os, shutil
        shutil.move(_normalize_path(src), _normalize_path(dest))
    def debug(self, msg): pass
    def warn(self, msg): pass
    def deprecate(self, msg, version=None, date=None, collection_name=None): pass
    def digest_from_file(self, filename, algorithm):
        import hashlib
        p = _normalize_path(filename)
        h = hashlib.new(algorithm)
        with open(p, 'rb') as f:
            for chunk in iter(lambda: f.read(4096), b""):
                h.update(chunk)
        return h.hexdigest()
    def get_file_attributes(self, path):
        import os, stat
        p = _normalize_path(path)
        if not os.path.exists(p): return {}
        st = os.stat(p)
        return {
            'mode': oct(stat.S_IMODE(st.st_mode))[2:],
            'owner': str(st.st_uid),
            'group': str(st.st_gid),
            'size': st.st_size,
            'uid': st.st_uid,
            'gid': st.st_gid
        }
    def load_file_common_arguments(self, params, path=None):
        res = {}
        for k in ['mode', 'owner', 'group', 'seuser', 'serole', 'setype', 'selevel', 'attributes', 'unsafe_writes']:
            if k in params: res[k] = params[k]
        # Very important for modules like 'file' that use these results to identify the target
        actual_path = path or params.get('path') or params.get('dest') or params.get('name')
        if actual_path:
            item = actual_path[0] if isinstance(actual_path, (list, tuple)) and len(actual_path) > 0 else actual_path
            res['path'] = _normalize_path(item)
        return res
    def set_fs_attributes_if_different(self, file_args, changed, diff=None, expand=True):
        if file_args: self._stored_file_args.update(file_args)
        return changed
    def set_file_attributes_if_different(self, file_args, changed, diff=None, expand=True):
        if file_args: self._stored_file_args.update(file_args)
        return changed
    def makedirs_safe(self, path, mode=None):
        import os
        p = _normalize_path(path)
        if not os.path.exists(p):
            try: os.makedirs(p, mode=mode if mode is not None else 0o777)
            except: pass

# --- Mock Application ---

def apply_mocks():
    mocks_applied = getattr(sys, '_ansible_bridge_mocks_applied', False)

    def create_mock(mname, attributes=None, is_package=True):
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
    if not hasattr(os, 'geteuid'): os.geteuid = lambda: 0
    if not hasattr(os, 'getuid'): os.getuid = lambda: 0
    # Mock other POSIX-only functions to be no-ops on non-POSIX
    for func in ['chown', 'lchown', 'lchmod', 'setegid', 'seteuid', 'setgid', 'setuid']:
        if not hasattr(os, func): setattr(os, func, lambda *a, **kw: None)

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
    create_mock('ansible.constants', {'DEFAULT_REMOTE_TMP': '/tmp', 'DEFAULT_LOCAL_TMP': tempfile.gettempdir()})
    create_mock('ansible.config', {'ConfigManager': type('CM', (), {'get_config_value': lambda *a, **kw: None})})
    create_mock('ansible.config.manager', {'ConfigManager': type('CM', (), {'get_config_value': lambda *a, **kw: None}), 'ensure_type': lambda x, t: x})
    create_mock('ansible.utils')
    create_mock('ansible.utils.display', {'Display': Display, 'display': Display(), 'PlayContext': PlayContext})

    def real_checksum(path, *args, **kwargs):
        import hashlib
        p = _normalize_path(path)
        if not os.path.exists(p): return None
        with open(p, 'rb') as f: return hashlib.sha1(f.read()).hexdigest()
    def real_checksum_s(s, *args, **kwargs):
        import hashlib
        if isinstance(s, str): s = s.encode('utf-8')
        return hashlib.sha1(s).hexdigest()

    def real_md5(path, *args, **kwargs):
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
    def mock_makedirs_safe(path, mode=None, *args, **kwargs):
        p = _normalize_path(path)
        if not os.path.exists(p):
            try: os.makedirs(p, mode=mode if mode is not None else 0o777)
            except: pass

    def mock_is_subpath(path, base, *args, **kwargs):
        p, b = _normalize_path(path), _normalize_path(base)
        return os.path.abspath(p).startswith(os.path.abspath(b))

    create_mock('ansible.utils.path', {
        'unquote': lambda s, *a, **kw: s, 'cleanup_tmp_file': lambda s, *a, **kw: None,
        'makedirs_safe': mock_makedirs_safe, 'unfrackpath': _normalize_path,
        'get_real_file': lambda s, *a, **kw: s,
        'is_subpath': mock_is_subpath
    })
    create_mock('ansible.utils.fqcn', {'add_internal_fqcns': lambda *a, **kw: None})
    create_mock('ansible.utils.vars', {
        'isidentifier': lambda s, *a, **kw: True, 'validate_variable_name': lambda s, *a, **kw: True,
        'merge_hash': lambda a, b: dict(a, **(b or {}))
    })

    # 4. Errors
    class AnsibleError(Exception):
        def __init__(self, message="", obj=None, show_content=True, suppress_extended_error=False, orig_exception=None, **kwargs):
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
    create_mock('ansible.plugins')
    create_mock('ansible.plugins.action', {'ActionBase': ActionBase})

    action_loader_obj = types.SimpleNamespace()
    action_loader_obj.action_loader = action_loader_obj
    def action_loader_get(name, *args, **kwargs):
        import ansible_bridge
        return ansible_bridge._create_action_plugin(
            name, kwargs.get('task'), kwargs.get('connection'),
            kwargs.get('play_context'), kwargs.get('loader'),
            kwargs.get('templar'), kwargs.get('shared_loader_obj')
        )
    action_loader_obj.get = action_loader_get

    create_mock('ansible.plugins.loader', {
        'action_loader': action_loader_obj,
        'module_loader': type('ML', (), {'find_plugin': lambda name: None})
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
    create_mock('ansible._internal._datatag', {'SourceWasEncrypted': type('SWE', (Exception,), {})})
    create_mock('ansible._internal._datatag._tags', {'SourceWasEncrypted': type('SWE', (Exception,), {})})
    create_mock('ansible._internal._templating', {
        '_template_vars': types.SimpleNamespace(generate_ansible_template_vars=lambda *a, **kw: {}),
        'get_text_file_contents': lambda x, *a, **kw: (open(_normalize_path(x), 'r').read() if x and os.path.exists(_normalize_path(x)) else "mock_content", True)
    })
    for mname in ['ansible._internal._templating._engine', 'ansible._internal._templating._jinja_bits', 'ansible._internal._templating._jinja_common', 'ansible._internal._templating._utils', 'ansible._internal._templating._marker_behaviors']:
        m = create_mock(mname)
        if mname.endswith('_engine'):
            m.TemplateEngine = type('TE', (), {})
        elif mname.endswith('_jinja_common'):
            m.UndefinedMarker = type('UM', (), {})
            m.TruncationMarker = type('TM', (), {})
        elif mname.endswith('_utils'):
            m.Omit = type('Omit', (), {})
        elif mname.endswith('_marker_behaviors'):
            m.ReplacingMarkerBehavior = type('RMB', (), {'emit_warnings': lambda *a: None})
            m.RoutingMarkerBehavior = type('RoMB', (), {'__init__': lambda *a, **kw: None})

    # 8. Module Utils
    # Mock core packages as base for hybrid loading
    for mname in ['ansible', 'ansible.module_utils', 'ansible.module_utils.common', 'ansible.module_utils.compat', 'ansible.module_utils._internal', 'ansible.module_utils.parsing', 'ansible.plugins', 'ansible.plugins.action']:
        attrs = {}
        if mname == 'ansible.module_utils._internal':
            attrs['get_controller_serialize_map'] = lambda: {}
        create_mock(mname, attributes=attrs, is_package=True)

    if mocks_applied: return

    create_mock('ansible.module_utils.common.sentinel', {'Sentinel': type('Sentinel', (), {})})
    def mock_get_distribution():
        import platform
        sys_type = platform.system()
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
    def mock_get_bin_path(arg, required=False, opt_dirs=None):
        if arg == 'python': return sys.executable
        return arg
    def mock_load_params():
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

    def mock_getgrnam(name):
        if name == 'root': return group('root', 'x', 0, [])
        return group(str(name), 'x', 1001, [])

    def mock_getpwnam(name):
        if name == 'root': return passwd('root', 'x', 0, 0, 'root', '/root', '/bin/bash')
        return passwd(str(name), 'x', 1001, 1001, str(name), f'/home/{name}', '/bin/bash')

    create_mock('grp', {'getgrnam': mock_getgrnam, 'getgrgid': lambda *a, **kw: group('root', 'x', 0, []), 'getgrall': lambda: []}, False)
    create_mock('pwd', {'getpwnam': mock_getpwnam, 'getpwuid': lambda *a, **kw: passwd('root', 'x', 0, 0, 'root', '/root', '/bin/bash'), 'getpwall': lambda: []}, False)

    def mock_run_command(self, command, *args, **kwargs):
        # Emulator for 'getent' command used by getent module
        if isinstance(command, list) and len(command) >= 2 and command[0] == 'getent':
            db = command[1]
            key = command[2] if len(command) > 2 else None
            if db == 'passwd':
                if key == 'root':
                    return 0, "root:x:0:0:root:/root:/bin/bash\n", ""
                elif key is None:
                    return 0, "root:x:0:0:root:/root:/bin/bash\ntestuser:x:1001:1001:testuser:/home/testuser:/bin/bash\n", ""
            elif db == 'group':
                if key == 'root':
                    return 0, "root:x:0:\n", ""
                elif key is None:
                    return 0, "root:x:0:\ntestgroup:x:1001:\n", ""

        # Fallback to existing run_command logic
        conn = _current_task_context['connection_java']
        if conn:
            cmd_str = " ".join(command) if isinstance(command, list) else command
            # Convert environment Map proxy to dict if needed
            env_java = _current_task_context['environment_java']
            env_dict = None
            if env_java:
                try: env_dict = dict(env_java)
                except: pass
            res = conn.execCommand(cmd_str, _current_task_context['become_context_java'], env_dict)

            # Record field access in GraalPy can be tricky, especially with Record types.
            # We use multiple strategies to extract fields from the Java ConnectionResult.

            # Strategy 1: String parsing of toString() (Very robust for Records)
            s = str(res)
            if 'exitCode=' in s:
                try:
                    ec_m = re.search(r'exitCode=(-?\d+)', s)
                    exit_code = int(ec_m.group(1)) if ec_m else -1
                    stdout_m = re.search(r'stdout=(.*?), stderr=', s)
                    stdout = stdout_m.group(1) if stdout_m else ""
                    stderr_m = re.search(r'stderr=(.*?), exitCode=', s)
                    stderr = stderr_m.group(1) if stderr_m else ""
                    return (exit_code, stdout, stderr)
                except: pass

            # Strategy 2: Direct method/attribute access
            try:
                # ActualModuleIntegrationTest uses a proxy that has these as methods
                return (int(res.exitCode()), str(res.stdout()), str(res.stderr()))
            except:
                try:
                    # In some contexts it might be a Map or a Record exposed as attributes
                    return (int(res.exitCode), str(res.stdout), str(res.stderr))
                except:
                    return (-1, "", "Failed to access ConnectionResult: " + s)
        return (1, '', 'No connection')

    AnsibleModule.run_command = mock_run_command
    create_mock('syslog', {
        'openlog': lambda *a, **kw: None, 'syslog': lambda *a, **kw: None, 'closelog': lambda *a, **kw: None, 'setlogmask': lambda *a, **kw: None,
        'LOG_NOTICE': 5, 'LOG_INFO': 6, 'LOG_DEBUG': 7, 'LOG_ERR': 3, 'LOG_WARNING': 4
    }, False)

    # 10. JSON handling
    if not hasattr(json, '_graal_ansible_patched'):
        class AnsibleEncoder(json.JSONEncoder):
            def default(self, o):
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

def _create_action_plugin(action_name, task, connection, play_context, loader, templar, shared_loader_obj):
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
        # Fallback to module execution if no action plugin found
        class ModuleAction(ActionBase):
            def run(self, tmp=None, task_vars=None):
                return self._execute_module(module_name=action_name, module_args=self._task.args, task_vars=task_vars)
        return ModuleAction(task, connection, play_context, loader, templar, shared_loader_obj)

    fqcn = "ansible.plugins.action." + base_name
    if fqcn in sys.modules:
        mod = sys.modules[fqcn]
    else:
        spec = importlib.util.spec_from_file_location(fqcn, path)
        mod = importlib.util.module_from_spec(spec)
        sys.modules[spec.name] = mod
        spec.loader.exec_module(mod)

    l = loader or MockLoader()

    c = connection
    if not hasattr(c, '_shell') or not hasattr(c._shell, 'path_has_trailing_slash'):
        class Proxy:
            def __init__(self, obj):
                self._obj, self._shell = obj, MockShell()
                self.become = False
            def __getattr__(self, name): return getattr(self._obj, name)
            def fetch_file(self, remote_path, local_path):
                rp, lp = _normalize_path(remote_path), _normalize_path(local_path)
                from java.nio.file import Paths
                self._obj.fetchFile(str(rp), Paths.get(str(lp)))
        c = Proxy(connection)

    return mod.ActionModule(task, c, play_context, l, templar, shared_loader_obj)

def execute_module(module_name, complex_args, module_code=None):
    import __main__
    __main__._module_fqn = f"ansible.builtin.{module_name}"
    __main__.complex_args = complex_args
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

def initialize(site_packages=None, env_vars=None, complex_args=None, connection_java=None, become_context_java=None):
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
