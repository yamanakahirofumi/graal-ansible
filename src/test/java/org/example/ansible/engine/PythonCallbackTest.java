package org.example.ansible.engine;

import org.example.ansible.inventory.Host;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class PythonCallbackTest {

    @TempDir
    Path tempDir;

    @Test
    public void testPythonCallbackLoading() throws Exception {
        // Prepare a mock Python callback plugin in a temporary directory to simulate a collection or custom path
        File callbackDir = tempDir.resolve("ansible/plugins/callback").toFile();
        callbackDir.mkdirs();
        File callbackFile = new File(callbackDir, "my_custom_callback.py");

        String pythonCode =
            "from ansible.plugins.callback import CallbackBase\n" +
            "class CallbackModule(CallbackBase):\n" +
            "    def v2_playbook_on_play_start(self, play):\n" +
            "        print(f'CUSTOM_CALLBACK_PLAY_START: {play.name}')\n" +
            "    def v2_playbook_on_task_start(self, task, is_conditional):\n" +
            "        print(f'CUSTOM_CALLBACK_TASK_START: {task.name} ({task.action})')\n" +
            "    def v2_runner_on_ok(self, result):\n" +
            "        print(f'CUSTOM_CALLBACK_OK: {result._host.name} for task {result._task.name} with action {result._task.action}')\n";

        java.nio.file.Files.writeString(callbackFile.toPath(), pythonCode);

        // Capture stdout
        PrintStream originalOut = System.out;
        ByteArrayOutputStream outContent = new ByteArrayOutputStream();
        System.setOut(new PrintStream(outContent));

        try (PythonCallback callback = new PythonCallback("my_custom_callback", List.of(tempDir.toString()))) {
            Play play = new Play("test play", "localhost", List.of());
            callback.v2_playbook_on_play_start(play);

            Task task = new Task("test task name", "test_action", Map.of());
            callback.v2_playbook_on_task_start(task, false);

            TaskResult result = TaskResult.success(false, Map.of("msg", "hello"));
            callback.v2_runner_on_ok(task, "localhost", result);
        } finally {
            System.setOut(originalOut);
        }

        String output = outContent.toString();
        assertTrue(output.contains("CUSTOM_CALLBACK_PLAY_START: test play"), "Output should contain play start message. Got: " + output);
        assertTrue(output.contains("CUSTOM_CALLBACK_TASK_START: test task name (test_action)"), "Output should contain task start message. Got: " + output);
        assertTrue(output.contains("CUSTOM_CALLBACK_OK: localhost for task test task name with action test_action"), "Output should contain ok message. Got: " + output);
    }
}
