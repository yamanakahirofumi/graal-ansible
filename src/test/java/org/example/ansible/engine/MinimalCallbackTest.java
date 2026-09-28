package org.example.ansible.engine;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class MinimalCallbackTest {

    @Test
    public void testMinimalCallbackSuccessAndChanged() {
        MinimalCallback callback = new MinimalCallback();
        Task task = new Task("test task", "ping", Map.of());

        TaskResult okResult = TaskResult.success(false, Map.of("ping", "pong"));
        TaskResult changedResult = TaskResult.success(true, Map.of("changed", true));

        PrintStream originalOut = System.out;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        System.setOut(new PrintStream(baos));

        try {
            callback.v2_runner_on_ok(task, "web1", okResult);
            callback.v2_runner_on_ok(task, "web2", changedResult);
        } finally {
            System.setOut(originalOut);
        }

        String output = baos.toString();
        assertTrue(output.contains("web1 | SUCCESS => {\"ping\":\"pong\",\"changed\":false}"));
        assertTrue(output.contains("web2 | CHANGED => {\"changed\":true}"));
    }

    @Test
    public void testMinimalCallbackFailedSkippedUnreachable() {
        MinimalCallback callback = new MinimalCallback();
        Task task = new Task("test task", "fail", Map.of());

        TaskResult failResult = TaskResult.failure("Execution failed");
        TaskResult skipResult = TaskResult.skipped("Condition not met");
        TaskResult unreachableResult = TaskResult.unreachable("Connection timed out");

        PrintStream originalOut = System.out;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        System.setOut(new PrintStream(baos));

        try {
            callback.v2_runner_on_failed(task, "host1", failResult, false);
            callback.v2_runner_on_skipped(task, "host2", skipResult);
            callback.v2_runner_on_unreachable(task, "host3", unreachableResult);
        } finally {
            System.setOut(originalOut);
        }

        String output = baos.toString();
        assertTrue(output.contains("host1 | FAILED! => {\"changed\":false,\"failed\":true,\"msg\":\"Execution failed\"}"));
        assertTrue(output.contains("host2 | SKIPPED"));
        assertTrue(output.contains("host3 | UNREACHABLE! =>"));
        assertTrue(output.contains("Connection timed out"));
        assertTrue(output.contains("\"unreachable\":true"));
    }
}
