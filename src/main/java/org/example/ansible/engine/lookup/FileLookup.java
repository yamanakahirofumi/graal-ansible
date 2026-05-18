package org.example.ansible.engine.lookup;

import com.hubspot.jinjava.interpret.JinjavaInterpreter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * file lookup plugin: reads the content of one or more files.
 */
public class FileLookup implements Lookup {
    @Override
    public List<Object> execute(JinjavaInterpreter interpreter, List<Object> terms, Map<String, Object> kwargs) {
        List<Object> results = new ArrayList<>();
        String playbookDir = (String) interpreter.getContext().get("playbook_dir");

        for (Object termObj : terms) {
            String term = termObj != null ? termObj.toString() : "";
            Path path = Paths.get(term);
            if (!path.isAbsolute() && playbookDir != null) {
                path = Paths.get(playbookDir, term);
            }

            try {
                results.add(Files.readString(path));
            } catch (IOException e) {
                throw new RuntimeException("File lookup failed for file: " + path + ". " + e.getMessage(), e);
            }
        }
        return results;
    }

    @Override
    public String getName() {
        return "file";
    }
}
