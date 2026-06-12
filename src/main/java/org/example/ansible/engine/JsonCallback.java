package org.example.ansible.engine;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Callback implementation that outputs execution results in JSON format.
 */
public class JsonCallback implements Callback {

    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final Map<String, Object> results = new LinkedHashMap<>();
    private final List<Map<String, Object>> plays = new ArrayList<>();

    private Map<String, Object> currentPlay;
    private List<Map<String, Object>> currentTasks;
    private Map<String, Object> currentTask;
    private Map<String, Object> currentHostResults;

    public JsonCallback() {
        results.put("plays", plays);
    }

    @Override
    public void v2_playbook_on_start(Playbook playbook) {
        // Playbook level info could be added here if needed
    }

    @Override
    public void v2_playbook_on_play_start(Play play) {
        currentPlay = new LinkedHashMap<>();
        Map<String, Object> playInfo = new HashMap<>();
        playInfo.put("name", play.name());
        playInfo.put("id", System.identityHashCode(play));
        currentPlay.put("play", playInfo);

        currentTasks = new ArrayList<>();
        currentPlay.put("tasks", currentTasks);
        plays.add(currentPlay);
    }

    @Override
    public void v2_playbook_on_task_start(Task task, boolean isConditional) {
        startTask(task.name(), task.action(), System.identityHashCode(task));
    }

    private void startTask(String name, String action, int id) {
        currentTask = new LinkedHashMap<>();
        Map<String, Object> taskInfo = new HashMap<>();
        taskInfo.put("name", name);
        taskInfo.put("action", action);
        taskInfo.put("id", id);
        currentTask.put("task", taskInfo);

        currentHostResults = new LinkedHashMap<>();
        currentTask.put("hosts", currentHostResults);
        currentTasks.add(currentTask);
    }

    @Override
    public void v2_runner_on_ok(String host, TaskResult result) {
        addHostResult(host, result);
    }

    @Override
    public void v2_runner_on_failed(String host, TaskResult result, boolean ignoreErrors) {
        Map<String, Object> data = addHostResult(host, result);
        if (ignoreErrors) {
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
        startTask(handlerName, "handler", System.identityHashCode(handlerName));
    }

    private Map<String, Object> addHostResult(String host, TaskResult result) {
        Map<String, Object> data = new HashMap<>(result.data());
        data.put("changed", result.changed());
        if (!result.success()) {
            data.put("failed", true);
            if (result.message() != null && !result.message().isEmpty()) {
                data.put("msg", result.message());
            }
        }
        currentHostResults.put(host, data);
        return data;
    }

    @Override
    public void v2_playbook_on_stats(Map<String, Map<String, Integer>> stats) {
        results.put("stats", stats);
        try {
            System.out.println(mapper.writeValueAsString(results));
        } catch (JsonProcessingException e) {
            // Fallback to simple print if JSON fails
            System.err.println("Failed to serialize results to JSON: " + e.getMessage());
        }
    }
}
