package org.example.ansible.engine;

import org.example.ansible.inventory.Inventory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class FreeStrategyOutputTest {

    private final ByteArrayOutputStream outContent = new ByteArrayOutputStream();
    private final PrintStream originalOut = System.out;

    @BeforeEach
    public void setUpStreams() {
        System.setOut(new PrintStream(outContent));
    }

    @AfterEach
    public void restoreStreams() {
        System.setOut(originalOut);
    }

    @Test
    public void testFreeStrategyRedundantHeaders() {
        ITaskExecutor mockTaskExecutor = mock(ITaskExecutor.class);
        PlaybookExecutor executor = new PlaybookExecutor(mockTaskExecutor);

        Inventory inventory = new Inventory();
        inventory.addHostToGroup("host1", "all");
        inventory.addHostToGroup("host2", "all");
        inventory.addHostToGroup("host3", "all");

        Task task = new Task("test ping", "ping", Map.of());

        // Canonical constructor for Play
        Play play = new Play(
                "test play",
                "all",
                List.of(task),
                Map.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                null,
                "free", // strategy
                null,
                null,
                null
        );
        Playbook playbook = new Playbook(List.of(play));

        when(mockTaskExecutor.execute(any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(), any()))
                .thenReturn(TaskResult.success(false, Map.of("ping", "pong")));

        executor.execute(playbook, inventory);

        String output = outContent.toString();

        // Count TASK [test ping] occurrences
        int count = countOccurrences(output, "TASK [test ping]");

        // Now it should be exactly 1
        assertEquals(1, count, "Task header should be printed exactly once (count=" + count + ")");
    }

    private int countOccurrences(String text, String match) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(match, index)) != -1) {
            count++;
            index += match.length();
        }
        return count;
    }
}
