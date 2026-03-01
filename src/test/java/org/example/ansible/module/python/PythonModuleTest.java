package org.example.ansible.module.python;

import org.example.ansible.engine.TaskResult;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PythonModuleTest {

    @Test
    void testSimplePythonModule() {
        // Simple Python module that echoes back arguments and sets changed=True
        String script = """
            import json
            import sys

            # complex_args is provided by PythonModule wrapper
            # Use polyglot.as_type if necessary, but for Map it should work.
            # GraalPy might return a polyglot object that needs to be treated carefully.
            import polyglot

            result = {
                "changed": True,
                "msg": "Hello from Python",
                "received_args": {k: complex_args[k] for k in complex_args}
            }
            sys.stdout.write(json.dumps(result))
            """;

        PythonModule module = new PythonModule("test_module", script);
        Map<String, Object> args = Map.of("foo", "bar");

        TaskResult result = module.execute(args);

        assertTrue(result.success(), "Execution failed: " + result.message());
        assertTrue(result.changed());
        assertEquals("OK", result.message());
        assertEquals("Hello from Python", result.data().get("msg"));

        @SuppressWarnings("unchecked")
        Map<String, Object> receivedArgs = (Map<String, Object>) result.data().get("received_args");
        assertEquals("bar", receivedArgs.get("foo"));
    }

    @Test
    void testPythonModuleFailure() {
        String script = """
            import json
            result = {
                "failed": True,
                "msg": "Something went wrong"
            }
            print(json.dumps(result))
            """;

        PythonModule module = new PythonModule("fail_module", script);
        TaskResult result = module.execute(Map.of());

        assertFalse(result.success());
        assertFalse(result.changed());
        assertEquals("Something went wrong", result.message());
    }
}
