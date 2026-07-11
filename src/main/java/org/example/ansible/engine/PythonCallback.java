package org.example.ansible.engine;

import org.example.ansible.util.PythonEnv;
import org.example.ansible.util.PythonOSMock;
import org.example.ansible.util.OSHandlerFactory;
import org.example.ansible.util.PythonAnsibleModuleMock;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * PythonCallback bridges Java callback events to a Python-based Ansible callback plugin.
 */
public class PythonCallback implements Callback, AutoCloseable {

    private final String callbackName;
    private final Context context;
    private final Value bindings;
    private List<String> collectionPaths = new ArrayList<>();

    public PythonCallback(String callbackName) {
        this(callbackName, List.of());
    }

    public PythonCallback(String callbackName, List<String> collectionPaths) {
        this.callbackName = callbackName;
        this.collectionPaths = new ArrayList<>(collectionPaths);

        Context.Builder builder = Context.newBuilder("python")
                .allowAllAccess(true)
                .option("python.IsolateNativeModules", "false");

        this.context = builder.build();
        this.bindings = this.context.getBindings("python");

        initializePython();
    }

    private void initializePython() {
        try {
            PythonOSMock pythonOSMock = new PythonOSMock(OSHandlerFactory.getHandler());
            bindings.putMember("os_java", pythonOSMock);
            bindings.putMember("AnsibleModuleJava", new PythonAnsibleModuleMock.Factory(pythonOSMock));

            // Pre-load the bridge
            this.context.eval(loadResource("ansible_bridge.py"));

            // Set up environment for the launcher
            bindings.putMember("callback_name", callbackName);
            bindings.putMember("site_packages_java", PythonEnv.getSitePackagesFromEnv());
            bindings.putMember("collection_paths_java", collectionPaths);

            // Load the launcher
            this.context.eval(loadResource("ansible_callback_launcher.py"));
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize Python callback: " + callbackName, e);
        }
    }

    private Source loadResource(String name) throws java.io.IOException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(name)) {
            if (is == null) {
                File file = new File("src/main/python", name);
                if (file.exists()) {
                    return Source.newBuilder("python", file).build();
                }
                throw new java.io.IOException("Resource not found: " + name);
            }
            String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            return Source.newBuilder("python", content, name).build();
        }
    }

    @Override
    public synchronized void v2_playbook_on_start(Playbook playbook) {
        callPython("v2_playbook_on_start", playbook);
    }

    @Override
    public synchronized void v2_playbook_on_play_start(Play play) {
        callPython("v2_playbook_on_play_start", play);
    }

    @Override
    public synchronized void v2_playbook_on_task_start(Task task, boolean isConditional) {
        callPython("v2_playbook_on_task_start", task, isConditional);
    }

    @Override
    public synchronized void v2_runner_on_ok(String host, TaskResult result) {
        callPython("v2_runner_on_ok", host, result);
    }

    @Override
    public synchronized void v2_runner_on_failed(String host, TaskResult result, boolean ignoreErrors) {
        callPython("v2_runner_on_failed", host, result, ignoreErrors);
    }

    @Override
    public synchronized void v2_runner_on_skipped(String host, TaskResult result) {
        callPython("v2_runner_on_skipped", host, result);
    }

    @Override
    public synchronized void v2_runner_on_unreachable(String host, TaskResult result) {
        callPython("v2_runner_on_unreachable", host, result);
    }

    @Override
    public synchronized void v2_playbook_on_handler_stats(String handlerName) {
        callPython("v2_playbook_on_handler_stats", handlerName);
    }

    @Override
    public synchronized void v2_playbook_on_stats(Map<String, Map<String, Integer>> stats) {
        callPython("v2_playbook_on_stats", stats);
    }

    private void callPython(String methodName, Object... args) {
        try {
            Value method = bindings.getMember(methodName);
            if (method != null && method.canExecute()) {
                method.execute(args);
            }
        } catch (Exception e) {
            System.err.println("Error calling Python callback method " + methodName + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    public void close() {
        if (context != null) {
            context.close();
        }
    }
}
