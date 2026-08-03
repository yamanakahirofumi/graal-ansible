package org.example.ansible.engine.filter;

import com.hubspot.jinjava.Jinjava;
import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class IpAddrFilterTest {

    private final Jinjava jinjava = new Jinjava();

    public IpAddrFilterTest() {
        jinjava.getGlobalContext().registerFilter(new IpAddrFilter());
    }

    @Test
    void testValidIpV4() {
        Map<String, Object> context = new HashMap<>();
        context.put("my_ip", "192.168.1.1");
        String rendered = jinjava.render("{{ my_ip | ipaddr }}", context);
        assertEquals("192.168.1.1", rendered);
    }

    @Test
    void testInvalidIpV4Format() {
        Map<String, Object> context = new HashMap<>();
        context.put("my_ip", "not-an-ip");
        String rendered = jinjava.render("{{ my_ip | ipaddr }}", context);
        assertEquals("false", rendered);
    }

    @Test
    void testInvalidIpV4Numeric() {
        Map<String, Object> context = new HashMap<>();
        context.put("my_ip", "999.999.999.999");
        String rendered = jinjava.render("{{ my_ip | ipaddr }}", context);
        assertEquals("false", rendered);
    }

    @Test
    void testInvalidIpV4LeadingZeros() {
        Map<String, Object> context = new HashMap<>();
        context.put("my_ip", "192.168.01.1");
        String rendered = jinjava.render("{{ my_ip | ipaddr }}", context);
        assertEquals("false", rendered);
    }

    @Test
    void testValidIpV6() {
        Map<String, Object> context = new HashMap<>();
        context.put("my_ip", "2001:db8::1");
        String rendered = jinjava.render("{{ my_ip | ipaddr }}", context);
        assertEquals("2001:db8::1", rendered);

        context.put("my_ip", "::1");
        rendered = jinjava.render("{{ my_ip | ipaddr }}", context);
        assertEquals("::1", rendered);
    }

    @Test
    void testInvalidIpV6() {
        Map<String, Object> context = new HashMap<>();
        context.put("my_ip", "1200::AB00:12:34::56");
        String rendered = jinjava.render("{{ my_ip | ipaddr }}", context);
        assertEquals("false", rendered);
    }

    @Test
    void testNullInput() {
        Map<String, Object> context = new HashMap<>();
        context.put("my_ip", null);
        String rendered = jinjava.render("{{ my_ip | ipaddr }}", context);
        assertEquals("", rendered); // Jinjava renders null as empty string by default
    }
}
