package org.example.ansible.engine.lookup;

import java.util.List;
import java.util.Map;

/**
 * Interface for Ansible-compatible lookup plugins.
 */
public interface Lookup {
    /**
     * Executes the lookup.
     *
     * @param terms     The terms passed to the lookup.
     * @param variables The current variable context.
     * @return A list of result items.
     */
    List<Object> execute(List<String> terms, Map<String, Object> variables);

    /**
     * Returns the name of the lookup plugin.
     *
     * @return The lookup plugin name.
     */
    String getName();
}
