package org.example.ansible.engine;

import org.example.ansible.inventory.Inventory;
import org.example.ansible.inventory.Host;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

public class CallbackIntegrationTest {

    @Test
    public void testCallbackEvents() {
        ITaskExecutor mockTaskExecutor = mock(ITaskExecutor.class);
        Callback mockCallback = mock(Callback.class);
        PlaybookExecutor executor = new PlaybookExecutor(mockTaskExecutor);
        executor.clearCallbacks();
        executor.addCallback(mockCallback);

        Inventory inventory = new Inventory();
        inventory.addHostToGroup("localhost", "all");

        Task task = new Task("test task", "ping", Map.of());
        Play play = new Play("test play", "all", List.of(task));
        Playbook playbook = new Playbook(List.of(play));

        TaskResult successResult = TaskResult.success(false, Map.of("ping", "pong"));
        when(mockTaskExecutor.execute(any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(), any()))
                .thenReturn(successResult);

        executor.execute(playbook, inventory);

        // Verify events
        verify(mockCallback).v2_playbook_on_start(eq(playbook));
        verify(mockCallback).v2_playbook_on_play_start(eq(play));
        verify(mockCallback).v2_playbook_on_task_start(eq(task), anyBoolean());
        verify(mockCallback).v2_runner_on_ok(eq(task), eq("localhost"), eq(successResult));

        ArgumentCaptor<Map<String, Map<String, Integer>>> statsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(mockCallback).v2_playbook_on_stats(statsCaptor.capture());

        Map<String, Map<String, Integer>> stats = statsCaptor.getValue();
        assertTrue(stats.containsKey("localhost"));
        assertEquals(1, stats.get("localhost").get("ok"));
        assertEquals(0, stats.get("localhost").get("failed"));
    }

    @Test
    public void testFailedTaskCallback() {
        ITaskExecutor mockTaskExecutor = mock(ITaskExecutor.class);
        Callback mockCallback = mock(Callback.class);
        PlaybookExecutor executor = new PlaybookExecutor(mockTaskExecutor);
        executor.clearCallbacks();
        executor.addCallback(mockCallback);

        Inventory inventory = new Inventory();
        inventory.addHostToGroup("localhost", "all");

        Task task = new Task("fail task", "fail", Map.of());
        Play play = new Play("test play", "all", List.of(task));
        Playbook playbook = new Playbook(List.of(play));

        TaskResult failResult = TaskResult.failure("Intentional failure");
        when(mockTaskExecutor.execute(any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(), any()))
                .thenReturn(failResult);

        executor.execute(playbook, inventory);

        verify(mockCallback).v2_runner_on_failed(eq(task), eq("localhost"), eq(failResult), eq(false));

        ArgumentCaptor<Map<String, Map<String, Integer>>> statsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(mockCallback).v2_playbook_on_stats(statsCaptor.capture());

        Map<String, Map<String, Integer>> stats = statsCaptor.getValue();
        assertEquals(1, stats.get("localhost").get("failed"));
    }
}
