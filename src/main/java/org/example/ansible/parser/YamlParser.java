package org.example.ansible.parser;

import org.example.ansible.engine.Play;
import org.example.ansible.engine.Playbook;
import org.example.ansible.engine.Role;
import org.example.ansible.engine.Task;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Parses Ansible Playbook YAML files into Playbook objects.
 */
public class YamlParser {

    private static final Set<String> RESERVED_TASK_KEYS = Set.of(
            "name", "register", "when", "loop", "loop_control", "until", "retries", "delay",
            "ignore_errors", "ignore_unreachable", "tags", "become", "become_user", "become_method", "become_flags",
            "vars", "notify", "listen", "with_items", "with_list", "with_dict",
            "failed_when", "changed_when", "delegate_to", "delegate_facts", "run_once",
            "block", "rescue", "always", "check_mode", "environment"
    );

    private final Yaml yaml;

    public YamlParser() {
        this.yaml = new Yaml();
    }

    /**
     * Parses a Playbook from a File.
     *
     * @param file The playbook file.
     * @return The parsed Playbook.
     */
    public Playbook parse(File file) {
        return parse(file, Map.of(), List.of());
    }

    /**
     * Parses a Playbook from a File with inherited variables and tags.
     */
    private Playbook parse(File file, Map<String, Object> inheritedVars, List<String> inheritedTags) {
        try (InputStream is = new FileInputStream(file)) {
            return parse(is, file.getAbsoluteFile().getParentFile(), inheritedVars, inheritedTags);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load playbook: " + file, e);
        }
    }

    /**
     * Parses a Playbook from an InputStream.
     *
     * @param inputStream The input stream of the YAML file.
     * @return The parsed Playbook.
     */
    public Playbook parse(InputStream inputStream) {
        return parse(inputStream, null, Map.of(), List.of());
    }

    /**
     * Parses a Playbook from an InputStream with a base directory for imports.
     */
    @SuppressWarnings("unchecked")
    public Playbook parse(InputStream inputStream, File currentDir, Map<String, Object> inheritedVars, List<String> inheritedTags) {
        Object raw = yaml.load(inputStream);
        List<Play> plays = new ArrayList<>();

        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Map<String, Object> mapItem = (Map<String, Object>) map;
                    if (mapItem.containsKey("import_playbook")) {
                        plays.addAll(handleImportPlaybook(mapItem, currentDir, inheritedVars, inheritedTags));
                    } else {
                        Play play = parsePlay(mapItem, inheritedTags);
                        if (!inheritedVars.isEmpty()) {
                            Map<String, Object> mergedVars = new HashMap<>(play.vars());
                            mergedVars.putAll(inheritedVars);
                            play = new Play(
                                    play.name(),
                                    play.hosts(),
                                    play.tasks(),
                                    mergedVars,
                                    play.varsFiles(),
                                    play.varsPrompt(),
                                    play.roles(),
                                    play.handlers(),
                                    play.become(),
                                    play.becomeMethod(),
                                    play.becomeUser(),
                                    play.becomeFlags(),
                                    play.checkMode(),
                                    play.environment(),
                                    play.tags()
                            );
                        }
                        plays.add(play);
                    }
                }
            }
        }

        return new Playbook(plays);
    }

    @SuppressWarnings("unchecked")
    private List<Play> handleImportPlaybook(Map<String, Object> map, File currentDir, Map<String, Object> inheritedVars, List<String> inheritedTags) {
        String importedFile = (String) map.get("import_playbook");
        Map<String, Object> importVars = (Map<String, Object>) map.getOrDefault("vars", Map.of());
        List<String> importTags = parseTags(map.get("tags"));

        // In Ansible, vars from import_playbook statement win over anything inherited from parent import_playbook.
        // And combined, they win over vars inside the imported plays.
        Map<String, Object> combinedVars = new HashMap<>(inheritedVars);
        combinedVars.putAll(importVars);

        List<String> combinedTags = new ArrayList<>(inheritedTags);
        combinedTags.addAll(importTags);

        File file = new File(importedFile);
        if (!file.isAbsolute() && currentDir != null) {
            file = new File(currentDir, importedFile);
        }

        Playbook importedPlaybook = parse(file, combinedVars, combinedTags);
        return importedPlaybook.plays();
    }

    /**
     * Parses a list of tasks from an InputStream.
     *
     * @param inputStream The input stream of the YAML file.
     * @return The list of parsed tasks.
     */
    public List<Task> parseTasks(InputStream inputStream) {
        return parseTasks(inputStream, List.of());
    }

    /**
     * Parses a list of tasks from an InputStream with inherited tags.
     *
     * @param inputStream   The input stream of the YAML file.
     * @param inheritedTags The tags to inherit.
     * @return The list of parsed tasks.
     */
    public List<Task> parseTasks(InputStream inputStream, List<String> inheritedTags) {
        Object raw = yaml.load(inputStream);
        return parseTaskList(raw, inheritedTags);
    }

    @SuppressWarnings("unchecked")
    private Play parsePlay(Map<String, Object> map, List<String> inheritedTags) {
        String name = (String) map.getOrDefault("name", "Unnamed Play");
        String hosts = (String) map.get("hosts");

        List<String> playTags = new ArrayList<>(inheritedTags);
        playTags.addAll(parseTags(map.get("tags")));

        List<Task> tasks = new ArrayList<>();
        Object tasksObj = map.get("tasks");

        if (tasksObj instanceof List<?> tasksList) {
            for (Object taskItem : tasksList) {
                if (taskItem instanceof Map<?, ?> taskMap) {
                    tasks.add(parseTask((Map<String, Object>) taskMap, playTags));
                }
            }
        }

        List<Role> roles = new ArrayList<>();
        Object rolesObj = map.get("roles");
        if (rolesObj instanceof List<?> rolesList) {
            for (Object roleItem : rolesList) {
                if (roleItem instanceof String roleName) {
                    roles.add(new Role(roleName));
                } else if (roleItem instanceof Map<?, ?> roleMap) {
                    Map<String, Object> rMap = (Map<String, Object>) roleMap;
                    String roleName = (String) rMap.get("role");
                    if (roleName == null) {
                        for (Map.Entry<String, Object> entry : rMap.entrySet()) {
                            if (!"vars".equals(entry.getKey()) && !"tags".equals(entry.getKey()) && !"when".equals(entry.getKey())) {
                                roleName = entry.getKey();
                                break;
                            }
                        }
                    }
                    Map<String, Object> roleVars = new HashMap<>(rMap);
                    roleVars.remove("role");
                    roles.add(new Role(roleName, roleVars));
                }
            }
        }

        List<Task> handlers = new ArrayList<>();
        Object handlersObj = map.get("handlers");
        if (handlersObj instanceof List<?> handlersList) {
            for (Object handlerItem : handlersList) {
                if (handlerItem instanceof Map<?, ?> handlerMap) {
                    handlers.add(parseTask((Map<String, Object>) handlerMap, playTags));
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

        List<Map<String, Object>> varsPrompt = new ArrayList<>();
        Object varsPromptObj = map.get("vars_prompt");
        if (varsPromptObj instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> promptMap) {
                    varsPrompt.add((Map<String, Object>) promptMap);
                } else if (item instanceof String s) {
                    varsPrompt.add(Map.of("name", s));
                }
            }
        } else if (varsPromptObj instanceof Map<?, ?> promptMap) {
            varsPrompt.add((Map<String, Object>) promptMap);
        }

        Object become = map.get("become");
        String becomeMethod = (String) map.get("become_method");
        String becomeUser = (String) map.get("become_user");
        String becomeFlags = (String) map.get("become_flags");
        Object checkMode = map.get("check_mode");
        Object environment = map.get("environment");

        return new Play(name, hosts, tasks, vars, varsFiles, varsPrompt, roles, handlers, become, becomeMethod, becomeUser, becomeFlags, checkMode, environment, playTags);
    }

    @SuppressWarnings("unchecked")
    private Task parseTask(Map<String, Object> map) {
        return parseTask(map, List.of());
    }

    @SuppressWarnings("unchecked")
    private Task parseTask(Map<String, Object> map, List<String> inheritedTags) {
        String name = (String) map.getOrDefault("name", "Unnamed Task");
        String action = null;
        Map<String, Object> args = Map.of();

        List<String> taskTags = new ArrayList<>(inheritedTags);
        taskTags.addAll(parseTags(map.get("tags")));

        List<Task> block = parseTaskList(map.get("block"), taskTags);
        List<Task> rescue = parseTaskList(map.get("rescue"), taskTags);
        List<Task> always = parseTaskList(map.get("always"), taskTags);

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
            Object withItems = map.get("with_items");
            if (withItems != null) {
                loop = wrapWithFilter(withItems, "flatten(levels=1)");
            }
        }
        if (loop == null) {
            Object withDict = map.get("with_dict");
            if (withDict != null) {
                loop = wrapWithFilter(withDict, "dict2items");
            }
        }
        if (loop == null) {
            loop = map.get("with_list");
        }
        Map<String, Object> loopControl = (Map<String, Object>) map.getOrDefault("loop_control", Map.of());

        Object failedWhen = map.get("failed_when");
        Object changedWhen = map.get("changed_when");
        boolean ignoreErrors = Boolean.TRUE.equals(map.get("ignore_errors"));

        Object until = map.get("until");
        Integer retries = (Integer) map.getOrDefault("retries", 3);
        Integer delay = (Integer) map.getOrDefault("delay", 5);
        String delegateTo = (String) map.get("delegate_to");
        boolean delegateFacts = Boolean.TRUE.equals(map.get("delegate_facts"));
        boolean runOnce = Boolean.TRUE.equals(map.get("run_once"));
        boolean ignoreUnreachable = Boolean.TRUE.equals(map.get("ignore_unreachable"));

        Object become = map.get("become");
        String becomeMethod = (String) map.get("become_method");
        String becomeUser = (String) map.get("become_user");
        String becomeFlags = (String) map.get("become_flags");
        Object checkMode = map.get("check_mode");
        Object environment = map.get("environment");

        List<String> notify = new ArrayList<>();
        Object notifyObj = map.get("notify");
        if (notifyObj instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof String s) notify.add(s);
            }
        } else if (notifyObj instanceof String s) {
            notify.add(s);
        }

        List<String> listen = new ArrayList<>();
        Object listenObj = map.get("listen");
        if (listenObj instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof String s) listen.add(s);
            }
        } else if (listenObj instanceof String s) {
            listen.add(s);
        }

        return new Task(name, action, args, vars, when, register, loop, loopControl, notify, failedWhen, changedWhen, ignoreErrors,
                until, retries, delay, delegateTo, delegateFacts, runOnce, ignoreUnreachable, block, rescue, always,
                become, becomeMethod, becomeUser, becomeFlags, checkMode, environment, taskTags, listen);
    }

    @SuppressWarnings("unchecked")
    private List<Task> parseTaskList(Object obj, List<String> inheritedTags) {
        List<Task> tasks = new ArrayList<>();
        if (obj instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    tasks.add(parseTask((Map<String, Object>) map, inheritedTags));
                }
            }
        }
        return tasks;
    }

    private Object wrapWithFilter(Object value, String filter) {
        if (value instanceof String s) {
            String trimmed = s.trim();
            if (trimmed.startsWith("{{") && trimmed.endsWith("}}")) {
                return trimmed.substring(0, trimmed.length() - 2) + " | " + filter + " }}";
            } else {
                return "{{ " + trimmed + " | " + filter + " }}";
            }
        } else {
            Map<String, Object> wrapped = new HashMap<>();
            wrapped.put("__ansible_loop_source", value);
            wrapped.put("__ansible_loop_filter", filter);
            return wrapped;
        }
    }

    private List<String> parseTags(Object obj) {
        if (obj == null) return List.of();
        if (obj instanceof List<?> list) {
            return list.stream().map(Object::toString).toList();
        } else if (obj instanceof String s) {
            return List.of(s);
        }
        return List.of(obj.toString());
    }
}
