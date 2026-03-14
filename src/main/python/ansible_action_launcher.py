import json
import sys
import os
import types

# Setup sys.path from site_packages_java
for p in site_packages_java:
    if p not in sys.path:
        sys.path.append(p)

try:
    # Aggressively mock native/problematic modules
    for mname in ['cryptography', 'cryptography.hazmat', 'cryptography.hazmat.bindings', '_cffi_backend', 'yaml._yaml', 'selinux']:
        sys.modules[mname] = None

    from ansible.plugins.loader import action_loader
    from ansible.utils.display import Display
    from ansible.template import Templar

    # Convert Java Map to native Python dict
    complex_args = dict(complex_args_java) if complex_args_java is not None else {}
    task_vars = dict(task_vars_java) if task_vars_java is not None else {}

    # Ensure task_vars values are native Python types
    def convert_java_to_python(obj):
        if hasattr(obj, 'getClass'):
            # It's a Java object
            if hasattr(obj, 'entrySet'): # Map
                return {convert_java_to_python(k): convert_java_to_python(v) for k, v in obj.entrySet()}
            if hasattr(obj, 'toArray'): # List
                return [convert_java_to_python(v) for v in obj]
        return obj

    task_vars = {k: convert_java_to_python(v) for k, v in task_vars.items()}

    class MockTask:
        def __init__(self, action, args):
            self.action = action
            self.args = args
            self.async_val = None
            self.check_mode = complex_args.get('_ansible_check_mode', False)
            self._parent = None
            self.delegate_to = None

    class MockConnection:
        def __init__(self, java_conn):
            self._java_conn = java_conn
            self._shell = types.SimpleNamespace(
                tmpdir=None,
                _generate_temp_dir_name=lambda: "ansible-tmp",
                _mkdtemp2=lambda **kwargs: types.SimpleNamespace(command="mkdir -p " + kwargs.get('basefile', 'tmp'), input_data=None)
            )
            self.transport = "ssh" # or "local"

        def exec_command(self, cmd, in_data=None, sudoable=True):
            # If it's a list, join it
            if isinstance(cmd, list):
                cmd = ' '.join(cmd)
            res = self._java_conn.execCommand(cmd, become_context_java)
            return (res.exitCode(), res.stdout(), res.stderr())

        def put_file(self, in_path, out_path):
            self._java_conn.putFile(str(in_path), str(out_path))

        def fetch_file(self, in_path, out_path):
            self._java_conn.fetchFile(str(in_path), str(out_path))

    class MockPlayContext:
        def __init__(self):
            self.check_mode = complex_args.get('_ansible_check_mode', False)
            self.become = False
            self.become_method = 'sudo'
            self.become_user = 'root'
            self.become_flags = ''

    class MockLoader:
        def get_real_file(self, file_path, decrypt=True):
            return file_path
        def path_exists(self, path):
            return os.path.exists(path)
        def is_file(self, path):
            return os.path.isfile(path)

    display = Display()

    def run_action():
        # Load action plugin
        action_plugin_class = action_loader.get(module_name, class_only=True)
        if not action_plugin_class:
            # Fallback for modules that don't have explicit action plugins but might use the default one
            action_plugin_class = action_loader.get('normal', class_only=True)

        task = MockTask(module_name, complex_args)
        connection = MockConnection(connection_java)
        play_context = MockPlayContext()
        loader = MockLoader()
        templar = Templar(loader=loader, variables=task_vars)

        action_obj = action_plugin_class(
            task=task,
            connection=connection,
            play_context=play_context,
            loader=loader,
            templar=templar
        )

        # Monkeypatch _execute_module to bridge back to Java
        def mocked_execute_module(module_name=None, module_args=None, tmp=None, task_vars=None, **kwargs):
            if module_name is None:
                module_name = task.action
            if module_args is None:
                module_args = task.args

            # Call back to Java TaskExecutor
            # task_executor_java is provided from Java
            res = task_executor_java.executeModule(module_name, module_args)

            # TaskResult in Java has success(), changed(), message(), data()
            # We need to return a dict that Ansible expects
            result_dict = dict(res.data())
            if not res.success():
                result_dict['failed'] = True
                result_dict['msg'] = res.message()
            return result_dict

        action_obj._execute_module = mocked_execute_module

        # Mock additional methods needed by template action
        action_obj._remote_expand_user = lambda path, **kwargs: path.replace("~", "/root")
        def mocked_low_level_execute_command(cmd, sudoable=True, in_data=None, executable=None, chdir=None, **kwargs):
            if chdir:
                cmd = f"cd {chdir} && {cmd}"
            # Bridge to MockConnection.exec_command which handles list and java connection
            rc, stdout, stderr = connection.exec_command(cmd, in_data=in_data, sudoable=sudoable)
            return {
                'rc': rc,
                'stdout': stdout,
                'stdout_lines': (stdout or "").splitlines(),
                'stderr': stderr,
                'stderr_lines': (stderr or "").splitlines()
            }
        action_obj._low_level_execute_command = mocked_low_level_execute_command
        action_obj.get_shell_option = lambda option, default=None: default
        action_obj._is_become_unprivileged = lambda: False

        # Run the action
        res = action_obj.run(task_vars=task_vars)
        return json.dumps(res)

    result = run_action()

except Exception as e:
    import traceback
    result = json.dumps({
        'failed': True,
        'msg': f'Action launcher error: {str(e)}',
        'traceback': traceback.format_exc()
    })
