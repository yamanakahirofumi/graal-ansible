package org.example.ansible.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.File;
import java.io.IOException;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.*;

public class DefaultAsyncJobManager implements AsyncJobManager {

    private final ScheduledExecutorService executorService = Executors.newScheduledThreadPool(10);
    private final Map<String, AsyncJob> activeJobs = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final File asyncDir;

    public DefaultAsyncJobManager() {
        String homeDir = System.getProperty("user.home");
        this.asyncDir = new File(homeDir, ".ansible_async");
        if (!asyncDir.exists()) {
            asyncDir.mkdirs();
        }
    }

    @Override
    public AsyncJob submit(String jid, int timeout, Callable<TaskResult> task) {
        String started = ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT);
        File resultFile = new File(asyncDir, jid);
        AsyncJob initialJob = new AsyncJob(started, 0, jid, resultFile.getAbsolutePath(), Map.of());
        activeJobs.put(jid, initialJob);
        persistJob(initialJob);

        CompletableFuture<TaskResult> future = new CompletableFuture<>();

        executorService.submit(() -> {
            try {
                future.complete(task.call());
            } catch (Throwable e) {
                future.completeExceptionally(e);
            }
        });

        executorService.schedule(() -> {
            if (!future.isDone()) {
                future.complete(TaskResult.failure("Async task timed out after " + timeout + " seconds"));
            }
        }, timeout, TimeUnit.SECONDS);

        future.whenComplete((result, ex) -> {
            if (ex != null) {
                updateJob(jid, TaskResult.failure("Async task failed: " + ex.getMessage()));
            } else {
                updateJob(jid, result);
            }
        });

        return initialJob;
    }

    private void updateJob(String jid, TaskResult taskResult) {
        AsyncJob current = activeJobs.get(jid);
        if (current == null) return;

        Map<String, Object> data = new java.util.HashMap<>(taskResult.data());
        data.put("changed", taskResult.changed());
        data.put("failed", !taskResult.success());
        data.put("finished", 1);
        if (taskResult.message() != null && !taskResult.message().isEmpty()) {
            data.put("msg", taskResult.message());
        }

        AsyncJob updated = new AsyncJob(
                current.started(),
                1,
                jid,
                current.resultsFile(),
                data
        );
        activeJobs.put(jid, updated);
        persistJob(updated);
    }

    private void persistJob(AsyncJob job) {
        try {
            objectMapper.writeValue(new File(job.resultsFile()), job);
        } catch (IOException e) {
            System.err.println("Failed to persist async job " + job.ansibleJobId() + ": " + e.getMessage());
        }
    }

    @Override
    public AsyncJob getJob(String jid) {
        AsyncJob job = activeJobs.get(jid);
        if (job == null) {
            File resultFile = new File(asyncDir, jid);
            if (resultFile.exists()) {
                try {
                    job = objectMapper.readValue(resultFile, AsyncJob.class);
                    activeJobs.put(jid, job);
                } catch (IOException e) {
                    return null;
                }
            }
        }
        return job;
    }

    @Override
    public boolean isCompleted(String jid) {
        AsyncJob job = getJob(jid);
        return job != null && job.finished() == 1;
    }

    @Override
    public void shutdown() {
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
