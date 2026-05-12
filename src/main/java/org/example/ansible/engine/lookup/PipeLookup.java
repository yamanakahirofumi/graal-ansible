package org.example.ansible.engine.lookup;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * lookup('pipe', ...)
 */
public class PipeLookup implements Lookup {
    @Override
    public List<Object> run(List<String> terms, Map<String, Object> variables, Map<String, Object> kwargs) {
        List<Object> results = new ArrayList<>();
        for (String term : terms) {
            try {
                Process process = new ProcessBuilder("/bin/sh", "-c", term).start();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    results.add(reader.lines().collect(Collectors.joining("\n")));
                }
                process.waitFor();
            } catch (Exception e) {
                throw new RuntimeException("Lookup failed for pipe: " + term, e);
            }
        }
        return results;
    }

    @Override
    public String getName() {
        return "pipe";
    }
}
