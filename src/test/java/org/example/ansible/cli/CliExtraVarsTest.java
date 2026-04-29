package org.example.ansible.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class CliExtraVarsTest {

    @TempDir
    Path tempDir;

    @Test
    @SuppressWarnings("unchecked")
    void testParseExtraVars() throws Exception {
        PlaybookCli cli = new PlaybookCli();
        Method parseExtraVarsMethod = PlaybookCli.class.getDeclaredMethod("parseExtraVars", List.class);
        parseExtraVarsMethod.setAccessible(true);

        // 1. key=value
        Map<String, Object> result1 = (Map<String, Object>) parseExtraVarsMethod.invoke(cli, List.of("key1=val1", "key2=val2"));
        assertEquals("val1", result1.get("key1"));
        assertEquals("val2", result1.get("key2"));

        // 2. JSON string
        Map<String, Object> result2 = (Map<String, Object>) parseExtraVarsMethod.invoke(cli, List.of("{\"key3\": \"val3\", \"key4\": 42}"));
        assertEquals("val3", result2.get("key3"));
        assertEquals(42, result2.get("key4"));

        // 3. YAML string (Map-like)
        Map<String, Object> result3 = (Map<String, Object>) parseExtraVarsMethod.invoke(cli, List.of("key5: val5\nkey6: 66"));
        assertEquals("val5", result3.get("key5"));
        assertEquals(66, result3.get("key6"));

        // 4. @file
        Path varsFile = tempDir.resolve("extra_vars.yml");
        Files.writeString(varsFile, "key7: val7\nkey8: val8");
        Map<String, Object> result4 = (Map<String, Object>) parseExtraVarsMethod.invoke(cli, List.of("@" + varsFile.toAbsolutePath().toString()));
        assertEquals("val7", result4.get("key7"));
        assertEquals("val8", result4.get("key8"));

        // 5. Mixed
        Map<String, Object> result5 = (Map<String, Object>) parseExtraVarsMethod.invoke(cli, List.of("key=val", "{\"json_key\": \"json_val\"}", "@" + varsFile.toAbsolutePath().toString()));
        assertEquals("val", result5.get("key"));
        assertEquals("json_val", result5.get("json_key"));
        assertEquals("val7", result5.get("key7"));
    }
}
