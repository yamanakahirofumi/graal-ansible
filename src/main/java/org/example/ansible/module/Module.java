package org.example.ansible.module;

import org.example.ansible.connection.BecomeContext;
import org.example.ansible.engine.TaskResult;
import org.graalvm.polyglot.Context;

import java.util.Map;

/**
 * Interface for all Ansible modules implemented in Java.
 */
public interface Module {
    /**
     * Executes the module with the given arguments.
     *
     * @param args          The module arguments.
     * @param becomeContext The privilege escalation context.
     * @return The result of the execution.
     */
    default TaskResult execute(Map<String, Object> args, BecomeContext becomeContext) {
        return execute(args, becomeContext, null);
    }

    /**
     * Executes the module with the given arguments and optional GraalVM context.
     *
     * @param args          The module arguments.
     * @param becomeContext The privilege escalation context.
     * @param pythonContext The shared GraalVM context for Python execution.
     * @return The result of the execution.
     */
    TaskResult execute(Map<String, Object> args, BecomeContext becomeContext, Context pythonContext);
}
