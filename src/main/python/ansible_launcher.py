import json
import sys
from typing import Any, Dict, List, Optional, Union, TYPE_CHECKING
import ansible_bridge

if TYPE_CHECKING:
    # Injected by GraalVM context
    complex_args_java: Any
    environment_java: Any
    site_packages_java: Any
    connection_java: Any
    become_context_java: Any
    module_name: str

# Convert Java Map to native Python dict
complex_args: Dict[str, Any] = ansible_bridge._deep_convert(complex_args_java) if 'complex_args_java' in globals() and complex_args_java is not None else {}
env: Optional[Dict[str, Any]] = environment_java if 'environment_java' in globals() else None

try:
    ansible_bridge.initialize(
        site_packages=site_packages_java if 'site_packages_java' in globals() else None,
        env_vars=env,
        complex_args=complex_args,
        connection_java=connection_java if 'connection_java' in globals() else None,
        become_context_java=become_context_java if 'become_context_java' in globals() else None
    )

    result = ansible_bridge.execute_module(module_name, complex_args)
except Exception as e:
    import traceback
    result = json.dumps({'failed': True, 'msg': f'Launcher error: {str(e)}', 'traceback': traceback.format_exc()})
