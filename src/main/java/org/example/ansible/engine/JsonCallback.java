package org.example.ansible.engine;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Callback implementation that outputs execution results in JSON format.
 * Refactored for thread-safety during parallel execution.
 */
public class JsonCallback implements Callback {

    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final Map<String, Object> results = new LinkedHashMap<>();
    private final List<Map<String, Object>> plays = Collections.synchronizedList(new ArrayList<>());

    // Maps to store context based on the object identity
    private final Map<Integer, Map<String, Object>> playMap = new ConcurrentHashMap<>();
    private final Map<Integer, List<Map<String, Object>>> playTasksMap = new ConcurrentHashMap<>();
    private final Map<Integer, Map<String, Object>> taskMap = new ConcurrentHashMap<>();
    private final Map<Integer, Map<String, Object>> taskHostsMap = new ConcurrentHashMap<>();

    // track the current play for the executing thread.
    private final ThreadLocal<Integer> currentPlayId = new ThreadLocal<>();
    // track the last task seen by this thread.
    private final ThreadLocal<Integer> currentTaskId = new ThreadLocal<>();

    // Global state for single-threaded or linear execution
    private volatile Integer lastPlayId;
    private volatile Integer lastTaskId;

    public JsonCallback() {
        results.put("plays", plays);
    }

    @Override
    public void v2_playbook_on_start(Playbook playbook) {
    }

    @Override
    public void v2_playbook_on_play_start(Play play) {
        int playId = System.identityHashCode(play);
        currentPlayId.set(playId);
        lastPlayId = playId;

        playMap.computeIfAbsent(playId, id -> {
            Map<String, Object> playData = new LinkedHashMap<>();
            Map<String, Object> playInfo = new HashMap<>();
            playInfo.put("name", play.name());
            playInfo.put("id", id);
            playData.put("play", playInfo);

            List<Map<String, Object>> tasks = Collections.synchronizedList(new ArrayList<>());
            playData.put("tasks", tasks);
            playTasksMap.put(id, tasks);
            plays.add(playData);
            return playData;
        });
    }

    @Override
    public void v2_playbook_on_task_start(Task task, boolean isConditional) {
        int taskId = System.identityHashCode(task);
        currentTaskId.set(taskId);
        lastTaskId = taskId;
        startTask(task.name(), task.action(), taskId);
    }

    private void startTask(String name, String action, int id) {
        Integer playId = getEffectivePlayId();
        if (playId == null) return;

        taskMap.computeIfAbsent(id, taskId -> {
            Map<String, Object> taskData = new LinkedHashMap<>();
            Map<String, Object> taskInfo = new HashMap<>();
            taskInfo.put("name", name);
            taskInfo.put("action", action);
            taskInfo.put("id", taskId);
            taskData.put("task", taskInfo);

            Map<String, Object> hostResults = new ConcurrentHashMap<>();
            taskData.put("hosts", hostResults);
            taskHostsMap.put(taskId, hostResults);

            List<Map<String, Object>> tasks = playTasksMap.get(playId);
            if (tasks != null) {
                tasks.add(taskData);
            }
            return taskData;
        });
    }

    @Override
    public void v2_runner_on_ok(String host, TaskResult result) {
        addHostResult(host, result);
    }

    @Override
    public void v2_runner_on_failed(String host, TaskResult result, boolean ignoreErrors) {
        Map<String, Object> data = addHostResult(host, result);
        if (data != null && ignoreErrors) {
            data.put("ignore_errors", true);
        }
    }

    @Override
    public void v2_runner_on_skipped(String host, TaskResult result) {
        addHostResult(host, result);
    }

    @Override
    public void v2_runner_on_unreachable(String host, TaskResult result) {
        addHostResult(host, result);
    }

    @Override
    public void v2_playbook_on_handler_stats(String handlerName) {
        int id = System.identityHashCode(handlerName);
        currentTaskId.set(id);
        lastTaskId = id;
        startTask(handlerName, "handler", id);
    }

    private Integer getEffectivePlayId() {
        Integer id = currentPlayId.get();
        return (id != null) ? id : lastPlayId;
    }

    private Integer getEffectiveTaskId() {
        Integer id = currentTaskId.get();
        return (id != null) ? id : lastTaskId;
    }

    private Map<String, Object> addHostResult(String host, TaskResult result) {
        Integer taskId = getEffectiveTaskId();
        if (taskId == null) return null;

        Map<String, Object> hostResults = taskHostsMap.get(taskId);
        if (hostResults == null) return null;

        Map<String, Object> data = new HashMap<>(result.data());
        data.put("changed", result.changed());
        if (!result.success()) {
            data.put("failed", true);
            if (result.message() != null && !result.message().isEmpty()) {
                data.put("msg", result.message());
            }
        }
        hostResults.put(host, data);
        return data;
    }

    @Override
    public synchronized void v2_playbook_on_stats(Map<String, Map<String, Integer>> stats) {
        results.put("stats", stats);
        try {
            System.out.println(mapper.writeValueAsString(results));
        } catch (JsonProcessingException e) {
            System.err.println("Failed to serialize results to JSON: " + e.getMessage());
        }
    }
}
