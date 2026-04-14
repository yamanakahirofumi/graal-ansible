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

        # Flatten common arguments if they are single-element lists
        for key in ['dest', 'src', 'path', 'name', '_raw_params']:
            if key in module_args and isinstance(module_args[key], list) and len(module_args[key]) == 1:
                module_args[key] = module_args[key][0]

        mock_task = Task()
        mock_task.action, mock_task.args = action_name, module_args
        # Initialize internal fields required by some Action Plugins (like copy)
        src_val = module_args.get('src') or module_args.get('_raw_params', '')
        if isinstance(src_val, list) and len(src_val) > 0: src_val = src_val[0]
        mock_task._original_basename = os.path.basename(str(src_val))

        l = ansible_bridge.MockLoader()
        if 'task_executor_java' in globals():
            base_dir = task_executor_java.resolveLocalPath(".")
            if base_dir:
                l.set_basedir(str(base_dir))
                mock_task._origin.path = str(base_dir)

        plugin = ansible_bridge._create_action_plugin(
            action_name,
            task=mock_task,
            connection=connection_java,
            play_context=PlayContext(),
            loader=l,
            templar=Templar(variables=task_vars),
            shared_loader_obj=sys.modules['ansible.plugins.loader'].action_loader
        )
        return plugin.run(tmp=None, task_vars=task_vars)

    except Exception as e:
        import traceback
        return {'failed': True, 'msg': str(e), 'traceback': traceback.format_exc()}

import json
res = run_action_plugin()
result = json.dumps(res)
