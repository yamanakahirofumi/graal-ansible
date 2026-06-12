package org.example.ansible.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class JsonCallbackTest {

    @Test
    public void testJsonOutputFormat() throws Exception {
        JsonCallback callback = new JsonCallback();

        Play playbook = new Play("test play", "all", List.of());
        Task task = new Task("test task", "debug", Map.of("msg", "hello"));
        TaskResult result = TaskResult.success(false, Map.of("msg", "hello"));

        callback.v2_playbook_on_play_start(playbook);
        callback.v2_playbook_on_task_start(task, false);
        callback.v2_runner_on_ok("localhost", result);

        Map<String, Map<String, Integer>> stats = Map.of("localhost", Map.of("ok", 1, "changed", 0, "failed", 0, "skipped", 0));

        PrintStream originalOut = System.out;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        System.setOut(new PrintStream(baos));

        try {
            callback.v2_playbook_on_stats(stats);
        } finally {
            System.setOut(originalOut);
        }

        String output = baos.toString();
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(output);

        assertTrue(root.has("plays"));
        assertTrue(root.has("stats"));

        JsonNode plays = root.get("plays");
        assertEquals(1, plays.size());
        assertEquals("test play", plays.get(0).get("play").get("name").asText());

        JsonNode tasks = plays.get(0).get("tasks");
        assertEquals(1, tasks.size());
        assertEquals("test task", tasks.get(0).get("task").get("name").asText());
        assertEquals("debug", tasks.get(0).get("task").get("action").asText());

        JsonNode hostResult = tasks.get(0).get("hosts").get("localhost");
        assertEquals("hello", hostResult.get("msg").asText());
        assertFalse(hostResult.get("changed").asBoolean());

        JsonNode hostStats = root.get("stats").get("localhost");
        assertEquals(1, hostStats.get("ok").asInt());
    }
}
