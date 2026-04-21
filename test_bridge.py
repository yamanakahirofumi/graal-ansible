import sys
import os
import json

class MockJava:
    def setPythonClasses(self, *args): pass
    def normalizePath(self, p): return p
    def exists(self, p): return True
    def create(self, *args):
        class M:
            def getParams(self): return {}
            def getCheck_mode(self): return False
            def get_debug(self): return False
            def get_diff(self): return False
            def boolean_value(self, v): return bool(v)
            def getTmpdir(self): return "/tmp"
        return M()

# Bridge checks 'os_java' in globals()
import ansible_bridge
ansible_bridge.os_java = MockJava()
ansible_bridge.AnsibleModuleJava = MockJava()

sys.path.append('src/main/python')
sys.path.append('target/python-packages')

# We need to bypass the check during import or satisfy it
import builtins
builtins.os_java = MockJava()
builtins.AnsibleModuleJava = MockJava()

ansible_bridge.apply_mocks()

try:
    from ansible.module_utils.facts.packages import get_all_pkg_managers
    mgrs = get_all_pkg_managers()
    print(f"Managers: {list(mgrs.keys())}")
except Exception as e:
    print(f"Error: {e}")
    import traceback
    traceback.print_exc()
