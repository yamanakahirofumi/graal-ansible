import json
import sys
import os
import types
import re
import tempfile
import hashlib
import shutil
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
    if isinstance(p, (bytes, str)):
        s = p.decode('utf-8', errors='replace') if isinstance(p, bytes) else p
    else:
        try: s = str(p)
        except: return p

    # Handle absolute paths on Windows
    if os.name == 'nt':
        # Remove leading slash if it precedes a drive letter (e.g., /C:/path -> C:/path)
        if len(s) > 2 and s[0] == '/' and s[2] == ':':
            s = s[1:]
        s = s.replace('/', '\\')
    return s

class JavaDictWrapper:
    def __init__(self, obj):
        self._obj = obj
    def __getitem__(self, key):
        return _deep_convert(self._obj.get(key))
    def __setitem__(self, key, value):
        self._obj.put(key, value)
    def __delitem__(self, key):
        self._obj.remove(key)
    def __contains__(self, key):
        return self._obj.containsKey(key)
    def __iter__(self):
        # Convert keys to list to avoid ConcurrentModificationException if needed,
        # but Java Set iterator should be fine in GraalPy if not modified during iteration.
        # Still, list() is safer.
        return iter(list(self._obj.keySet()))
    def __len__(self):
        return self._obj.size()
    def get(self, key, default=None):
        if key in self: return self[key]
        return default
    def pop(self, key, *args):
        if key in self:
            val = self[key]
            self._obj.remove(key)
            return val
        if args: return args[0]
        raise KeyError(key)
    def copy(self):
        return dict(self.items())
    def items(self):
        res = {}
        for k in self._obj.keySet():
            res[str(k)] = _deep_convert(self._obj.get(k))
        return res.items()
    def update(self, other):
        for k, v in other.items(): self[k] = v

def _deep_convert(obj):
    if obj is None: return None
    if isinstance(obj, bool): return obj
    if isinstance(obj, (int, float)): return obj
    if isinstance(obj, (str, bytes)):
        return obj.decode('utf-8', errors='replace') if isinstance(obj, bytes) else obj

    # Check for Java objects
    if hasattr(obj, 'getClass'):
        cls_name = obj.getClass().getName()
        if 'Map' in cls_name:
            return JavaDictWrapper(obj)
        if 'List' in cls_name or 'Set' in cls_name:
            res = []
            try:
                # Use java iterator for reliability
                it = obj.iterator()
                while it.hasNext():
                    res.append(_deep_convert(it.next()))
            except:
                try:
                    for i in obj:
                        res.append(_deep_convert(i))
                except: pass
            return res
        if 'TaskResult' in cls_name:
            try:
                data = _deep_convert(obj.data())
                if isinstance(data, JavaDictWrapper): data = data.copy()
                data['failed'] = not obj.success()
                data['changed'] = obj.changed()
                return data
            except: pass
        try: return str(obj)
        except: return obj

    if isinstance(obj, JavaDictWrapper):
        return obj
    if isinstance(obj, dict):
        return {str(k): _deep_convert(v) for k, v in obj.items()}
    if isinstance(obj, (list, tuple, set, frozenset)):
        return [_deep_convert(i) for i in obj]
    return obj

class CustomEncoder(json.JSONEncoder):
    def default(self, obj):
        if isinstance(obj, JavaDictWrapper):
            return obj.copy()
        return super().default(obj)

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
            for mname in ['ansible', 'ansible.module_utils', 'ansible.plugins']:
                if mname in sys.modules:
                    m = sys.modules[mname]
                    if hasattr(m, '__path__') and isinstance(m.__path__, list):
                        rel_path = mname.replace('.', '/')
                        cand = os.path.join(p_str, rel_path)
                        if os.path.exists(cand) and cand not in m.__path__:
                            m.__path__.insert(0, cand)

def setup_env(env_vars):
    if env_vars:
        for k, v in dict(env_vars).items(): os.environ[str(k)] = str(v)

# --- Mock Classes ---

class MockLoader:
    def __init__(self):
        self._basedir = os.getcwd()
    def get_basedir(self): return self._basedir
    def set_basedir(self, basedir): self._basedir = basedir
    def get_real_file(self, file_path, decrypt=True): return file_path
    def get_text_file_contents(self, file_path, loader=None):
        fp = _normalize_path(file_path)
        if not os.path.isabs(fp):
            fp = os.path.join(self._basedir, fp)
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
        self.tmpdir = tempfile.gettempdir()
    def path_has_trailing_slash(self, path):
        if isinstance(path, list): return any(str(p).endswith('/') or str(p).endswith('\\') for p in path)
        return str(path).endswith('/') or str(path).endswith('\\')
    def join_path(self, *args): return os.path.join(*args)
    def expand_user(self, path, *args, **kwargs): return path

class Display:
    def __init__(self, *args, **kwargs):
        self.verbosity = 10
    def display(self, *args, **kwargs): pass
    def debug(self, *args, **kwargs): pass
    def verbose(self, *args, **kwargs): pass
    def warning(self, *args, **kwargs): pass
    def error(self, *args, **kwargs): pass
    def deprecated(self, *args, **kwargs): pass
    def vvvv(self, *args, **kwargs): pass
    def vv(self, *args, **kwargs): pass
    def vvv(self, *args, **kwargs): pass

class PlayContext:
    def __init__(self):
        self.verbosity = 10
        self.check_mode = False
        self.diff = False
        self.become = False

class ActionBase:
    def __init__(self, task, connection, play_context, loader, templar, shared_loader_obj):
        self._task, self._connection, self._play_context = task, connection, play_context
        self._loader, self._templar = loader, templar
        self._shared_loader_obj = shared_loader_obj
        self._display = self.display = Display()
        self._supports_check_mode = True
    def run(self, tmp=None, task_vars=None): return {'changed': False, 'failed': False}
    def validate_argument_spec(self, argument_spec, *args, **kwargs):
        res = (self._task.args or {}).copy()
        if argument_spec:
            for k, v in argument_spec.items():
                if k not in res and isinstance(v, dict) and 'default' in v:
                    res[k] = v['default']
                elif k not in res:
                    res[k] = None
        return types.SimpleNamespace(error=None, warning=None), res
    def _execute_module(self, module_name=None, module_args=None, tmp=None, task_vars=None, *args, **kwargs):
        m_name = module_name or self._task.action
        m_args = module_args or self._task.args
        if 'task_executor_java' in globals():
            res = task_executor_java.execute_from_python(m_name, m_args, task_vars or {})
            if res is not None:
                r_dict = _deep_convert(res)
                if isinstance(r_dict, JavaDictWrapper): r_dict = r_dict.copy()
                if not isinstance(r_dict, dict):
                    try: r_dict = dict(r_dict)
                    except: r_dict = {'failed': True, 'msg': 'Module result not a dict'}
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
        p = _normalize_path(path)
        if os.path.exists(p):
            csum = hashlib.sha1(open(p, 'rb').read()).hexdigest()
            return {'exists': True, 'checksum': csum, 'isdir': os.path.isdir(p), 'isreg': os.path.isfile(p), 'islnk': os.path.islink(p)}
        return {'exists': False, 'checksum': None, 'isdir': False, 'isreg': False, 'islnk': False}
    def _transfer_file(self, local_path, remote_path):
        conn = _current_task_context['connection_java']
        lp, rp = _normalize_path(local_path), _normalize_path(remote_path)
        if conn:
            from java.nio.file import Paths
            conn.putFile(Paths.get(str(lp)), str(rp))
        return hashlib.sha1(open(lp, 'rb').read()).hexdigest()
    def _fixup_perms2(self, *args, **kwargs): pass

class Task:
    def __init__(self):
        self.action, self.args, self.async_val = None, {}, 0
        self._origin = types.SimpleNamespace(path=None)
        self.collections, self.tags = [], []
        self.implicit = False
        self._parent = types.SimpleNamespace(_play=types.SimpleNamespace(_action_groups={}))
        self.diff = self.check_mode = self.no_log = False
        self.delegate_to = None
        self.delegate_facts = False
        self.environment = self.module_defaults = {}
        self._role = None
        self._original_basename = None
    def get_name(self): return "mock_task"
    def copy(self):
        nt = Task()
        nt.__dict__.update(self.__dict__)
        nt.args = self.args.copy() if self.args else {}
        return nt

class MockEngine:
    def __init__(self, templar):
        self.templar = templar
    def extend(self, **kwargs):
        return self
    def evaluate_expression(self, expr):
        return self.templar.template("{{ " + expr + " }}")

class Templar:
    def __init__(self, loader=None, variables=None):
        self.available_variables = variables or {}
        self._engine = MockEngine(self)
    def template(self, msg, *args, **kwargs):
        if msg is None or not isinstance(msg, str): return msg
        if '{{' not in msg and '{%' not in msg: return msg
        try:
            import jinja2
            vars_dict = self.available_variables
            if hasattr(vars_dict, 'copy'): vars_dict = vars_dict.copy()
            elif hasattr(vars_dict, 'items'): vars_dict = dict(vars_dict.items())

            env = jinja2.Environment()
            t = env.from_string(msg)
            return t.render(vars_dict)
        except: return msg
    def resolve_to_container(self, x): return x
    def evaluate_conditional(self, conditional=None, *args, **kwargs):
        c = conditional
        if c is None and args: c = args[0]
        if c is None: c = kwargs.get('c')
        try: return eval(str(c), {}, self.available_variables)
        except: return False
    def copy_with_new_env(self, *args, **kwargs): return self

class AnsibleModule:
    def __init__(self, argument_spec, bypass_checks=False, no_log=False,
                 check_invalid_arguments=True, mutations=None,
                 supports_check_mode=False, add_file_common_args=False,
                 supports_async=False, **kwargs):
        self.params, self.aliases, self._stored_file_args = {}, {}, {}
        self.tmpdir = tempfile.gettempdir()
        spec = argument_spec.copy() if argument_spec else {}
        if add_file_common_args:
            from ansible.module_utils.basic import FILE_COMMON_ARGUMENTS
            spec.update(FILE_COMMON_ARGUMENTS)
        for k, v in spec.items():
            if isinstance(v, dict):
                self.params[k] = v.get('default')
                if 'aliases' in v:
                    for al in v['aliases']: self.aliases[al] = k
            else: self.params[k] = None
        ia = _current_task_context['complex_args'] or {}
        # Ensure complex_args is a dict for items()
        if hasattr(ia, 'items'):
            for k, v in ia.items(): self.params[self.aliases.get(k, k)] = v

        # Simple type conversion for 'path'
        for k, v in spec.items():
            if isinstance(v, dict) and v.get('type') == 'path' and self.params.get(k):
                self.params[k] = _normalize_path(self.params[k])

        for k, v in spec.items():
            if isinstance(v, dict) and v.get('required') and self.params.get(k) is None:
                self.fail_json(msg=f"missing required arguments: {k}")
        if 'path' in self.params and self.params['path'] is None:
            self.params['path'] = self.params.get('dest') or self.params.get('name')
        self.check_mode = self._diff = self._debug = False
    def exit_json(self, **kwargs):
        global _last_module_result
        if 'changed' not in kwargs: kwargs['changed'] = False
        for k, v in self._stored_file_args.items():
            if kwargs.get(k) is None: kwargs[k] = v

        res = _deep_convert(kwargs)
        if isinstance(res, JavaDictWrapper): res = res.copy()
        _last_module_result = res
        print(json.dumps(res))
        sys.exit(0)
    def fail_json(self, **kwargs):
        global _last_module_result
        kwargs['failed'] = True
        res = _deep_convert(kwargs)
        if isinstance(res, JavaDictWrapper): res = res.copy()
        _last_module_result = res
        print(json.dumps(res))
        sys.exit(1)
    def debug(self, msg): pass
    def log(self, msg, **kwargs): pass
    def warn(self, msg): pass
    def deprecate(self, msg, version=None, **kwargs): pass
    def run_command(self, args, **kwargs):
        conn = _current_task_context['connection_java']
        if conn:
            cmd_str = " ".join(args) if isinstance(args, list) else args
            env = _current_task_context.get('environment_java')
            res = conn.execCommand(cmd_str, _current_task_context['become_context_java'], env)
            return (int(res.exitCode()), str(res.stdout()), str(res.stderr()))
        return (1, '', 'No connection')
    def get_bin_path(self, arg, *a, **kw): return arg
    def sha1(self, path):
        p = _normalize_path(path)
        if os.path.exists(p) and not os.path.isdir(p): return hashlib.sha1(open(p, 'rb').read()).hexdigest()
        return None
    def md5(self, path):
        p = _normalize_path(path)
        if os.path.exists(p) and not os.path.isdir(p): return hashlib.md5(open(p, 'rb').read()).hexdigest()
        return None
    def digest_from_file(self, path, algorithm):
        if algorithm == 'sha1': return self.sha1(path)
        if algorithm == 'md5': return self.md5(path)
        return None
    def atomic_move(self, src, dest, **kwargs):
        shutil.move(_normalize_path(src), _normalize_path(dest))
    def load_file_common_arguments(self, params, path=None):
        res = {}
        for k in ['mode', 'owner', 'group', 'seuser', 'serole', 'setype', 'selevel', 'unsafe_writes', 'attributes']:
            if k in params: res[k] = params[k]
        res['path'] = _normalize_path(path or params.get('path') or params.get('dest'))
        return res
    def get_file_attributes(self, path):
        p = _normalize_path(path)
        res = {'path': p}
        if os.path.exists(p):
            st = os.stat(p)
            res['mode'] = oct(st.st_mode & 0o777)[2:].zfill(4)
        return res
    def set_fs_attributes_if_different(self, file_args, changed, diff=None, expand=True):
        if file_args: self._stored_file_args.update(file_args)
        return changed
    def set_file_attributes_if_different(self, file_args, changed, diff=None, expand=True):
        return self.set_fs_attributes_if_different(file_args, changed)
    def boolean(self, arg):
        if arg is None or isinstance(arg, bool): return arg
        return str(arg).lower() in ('yes', 'on', '1', 'true', 't', 'y')

def apply_mocks():
    def create_mock(mname, attributes=None, is_package=True):
        m = sys.modules.get(mname)
        if not m:
            m = types.ModuleType(mname)
            sys.modules[mname] = m
        if is_package:
            if not hasattr(m, '__path__'): m.__path__ = []
        if attributes:
            for k, v in attributes.items(): setattr(m, k, v)
        return m

    create_mock('ansible.errors', {
        'AnsibleError': Exception, 'AnsibleActionFail': type('AAF', (Exception,), {}), 'AnsibleTemplateError': type('ATE', (Exception,), {}),
        'AnsibleAssertionError': type('AAE', (Exception,), {}), 'AnsibleFileNotFound': type('AFNF', (Exception,), {}), 'AnsibleParserError': type('APE', (Exception,), {}),
        'AnsibleValueOmittedError': type('AVOE', (Exception,), {}), 'AnsibleConnectionFailure': type('ACF', (Exception,), {}), 'AnsibleActionSkip': type('AAS', (Exception,), {}),
        'AnsibleOptionsError': type('AOE', (Exception,), {}), 'AnsibleUndefinedConfigEntry': type('AUCE', (Exception,), {}), 'AnsibleRequiredOptionError': type('AROE', (Exception,), {}),
        'AnsibleVariableTypeError': type('AVTE', (Exception,), {}), 'AnsibleTemplateSyntaxError': type('ATSE', (Exception,), {}), 'AnsibleUndefinedVariable': type('AUV', (Exception,), {}), 'AnsibleTypeError': type('ATE', (Exception,), {}),
        'AnsibleRuntimeError': type('ARE', (Exception,), {}), 'AnsiblePluginNotFound': type('APNF', (Exception,), {}), 'AnsibleTemplatePluginError': type('ATPE', (Exception,), {}),
        'AnsibleBrokenConditionalError': type('ABCE', (Exception,), {}), 'AnsibleTemplateTransformLimitError': type('ATTLO', (Exception,), {}),
        'TemplateTrustCheckFailedError': type('TTCFE', (Exception,), {}), 'AnsibleJSONParserError': type('AJPE', (Exception,), {})
    })

    create_mock('ansible.utils.hashing', {
        'checksum': lambda p: hashlib.sha1(open(p, 'rb').read()).hexdigest() if os.path.exists(p) and not os.path.isdir(p) else None,
        'checksum_s': lambda s: hashlib.sha1(str(s).encode()).hexdigest(),
        'md5': lambda p: hashlib.md5(open(p, 'rb').read()).hexdigest() if os.path.exists(p) and not os.path.isdir(p) else None,
        'md5_s': lambda s: hashlib.md5(str(s).encode()).hexdigest(),
        'secure_hash': lambda p: hashlib.sha1(open(p, 'rb').read()).hexdigest() if os.path.exists(p) and not os.path.isdir(p) else None,
        'secure_hash_s': lambda s: hashlib.sha1(str(s).encode()).hexdigest()
    })

    create_mock('ansible.utils.display', {'Display': Display, 'display': Display()})

    class MockTag:
        def __init__(self, *args, **kwargs): pass
        @staticmethod
        def is_tagged_on(data): return False
        def tag(self, v): return v
    class MockSourceWasEncrypted(Exception):
        @staticmethod
        def is_tagged_on(data): return False
    create_mock('ansible._internal._datatag', {'SourceWasEncrypted': MockSourceWasEncrypted, '_utils': types.ModuleType('utils')})
    create_mock('ansible._internal._datatag._tags', {
        'SourceWasEncrypted': MockSourceWasEncrypted,
        'Origin': MockTag, 'VaultedValue': MockTag, 'TrustedAsTemplate': MockTag
    })

    create_mock('ansible.module_utils.basic', {
        'AnsibleModule': AnsibleModule,
        '_load_params': lambda: (_current_task_context['complex_args'] or {}, 'main'),
        'FILE_COMMON_ARGUMENTS': {'mode':{}, 'owner':{}, 'group':{}, 'seuser':{}, 'serole':{}, 'setype':{}, 'selevel':{}, 'unsafe_writes':{}, 'attributes':{}},
        'missing_required_lib': lambda *a, **kw: None,
        'is_executable': lambda p: os.access(p, os.X_OK)
    })

    def combine_vars(a, b, **kw):
        res = a.copy() if hasattr(a, 'copy') else dict(a.items() if hasattr(a, 'items') else a)
        res.update(b.items() if hasattr(b, 'items') else b)
        return res

    create_mock('ansible.utils.vars', {
        'isidentifier': lambda s: True,
        'validate_variable_name': lambda s: True,
        'merge_hash': combine_vars,
        'combine_vars': combine_vars
    })

    loader_mod = create_mock('ansible.plugins.loader')
    loader_mod.action_loader = types.SimpleNamespace()
    loader_mod.action_loader.get = lambda name, **kw: _create_action_plugin(name, kw.get('task'), kw.get('connection'), kw.get('play_context'), kw.get('loader'), kw.get('templar'), loader_mod)
    loader_mod.lookup_loader = types.SimpleNamespace()
    loader_mod.lookup_loader.get = lambda *a, **kw: None
    loader_mod.ps_module_utils_loader = types.SimpleNamespace()
    loader_mod.module_utils_loader = types.SimpleNamespace()
    loader_mod.Jinja2Loader = types.SimpleNamespace()
    loader_mod.filter_loader = types.SimpleNamespace()
    loader_mod.filter_loader._wrap_funcs = lambda *a, **kw: {}
    loader_mod.test_loader = types.SimpleNamespace()
    loader_mod.test_loader._wrap_funcs = lambda *a, **kw: {}
    loader_mod.module_loader = types.SimpleNamespace()
    loader_mod.module_loader.find_plugin = lambda *a, **kw: None
    loader_mod.module_loader.find_plugin_with_context = lambda name, **kw: types.SimpleNamespace(resolved_fqcn=name)

    create_mock('ansible.plugins.action', {'ActionBase': ActionBase, 'ActionModule': ActionBase})
    create_mock('ansible.playbook.task', {'Task': Task})
    create_mock('ansible.playbook.play_context', {'PlayContext': PlayContext})
    def trust_as_template(x):
        if isinstance(x, tuple) and len(x) > 0: return x[0]
        return x
    create_mock('ansible.template', {'Templar': Templar, 'trust_as_template': trust_as_template})

    dt_mod = create_mock('ansible.module_utils.facts.system.date_time')
    class MockDTColl:
        name = 'date_time'
        _fact_ids = set(['date_time'])
        @classmethod
        def platform_match(cls, p): return cls
        def __init__(self, *a, **kw): self.namespace = kw.get('namespace')
        def collect_with_namespace(self, module=None, collected_facts=None):
            import datetime
            now = datetime.datetime.now()
            facts = {
                'date_time': {
                    'year': now.strftime('%Y'), 'month': now.strftime('%m'), 'weekday': now.strftime('%A'),
                    'day': now.strftime('%d'), 'hour': now.strftime('%H'), 'minute': now.strftime('%M'),
                    'second': now.strftime('%S'), 'epoch': str(int(now.timestamp())),
                    'date': now.strftime('%Y-%m-%d'), 'time': now.strftime('%H:%M:%S'),
                    'iso8601': now.strftime('%Y-%m-%dT%H:%M:%SZ'), 'tz': 'UTC', 'tz_offset': '+0000'
                }
            }
            if self.namespace:
                res = {}
                for k, v in facts['date_time'].items(): res[self.namespace.prefix + k] = v
                return res
            return facts
    dt_mod.DateTimeFactCollector = MockDTColl

    ansiballz_dir = tempfile.mkdtemp()
    for f in ['_wrapper.py', '__init__.py']:
        with open(os.path.join(ansiballz_dir, f), 'w') as fh: fh.write("# mock")
    create_mock('ansible._internal._ansiballz', {
        '__file__': os.path.join(ansiballz_dir, '__init__.py'),
        '_builder': types.ModuleType('builder'),
        '_wrapper': types.ModuleType('wrapper')
    })

    create_mock('grp', {
        'getgrnam': lambda n: types.SimpleNamespace(gr_gid=0, gr_name=str(n), gr_mem=[]),
        'getgrgid': lambda i: types.SimpleNamespace(gr_name='root', gr_gid=0, gr_mem=[]),
        'getgrall': lambda: []
    }, is_package=False)
    create_mock('pwd', {
        'getpwnam': lambda n: types.SimpleNamespace(pw_uid=0, pw_gid=0, pw_name=str(n), pw_dir='/root', pw_shell='/bin/bash'),
        'getpwuid': lambda i: types.SimpleNamespace(pw_name='root', pw_uid=0, pw_gid=0, pw_dir='/root', pw_shell='/bin/bash'),
        'getpwall': lambda: []
    }, is_package=False)

    create_mock('ansible.module_utils.parsing.convert_bool', {
        'boolean': lambda x, **kw: str(x).lower() in ('yes', 'true', 't', '1', 'on', 'y')
    })

    def get_config_value(name, variables=None):
        if name == 'FACTS_MODULES': return ['ansible.legacy.setup']
        if name == 'CONNECTION_FACTS_MODULES': return {}
        return None
    create_mock('ansible.constants', {
        'DEFAULT_REMOTE_TMP': '/tmp',
        'DEFAULT_LOCAL_TMP': tempfile.gettempdir(),
        'DEFAULT_KEEP_REMOTE_FILES': False,
        'config': types.SimpleNamespace(get_config_value=get_config_value),
        '_ACTION_SETUP': frozenset(['setup', 'gather_facts', 'ansible.builtin.setup', 'ansible.builtin.gather_facts', 'ansible.legacy.setup', 'ansible.legacy.gather_facts'])
    })

    create_mock('ansible.executor.module_common', {
        '_apply_action_arg_defaults': lambda name, task, args, templar: args
    })

    create_mock('ansible._internal._errors', {})
    eu = create_mock('ansible._internal._errors._error_utils')
    eu.result_dict_from_captured_errors = lambda msg, errors: {'msg': msg, 'failed': True, 'exception': str(errors[0]) if errors else ''}
    create_mock('ansible._internal._errors._handler', {'ErrorHandler': type('ErrorHandler', (), {})})

    _template_vars = types.ModuleType('_template_vars')
    _template_vars.generate_ansible_template_vars = lambda *a, **kw: {}
    create_mock('ansible._internal._templating', {
        '_jinja_bits': types.ModuleType('jinja_bits'),
        '_template_vars': _template_vars
    })
    create_mock('ansible._internal._templating._engine', {'TemplateEngine': type('TemplateEngine', (), {})})
    create_mock('ansible._internal._templating._jinja_common', {
        'UndefinedMarker': type('UndefinedMarker', (), {}),
        'TruncationMarker': type('TruncationMarker', (), {})
    })
    create_mock('ansible._internal._templating._utils', {'Omit': type('Omit', (), {})})

    class ReplacingMarkerBehavior:
        def __init__(self, *a, **kw): pass
        def emit_warnings(self): pass
    class RoutingMarkerBehavior:
        def __init__(self, *a, **kw): pass
    create_mock('ansible._internal._templating._marker_behaviors', {
        'ReplacingMarkerBehavior': ReplacingMarkerBehavior,
        'RoutingMarkerBehavior': RoutingMarkerBehavior
    })

    create_mock('ansible.module_utils.common.validation', {
        '_check_type_str_no_conversion': lambda x: x,
        '_check_type_list_strict': lambda x: x
    })

def _create_action_plugin(action_name, task, connection, play_context, loader, templar, shared_loader_obj):
    import importlib.util
    base_name = action_name.split('.')[-1]
    path = None
    for p in sys.path:
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
        sys.modules[fqcn] = mod
        spec.loader.exec_module(mod)

    l, c = loader or MockLoader(), connection
    if not hasattr(c, '_shell'):
        class Proxy:
            def __init__(self, obj):
                self._obj = obj
                self._shell = self.shell = MockShell()
                try: cn = obj.getClass().getName()
                except: cn = ""
                self.transport = 'local' if 'LocalConnection' in cn else 'ssh'
                self.ansible_name = 'ansible.builtin.local'
                self.become = False
                self.check_password_prompt = lambda *a, **kw: False
            def fetch_file(self, in_path, out_path):
                from java.nio.file import Paths
                return self._obj.fetchFile(str(in_path), Paths.get(str(out_path)))
            def put_file(self, in_path, out_path):
                from java.nio.file import Paths
                return self._obj.putFile(Paths.get(str(in_path)), str(out_path))
            def __getattr__(self, name): return getattr(self._obj, name)
        c = Proxy(connection)

    if not hasattr(shared_loader_obj, 'module_loader'):
        shared_loader_obj.module_loader = sys.modules['ansible.plugins.loader'].module_loader

    return mod.ActionModule(task, c, play_context, l, templar, shared_loader_obj)

def execute_module(module_name, complex_args, module_code=None):
    global _last_module_result
    _last_module_result = None

    if module_name in ['setup', 'ansible.builtin.setup', 'ansible.legacy.setup']:
        res = {'ansible_facts': {'ansible_os_family': 'Mocked'}, 'changed': False}
        _last_module_result = res
        return json.dumps(res, cls=CustomEncoder)

    import __main__
    __main__._module_fqn = f"ansible.builtin.{module_name}"
    __main__.complex_args = complex_args
    old_stdout, sys.stdout = sys.stdout, StringIO()
    try:
        if module_code:
            exec(module_code, {'complex_args': complex_args, '__name__': '__main__'})
        else:
            base_name = module_name.split('.')[-1]
            path = None
            for p in sys.path:
                cand = os.path.join(p, 'ansible/modules', base_name + '.py')
                if os.path.exists(cand): path = cand; break
            if not path: return json.dumps({'failed': True, 'msg': f'Module {module_name} not found'})
            with open(path, 'rb') as f:
                exec(compile(f.read(), path, 'exec'), {'__name__': '__main__', '__file__': path, '__package__': 'ansible.modules'})

        return json.dumps(_last_module_result, cls=CustomEncoder) if _last_module_result else sys.stdout.getvalue()
    except SystemExit:
        return json.dumps(_last_module_result, cls=CustomEncoder) if _last_module_result else sys.stdout.getvalue()
    except Exception as e:
        import traceback
        return json.dumps({'failed': True, 'msg': str(e), 'traceback': traceback.format_exc()}, cls=CustomEncoder)
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
