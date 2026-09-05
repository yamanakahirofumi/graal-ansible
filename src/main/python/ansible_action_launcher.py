import json
import sys
import os
import ansible_bridge
import types
from typing import Any, Dict, List, Optional, Union, TYPE_CHECKING

if TYPE_CHECKING:
    # Injected by GraalVM context
    task_vars_java: Any
    module_args_java: Any
    environment_java: Any
    site_packages_java: Any
    connection_java: Any
    become_context_java: Any
    action_name: str
    task_executor_java: Any

# Convert Java Map to native Python dict
task_vars: Dict[str, Any] = ansible_bridge._deep_convert(task_vars_java) if 'task_vars_java' in globals() and task_vars_java is not None else {}
module_args: Dict[str, Any] = ansible_bridge._deep_convert(module_args_java) if 'module_args_java' in globals() and module_args_java is not None else {}
env: Optional[Dict[str, Any]] = environment_java if 'environment_java' in globals() else None
site_packages: List[str] = [str(s) for s in site_packages_java] if 'site_packages_java' in globals() and site_packages_java is not None else []
collection_paths: List[str] = [str(s) for s in collection_paths_java] if 'collection_paths_java' in globals() and collection_paths_java is not None else []

def run_action_plugin() -> Dict[str, Any]:
    try:
        ansible_bridge.initialize(
            site_packages=site_packages,
            env_vars=env,
            complex_args=module_args,
            connection_java=connection_java if 'connection_java' in globals() else None,
            become_context_java=become_context_java if 'become_context_java' in globals() else None,
            collection_paths=collection_paths
        )

        from ansible.playbook.task import Task
        from ansible.playbook.play_context import PlayContext
        from ansible.template import Templar

        # Flatten common arguments if they are single-element lists
        for key in ['dest', 'path', 'src', 'name']:
            if key in module_args and isinstance(module_args[key], list) and len(module_args[key]) == 1:
                module_args[key] = module_args[key][0]

        mock_task = Task()
        mock_task.action, mock_task.args = action_name, module_args
        # Initialize internal fields required by some Action Plugins (like copy)
        src_val = module_args.get('src', '')
        if isinstance(src_val, list) and len(src_val) > 0: src_val = src_val[0]
        mock_task._original_basename = os.path.basename(str(src_val))

        l = ansible_bridge.MockLoader()
        if 'task_executor_java' in globals():
            base_dir = task_executor_java.resolveLocalPath(".")
            if base_dir:
                l.set_basedir(str(base_dir))
                mock_task._origin.path = str(base_dir)

        check_mode_val = bool(module_args.get('_ansible_check_mode') or task_vars.get('ansible_check_mode'))
        play_ctx = PlayContext()
        play_ctx.check_mode = check_mode_val
        mock_task.check_mode = check_mode_val

        plugin = ansible_bridge._create_action_plugin(
            action_name,
            task=mock_task,
            connection=connection_java if 'connection_java' in globals() else None,
            play_context=play_ctx,
            loader=l,
            templar=Templar(variables=task_vars),
            shared_loader_obj=sys.modules['ansible.plugins.loader']
        )
        res: Dict[str, Any] = plugin.run(tmp=None, task_vars=task_vars)
        return res

    except Exception as e:
        import traceback
        return {'failed': True, 'msg': str(e), 'traceback': traceback.format_exc()}

res_plugin = run_action_plugin()
result = json.dumps(res_plugin)
