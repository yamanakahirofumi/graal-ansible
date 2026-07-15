import json
import sys
import os
import ansible_bridge
import types
from typing import Any, Dict, List, Optional, Union, TYPE_CHECKING

if TYPE_CHECKING:
    # Injected by GraalVM context
    plugin_name: str
    inventory_path: str
    site_packages_java: Any
    collection_paths_java: Any

def run_inventory_plugin() -> Dict[str, Any]:
    try:
        site_packages: List[str] = [str(s) for s in site_packages_java] if 'site_packages_java' in globals() and site_packages_java is not None else []
        collection_paths: List[str] = [str(s) for s in collection_paths_java] if 'collection_paths_java' in globals() and collection_paths_java is not None else []

        ansible_bridge.setup_sys_path(site_packages, collection_paths)
        ansible_bridge.apply_mocks()

        from ansible.inventory.data import InventoryData

        inventory = InventoryData()
        loader = ansible_bridge.MockLoader()

        plugin = ansible_bridge._create_inventory_plugin(plugin_name)
        plugin.parse(inventory, loader, inventory_path)

        return inventory.to_dict()

    except Exception as e:
        import traceback
        return {'failed': True, 'msg': str(e), 'traceback': traceback.format_exc()}

res_plugin = run_inventory_plugin()
result = json.dumps(res_plugin)
