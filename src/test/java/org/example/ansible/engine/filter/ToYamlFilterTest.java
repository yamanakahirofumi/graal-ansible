package org.example.ansible.engine.filter;

import org.example.ansible.engine.VariableResolver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToYamlFilterTest {

    @Test
    void testToYamlMapAndList() {
        ToYamlFilter filter = new ToYamlFilter();

        Map<String, Object> mapInput = Map.of("service", "web", "port", 80);
        Object yamlMap = filter.filter(mapInput, null);
        assertTrue(yamlMap instanceof String);
        String yamlMapStr = (String) yamlMap;
        assertTrue(yamlMapStr.contains("service: web"));
        assertTrue(yamlMapStr.contains("port: 80"));

        List<Object> listInput = List.of("alpha", "beta", 30);
        Object yamlList = filter.filter(listInput, null);
        assertTrue(yamlList instanceof String);
        String yamlListStr = (String) yamlList;
        assertTrue(yamlListStr.contains("- alpha"));
        assertTrue(yamlListStr.contains("- beta"));
        assertTrue(yamlListStr.contains("- 30"));
    }

    @Test
    void testToYamlPrimitivesAndNull() {
        ToYamlFilter filter = new ToYamlFilter();

        Object strYaml = filter.filter("hello world", null);
        assertTrue(strYaml instanceof String);
        assertTrue(((String) strYaml).contains("hello world"));

        Object intYaml = filter.filter(100, null);
        assertTrue(intYaml instanceof String);
        assertTrue(((String) intYaml).contains("100"));

        Object nullYaml = filter.filter(null, null);
        assertTrue(nullYaml instanceof String);
        assertTrue(((String) nullYaml).contains("null"));
    }

    @Test
    void testToYamlFilterName() {
        ToYamlFilter filter = new ToYamlFilter();
        assertEquals("to_yaml", filter.getName());
    }

    @Test
    void testToYamlIntegrationWithVariableResolver() {
        VariableResolver resolver = new VariableResolver();
        Map<String, Object> vars = Map.of("config", Map.of("host", "127.0.0.1"));

        Object result = resolver.resolveValue("{{ config | to_yaml }}", vars);
        assertTrue(result instanceof String);
        String strResult = (String) result;
        assertTrue(strResult.contains("host: 127.0.0.1"));
    }
}
