package org.example.ansible.engine.lookup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * file lookup plugin: reads file contents.
 */
public class FileLookup implements Lookup {
    @Override
    public List<Object> execute(List<String> terms, Map<String, Object> variables) {
        List<Object> results = new ArrayList<>();
        String playbookDir = (String) variables.get("playbook_dir");

        for (String term : terms) {
            Path path = Paths.get(term);
            if (!path.isAbsolute() && playbookDir != null) {
                path = Paths.get(playbookDir, term);
            }

            try {
                String content = Files.readString(path);
                results.add(content);
            } catch (IOException e) {
                throw new RuntimeException("Lookup failed for file: " + path + ". " + e.getMessage(), e);
            }
        }
        return results;
    }

    @Override
    public String getName() {
        return "file";
    }
}
