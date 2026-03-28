import json
import sys
import ansible_bridge

# Convert Java Map to native Python dict
complex_args = ansible_bridge._deep_convert(complex_args_java) if complex_args_java is not None else {}
env = environment_java if 'environment_java' in globals() else None

try:
    ansible_bridge.initialize(
        site_packages=site_packages_java,
        env_vars=env,
        complex_args=complex_args,
        connection_java=connection_java,
        become_context_java=become_context_java
    )

    result = ansible_bridge.execute_module(module_name, complex_args, module_code)
except Exception as e:
    import traceback
    result = json.dumps({'failed': True, 'msg': f'Mock launcher error: {str(e)}', 'traceback': traceback.format_exc()})
