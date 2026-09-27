package org.example.ansible.engine;

import org.example.ansible.util.PythonEnv;

import java.util.List;

/**
 * Factory class for creating and resolving stdout callback plugin instances.
 */
public class CallbackFactory {

    /**
     * Resolves and creates a stdout callback instance based on precedence rules:
     * 1. ANSIBLE_STDOUT_CALLBACK environment variable (or explicit parameter)
     * 2. stdout_callback in ansible.cfg ([defaults] section)
     * 3. Default fallback ("default")
     *
     * @param cliCallback Callback name passed from CLI or env (can be null)
     * @param collectionPaths Python collection search paths for Python callbacks (can be null)
     * @return Resolved Callback instance
     */
    public static Callback createStdoutCallback(String cliCallback, List<String> collectionPaths) {
        String callbackName = cliCallback;
        if (callbackName == null || callbackName.trim().isEmpty()) {
            callbackName = System.getenv("ANSIBLE_STDOUT_CALLBACK");
        }
        if (callbackName == null || callbackName.trim().isEmpty()) {
            callbackName = PythonEnv.getStdoutCallbackFromCfg();
        }
        if (callbackName == null || callbackName.trim().isEmpty()) {
            callbackName = "default";
        }

        callbackName = callbackName.trim().toLowerCase();

        switch (callbackName) {
            case "default":
                return new DefaultCallback();
            case "json":
                return new JsonCallback();
            case "minimal":
                return new MinimalCallback();
            case "yaml":
                return new YamlCallback();
            default:
                try {
                    return new PythonCallback(callbackName, collectionPaths);
                } catch (Exception e) {
                    System.err.println("Warning: Failed to load callback plugin '" + callbackName + "': " + e.getMessage());
                    System.err.println("Falling back to 'default'.");
                    return new DefaultCallback();
                }
        }
    }
}
