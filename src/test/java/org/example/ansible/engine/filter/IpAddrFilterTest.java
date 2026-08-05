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
    void testInvalidIpV4NumericPartiallyOutOfBounds() {
        Map<String, Object> context = new HashMap<>();
        context.put("my_ip", "192.168.1.256");
        String rendered = jinjava.render("{{ my_ip | ipaddr }}", context);
        assertEquals("false", rendered);
    }

    @Test
    void testInvalidIpV4OctetCount() {
        Map<String, Object> context = new HashMap<>();
        context.put("my_ip", "192.168.1");
        String rendered = jinjava.render("{{ my_ip | ipaddr }}", context);
        assertEquals("false", rendered);
    }

    @Test
    void testInvalidIpV4OctetCountExtra() {
        Map<String, Object> context = new HashMap<>();
        context.put("my_ip", "192.168.1.1.1");
        String rendered = jinjava.render("{{ my_ip | ipaddr }}", context);
        assertEquals("false", rendered);
    }

    @Test
    void testIpV4LeadingZeros() {
        Map<String, Object> context = new HashMap<>();
        context.put("my_ip", "192.168.01.1");
        String rendered = jinjava.render("{{ my_ip | ipaddr }}", context);
        assertEquals("false", rendered);
    }

    @Test
    void testValidIpV6Standard() {
        Map<String, Object> context = new HashMap<>();
        context.put("my_ip", "2001:0db8:85a3:0000:0000:8a2e:0370:7334");
        String rendered = jinjava.render("{{ my_ip | ipaddr }}", context);
        assertEquals("2001:0db8:85a3:0000:0000:8a2e:0370:7334", rendered);
    }

    @Test
    void testValidIpV6Compressed() {
        Map<String, Object> context = new HashMap<>();
        context.put("my_ip", "2001:db8:85a3::8a2e:370:7334");
        String rendered = jinjava.render("{{ my_ip | ipaddr }}", context);
        assertEquals("2001:db8:85a3::8a2e:370:7334", rendered);
    }

    @Test
    void testValidIpV6Loopback() {
        Map<String, Object> context = new HashMap<>();
        context.put("my_ip", "::1");
        String rendered = jinjava.render("{{ my_ip | ipaddr }}", context);
        assertEquals("::1", rendered);
    }

    @Test
    void testValidIpV6Unspecified() {
        Map<String, Object> context = new HashMap<>();
        context.put("my_ip", "::");
        String rendered = jinjava.render("{{ my_ip | ipaddr }}", context);
        assertEquals("::", rendered);
    }

    @Test
    void testInvalidIpV6MultipleDoubleColons() {
        Map<String, Object> context = new HashMap<>();
        context.put("my_ip", "2001::1::2");
        String rendered = jinjava.render("{{ my_ip | ipaddr }}", context);
        assertEquals("false", rendered);
    }

    @Test
    void testInvalidIpV6TripleColons() {
        Map<String, Object> context = new HashMap<>();
        context.put("my_ip", "2001:::1");
        String rendered = jinjava.render("{{ my_ip | ipaddr }}", context);
        assertEquals("false", rendered);
    }

    @Test
    void testInvalidIpV6TooManyGroups() {
        Map<String, Object> context = new HashMap<>();
        context.put("my_ip", "2001:0db8:85a3:0000:0000:8a2e:0370:7334:1111");
        String rendered = jinjava.render("{{ my_ip | ipaddr }}", context);
        assertEquals("false", rendered);
    }

    @Test
    void testInvalidIpV6NonHex() {
        Map<String, Object> context = new HashMap<>();
        context.put("my_ip", "2001:0db8:85a3:0000:0000:8a2e:0370:733g");
        String rendered = jinjava.render("{{ my_ip | ipaddr }}", context);
        assertEquals("false", rendered);
    }

    @Test
    void testValidIpV4MappedIpV6() {
        Map<String, Object> context = new HashMap<>();
        context.put("my_ip", "::ffff:192.168.1.1");
        String rendered = jinjava.render("{{ my_ip | ipaddr }}", context);
        assertEquals("::ffff:192.168.1.1", rendered);
    }

    @Test
    void testValidIpV4CompatibleIpV6() {
        Map<String, Object> context = new HashMap<>();
        context.put("my_ip", "::192.168.1.1");
        String rendered = jinjava.render("{{ my_ip | ipaddr }}", context);
        assertEquals("::192.168.1.1", rendered);
    }

    @Test
    void testInvalidIpV4MappedIpV6() {
        Map<String, Object> context = new HashMap<>();
        context.put("my_ip", "::ffff:192.168.1.256");
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

    @Test
    void testEmptyInput() {
        Map<String, Object> context = new HashMap<>();
        context.put("my_ip", "");
        String rendered = jinjava.render("{{ my_ip | ipaddr }}", context);
        assertEquals("false", rendered);
    }
}