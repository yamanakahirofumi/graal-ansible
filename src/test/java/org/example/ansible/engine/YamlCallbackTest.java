package org.example.ansible.engine;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class YamlCallbackTest {

    @Test
    public void testYamlCallbackOutput() {
        YamlCallback callback = new YamlCallback();
        Play play = new Play("Yaml Play", "all", List.of());
        Task task = new Task("Yaml Task", "debug", Map.of());

        TaskResult result = TaskResult.success(true, Map.of("msg", "hello world", "rc", 0));

        PrintStream originalOut = System.out;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        System.setOut(new PrintStream(baos));

        try {
            callback.v2_playbook_on_play_start(play);
            callback.v2_playbook_on_task_start(task, false);
            callback.v2_runner_on_ok(task, "localhost", result);
        } finally {
            System.setOut(originalOut);
        }

        String output = baos.toString();
        assertTrue(output.contains("PLAY [Yaml Play]"));
        assertTrue(output.contains("TASK [Yaml Task]"));
        assertTrue(output.contains("changed: [localhost] =>"));
        assertTrue(output.contains("msg: hello world"));
        assertTrue(output.contains("changed: true"));
    }
}
