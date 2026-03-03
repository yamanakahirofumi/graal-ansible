package org.example.ansible.engine.filter;

import com.hubspot.jinjava.Jinjava;
import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class DefaultFilterTest {

    private final Jinjava jinjava = new Jinjava();

    public DefaultFilterTest() {
        jinjava.getGlobalContext().registerFilter(new DefaultFilter());
    }

    @Test
    void testDefaultValueWithUndefinedVariable() {
        Map<String, Object> context = new HashMap<>();
        String rendered = jinjava.render("{{ undefined_var | default('fallback') }}", context);
        assertEquals("fallback", rendered);
    }

    @Test
    void testDefaultValueWithDefinedVariable() {
        Map<String, Object> context = new HashMap<>();
        context.put("defined_var", "actual");
        String rendered = jinjava.render("{{ defined_var | default('fallback') }}", context);
        assertEquals("actual", rendered);
    }

    @Test
    void testDefaultValueWithEmptyString() {
        Map<String, Object> context = new HashMap<>();
        context.put("empty_var", "");
        String rendered = jinjava.render("{{ empty_var | default('fallback') }}", context);
        assertEquals("fallback", rendered);
    }

    @Test
    void testDefaultValueWithNull() {
        Map<String, Object> context = new HashMap<>();
        context.put("null_var", null);
        String rendered = jinjava.render("{{ null_var | default('fallback') }}", context);
        assertEquals("fallback", rendered);
    }
}
