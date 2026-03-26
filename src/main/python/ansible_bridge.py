import json
import sys
import os
import types
from io import StringIO

# Global context to hold current task state
_current_task_context = {
    'complex_args': {},
    'connection_java': None,
    'become_context_java': None,
    'environment_java': None
}

def _deep_convert(obj):
    if hasattr(obj, 'items'):
        return {str(k): _deep_convert(v) for k, v in obj.items()}
    elif isinstance(obj, (list, tuple)):
        return [_deep_convert(v) for v in obj]
    elif hasattr(obj, '__iter__') and not isinstance(obj, (str, bytes)):
        try: return [_deep_convert(v) for v in obj]
        except: return obj
    return obj

def bind_task(complex_args, connection_java, become_context_java, environment_java):
    if complex_args is not None:
        complex_args = _deep_convert(complex_args)
        if not isinstance(complex_args, dict):
            try: complex_args = dict(complex_args)
            except: pass

    _current_task_context.update({
        'complex_args': complex_args or {},
        'connection_java': connection_java,
        'become_context_java': become_context_java,
        'environment_java': environment_java
    })

def setup_sys_path(site_packages):
    if site_packages:
        for p in site_packages:
            p_str = str(p)
            if p_str not in sys.path: sys.path.append(p_str)
            # Link mocked packages to disk paths to allow loading non-mocked submodules
            for mname in ['ansible', 'ansible.module_utils', 'ansible.module_utils.common', 'ansible.module_utils.compat', 'ansible.module_utils._internal', 'ansible.module_utils.parsing']:
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
        if file_path and os.path.exists(file_path):
            with open(file_path, 'r', encoding='utf-8', errors='surrogateescape') as f:
                return f.read(), True
        return "", False
    def cleanup_tmp_file(self, *args, **kwargs):
        pass

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
        missing = []
        for k, v in argument_spec.items():
            if k in input_args: res[k] = input_args[k]
            elif isinstance(v, dict) and 'default' in v: res[k] = v['default']
            elif isinstance(v, dict) and v.get('required'):
                res[k] = None
                missing.append(k)
            else: res[k] = None
        return types.SimpleNamespace(error=None, warning=None), res
    def _execute_module(self, module_name=None, module_args=None, tmp=None, task_vars=None, *args, **kwargs):
        m_name = module_name or self._task.action
        m_args = module_args or self._task.args
        if 'task_executor_java' in globals():
            res = task_executor_java.execute_from_python(m_name, m_args, task_vars or {})
            if res is not None:
                r_dict = dict(res)
                if 'changed' not in r_dict: r_dict['changed'] = True
                return r_dict
            return {'changed': True}
        return {'failed': True, 'msg': 'task_executor_java not available'}
    def _remove_tmp_path(self, *args, **kwargs): pass
    def _find_needle(self, name, needle, *args, **kwargs):
        if needle and 'task_executor_java' in globals():
            res = task_executor_java.resolveLocalPath(needle)
            if res: return str(res)
        return needle
    def _remote_expand_user(self, path, *args, **kwargs): return path
    def _execute_remote_stat(self, path, all_vars, follow=False, *args, **kwargs):
        import hashlib
        if os.path.exists(path):
            with open(path, 'rb') as f:
                csum = hashlib.sha1(f.read()).hexdigest()
            return {'exists': True, 'checksum': csum, 'isdir': os.path.isdir(path), 'isreg': os.path.isfile(path), 'islnk': os.path.islink(path)}
        return {'exists': False, 'checksum': None, 'isdir': False, 'isreg': False, 'islnk': False}
    def _transfer_file(self, local_path, remote_path):
        conn = _current_task_context['connection_java']
        if conn:
            from java.nio.file import Paths
            conn.putFile(Paths.get(str(local_path)), str(remote_path))
        import hashlib
        return hashlib.sha1(open(local_path, 'rb').read()).hexdigest()
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
    def copy_with_new_env(self, *args, **kwargs): return self

class AnsibleModule:
    def __init__(self, argument_spec, *args, **kwargs):
        self.params = {}
        self.aliases = {}
        input_args = _current_task_context['complex_args'] or {}
        effective_spec = argument_spec.copy() if argument_spec else {}
        if kwargs.get('add_file_common_args'):
            effective_spec.update(sys.modules['ansible.module_utils.basic'].FILE_COMMON_ARGUMENTS)

        missing = []
        for k, v in effective_spec.items():
            if k in input_args:
                self.params[k] = input_args[k]
            elif isinstance(v, dict) and 'default' in v:
                self.params[k] = v['default']
            elif isinstance(v, dict) and v.get('required'):
                missing.append(k)
            else:
                self.params[k] = None

        if missing and not input_args:
            self.fail_json(msg=f"missing required arguments: {', '.join(missing)}")

        for k, v in input_args.items():
            if k not in self.params: self.params[k] = v
        if '_raw_params' in input_args: self.params['_raw_params'] = input_args['_raw_params']
        self.params['_uses_shell'] = input_args.get('_uses_shell', False)
        self.check_mode = input_args.get('_ansible_check_mode', False)

        # Force common parameters to string if they are bytes or Graal values
        for key in ['path', 'dest', 'src', 'regexp', 'replace', 'line']:
            if key in self.params:
                val = self.params[key]
                if isinstance(val, bytes):
                    self.params[key] = val.decode('utf-8')
                elif hasattr(val, 'decode'):
                    try: self.params[key] = val.decode('utf-8')
                    except: pass

        # Resolve relative paths
        for key in ['path', 'dest']:
            if key in self.params and self.params[key]:
                p = str(self.params[key])
                if not os.path.isabs(p) and 'task_executor_java' in globals():
                    res = task_executor_java.resolveLocalPath(p)
                    if res: self.params[key] = str(res)
                else:
                    self.params[key] = p

        self._debug = self._diff = False
        self.tmpdir = "/tmp"

    def exit_json(self, **kwargs):
        if 'changed' not in kwargs: kwargs['changed'] = False
        # Inject file attributes if they were requested but not in the result
        for key in ['mode', 'owner', 'group', 'seuser', 'serole', 'setype', 'selevel', 'attributes']:
            if key in self.params and key not in kwargs:
                kwargs[key] = self.params[key]
        print(json.dumps(kwargs)); sys.exit(0)
    def fail_json(self, **kwargs):
        kwargs['failed'] = True
        if 'msg' not in kwargs: kwargs['msg'] = 'Module failed'
        print(json.dumps(kwargs))
        sys.exit(1)
    def warn(self, msg): pass
    def debug(self, msg): pass
    def run_command(self, args, **kwargs):
        if isinstance(args, list):
            args = [item.decode('utf-8') if isinstance(item, bytes) else str(item) for item in args]
        elif isinstance(args, bytes):
            args = args.decode('utf-8')

        conn = _current_task_context['connection_java']
        if conn:
            command = " ".join(args) if isinstance(args, list) else args
            env = dict(_current_task_context['environment_java']) if _current_task_context['environment_java'] is not None else None
            res = conn.execCommand(command, _current_task_context['become_context_java'], env)
            return (res.exitCode(), res.stdout(), res.stderr())

        import subprocess
        try:
            p = subprocess.Popen(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE, shell=isinstance(args, str))
            stdout, stderr = p.communicate()
            return (p.returncode, stdout.decode('utf-8'), stderr.decode('utf-8'))
        except Exception as e:
            return (1, '', str(e))
    def get_bin_path(self, arg, required=False, opt_dirs=None): return arg
    def sha1(self, path):
        import hashlib
        try:
            with open(path, 'rb') as f: return hashlib.sha1(f.read()).hexdigest()
        except: return None
    def md5(self, path):
        import hashlib
        try:
            with open(path, 'rb') as f: return hashlib.md5(f.read()).hexdigest()
        except: return None
    def sha256(self, path):
        import hashlib
        try:
            with open(path, 'rb') as f: return hashlib.sha256(f.read()).hexdigest()
        except: return None
    def atomic_move(self, src, dest, unsafe_writes=False, **kwargs):
        import os, shutil
        shutil.move(src, dest)
    def load_file_common_arguments(self, params, path=None):
        res = {}
        for k in ['mode', 'owner', 'group', 'seuser', 'serole', 'setype', 'selevel', 'attributes', 'unsafe_writes']:
            if k in params: res[k] = params[k]
        return res
    def set_fs_attributes_if_different(self, file_args, changed, diff=None, expand=True):
        return changed

    def set_file_attributes_if_different(self, file_args, changed, diff=None, expand=True):
        return self.set_fs_attributes_if_different(file_args, changed, diff, expand)

# --- Mock Application ---

def apply_mocks():
    mocks_applied = getattr(sys, '_ansible_bridge_mocks_applied', False)

    def create_mock(mname, attributes=None, is_package=True):
        parts = mname.split('.')
        for i in range(len(parts)):
            parent_name = '.'.join(parts[:i+1])
            if parent_name not in sys.modules:
                m = types.ModuleType(parent_name)
                if is_package or i < len(parts) - 1:
                    m.__path__ = []
                sys.modules[parent_name] = m
            if i > 0:
                setattr(sys.modules['.'.join(parts[:i])], parts[i], sys.modules[parent_name])

        m = sys.modules[mname]
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
    create_mock('syslog', {'openlog': lambda *a, **kw: None, 'syslog': lambda *a, **kw: None, 'closelog': lambda *a, **kw: None, 'setlogmask': lambda *a, **kw: None,
                          'LOG_NOTICE': 5, 'LOG_INFO': 6, 'LOG_DEBUG': 7, 'LOG_ERR': 3, 'LOG_WARNING': 4}, False)
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
        if not os.path.exists(path): return None
        with open(path, 'rb') as f: return hashlib.sha1(f.read()).hexdigest()
    def real_checksum_s(s, *args, **kwargs):
        import hashlib
        if isinstance(s, str): s = s.encode('utf-8')
        return hashlib.sha1(s).hexdigest()

    create_mock('ansible.utils.hashing', {
        'checksum_s': real_checksum_s,
        'checksum': real_checksum,
        'secure_hash': real_checksum,
        'secure_hash_s': real_checksum_s
    })

    # 3. Utils
    create_mock('ansible.utils.path', {
        'unquote': lambda s, *a, **kw: s, 'cleanup_tmp_file': lambda s, *a, **kw: None,
        'makedirs_safe': lambda s, *a, **kw: None, 'unfrackpath': lambda s, *a, **kw: s,
        'get_real_file': lambda s, *a, **kw: s
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
    class AnsibleFileNotFound(AnsibleError): pass
    create_mock('ansible.errors', {
        'AnsibleError': AnsibleError, 'AnsibleValueOmittedError': AnsibleValueOmittedError,
        'AnsibleActionFail': AnsibleActionFail, 'AnsibleActionSkip': AnsibleActionSkip,
        'AnsibleFileNotFound': AnsibleFileNotFound
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
    create_mock('ansible._internal._templating', {
        '_template_vars': types.SimpleNamespace(generate_ansible_template_vars=lambda *a, **kw: {}),
        'get_text_file_contents': lambda x, *a, **kw: (open(x, 'r').read() if x and os.path.exists(x) else "mock_content", True)
    })
    for mname in ['ansible._internal._templating._jinja_common', 'ansible._internal._templating._utils', 'ansible._internal._templating._marker_behaviors']:
        m = create_mock(mname)
        if mname.endswith('_jinja_common'):
            m.UndefinedMarker = type('UM', (), {})
            m.TruncationMarker = type('TM', (), {})
        elif mname.endswith('_utils'):
            m.Omit = type('Omit', (), {})
        elif mname.endswith('_marker_behaviors'):
            m.ReplacingMarkerBehavior = type('RMB', (), {'emit_warnings': lambda *a: None})
            m.RoutingMarkerBehavior = type('RoMB', (), {'__init__': lambda *a, **kw: None})

    # 8. Module Utils
    # Mock core packages as base for hybrid loading
    for mname in ['ansible', 'ansible.module_utils', 'ansible.module_utils.common', 'ansible.module_utils.compat', 'ansible.module_utils._internal', 'ansible.module_utils.parsing']:
        attrs = {}
        if mname == 'ansible.module_utils._internal':
            attrs['get_controller_serialize_map'] = lambda: {}
        create_mock(mname, attributes=attrs, is_package=True)

    if mocks_applied: return

    # New mocks for lineinfile/replace and other standard modules
    for m in ['facts', 'facts.system', 'facts.collector', 'facts.utils', 'facts.packages',
              'facts.namespace', 'facts.ansible_collector', 'facts.system.chroot',
              'facts.system.service_mgr', 'facts.system.distribution',
              'urls', 'six', 'compat', 'compat.version', 'service', 'pycompat24', 'distro', 'yumdnf',
              'common', 'common.sentinel', 'common.respawn', 'common.file', 'common.locale',
              'common.collections', 'common.sys_info', 'common.text', 'common.text.converters',
              'common.process', 'common.validation', 'common.parameters']:
        create_mock(f'ansible.module_utils.{m}')

    create_mock('ansible.module_utils.yumdnf', {
        'YumDnf': type('YumDnf', (), {}),
        'yumdnf_argument_spec': lambda *a, **kw: {}
    })
    create_mock('ansible.module_utils.common.process', {'get_bin_path': lambda x, *a, **kw: x})

    import stat as stat_mod
    create_mock('ansible.module_utils.common.file', {
        'is_executable': lambda x: os.path.isfile(x) and os.access(x, os.X_OK),
        'S_IRWXU_RXG_RXO': stat_mod.S_IRWXU | (stat_mod.S_IRGRP | stat_mod.S_IXGRP) | (stat_mod.S_IROTH | stat_mod.S_IXOTH),
        'S_IRWU_RG_RO': stat_mod.S_IRUSR | stat_mod.S_IWUSR | stat_mod.S_IRGRP | stat_mod.S_IROTH,
        'S_IRWU_RWG_RWO': stat_mod.S_IRUSR | stat_mod.S_IWUSR | stat_mod.S_IRGRP | stat_mod.S_IWGRP | stat_mod.S_IROTH | stat_mod.S_IWOTH,
    })
    create_mock('ansible.module_utils.common.sentinel', {'Sentinel': type('Sentinel', (), {})})
    create_mock('ansible.module_utils.common.respawn', {
        'respawn_module': lambda *a, **kw: None,
        'has_respawned': lambda *a, **kw: False,
        'probe_interpreters_for_module': lambda *a, **kw: None
    })
    create_mock('ansible.module_utils.common.sys_info', {
        'get_distribution': lambda: 'Linux',
        'get_distribution_version': lambda: '20.04',
        'get_platform_subclass': lambda cls: cls
    })
    create_mock('ansible.module_utils.common.locale', {'get_best_parsable_locale': lambda *a, **kw: 'C'})
    create_mock('ansible.module_utils.common.collections', {
        'is_iterable': lambda x, *a, **kw: hasattr(x, '__iter__') and not isinstance(x, (str, bytes)),
        'is_sequence': lambda x: isinstance(x, (list, tuple))
    })
    create_mock('ansible.module_utils.compat.version', {'LooseVersion': lambda x: x, 'StrictVersion': lambda x: x})
    from urllib.parse import urlparse
    create_mock('ansible.module_utils.urls', {
        'fetch_url': lambda *a, **kw: (None, {'status': 404}),
        'fetch_file': lambda *a, **kw: None,
        'url_argument_spec': lambda *a, **kw: {},
        'get_response_filename': lambda *a, **kw: None,
        'parse_content_type': lambda *a, **kw: (None, {}),
        'generic_urlparse': lambda *a, **kw: None,
        'prepare_multipart': lambda *a, **kw: None,
        'open_url': lambda *a, **kw: None,
        'url_redirect_argument_spec': lambda *a, **kw: {},
        'get_user_agent': lambda *a, **kw: 'ansible-agent',
        'urlparse': urlparse
    })
    create_mock('ansible.module_utils.facts.ansible_collector', {'get_ansible_collector': lambda *a, **kw: type('AC', (), {'collect': lambda *a, **kw: {'ansible_os_family': 'Linux', 'ansible_system': 'Linux'}})})
    facts_mod = create_mock('ansible.module_utils.facts', {
        'default_collectors': type('DC', (), {'collectors': []}),
        'timeout': type('TO', (), {'TimeoutError': Exception})
    })
    create_mock('ansible.module_utils.facts.collector', {
        'BaseFactCollector': type('BFC', (), {}),
        'CollectorNotFoundError': Exception,
        'find_collectors_for_platform': lambda *a, **kw: [],
        'CycleFoundInFactDeps': Exception,
        'UnresolvedFactDep': Exception
    })
    create_mock('ansible.module_utils.facts.namespace', {'PrefixFactNamespace': type('PFN', (), {'__init__': lambda *a, **kw: None})})
    create_mock('ansible.module_utils.facts.system.chroot', {'is_chroot': lambda: False})
    create_mock('ansible.module_utils.facts.system.service_mgr', {'ServiceMgrFactCollector': type('SMFC', (), {})})
    create_mock('ansible.module_utils.facts.utils', {
        'get_file_content': lambda *a, **kw: None,
        'get_mount_size': lambda *a, **kw: {},
        'get_file_lines': lambda *a, **kw: []
    })
    create_mock('ansible.module_utils.facts.packages', {
        'CLIMgr': type('CLIMgr', (), {}),
        'RespawningLibMgr': type('RespawningLibMgr', (), {}),
        'get_all_pkg_managers': lambda *a, **kw: {}
    })
    create_mock('ansible.module_utils.service', {
        'fail_if_missing': lambda *a, **kw: None,
        'is_systemd_managed': lambda *a, **kw: False,
        'sysv_is_enabled': lambda *a, **kw: False,
        'get_sysv_script': lambda *a, **kw: None,
        'sysv_exists': lambda *a, **kw: False,
        'get_ps': lambda *a, **kw: None,
        'daemonize': lambda *a, **kw: None
    })

    create_mock('ansible.module_utils.common.text', is_package=True)

    def to_bytes(s, encoding='utf-8', errors='surrogate_or_strict', nonstring='simplerepr'):
        if isinstance(s, bytes): return s
        if s is None: return None
        return str(s).encode(encoding, errors)

    def to_text(s, encoding='utf-8', errors='surrogate_or_strict', nonstring='simplerepr'):
        if isinstance(s, str): return s
        if s is None: return None
        if isinstance(s, bytes): return s.decode(encoding, errors)
        return str(s)

    create_mock('ansible.module_utils.common.text.converters', {
        'to_bytes': to_bytes,
        'to_text': to_text,
        'to_native': to_text,
        'to_unicode': to_text
    })
    create_mock('ansible.module_utils.common.validation', {
        '_check_type_str_no_conversion': lambda s, *a, **kw: s,
        'check_type_int': lambda x: int(x),
        'check_type_bool': lambda x: str(x).lower() in ('yes', 'true', 't', '1'),
        'check_type_list': lambda x: x if isinstance(x, list) else [x]
    })

    create_mock('ansible.module_utils.parsing.convert_bool', {
        'convert_bool': lambda x, *a, **kw: str(x).lower() in ('yes', 'true', 't', '1'),
        'boolean': lambda x, *a, **kw: str(x).lower() in ('yes', 'true', 't', '1')
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
    create_mock('ansible.module_utils.basic', {
        'AnsibleModule': AnsibleModule,
        '_load_params': lambda: (_current_task_context['complex_args'], 'main'),
        'FILE_COMMON_ARGUMENTS': FILE_COMMON_ARGUMENTS,
        'missing_required_lib': lambda *a, **kw: None,
        'sanitize_keys': lambda x, *a, **kw: x,
        'get_bin_path': mock_get_bin_path,
        'is_executable': lambda x: os.path.isfile(x) and os.access(x, os.X_OK),
        'get_distribution': lambda: 'Linux',
        'get_distribution_version': lambda: '20.04',
        'get_distribution_release': lambda: 'focal'
    })

    # 9. Password/Group System Mocks
    import collections
    passwd, group = collections.namedtuple('passwd', ['pw_name', 'pw_passwd', 'pw_uid', 'pw_gid', 'pw_gecos', 'pw_dir', 'pw_shell']), collections.namedtuple('group', ['gr_name', 'gr_passwd', 'gr_gid', 'gr_mem'])
    create_mock('grp', {'getgrnam': lambda *a, **kw: group('root', 'x', 0, []), 'getgrgid': lambda *a, **kw: group('root', 'x', 0, []), 'getgrall': lambda: []}, False)
    create_mock('pwd', {'getpwnam': lambda *a, **kw: passwd('root', 'x', 0, 0, 'root', '/root', '/bin/bash'), 'getpwuid': lambda *a, **kw: passwd('root', 'x', 0, 0, 'root', '/root', '/bin/bash'), 'getpwall': lambda: []}, False)

    # 10. JSON handling
    if not hasattr(json, '_graal_ansible_patched'):
        class AnsibleEncoder(json.JSONEncoder):
            def default(self, o):
                if isinstance(o, (set, frozenset, range)): return list(o)
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
    base_name = action_name
    if base_name.startswith('ansible.builtin.'): base_name = base_name[16:]
    elif base_name.startswith('ansible.legacy.'): base_name = base_name[15:]

    path = None
    site_pkgs = globals().get('site_packages_java')
    if site_pkgs:
        for p in site_pkgs:
            cand = os.path.join(str(p), 'ansible/plugins/action', base_name + '.py')
            if os.path.exists(cand): path = cand; break

    if not path:
        for p in sys.path:
            cand = os.path.join(p, 'ansible/plugins/action', base_name + '.py')
            if os.path.exists(cand): path = cand; break

    if not path: raise Exception(f"Action plugin {action_name} not found")

    spec = importlib.util.spec_from_file_location("ansible.plugins.action." + base_name, path)
    mod = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = mod
    spec.loader.exec_module(mod)

    l = loader or MockLoader()

    c = connection
    if not hasattr(c, '_shell') or not hasattr(c._shell, 'path_has_trailing_slash'):
        class Proxy:
            def __init__(self, obj): self._obj, self._shell = obj, MockShell()
            def __getattr__(self, name): return getattr(self._obj, name)
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
                content = f.read()
                path_str = str(path)
                code = compile(content, path_str, 'exec')
                exec(code, {'__name__': '__main__', '__file__': path_str, '__package__': 'ansible.modules'})
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
