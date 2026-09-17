package org.example.ansible.engine.lookup;

import org.example.ansible.engine.VariableResolver;
import com.hubspot.jinjava.interpret.JinjavaInterpreter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * template lookup plugin: reads and renders template files.
 */
public class TemplateLookup implements Lookup {
    @Override
    public List<Object> execute(JinjavaInterpreter interpreter, List<Object> terms, Map<String, Object> kwargs) {
        List<Object> results = new ArrayList<>();
        String playbookDir = (String) interpreter.getContext().get("playbook_dir");

        VariableResolver resolver = (VariableResolver) interpreter.getContext().get("__ansible_resolver");
        if (resolver == null) {
            throw new IllegalStateException("VariableResolver not found in Jinjava context");
        }

        boolean convertData = true;
        if (kwargs != null && kwargs.containsKey("convert_data")) {
            Object cd = kwargs.get("convert_data");
            if (cd instanceof Boolean b) {
                convertData = b;
            } else if (cd != null) {
                convertData = Boolean.parseBoolean(cd.toString());
            }
        }

        String errors = kwargs != null && kwargs.containsKey("errors") ? kwargs.get("errors").toString() : "strict";

        for (Object termObj : terms) {
            String term = termObj != null ? termObj.toString() : "";
            Path path = Paths.get(term);
            if (!path.isAbsolute() && playbookDir != null) {
                path = Paths.get(playbookDir, term);
            }

            try {
                String templateContent = Files.readString(path);
                // We use resolveValue which handles templating
                Object rendered = resolver.resolveValue(templateContent, interpreter.getContext());
                if (convertData && rendered instanceof String str) {
                    try {
                        Object parsed = org.example.ansible.util.YamlUtil.createYaml().load(str);
                        if (parsed instanceof Map || parsed instanceof List) {
                            results.add(parsed);
                            continue;
                        }
                    } catch (Exception ignored) {
                    }
                }
                results.add(rendered != null ? rendered : "");
            } catch (Exception e) {
                if ("warn".equalsIgnoreCase(errors) || "ignore".equalsIgnoreCase(errors)) {
                    continue;
                }
                throw new RuntimeException("Template lookup failed for file: " + path + ". " + e.getMessage(), e);
            }
        }
        return results;
    }

    @Override
    public String getName() {
        return "template";
    }
}
