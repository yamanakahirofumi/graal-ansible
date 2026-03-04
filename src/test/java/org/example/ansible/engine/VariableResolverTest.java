package org.example.ansible.engine;

import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class VariableResolverTest {

    private final VariableResolver resolver = new VariableResolver();

    @Test
    void testSimpleResolution() {
        Map<String, Object> variables = Map.of("name", "world");
        String template = "hello {{ name }}";
        assertEquals("hello world", resolver.resolveValue(template, variables));
    }

    @Test
    void testJinjaFilter() {
        Map<String, Object> variables = Map.of("name", "world");
        String template = "hello {{ name | upper }}";
        assertEquals("hello WORLD", resolver.resolveValue(template, variables));
    }

    @Test
    void testRecursiveResolution() {
        Map<String, Object> variables = Map.of("port", 8080);
        Map<String, Object> args = Map.of(
            "msg", "listening on {{ port }}",
            "nested", Map.of("key", "{{ port }}"),
            "list", List.of("port {{ port }}")
        );

        Map<String, Object> resolved = resolver.resolve(args, variables);
        assertEquals("listening on 8080", resolved.get("msg"));
        // VariableResolver now returns the raw object if it's just {{ var }}
        assertEquals(8080, resolved.get("nested") instanceof Map ? ((Map)resolved.get("nested")).get("key") : null);
        assertEquals("port 8080", ((List<Object>) resolved.get("list")).get(0));
    }

    @Test
    void testMissingVariable() {
        Map<String, Object> variables = Map.of();
        String template = "hello {{ name }}";
        Object result = resolver.resolveValue(template, variables);
        System.out.println("Result for missing var: " + result);
    }

    @Test
    void testDefaultFilter() {
        Map<String, Object> variables = Map.of();
        String template = "{{ undefined_var | default('fallback') }}";
        assertEquals("fallback", resolver.resolveValue(template, variables));

        variables = Map.of("defined_var", "actual");
        template = "{{ defined_var | default('fallback') }}";
        assertEquals("actual", resolver.resolveValue(template, variables));
    }

    @Test
    void testIpAddrFilter() {
        Map<String, Object> variables = Map.of("my_ip", "192.168.1.1");
        String template = "{{ my_ip | ipaddr }}";
        assertEquals("192.168.1.1", resolver.resolveValue(template, variables));

        variables = Map.of("my_ip", "not-an-ip");
        template = "{{ my_ip | ipaddr }}";
        assertEquals(false, resolver.resolveValue(template, variables));
    }

    @Test
    void testNullVariable() {
        Map<String, Object> variables = new HashMap<>();
        variables.put("null_var", null);
        Map<String, Object> args = new HashMap<>();
        args.put("key", "{{ null_var }}");

        Map<String, Object> resolved = resolver.resolve(args, variables);
        assertNotNull(resolved);
        assertTrue(resolved.containsKey("key"));
    }
}
