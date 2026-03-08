import json
import sys
from io import StringIO

# Setup sys.path from site_packages_java
for p in site_packages_java:
    if p not in sys.path:
        sys.path.append(p)

# Convert Java Map to native Python dict
complex_args = dict(complex_args_java) if complex_args_java is not None else {}

def run_module():
    old_stdout = sys.stdout
    sys.stdout = mystdout = StringIO()
    try:
        module_globals = {'complex_args': complex_args, 'ansible_module_results': {}}
        exec(module_code, module_globals)
        return mystdout.getvalue()
    finally:
        sys.stdout = old_stdout

result = run_module()
