package org.example.ansible.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

/**
 * Manages asynchronous jobs, handling background execution and persistence.
 */
public class AsyncJobManager {
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static String asyncDir = System.getProperty("user.home") + "/.ansible_async";
    private static final AsyncJobManager INSTANCE = new AsyncJobManager();

    private final ExecutorService executorService = Executors.newCachedThreadPool();
    private final Map<String, Future<?>> runningJobs = new ConcurrentHashMap<>();

    private AsyncJobManager() {
        initDir();
    }

    private void initDir() {
        try {
            Files.createDirectories(Paths.get(asyncDir));
        } catch (IOException e) {
            System.err.println("Warning: Failed to create " + asyncDir + ", async jobs might fail if directory is not writable.");
        }
    }

    public static void setAsyncDir(String dir) {
        asyncDir = dir;
        INSTANCE.initDir();
    }

    public static AsyncJobManager getInstance() {
        return INSTANCE;
    }

    /**
     * Submits a task for asynchronous execution.
     *
     * @param taskCallable    The task to execute.
     * @param timeoutSeconds  Maximum execution time in seconds.
     * @return The job ID (jid).
     */
    public String submit(Callable<TaskResult> taskCallable, int timeoutSeconds) {
        String jid = "j" + UUID.randomUUID().toString().replace("-", "");
        Path jobPath = Paths.get(asyncDir, jid);

        try {
            Files.createDirectories(jobPath.getParent());
        } catch (IOException ignored) {}

        Map<String, Object> initialData = new HashMap<>();
        initialData.put("started", Instant.now().toString());
        initialData.put("finished", 0);
        initialData.put("ansible_job_id", jid);
        initialData.put("results_file", jobPath.toAbsolutePath().toString());
        writeJobFile(jobPath, initialData);

        Future<?> future = executorService.submit(() -> {
            try {
                TaskResult result = taskCallable.call();
                Map<String, Object> finalData = new HashMap<>(initialData);
                finalData.put("finished", 1);

                finalData.putAll(result.data());
                finalData.put("changed", result.changed());
                finalData.put("failed", !result.success());
                if (result.message() != null) {
                    finalData.put("msg", result.message());
                }

                writeJobFile(jobPath, finalData);
            } catch (Exception e) {
                Map<String, Object> errorData = new HashMap<>(initialData);
                errorData.put("finished", 1);
                errorData.put("failed", true);
                errorData.put("msg", e.getMessage() != null ? e.getMessage() : e.toString());
                writeJobFile(jobPath, errorData);
            } finally {
                runningJobs.remove(jid);
            }
        });

        runningJobs.put(jid, future);

        if (timeoutSeconds > 0) {
             CompletableFuture.runAsync(() -> {
                 try {
                     future.get(timeoutSeconds, TimeUnit.SECONDS);
                 } catch (TimeoutException e) {
                     future.cancel(true);
                     Map<String, Object> timeoutData = new HashMap<>(initialData);
                     timeoutData.put("finished", 1);
                     timeoutData.put("failed", true);
                     timeoutData.put("msg", "Async task timed out");
                     timeoutData.put("async_result_timeout", true);
                     writeJobFile(jobPath, timeoutData);
                 } catch (Exception ignored) {
                 } finally {
                     runningJobs.remove(jid);
                 }
             });
        }

        return jid;
    }

    /**
     * Gets the status of an async job by its ID.
     *
     * @param jid The job ID.
     * @return A map containing job status information, or null if not found.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getJobStatus(String jid) {
        Path jobPath = Paths.get(asyncDir, jid);
        if (!Files.exists(jobPath)) {
            return null;
        }
        try {
            return objectMapper.readValue(jobPath.toFile(), Map.class);
        } catch (IOException e) {
            return null;
        }
    }

    private void writeJobFile(Path path, Map<String, Object> data) {
        try {
            objectMapper.writeValue(path.toFile(), data);
        } catch (IOException e) {
            System.err.println("Error writing async job file " + path + ": " + e.getMessage());
        }
    }
}
