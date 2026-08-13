package org.example.ansible.engine.lookup;

import org.example.ansible.util.Truthiness;

import com.hubspot.jinjava.interpret.JinjavaInterpreter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * first_found lookup plugin: searches for the first existing file in a list of candidates.
 */
public class FirstFoundLookup implements Lookup {

    @Override
    public List<Object> execute(JinjavaInterpreter interpreter, List<Object> terms, Map<String, Object> kwargs) {
        final List<String> files = new ArrayList<>();
        final List<String> paths = new ArrayList<>();
        boolean skip = false;

        // Process terms
        for (final Object term : terms) {
            if (term instanceof Map<?, ?> map) {
                if (map.containsKey("files")) {
                    files.addAll(parseList(map.get("files")));
                }
                if (map.containsKey("paths")) {
                    paths.addAll(parseList(map.get("paths")));
                }
                if (map.containsKey("skip")) {
                    skip = Truthiness.isTrue(map.get("skip"));
                }
            } else {
                files.addAll(parseList(term));
            }
        }

        // Process kwargs
        if (kwargs != null) {
            if (kwargs.containsKey("files")) {
                files.addAll(parseList(kwargs.get("files")));
            }
            if (kwargs.containsKey("paths")) {
                paths.addAll(parseList(kwargs.get("paths")));
            }
            if (kwargs.containsKey("skip")) {
                skip = Truthiness.isTrue(kwargs.get("skip"));
            }
        }

        final String playbookDir = (String) interpreter.getContext().get("playbook_dir");
        final List<Object> results = new ArrayList<>();

        for (final String file : files) {
            final Path filePath = Paths.get(file);
            if (filePath.isAbsolute()) {
                if (Files.isRegularFile(filePath)) {
                    results.add(filePath.toAbsolutePath().toString());
                    return results;
                }
            } else {
                if (paths.isEmpty()) {
                    Path resolved = filePath;
                    if (playbookDir != null) {
                        resolved = Paths.get(playbookDir, file);
                    }
                    if (Files.isRegularFile(resolved)) {
                        results.add(resolved.toAbsolutePath().toString());
                        return results;
                    }
                } else {
                    for (final String path : paths) {
                        final Path pathPath = Paths.get(path);
                        final Path resolvedPath;
                        if (pathPath.isAbsolute()) {
                            resolvedPath = pathPath;
                        } else if (playbookDir != null) {
                            resolvedPath = Paths.get(playbookDir, path);
                        } else {
                            resolvedPath = pathPath;
                        }

                        final Path finalPath = resolvedPath.resolve(file);
                        if (Files.isRegularFile(finalPath)) {
                            results.add(finalPath.toAbsolutePath().toString());
                            return results;
                        }
                    }
                }
            }
        }

        if (results.isEmpty() && !skip) {
            throw new RuntimeException("No file was found for first_found lookup. Candidates: " + files);
        }

        return results;
    }

    private List<String> parseList(Object value) {
        final List<String> list = new ArrayList<>();
        if (value == null) {
            return list;
        }
        if (value instanceof List<?> l) {
            for (final Object item : l) {
                list.addAll(parseList(item));
            }
        } else if (value instanceof String s) {
            if (s.contains(",")) {
                for (final String part : s.split(",")) {
                    if (!part.trim().isEmpty()) {
                        list.add(part.trim());
                    }
                }
            } else if (!s.trim().isEmpty()) {
                list.add(s.trim());
            }
        } else {
            final String s = value.toString().trim();
            if (!s.isEmpty()) {
                list.add(s);
            }
        }
        return list;
    }

    @Override
    public String getName() {
        return "first_found";
    }
}
