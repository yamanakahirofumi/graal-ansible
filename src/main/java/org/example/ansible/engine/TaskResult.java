package org.example.ansible.engine;

import java.util.Map;

/**
 * Represents the result of a task execution.
 *
 * @param success Whether the task was successful.
 * @param changed Whether the task changed the system state.
 * @param message An optional message or error description.
 * @param data    Additional data returned by the module.
 */
public record TaskResult(boolean success, boolean changed, String message, Map<String, Object> data) {

    /**
     * Creates a successful task result.
     *
     * @param changed Whether the system state was changed.
     * @param data    The result data.
     * @return A successful TaskResult.
     */
    public static TaskResult success(boolean changed, Map<String, Object> data) {
        return new TaskResult(true, changed, "OK", data);
    }

    /**
     * Creates a failed task result.
     *
     * @param message The error message.
     * @return A failed TaskResult.
     */
    public static TaskResult failure(String message) {
        return new TaskResult(false, false, message, Map.of());
    }

    /**
     * Creates a failed task result with data.
     *
     * @param message The error message.
     * @param data    The result data.
     * @return A failed TaskResult.
     */
    public static TaskResult failure(String message, Map<String, Object> data) {
        return new TaskResult(false, false, message, data);
    }

    /**
     * Creates a successful task result from a data map, extracting the "changed" flag.
     *
     * @param data The result data map.
     * @return A successful TaskResult.
     */
    public static TaskResult success(final Map<String, Object> data) {
        final boolean changed = Boolean.TRUE.equals(data.get("changed"));
        return new TaskResult(true, changed, "OK", data);
    }
}
