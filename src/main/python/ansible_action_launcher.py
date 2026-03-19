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

def patch_action_base():
    """Patches ActionBase to use the Java bridge for module execution."""
    from ansible.plugins.action import ActionBase
    def mocked_execute_module(self, module_name=None, module_args=None, tmp=None, task_vars=None, *args, **kwargs):
        if module_name is None:
            module_name = self._task.action
        if module_args is None:
            module_args = self._task.args
        if task_vars is None:
            task_vars = {}

        # Call back to Java ITaskExecutor.execute_from_python
        res = task_executor_java.execute_from_python(module_name, module_args, task_vars)
        return dict(res) if res is not None else {}

    ActionBase._execute_module = mocked_execute_module

def run_action_plugin():
    """Loads and runs the specified Action Plugin."""
    try:
        ansible_bridge.setup_sys_path(site_packages)
        ansible_bridge.setup_env(env)
        ansible_bridge.mock_problematic_modules()

        # Bind current task context before patching
        ansible_bridge.bind_task(module_args, connection_java, become_context_java, env)
        ansible_bridge.patch_ansible()

        # Optional: Aggressively mock modules that are known to cause issues in GraalPy
        # but are not strictly needed for basic action plugin initialization
        # if 'ansible.executor.module_common' not in sys.modules:
        #    ...

        from ansible.plugins.action import ActionBase
        from ansible.playbook.task import Task
        from ansible.playbook.play_context import PlayContext
        from ansible.plugins.loader import action_loader

        patch_action_base()

        plugin_class = action_loader.get(action_name, class_only=True)
        if not plugin_class:
            return {'failed': True, 'msg': f'Action plugin {action_name} not found'}

        mock_task = Task()
        mock_task.action = action_name
        mock_task.args = module_args

        mock_connection = connection_java
        mock_play_context = PlayContext()

        plugin = plugin_class(
            task=mock_task,
            connection=mock_connection,
            play_context=mock_play_context,
            loader=None,
            templar=None,
            shared_loader_obj=None
        )

        return plugin.run(tmp=None, task_vars=task_vars)

    except Exception as e:
        import traceback
        return {'failed': True, 'msg': f'Action Plugin launcher error: {str(e)}', 'traceback': traceback.format_exc()}

# The result will be picked up by Java
result = json.dumps(run_action_plugin())
