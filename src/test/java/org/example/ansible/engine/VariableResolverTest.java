package org.example.ansible.engine;

import org.junit.jupiter.api.Test;
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
        assertEquals("8080", ((Map<String, Object>) resolved.get("nested")).get("key"));
        assertEquals("port 8080", ((List<Object>) resolved.get("list")).get(0));
    }

    @Test
    void testMissingVariable() {
        Map<String, Object> variables = Map.of();
        String template = "hello {{ name }}";
        // Jinjava by default might leave it or replace with empty, depends on config.
        // Let's see what the default behavior is.
        Object result = resolver.resolveValue(template, variables);
        // Usually Jinja2 leaves it if not found, but Jinjava might be different.
        // Actually, Ansible would fail if it's mandatory, but here we just check the output.
        System.out.println("Result for missing var: " + result);
    }
}
