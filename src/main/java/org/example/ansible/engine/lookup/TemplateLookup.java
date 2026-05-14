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
    public List<Object> execute(List<Object> terms, Map<String, Object> variables) {
        List<Object> results = new ArrayList<>();
        String playbookDir = (String) variables.get("playbook_dir");

        JinjavaInterpreter interpreter = JinjavaInterpreter.getCurrent();
        if (interpreter == null) {
            throw new IllegalStateException("JinjavaInterpreter not found");
        }

        VariableResolver resolver = (VariableResolver) interpreter.getContext().get("__ansible_resolver");
        if (resolver == null) {
            throw new IllegalStateException("VariableResolver not found in Jinjava context");
        }

        for (Object termObj : terms) {
            String term = termObj != null ? termObj.toString() : "";
            Path path = Paths.get(term);
            if (!path.isAbsolute() && playbookDir != null) {
                path = Paths.get(playbookDir, term);
            }

            try {
                String templateContent = Files.readString(path);
                // We use resolveValue which handles templating
                Object rendered = resolver.resolveValue(templateContent, variables);
                results.add(rendered != null ? rendered.toString() : "");
            } catch (IOException e) {
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
