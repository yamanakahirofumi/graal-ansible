package org.example.ansible.module;

import org.example.ansible.connection.BecomeContext;
import org.example.ansible.engine.TaskResult;
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
    TaskResult execute(Map<String, Object> args, BecomeContext becomeContext);
}
