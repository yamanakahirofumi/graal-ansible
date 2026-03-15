import json
import sys
import ansible_bridge

# Convert Java Map to native Python dict
complex_args = dict(complex_args_java) if complex_args_java is not None else {}

try:
    ansible_bridge.setup_sys_path(site_packages_java)
    ansible_bridge.setup_env(environment_java if 'environment_java' in globals() else None)
    ansible_bridge.mock_problematic_modules()
    ansible_bridge.patch_ansible(complex_args, connection_java, become_context_java, environment_java if 'environment_java' in globals() else None)

    result = ansible_bridge.execute_module(module_name, complex_args)
except Exception as e:
    import traceback
    result = json.dumps({'failed': True, 'msg': f'Launcher error: {str(e)}', 'traceback': traceback.format_exc()})
