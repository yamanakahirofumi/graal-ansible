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

    @Test
    void testSplitBracketAwareExtra() {
        // Unmatched brackets
        assertEquals(List.of("web[0,1,db"), HostPatternParser.splitBracketAware("web[0,1,db"));
        assertEquals(List.of("web]db"), HostPatternParser.splitBracketAware("web]db"));

        // Multiple brackets with no separators
        assertEquals(List.of("web[1:2]db[a:b]"), HostPatternParser.splitBracketAware("web[1:2]db[a:b]"));

        // Only separators and spaces
        assertEquals(List.of("web"), HostPatternParser.splitBracketAware(" , web , : "));
    }

    @Test
    void testExpandPatternNumericalExtra() {
        // Complex zero padding with larger bounds
        assertEquals(
            List.of("app01", "app02", "app03", "app04", "app05", "app06", "app07", "app08", "app09", "app10"),
            HostPatternParser.expandPattern("app[01:10]")
        );

        // Mix of start and end lengths with no zero padding
        assertEquals(
            List.of("web9", "web10", "web11"),
            HostPatternParser.expandPattern("web[9:11]")
        );

        // Non-padded starting with 0, but length is 1
        assertEquals(
            List.of("web0", "web1", "web2"),
            HostPatternParser.expandPattern("web[0:2]")
        );

        // Negative bounds within range - currently the regex is ^(\d+)([:-])(\d+)$ which only matches positive integers
        // Thus negative bounds are treated as non-range brackets
        assertEquals(
            List.of("web[-1:3]"),
            HostPatternParser.expandPattern("web[-1:3]")
        );
    }

    @Test
    void testExpandPatternAlphabeticalExtra() {
        // Mixed uppercase and lowercase alphabetical range
        // From 'Z' (90) to 'a' (97): Z (90), [ (91), \ (92), ] (93), ^ (94), _ (95), ` (96), a (97)
        List<String> expectedMixed = List.of(
            "dbZ", "db[", "db\\", "db]", "db^", "db_", "db`", "dba"
        );
        assertEquals(expectedMixed, HostPatternParser.expandPattern("db[Z:a]"));

        // Single character range
        assertEquals(List.of("dba"), HostPatternParser.expandPattern("db[a:a]"));
    }

    @Test
    void testExpandPatternRecursiveNested() {
        // Nested bracket structure
        assertEquals(List.of("web[[0:2]]"), HostPatternParser.expandPattern("web[[0:2]]"));
    }
}
