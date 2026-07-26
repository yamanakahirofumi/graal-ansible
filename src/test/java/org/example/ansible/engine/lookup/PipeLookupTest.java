package org.example.ansible.engine.lookup;

import org.example.ansible.engine.VariableResolver;
import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class PipeLookupTest {

    @Test
    void testPipeLookupSuccess() {
        VariableResolver resolver = new VariableResolver();
        Map<String, Object> variables = new HashMap<>();

        // standard cross-platform echo
        String template = "{{ lookup('pipe', 'echo PipeLookupSuccess') }}";
        Object result = resolver.resolveValue(template, variables);
        assertEquals("PipeLookupSuccess", result);
    }

    @Test
    void testPipeLookupNonZeroExitCode() {
        VariableResolver resolver = new VariableResolver();
        Map<String, Object> variables = new HashMap<>();

        // exit with error code (1) to trigger exception
        // Note: exit 1 command works in standard shells
        String template = "{{ lookup('pipe', 'exit 1') }}";
        assertThrows(RuntimeException.class, () -> resolver.resolveValue(template, variables));
    }
}
