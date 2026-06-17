package org.example.ansible.engine;

/**
 * Factory for creating Strategy instances.
 */
public class StrategyFactory {
    public static Strategy getStrategy(String name) {
        if ("free".equalsIgnoreCase(name)) {
            return new FreeStrategy();
        }
        // Default to linear
        return new LinearStrategy();
    }
}
