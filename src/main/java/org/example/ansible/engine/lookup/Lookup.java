package org.example.ansible.engine.lookup;

import java.util.List;
import java.util.Map;

/**
 * Interface for lookup plugins.
 */
public interface Lookup {
    /**
     * Executes the lookup.
     *
     * @param terms     The terms to look up (e.g., file names, variable names).
     * @param variables The current variable context.
     * @param kwargs    Optional keyword arguments for the lookup.
     * @return A list of resolved values.
     */
    List<Object> run(List<String> terms, Map<String, Object> variables, Map<String, Object> kwargs);

    /**
     * Gets the name of the lookup plugin.
     *
     * @return The plugin name.
     */
    String getName();
}
