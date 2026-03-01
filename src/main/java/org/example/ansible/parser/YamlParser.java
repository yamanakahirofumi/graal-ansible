package org.example.ansible.parser;

import org.example.ansible.engine.Play;
import org.example.ansible.engine.Playbook;
import org.example.ansible.engine.Task;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Parses Ansible Playbook YAML files into Playbook objects.
 */
public class YamlParser {

    private static final Set<String> RESERVED_TASK_KEYS = Set.of(
            "name", "register", "when", "loop", "until", "retries", "delay",
            "ignore_errors", "tags", "become", "become_user", "become_method",
            "vars", "notify", "with_items", "with_list", "with_dict"
    );

    private final Yaml yaml;

    public YamlParser() {
        this.yaml = new Yaml();
    }

    /**
     * Parses a Playbook from an InputStream.
     *
     * @param inputStream The input stream of the YAML file.
     * @return The parsed Playbook.
     */
    @SuppressWarnings("unchecked")
    public Playbook parse(InputStream inputStream) {
        Object raw = yaml.load(inputStream);
        List<Play> plays = new ArrayList<>();

        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    plays.add(parsePlay((Map<String, Object>) map));
                }
            }
        }

        return new Playbook(plays);
    }

    @SuppressWarnings("unchecked")
    private Play parsePlay(Map<String, Object> map) {
        String name = (String) map.getOrDefault("name", "Unnamed Play");
        String hosts = (String) map.get("hosts");
        List<Task> tasks = new ArrayList<>();
        Object tasksObj = map.get("tasks");

        if (tasksObj instanceof List<?> tasksList) {
            for (Object taskItem : tasksList) {
                if (taskItem instanceof Map<?, ?> taskMap) {
                    tasks.add(parseTask((Map<String, Object>) taskMap));
                }
            }
        }

        Map<String, Object> vars = (Map<String, Object>) map.getOrDefault("vars", Map.of());

        return new Play(name, hosts, tasks, vars);
    }

    @SuppressWarnings("unchecked")
    private Task parseTask(Map<String, Object> map) {
        String name = (String) map.getOrDefault("name", "Unnamed Task");
        String action = null;
        Map<String, Object> args = Map.of();

        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = entry.getKey();
            if (RESERVED_TASK_KEYS.contains(key)) {
                continue;
            }

            // The first non-reserved key is treated as the module name (action)
            action = key;
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> argsMap) {
                args = (Map<String, Object>) argsMap;
            } else if (value instanceof String strValue) {
                args = Map.of("_raw_params", strValue);
            }
            break;
        }

        if (action == null) {
            throw new IllegalArgumentException("Task '" + name + "' is missing a module/action.");
        }

        Map<String, Object> vars = (Map<String, Object>) map.getOrDefault("vars", Map.of());

        return new Task(name, action, args, vars);
    }
}
