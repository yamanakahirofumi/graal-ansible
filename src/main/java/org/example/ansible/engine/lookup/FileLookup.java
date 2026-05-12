package org.example.ansible.engine.lookup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * lookup('file', ...)
 */
public class FileLookup implements Lookup {
    @Override
    public List<Object> run(List<String> terms, Map<String, Object> variables, Map<String, Object> kwargs) {
        List<Object> results = new ArrayList<>();
        Path baseDir = null;
        Object playbookDir = variables.get("playbook_dir");
        if (playbookDir != null) {
            baseDir = Path.of(playbookDir.toString());
        }

        for (String term : terms) {
            Path path = Path.of(term);
            if (!path.isAbsolute() && baseDir != null) {
                path = baseDir.resolve(term);
            }
            try {
                results.add(Files.readString(path));
            } catch (IOException e) {
                throw new RuntimeException("Lookup failed for file: " + term, e);
            }
        }
        return results;
    }

    @Override
    public String getName() {
        return "file";
    }
}
