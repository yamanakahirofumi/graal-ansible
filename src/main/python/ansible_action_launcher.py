import json
import sys
import os
import ansible_bridge
import types

# Convert Java Map to native Python dict
task_vars = dict(task_vars_java) if task_vars_java is not None else {}
module_args = dict(module_args_java) if module_args_java is not None else {}
env = environment_java if 'environment_java' in globals() else None
site_packages = [str(s) for s in site_packages_java] if 'site_packages_java' in globals() and site_packages_java is not None else []

def patch_action_base(ActionBaseClass):
    """Patches the provided ActionBase class to use the Java bridge."""
    def mocked_execute_module(self, module_name=None, module_args=None, tmp=None, task_vars=None, *args, **kwargs):
        if module_name is None: module_name = self._task.action
        if module_args is None: module_args = self._task.args
        res = task_executor_java.execute_from_python(module_name, module_args, task_vars or {})
        return dict(res) if res is not None else {}
    ActionBaseClass._execute_module = mocked_execute_module

def run_action_plugin():
    try:
        ansible_bridge.initialize(
            site_packages=site_packages,
            env_vars=env,
            complex_args=module_args,
            connection_java=connection_java,
            become_context_java=become_context_java
        )

        # Robust Lightweight Mocks
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
                def _remove_tmp_path(self, *args, **kwargs): pass
            patch_action_base(ActionBase)
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

        from ansible.plugins.action import ActionBase
        from ansible.playbook.task import Task
        from ansible.playbook.play_context import PlayContext
        from ansible.template import Templar

        # Dynamic loading
        import importlib.util
        path = None
        for p in site_packages:
            candidate = os.path.join(p, 'ansible/plugins/action', action_name + '.py')
            if os.path.exists(candidate):
                path = candidate
                break
        if not path: return {'failed': True, 'msg': f'Action plugin {action_name} not found'}

        spec = importlib.util.spec_from_file_location("ansible.plugins.action." + action_name, path)
        mod = importlib.util.module_from_spec(spec)
        sys.modules[spec.name] = mod
        spec.loader.exec_module(mod)

        mock_task = Task()
        mock_task.action, mock_task.args = action_name, module_args

        class MockShell:
            def __init__(self): self.tmpdir = None
        class ConnectionProxy:
            def __init__(self, java_conn):
                self._java_conn, self._shell = java_conn, MockShell()
            def __getattr__(self, name): return getattr(self._java_conn, name)

        plugin = mod.ActionModule(
            task=mock_task,
            connection=ConnectionProxy(connection_java),
            play_context=PlayContext(),
            loader=None,
            templar=Templar(variables=task_vars),
            shared_loader_obj=None
        )
        return plugin.run(tmp=None, task_vars=task_vars)

    except Exception as e:
        import traceback
        return {'failed': True, 'msg': str(e), 'traceback': traceback.format_exc()}

import json
res = run_action_plugin()
result = json.dumps(res)
