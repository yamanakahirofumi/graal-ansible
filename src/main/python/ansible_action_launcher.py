import json
import sys
import os
import ansible_bridge
import types

# Convert Java Map to native Python dict
task_vars = ansible_bridge._deep_convert(task_vars_java) if task_vars_java is not None else {}
module_args = ansible_bridge._deep_convert(module_args_java) if module_args_java is not None else {}
env = environment_java if 'environment_java' in globals() else None
site_packages = [str(s) for s in site_packages_java] if 'site_packages_java' in globals() and site_packages_java is not None else []

def run_action_plugin():
    try:
        ansible_bridge.initialize(
            site_packages=site_packages,
            env_vars=env,
            complex_args=module_args,
            connection_java=connection_java,
            become_context_java=become_context_java
        )

        from ansible.playbook.task import Task
        from ansible.playbook.play_context import PlayContext
        from ansible.template import Templar

        mock_task = Task()
        mock_task.action, mock_task.args = action_name, module_args
        # Initialize internal fields required by some Action Plugins (like copy)
        mock_task._original_basename = os.path.basename(str(module_args.get('src', '')))

        l = ansible_bridge.MockLoader()
        if 'task_executor_java' in globals():
            base_dir = task_executor_java.resolveLocalPath(".")
            if base_dir:
                l.set_basedir(str(base_dir))
                mock_task._origin.path = str(base_dir)

        # Ensure action_loader mock has action_loader itself if needed (for shell -> command chain)
        loader_mod = sys.modules['ansible.plugins.loader']
        if not hasattr(loader_mod.action_loader, 'action_loader'):
            loader_mod.action_loader.action_loader = loader_mod.action_loader

        plugin = ansible_bridge._create_action_plugin(
            action_name,
            task=mock_task,
            connection=connection_java,
            play_context=PlayContext(),
            loader=l,
            templar=Templar(variables=task_vars),
            shared_loader_obj=loader_mod.action_loader
        )
        return plugin.run(tmp=None, task_vars=task_vars)

    except Exception as e:
        import traceback
        return {'failed': True, 'msg': str(e), 'traceback': traceback.format_exc()}

import json
res = run_action_plugin()
result = json.dumps(res, cls=ansible_bridge.CustomEncoder)
