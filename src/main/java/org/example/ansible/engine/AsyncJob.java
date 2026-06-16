package org.example.ansible.engine;

import java.util.Map;

/**
 * Represents the state of an asynchronous job.
 */
public record AsyncJob(
        String started,
        int finished,
        String ansibleJobId,
        String resultsFile,
        Map<String, Object> result
) {}
