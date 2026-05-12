package org.example.ansible.engine.lookup;

import com.hubspot.jinjava.Jinjava;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * lookup('template', ...)
 */
public class TemplateLookup implements Lookup {
    private final Jinjava jinjava;

    public TemplateLookup(Jinjava jinjava) {
        this.jinjava = jinjava;
    }

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
                String templateContent = Files.readString(path);
                results.add(jinjava.render(templateContent, variables));
            } catch (IOException e) {
                throw new RuntimeException("Lookup failed for template: " + term, e);
            }
        }
        return results;
    }

    @Override
    public String getName() {
        return "template";
    }
}
