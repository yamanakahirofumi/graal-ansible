package org.example.ansible.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PythonEnvTest {

    @Test
    void testGetCollectionPathsWithCliOption() {
        List<String> paths = PythonEnv.getCollectionPaths("/custom/cli/path");
        assertTrue(paths.contains("/custom/cli/path"));
        assertEquals("/custom/cli/path", paths.get(0));
    }

    @Test
    void testParseAnsibleCfgCollectionsPath(@TempDir File tempDir) throws IOException {
        File cfgFile = new File(tempDir, "ansible.cfg");
        try (FileWriter writer = new FileWriter(cfgFile)) {
            writer.write("[defaults]\n");
            writer.write("collections_paths = /path/one:/path/two\n");
            writer.write("other_setting = true\n");
        }

        List<String> parsed = PythonEnv.parseAnsibleCfgCollectionsPath(cfgFile);
        assertEquals(2, parsed.size());
        assertEquals("/path/one", parsed.get(0));
        assertEquals("/path/two", parsed.get(1));
    }

    @Test
    void testParseAnsibleCfgCollectionsPathSingleKeyAlias(@TempDir File tempDir) throws IOException {
        File cfgFile = new File(tempDir, "ansible.cfg");
        try (FileWriter writer = new FileWriter(cfgFile)) {
            writer.write("# Comment line\n");
            writer.write("[defaults]\n");
            writer.write("collections_path = /path/alias1;/path/alias2\n");
        }

        List<String> parsed = PythonEnv.parseAnsibleCfgCollectionsPath(cfgFile);
        assertEquals(2, parsed.size());
        assertEquals("/path/alias1", parsed.get(0));
        assertEquals("/path/alias2", parsed.get(1));
    }

    @Test
    void testGetCollectionPathsDefaultFallback() {
        List<String> paths = PythonEnv.getCollectionPaths(null);
        boolean containsDefault = false;
        for (String p : paths) {
            if (p.contains("collections")) {
                containsDefault = true;
                break;
            }
        }
        assertTrue(containsDefault, "Collection paths should include default collection directories");
    }
}
