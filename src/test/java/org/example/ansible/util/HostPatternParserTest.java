package org.example.ansible.util;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class HostPatternParserTest {

    @Test
    void testSplitBracketAware() {
        // Simple comma split
        assertEquals(List.of("web1", "db1"), HostPatternParser.splitBracketAware("web1,db1"));

        // Simple colon split
        assertEquals(List.of("web1", "db1"), HostPatternParser.splitBracketAware("web1:db1"));

        // Comma inside bracket should not split
        assertEquals(List.of("web[0,1]", "db"), HostPatternParser.splitBracketAware("web[0,1],db"));

        // Colon inside bracket should not split
        assertEquals(List.of("web[0:5]", "db"), HostPatternParser.splitBracketAware("web[0:5],db"));
        assertEquals(List.of("web[0:5]", "db"), HostPatternParser.splitBracketAware("web[0:5]:db"));

        // Multiple brackets and spaces
        assertEquals(List.of("web[1:2]", "db[a:b]"), HostPatternParser.splitBracketAware("  web[1:2] , db[a:b]  "));

        // Null and empty checks
        assertTrue(HostPatternParser.splitBracketAware(null).isEmpty());
        assertTrue(HostPatternParser.splitBracketAware("").isEmpty());
    }

    @Test
    void testExpandPatternNumerical() {
        // Simple numerical range with colon
        assertEquals(List.of("web0", "web1", "web2"), HostPatternParser.expandPattern("web[0:2]"));

        // Simple numerical range with dash
        assertEquals(List.of("web0", "web1", "web2"), HostPatternParser.expandPattern("web[0-2]"));

        // Zero padding
        assertEquals(List.of("app01", "app02", "app03"), HostPatternParser.expandPattern("app[01:03]"));
        assertEquals(List.of("app001", "app002"), HostPatternParser.expandPattern("app[001:002]"));

        // Reverse range
        assertEquals(List.of("web2", "web1", "web0"), HostPatternParser.expandPattern("web[2:0]"));
    }

    @Test
    void testExpandPatternAlphabetical() {
        // Lowercase alphabetical range
        assertEquals(List.of("dba", "dbb", "dbc"), HostPatternParser.expandPattern("db[a:c]"));

        // Uppercase alphabetical range
        assertEquals(List.of("dbA", "dbB", "dbC"), HostPatternParser.expandPattern("db[A-C]"));

        // Reverse alphabetical range
        assertEquals(List.of("dbc", "dbb", "dba"), HostPatternParser.expandPattern("db[c:a]"));
    }

    @Test
    void testExpandPatternRecursive() {
        // Multiple ranges in one pattern
        List<String> expected = List.of(
            "web1-dba", "web1-dbb",
            "web2-dba", "web2-dbb"
        );
        assertEquals(expected, HostPatternParser.expandPattern("web[1:2]-db[a:b]"));
    }

    @Test
    void testExpandPatternNonRangeBrackets() {
        // Bracket with single number (not a range)
        assertEquals(List.of("web[0]"), HostPatternParser.expandPattern("web[0]"));

        // Bracket with normal text
        assertEquals(List.of("web[all]"), HostPatternParser.expandPattern("web[all]"));

        // Bracket unmatched
        assertEquals(List.of("web[0:2"), HostPatternParser.expandPattern("web[0:2"));
    }
}
