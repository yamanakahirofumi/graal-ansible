package org.example.ansible.engine;

import org.example.ansible.connection.BecomeContext;
import org.example.ansible.connection.Connection;
import org.example.ansible.connection.ConnectionFactory;
import org.example.ansible.connection.DefaultConnectionFactory;
import org.example.ansible.connection.UnreachableException;
import org.example.ansible.inventory.Host;
import org.example.ansible.util.OSHandler;
import org.example.ansible.util.OSHandlerFactory;
import org.example.ansible.util.Truthiness;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.example.ansible.plugin.ActionPlugin;
import org.example.ansible.plugin.DebugAction;
import org.example.ansible.plugin.SetFactAction;
import org.example.ansible.plugin.CopyAction;
import org.example.ansible.plugin.TemplateAction;
import org.example.ansible.module.CommandModule;
import org.example.ansible.module.SetupModule;
import org.example.ansible.util.PythonEnv;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TaskExecutor represents a Worker Process that executes individual tasks.
 * It handles variable resolution, loops, and conditional execution.
 * It operates on the Control Node (管理ノード).
 */
public class TaskExecutor implements ITaskExecutor {

    private static final ThreadLocal<Connection> currentConnection = new ThreadLocal<>();
    private static final ThreadLocal<Map<String, String>> currentEnvironment = new ThreadLocal<>();
    private static final ThreadLocal<BecomeContext> currentBecomeContext = new ThreadLocal<>();
    private static final ThreadLocal<VariableManager> currentVariableManager = new ThreadLocal<>();

    public static void setCurrentConnection(Connection connection) {
        currentConnection.set(connection);
    }

    public static Connection getCurrentConnection() {
        return currentConnection.get();
    }

    public static void clearCurrentConnection() {
        currentConnection.remove();
    }

    public static void setCurrentEnvironment(Map<String, String> environment) {
        currentEnvironment.set(environment);
    }

    public static Map<String, String> getCurrentEnvironment() {
        return currentEnvironment.get();
    }

    public static void clearCurrentEnvironment() {
        currentEnvironment.remove();
    }

    public static void setCurrentBecomeContext(BecomeContext becomeContext) {
        currentBecomeContext.set(becomeContext);
    }

    public static BecomeContext getCurrentBecomeContext() {
        return currentBecomeContext.get();
    }

    public static void clearCurrentBecomeContext() {
        currentBecomeContext.remove();
    }

    public static void setCurrentVariableManager(VariableManager variableManager) {
        currentVariableManager.set(variableManager);
    }

    public static VariableManager getCurrentVariableManager() {
        return currentVariableManager.get();
    }

    public static void clearCurrentVariableManager() {
        currentVariableManager.remove();
    }

    private final Map<String, org.example.ansible.module.Module> modules = new HashMap<>();
    private final Map<String, ActionPlugin> builtInActionPlugins = new HashMap<>();
    private final Map<String, Boolean> actionPluginCache = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OSHandler osHandler;
    private final Context context;
    private final VariableResolver variableResolver = new VariableResolver();
    private final ConnectionFactory connectionFactory;

    public TaskExecutor() {
        this(OSHandlerFactory.getHandler());
    }

    public TaskExecutor(OSHandler osHandler) {
        this(osHandler, new DefaultConnectionFactory());
    }

    public TaskExecutor(OSHandler osHandler, ConnectionFactory connectionFactory) {
        this.osHandler = osHandler;
        this.connectionFactory = connectionFactory;
        this.builtInActionPlugins.put("debug", new DebugAction());
        this.builtInActionPlugins.put("set_fact", new SetFactAction());
        this.builtInActionPlugins.put("copy", new CopyAction());
        this.builtInActionPlugins.put("template", new TemplateAction());

        this.modules.put("command", new CommandModule("command"));
        this.modules.put("shell", new CommandModule("shell"));
        this.modules.put("setup", new SetupModule());

        Context.Builder builder = Context.newBuilder("python")
                .allowAllAccess(true);

        if ("Linux".equals(osHandler.getOSFamily())) {
            builder.option("python.IsolateNativeModules", "true");
            builder.option("python.PosixModuleBackend", "native");
        }

        this.context = builder.build();
    }

    @Override
    public OSHandler getOsHandler() {
        return osHandler;
    }

    @Override
    public VariableResolver getVariableResolver() {
        return variableResolver;
    }

    @Override
    public VariableManager getVariableManager() {
        return getCurrentVariableManager();
    }

    @Override
    public String resolveLocalPath(String path) {
        if (path == null) return null;
        File file = new File(path);
        if (file.isAbsolute()) {
            return path;
        }
        VariableManager vm = getVariableManager();
        if (vm != null && vm.getBaseDir() != null) {
            return vm.getBaseDir().resolve(path).toAbsolutePath().toString();
        }
        return file.getAbsolutePath();
    }

    public void registerModule(String action, org.example.ansible.module.Module module) {
        modules.put(action, module);
    }

    @Override
    public TaskResult execute(Play play, Host host, Task task, VariableManager variableManager, boolean inheritedCheckMode, Object inheritedEnvironment, Connection connection, ConnectionFactory connectionFactory) {
        Map<String, Object> allVars = variableManager.getAllVariables(play, host, task);

        if (task.loop() != null) {
            return executeLoopTask(play, host, task, variableManager, allVars, inheritedCheckMode, inheritedEnvironment, connection, connectionFactory);
        } else {
            TaskResult result = executeSingleTask(play, host, task, allVars, variableManager, inheritedCheckMode, inheritedEnvironment, connection, connectionFactory);
            if (result != null) {
                return evaluateResultCustomization(task, result, allVars);
            }
            return result;
        }
    }

    private TaskResult executeSingleTask(Play play, Host host, Task task, Map<String, Object> variables, VariableManager variableManager, boolean inheritedCheckMode, Object inheritedEnvironment, Connection connection, ConnectionFactory connectionFactory) {
        if (!variableResolver.isWhenConditionMet(task.when(), variables)) {
            return TaskResult.skipped("Skipped due to when condition");
        }

        Map<String, Object> resolvedArgs = new HashMap<>(variableResolver.resolve(task.args(), variables));
        boolean effectiveCheckMode = variableResolver.resolveCheckMode(task.checkMode(), variables, inheritedCheckMode);

        if (effectiveCheckMode) {
            resolvedArgs.put("_ansible_check_mode", true);
        }

        String resolvedDelegateTo = null;
        Connection effectiveConnection = connection;
        boolean closeDelegatedConnection = false;

        if (task.delegateTo() != null) {
            Object resolved = variableResolver.resolveValue(variableResolver.wrapInJinja(task.delegateTo()), variables);
            resolvedDelegateTo = resolved != null ? resolved.toString() : null;
            if (resolvedDelegateTo != null) {
                // We resolve variables for the delegated host if possible
                Map<String, Object> delegatedVars = variableManager != null ? variableManager.getVariablesForHost(resolvedDelegateTo) : variables;
                Host delegatedHost = new Host(resolvedDelegateTo);
                ConnectionFactory factoryToUse = connectionFactory != null ? connectionFactory : this.connectionFactory;
                effectiveConnection = factoryToUse.createConnection(delegatedHost, delegatedVars);
                try {
                    effectiveConnection.connect();
                } catch (UnreachableException e) {
                    throw e;
                } catch (Exception e) {
                    throw new UnreachableException("Failed to connect to delegated host " + resolvedDelegateTo + ": " + e.getMessage(), e);
                }
                closeDelegatedConnection = true;
            }
        }

        Task resolvedTask = new Task(task.name(), task.action(), resolvedArgs, task.vars(), task.when(), task.register(), task.loop(), task.notifications(), task.failedWhen(), task.changedWhen(), task.ignoreErrors(),
                task.until(), task.retries(), task.delay(), resolvedDelegateTo, task.delegateFacts(), task.runOnce(), task.ignoreUnreachable(), task.block(), task.rescue(), task.always(),
                task.become(), task.becomeMethod(), task.becomeUser(), task.becomeFlags(), task.checkMode(), task.environment());

        try {
            if ("meta".equals(resolvedTask.action())) {
                TaskResult metaResult = TaskResult.success(false, Map.of("meta", resolvedTask.args().getOrDefault("_raw_params", ""), "changed", false));
                if (resolvedDelegateTo != null) {
                    Map<String, Object> dataWithDelegate = new HashMap<>(metaResult.data());
                    dataWithDelegate.put("_ansible_delegated_host", resolvedDelegateTo);
                    metaResult = new TaskResult(metaResult.success(), metaResult.changed(), metaResult.message(), dataWithDelegate);
                }
                return metaResult;
            }

            BecomeContext becomeContext = variableResolver.resolveBecomeContext(play, resolvedTask, variables);
            Map<String, String> resolvedEnvironment = variableResolver.resolveEnvironment(play, task, variables, inheritedEnvironment);

            setCurrentVariableManager(variableManager);
            try {
                // Action Plugin detection
                if (isActionPlugin(resolvedTask.action())) {
                    TaskResult actionResult = executeActionPlugin(resolvedTask, becomeContext, effectiveConnection, resolvedEnvironment, variables);
                    if (resolvedDelegateTo != null) {
                        Map<String, Object> dataWithDelegate = new HashMap<>(actionResult.data());
                        dataWithDelegate.put("_ansible_delegated_host", resolvedDelegateTo);
                        actionResult = new TaskResult(actionResult.success(), actionResult.changed(), actionResult.message(), dataWithDelegate);
                    }
                    return actionResult;
                }
            } finally {
                clearCurrentVariableManager();
            }

            if (task.until() == null) {
                TaskResult result = execute(resolvedTask, becomeContext, effectiveConnection, resolvedEnvironment);
                if (resolvedDelegateTo != null) {
                    Map<String, Object> dataWithDelegate = new HashMap<>(result.data());
                    dataWithDelegate.put("_ansible_delegated_host", resolvedDelegateTo);
                    result = new TaskResult(result.success(), result.changed(), result.message(), dataWithDelegate);
                }
                return result;
            }

            // Retry logic
            TaskResult lastResult = null;
            for (int i = 0; i < task.retries(); i++) {
                lastResult = execute(resolvedTask, becomeContext, effectiveConnection, resolvedEnvironment);
                if (resolvedDelegateTo != null) {
                    Map<String, Object> dataWithDelegate = new HashMap<>(lastResult.data());
                    dataWithDelegate.put("_ansible_delegated_host", resolvedDelegateTo);
                    lastResult = new TaskResult(lastResult.success(), lastResult.changed(), lastResult.message(), dataWithDelegate);
                }

                if (task.register() != null && variableManager != null) {
                variableManager.registerVariable(host.name(), task.register(), lastResult.data());
                variables = variableManager.getAllVariables(play, host, task);
            }

            Map<String, Object> evalVars = new HashMap<>(variables);
            evalVars.putAll(lastResult.data());
            Object untilResult = variableResolver.resolveValue(variableResolver.wrapInJinja(task.until()), evalVars);

            if (Truthiness.isTrue(untilResult)) {
                return lastResult;
            }

                if (i < task.retries() - 1) {
                    try {
                        Thread.sleep(task.delay() * 1000L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }

            if (lastResult != null && lastResult.success()) {
                return new TaskResult(false, lastResult.changed(), "Until condition not met after " + task.retries() + " retries", lastResult.data());
            }

            return lastResult;
        } finally {
            if (closeDelegatedConnection && effectiveConnection != null) {
                try {
                    effectiveConnection.close();
                } catch (Exception e) {
                    // Ignore close errors
                }
            }
        }
    }

    private boolean isActionPlugin(String action) {
        if (action == null) return false;
        if (builtInActionPlugins.containsKey(action)) {
            return true;
        }
        if (!Boolean.parseBoolean(System.getProperty("ansible.action_plugins.enabled", "false"))) {
            return false;
        }
        return actionPluginCache.computeIfAbsent(action, a -> {
            List<String> sitePackages = PythonEnv.getSitePackagesFromEnv();
            for (String path : sitePackages) {
                File actionDir = new File(path, "ansible/plugins/action");
                if (actionDir.exists() && actionDir.isDirectory()) {
                    File actionFile = new File(actionDir, a + ".py");
                    if (actionFile.exists()) {
                        return true;
                    }
                }
            }
            return false;
        });
    }

    private TaskResult executeLoopTask(Play play, Host host, Task task, VariableManager variableManager, Map<String, Object> allVars, boolean inheritedCheckMode, Object inheritedEnvironment, Connection connection, ConnectionFactory connectionFactory) {
        List<?> items = resolveLoopItems(task.loop(), allVars);
        if (items == null) {
            return TaskResult.failure("loop must be a list or a template that resolves to a list");
        }

        List<Map<String, Object>> loopResults = new ArrayList<>();
        boolean anyFailed = false;
        boolean anyChanged = false;
        boolean allSkipped = true;

        for (Object item : items) {
            TaskResult result = executeLoopIteration(play, host, task, item, allVars, variableManager, inheritedCheckMode, inheritedEnvironment, connection, connectionFactory);

            Map<String, Object> resultData = buildIterationResultData(result, item);
            loopResults.add(resultData);

            if (!result.success() && !result.isSkipped()) anyFailed = true;
            if (result.changed()) anyChanged = true;
            if (!result.isSkipped()) allSkipped = false;
        }

        return buildFinalLoopResult(loopResults, anyFailed, anyChanged, allSkipped);
    }

    private List<?> resolveLoopItems(Object loop, Map<String, Object> variables) {
        Object resolved = loop;
        if (loop instanceof String str) {
            resolved = variableResolver.resolveValue(variableResolver.wrapInJinja(str), variables);
        }

        if (resolved instanceof List<?> items) {
            return items;
        }
        return null;
    }

    private TaskResult executeLoopIteration(Play play, Host host, Task task, Object item, Map<String, Object> allVars, VariableManager variableManager, boolean inheritedCheckMode, Object inheritedEnvironment, Connection connection, ConnectionFactory connectionFactory) {
        Map<String, Object> iterationVars = new HashMap<>(allVars);
        iterationVars.put("item", item);

        TaskResult result = executeSingleTask(play, host, task, iterationVars, variableManager, inheritedCheckMode, inheritedEnvironment, connection, connectionFactory);
        if (result != null) {
            result = evaluateResultCustomization(task, result, iterationVars);
        }
        return result;
    }

    private Map<String, Object> buildIterationResultData(TaskResult result, Object item) {
        Map<String, Object> resultData = new HashMap<>(result.data());
        resultData.put("item", item);
        resultData.put("changed", result.changed());
        resultData.put("failed", !result.success());
        if (result.isSkipped()) {
            resultData.put("skipped", true);
        }
        return resultData;
    }

    private TaskResult buildFinalLoopResult(List<Map<String, Object>> loopResults, boolean anyFailed, boolean anyChanged, boolean allSkipped) {
        Map<String, Object> finalData = new HashMap<>();
        finalData.put("results", loopResults);
        finalData.put("changed", anyChanged);
        if (allSkipped) {
            finalData.put("skipped", true);
        }

        return new TaskResult(!anyFailed, anyChanged, anyFailed ? "One or more loop items failed" : "OK", finalData);
    }

    private TaskResult evaluateResultCustomization(Task task, TaskResult result, Map<String, Object> variables) {
        if (result.isSkipped()) return result;

        boolean success = result.success();
        boolean changed = result.changed();

        Map<String, Object> evalVars = new HashMap<>(variables);
        evalVars.putAll(result.data());

        if (task.failedWhen() != null) {
            List<Object> conditions;
            if (task.failedWhen() instanceof List<?> list) {
                conditions = (List<Object>) list;
            } else {
                conditions = List.of(task.failedWhen());
            }

            for (Object condition : conditions) {
                Object conditionResult = variableResolver.resolveValue(variableResolver.wrapInJinja(condition), evalVars);
                if (Truthiness.isTrue(conditionResult)) {
                    success = false;
                    break;
                }
            }
        }

        if (task.changedWhen() != null) {
            List<Object> conditions;
            if (task.changedWhen() instanceof List<?> list) {
                conditions = (List<Object>) list;
            } else {
                conditions = List.of(task.changedWhen());
            }

            boolean allChanged = true;
            for (Object condition : conditions) {
                Object conditionResult = variableResolver.resolveValue(variableResolver.wrapInJinja(condition), evalVars);
                if (!Truthiness.isTrue(conditionResult)) {
                    allChanged = false;
                    break;
                }
            }
            changed = allChanged;
        }

        if (success == result.success() && changed == result.changed()) {
            return result;
        }

        Map<String, Object> newData = new HashMap<>(result.data());
        newData.put("changed", changed);
        return new TaskResult(success, changed, result.message(), newData);
    }

    @Override
    public TaskResult execute(Task task, BecomeContext becomeContext, Map<String, String> environment) {
        org.example.ansible.module.Module module = modules.get(task.action());
        if (module == null) {
            return TaskResult.failure("Module not found: " + task.action());
        }
        try {
            return module.execute(task.args(), becomeContext, context);
        } catch (Exception e) {
            return TaskResult.failure("Execution failed: " + e.getMessage());
        }
    }

    @Override
    public TaskResult execute(Task task, BecomeContext becomeContext, Connection connection, Map<String, String> environment) {
        setCurrentConnection(connection);
        setCurrentEnvironment(environment);
        setCurrentBecomeContext(becomeContext);
        try {
            return execute(task, becomeContext, environment);
        } finally {
            clearCurrentConnection();
            clearCurrentEnvironment();
            clearCurrentBecomeContext();
        }
    }

    protected TaskResult executeActionPlugin(Task task, BecomeContext becomeContext, Connection connection, Map<String, String> environment, Map<String, Object> taskVars) {
        ActionPlugin builtInPlugin = builtInActionPlugins.get(task.action());
        if (builtInPlugin != null) {
            setCurrentConnection(connection);
            setCurrentEnvironment(environment);
            setCurrentBecomeContext(becomeContext);
            try {
                return builtInPlugin.execute(task, taskVars, this);
            } catch (Exception e) {
                return TaskResult.failure("Built-in Action Plugin execution failed: " + e.getMessage());
            } finally {
                clearCurrentConnection();
                clearCurrentEnvironment();
                clearCurrentBecomeContext();
            }
        }

        setCurrentConnection(connection);
        setCurrentEnvironment(environment);
        setCurrentBecomeContext(becomeContext);
        try {
            List<String> sitePackages = PythonEnv.getSitePackagesFromEnv();

            context.getBindings("python").putMember("task_executor_java", this);
            context.getBindings("python").putMember("connection_java", connection);
            context.getBindings("python").putMember("become_context_java", becomeContext);
            context.getBindings("python").putMember("environment_java", environment);
            context.getBindings("python").putMember("task_vars_java", taskVars);
            context.getBindings("python").putMember("action_name", task.action());
            context.getBindings("python").putMember("module_args_java", task.args());
            context.getBindings("python").putMember("site_packages_java", sitePackages);

            context.eval(loadResource("ansible_bridge.py"));
            context.eval(loadResource("ansible_action_launcher.py"));

            org.graalvm.polyglot.Value pythonResult = context.getBindings("python").getMember("result");

            if (pythonResult == null || !pythonResult.isString()) {
                return TaskResult.failure("Action Plugin produced no valid output");
            }

            String output = pythonResult.asString();
            @SuppressWarnings("unchecked")
            Map<String, Object> resultMap = objectMapper.readValue(output, Map.class);
            return TaskResult.success(resultMap);

        } catch (Throwable t) {
            return TaskResult.failure("Action Plugin execution failed: " + t.getMessage());
        } finally {
            clearCurrentConnection();
            clearCurrentEnvironment();
            clearCurrentBecomeContext();
        }
    }

    @Override
    public Map<String, Object> execute_from_python(String moduleName, Map<String, Object> moduleArgs, Map<String, Object> taskVars) {
        // Create a temporary task for the module execution
        Task subTask = new Task(
                "execute_from_python",
                moduleName,
                moduleArgs,
                null, null, null, null, null, null, null, false,
                null, 0, 0, null, false, false, false, null, null, null,
                null, null, null, null, null, null
        );

        // Execute as a normal module, using the current connection and environment
        TaskResult result = execute(subTask, getCurrentBecomeContext(), getCurrentConnection(), getCurrentEnvironment());
        return result.data();
    }

    private Source loadResource(String name) throws java.io.IOException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(name)) {
            if (is == null) {
                // Try to find in filesystem if not in classpath (for development)
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
    public void close() {
        if (context != null) {
            context.close();
        }
    }
}
