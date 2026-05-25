package org.example.ansible.engine.filter;

import com.hubspot.jinjava.Jinjava;
import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class UrlencodeFilterTest {

    private final Jinjava jinjava = new Jinjava();

    public UrlencodeFilterTest() {
        jinjava.getGlobalContext().registerFilter(new UrlencodeFilter());
    }

    @Test
    void testEncodeString() {
        Map<String, Object> context = new HashMap<>();
        context.put("val", "hello world!");
        String rendered = jinjava.render("{{ val | urlencode }}", context);
        assertEquals("hello%20world%21", rendered);
    }

    @Test
    void testEncodeStringWithSpecialChars() {
        Map<String, Object> context = new HashMap<>();
        context.put("val", "a+b c*d~e");
        String rendered = jinjava.render("{{ val | urlencode }}", context);
        // Ansible: space -> %20, + -> %2B, * -> %2A, ~ -> ~
        assertEquals("a%2Bb%20c%2Ad~e", rendered);
    }

    @Test
    void testEncodeMap() {
        Map<String, Object> context = new HashMap<>();
        Map<String, Object> params = new HashMap<>();
        params.put("name", "john doe");
        params.put("city", "new york");
        context.put("params", params);
        String rendered = jinjava.render("{{ params | urlencode }}", context);
        // Map entry order is not guaranteed, but both should be present
        assertTrue(rendered.contains("name=john+doe"));
        assertTrue(rendered.contains("city=new+york"));
        assertTrue(rendered.contains("&"));
    }

    @Test
    void testEncodeListPairs() {
        Map<String, Object> context = new HashMap<>();
        List<List<String>> pairs = List.of(
            List.of("name", "john doe"),
            List.of("city", "new york")
        );
        context.put("pairs", pairs);
        String rendered = jinjava.render("{{ pairs | urlencode }}", context);
        assertEquals("name=john+doe&city=new+york", rendered);
    }

    @Test
    void testEncodeNull() {
        Map<String, Object> context = new HashMap<>();
        context.put("val", null);
        String rendered = jinjava.render("{{ val | urlencode }}", context);
        assertEquals("", rendered);
    }
}
