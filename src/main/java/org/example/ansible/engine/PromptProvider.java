package org.example.ansible.engine;

import java.util.Map;

/**
 * Provides a mechanism to prompt the user for input during playbook execution.
 */
public interface PromptProvider {
    /**
     * Prompts the user for a value.
     *
     * @param promptDef The definition of the prompt (name, prompt message, private, etc.)
     * @return The value entered by the user.
     */
    String prompt(Map<String, Object> promptDef);
}
