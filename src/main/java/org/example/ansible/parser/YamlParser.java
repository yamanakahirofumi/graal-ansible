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
            "vars", "notify", "with_items", "with_list", "with_dict",
            "failed_when", "changed_when", "delegate_to", "run_once",
            "block", "rescue", "always"
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

        List<Task> handlers = new ArrayList<>();
        Object handlersObj = map.get("handlers");
        if (handlersObj instanceof List<?> handlersList) {
            for (Object handlerItem : handlersList) {
                if (handlerItem instanceof Map<?, ?> handlerMap) {
                    handlers.add(parseTask((Map<String, Object>) handlerMap));
                }
            }
        }

        Map<String, Object> vars = (Map<String, Object>) map.getOrDefault("vars", Map.of());

        List<String> varsFiles = new ArrayList<>();
        Object varsFilesObj = map.get("vars_files");
        if (varsFilesObj instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof String s) {
                    varsFiles.add(s);
                }
            }
        } else if (varsFilesObj instanceof String s) {
            varsFiles.add(s);
        }

        return new Play(name, hosts, tasks, vars, varsFiles, handlers);
    }

    @SuppressWarnings("unchecked")
    private Task parseTask(Map<String, Object> map) {
        String name = (String) map.getOrDefault("name", "Unnamed Task");
        String action = null;
        Map<String, Object> args = Map.of();

        List<Task> block = parseTaskList(map.get("block"));
        List<Task> rescue = parseTaskList(map.get("rescue"));
        List<Task> always = parseTaskList(map.get("always"));

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

        if (action == null && block.isEmpty()) {
            throw new IllegalArgumentException("Task '" + name + "' is missing a module/action.");
        }

        Map<String, Object> vars = (Map<String, Object>) map.getOrDefault("vars", Map.of());
        Object when = map.get("when");
        String register = (String) map.get("register");
        Object loop = map.get("loop");
        if (loop == null) {
            loop = map.get("with_items");
        }

        Object failedWhen = map.get("failed_when");
        Object changedWhen = map.get("changed_when");
        boolean ignoreErrors = Boolean.TRUE.equals(map.get("ignore_errors"));

        Object until = map.get("until");
        Integer retries = (Integer) map.getOrDefault("retries", 3);
        Integer delay = (Integer) map.getOrDefault("delay", 5);
        String delegateTo = (String) map.get("delegate_to");
        boolean runOnce = Boolean.TRUE.equals(map.get("run_once"));

        List<String> notify = new ArrayList<>();
        Object notifyObj = map.get("notify");
        if (notifyObj instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof String s) notify.add(s);
            }
        } else if (notifyObj instanceof String s) {
            notify.add(s);
        }

        return new Task(name, action, args, vars, when, register, loop, notify, failedWhen, changedWhen, ignoreErrors,
                until, retries, delay, delegateTo, runOnce, block, rescue, always);
    }

    @SuppressWarnings("unchecked")
    private List<Task> parseTaskList(Object obj) {
        List<Task> tasks = new ArrayList<>();
        if (obj instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    tasks.add(parseTask((Map<String, Object>) map));
                }
            }
        }
        return tasks;
    }
}
