package org.example.ansible.module;

import org.example.ansible.connection.BecomeContext;
import org.example.ansible.engine.TaskResult;
import org.graalvm.polyglot.Context;

import java.util.Map;

/**
 * Interface for all Ansible modules.
 */
@FunctionalInterface
public interface Module {
    /**
     * Executes the module with the given arguments and Polyglot context.
     *
     * @param args          The module arguments.
     * @param becomeContext The privilege escalation context.
     * @param context       The GraalVM Polyglot context for execution.
     * @return The result of the execution.
     */
    TaskResult execute(Map<String, Object> args, BecomeContext becomeContext, Context context);
}
