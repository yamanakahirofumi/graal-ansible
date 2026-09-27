package org.example.ansible.engine;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MinimalCallback outputs execution results in a simple one-line format per host.
 */
public class MinimalCallback implements Callback {

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public void v2_playbook_on_start(Playbook playbook) {
    }

    @Override
    public void v2_playbook_on_play_start(Play play) {
    }

    @Override
    public void v2_playbook_on_task_start(Task task, boolean isConditional) {
    }

    @Override
    public synchronized void v2_runner_on_ok(Task task, String host, TaskResult result) {
        String json = formatData(result);
        if (result.changed()) {
            System.out.println(host + " | CHANGED => " + json);
        } else {
            System.out.println(host + " | SUCCESS => " + json);
        }
    }

    @Override
    public synchronized void v2_runner_on_failed(Task task, String host, TaskResult result, boolean ignoreErrors) {
        String json = formatData(result);
        if (ignoreErrors) {
            System.out.println(host + " | FAILED! => " + json);
            System.out.println("...ignoring");
        } else {
            System.out.println(host + " | FAILED! => " + json);
        }
    }

    @Override
    public synchronized void v2_runner_on_skipped(Task task, String host, TaskResult result) {
        System.out.println(host + " | SKIPPED");
    }

    @Override
    public synchronized void v2_runner_on_unreachable(Task task, String host, TaskResult result) {
        String json = formatData(result);
        System.out.println(host + " | UNREACHABLE! => " + json);
    }

    @Override
    public void v2_playbook_on_handler_stats(String handlerName) {
    }

    @Override
    public void v2_playbook_on_stats(Map<String, Map<String, Integer>> stats) {
    }

    private String formatData(TaskResult result) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (result.data() != null) {
            data.putAll(result.data());
        }
        data.put("changed", result.changed());
        if (!result.success()) {
            data.put("failed", true);
            if (result.message() != null && !result.message().isEmpty()) {
                data.put("msg", result.message());
            }
        }
        try {
            return mapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            return data.toString();
        }
    }
}
