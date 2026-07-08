package org.example.ansible.engine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class DefaultCallbackThreadSafeTest {

    private final PrintStream originalOut = System.out;
    private ByteArrayOutputStream baos;
    private DefaultCallback callback;

    @BeforeEach
    void setUp() {
        baos = new ByteArrayOutputStream();
        System.setOut(new PrintStream(baos));
        callback = new DefaultCallback();
    }

    @AfterEach
    void tearDown() {
        System.setOut(originalOut);
    }

    @Test
    void testTaskHeaderDeduplication() throws InterruptedException {
        Task task = new Task("test task", "ping", Map.of());
        int numThreads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch latch = new CountDownLatch(numThreads);

        for (int i = 0; i < numThreads; i++) {
            executor.submit(() -> {
                try {
                    callback.v2_playbook_on_task_start(task, false);
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        String output = baos.toString();
        // Count how many times "TASK [test task]" appears
        int count = countOccurrences(output, "TASK [test task]");

        assertEquals(1, count, "Task header should be printed only once even when called from multiple threads");
    }

    @Test
    void testHandlerHeaderDeduplication() throws InterruptedException {
        String handlerName = "test handler";
        int numThreads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch latch = new CountDownLatch(numThreads);

        for (int i = 0; i < numThreads; i++) {
            executor.submit(() -> {
                try {
                    callback.v2_playbook_on_handler_stats(handlerName);
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        String output = baos.toString();
        int count = countOccurrences(output, "RUNNING HANDLER [test handler]");

        assertEquals(1, count, "Handler header should be printed only once even when called from multiple threads");
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
