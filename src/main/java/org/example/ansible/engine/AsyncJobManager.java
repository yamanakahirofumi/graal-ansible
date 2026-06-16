package org.example.ansible.engine;

import java.util.concurrent.Callable;

/**
 * Manages asynchronous jobs.
 */
public interface AsyncJobManager {
    AsyncJob submit(String jid, int timeout, Callable<TaskResult> task);
    AsyncJob getJob(String jid);
    boolean isCompleted(String jid);
    void shutdown();
}
