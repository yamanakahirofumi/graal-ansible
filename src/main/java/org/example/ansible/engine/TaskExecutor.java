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

import org.example.ansible.module.python.PythonModule;
import org.example.ansible.util.PythonAnsibleModuleMock;
import org.example.ansible.util.PythonEnv;
import org.example.ansible.util.PythonOSMock;

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
    private static final ThreadLocal<List<String>> currentCollectionPaths = new ThreadLocal<>();

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

    public static void setCurrentCollectionPaths(List<String> collectionPaths) {
        currentCollectionPaths.set(collectionPaths);
    }

    public static List<String> getCurrentCollectionPaths() {
        return currentCollectionPaths.get();
    }

    public static void clearCurrentCollectionPaths() {
        currentCollectionPaths.remove();
    }

    private static final List<String> WELL_KNOWN_ACTION_PLUGINS = List.of(
            "debug", "set_fact", "copy", "template", "assemble", "group_by",
            "include_vars", "fetch", "pause", "wait_for_connection", "gather_facts",
            "unarchive", "uri", "script", "reboot", "async_status", "add_host", "assert",
            "command", "shell", "meta"
    );
    private final Map<String, org.example.ansible.module.Module> modules = new HashMap<>();
    private final Map<String, Boolean> actionPluginCache = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OSHandler osHandler;
    private final Context context;
    private final VariableResolver variableResolver = new VariableResolver();
    private final ConnectionFactory connectionFactory;
    private final PythonOSMock pythonOSMock;
    private final AsyncJobManager asyncJobManager = new DefaultAsyncJobManager();
    private List<String> collectionPaths = new ArrayList<>();

    public TaskExecutor() {
        this(OSHandlerFactory.getHandler());
    }

    public TaskExecutor(OSHandler osHandler) {
        this(osHandler, new DefaultConnectionFactory());
    }

    public TaskExecutor(OSHandler osHandler, ConnectionFactory connectionFactory) {
        this.osHandler = osHandler;
        this.connectionFactory = connectionFactory;
        this.pythonOSMock = new PythonOSMock(osHandler);

        registerModule("async_status", new AsyncStatusModule(asyncJobManager));

        Context.Builder builder = Context.newBuilder("python")
                .allowAllAccess(true);

        // Using IsolateNativeModules=true for stability on Linux as specified in GraalPy-Integration.md
        builder.option("python.IsolateNativeModules", "true");
        builder.option("python.PosixModuleBackend", "native");

        this.context = builder.build();

        // Pre-load the bridge
        try {
            this.context.getBindings("python").putMember("os_java", this.pythonOSMock);
            this.context.getBindings("python").putMember("AnsibleModuleJava", new PythonAnsibleModuleMock.Factory(this.pythonOSMock));
            this.context.eval(loadResource("ansible_bridge.py"));
        } catch (Exception e) {
            throw new RuntimeException("Failed to pre-load ansible_bridge.py", e);
        }
    }

    @Override
    public void setCollectionPaths(List<String> collectionPaths) {
        this.collectionPaths = collectionPaths != null ? collectionPaths : new ArrayList<>();
    }

    @Override
    public List<String> getCollectionPaths() {
        return collectionPaths;
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
    public TaskResult execute(Play play, Host host, Task task, VariableManager variableManager, boolean inheritedCheckMode, List<Object> inheritedEnvironments, Map<String, Object> blockVars, Connection connection, ConnectionFactory connectionFactory) {
        return execute(play, host, task, variableManager, inheritedCheckMode, inheritedEnvironments, blockVars, null, null, connection, connectionFactory);
    }

    @Override
    public TaskResult execute(Play play, Host host, Task task, VariableManager variableManager, boolean inheritedCheckMode, List<Object> inheritedEnvironments, Map<String, Object> blockVars, List<Role> activeRoles, Map<String, Object> includeParams, Connection connection, ConnectionFactory connectionFactory) {
        Map<String, Object> allVars = variableManager.getAllVariables(play, host, task, blockVars, activeRoles, includeParams);

        if (task.loop() != null) {
            return executeLoopTask(play, host, task, variableManager, allVars, inheritedCheckMode, inheritedEnvironments, blockVars, activeRoles, includeParams, connection, connectionFactory);
        } else {
            return executeTaskWithRetry(play, host, task, allVars, variableManager, inheritedCheckMode, inheritedEnvironments, blockVars, activeRoles, includeParams, connection, connectionFactory);
        }
    }

    private TaskResult executeTaskWithRetry(Play play, Host host, Task task, Map<String, Object> variables, VariableManager variableManager, boolean inheritedCheckMode, List<Object> inheritedEnvironments, Map<String, Object> blockVars, List<Role> activeRoles, Map<String, Object> includeParams, Connection connection, ConnectionFactory connectionFactory) {
        if (task.until() == null) {
            TaskResult result = executeSingleTask(play, host, task, variables, variableManager, inheritedCheckMode, inheritedEnvironments, blockVars, activeRoles, includeParams, connection, connectionFactory);
            if (result != null && !result.isSkipped()) {
                result = evaluateResultCustomization(task, result, variables);
            }
            return result;
        }

        // Retry logic
        TaskResult lastResult = null;
        int attempts = 0;
        List<Map<String, Object>> results = new ArrayList<>();
        Map<String, Object> currentVars = new HashMap<>(variables);

        for (int i = 0; i < task.retries(); i++) {
            attempts++;
            lastResult = executeSingleTask(play, host, task, currentVars, variableManager, inheritedCheckMode, inheritedEnvironments, blockVars, activeRoles, includeParams, connection, connectionFactory);
            if (lastResult.isSkipped()) return lastResult;

            // Apply failed_when / changed_when to each attempt
            lastResult = evaluateResultCustomization(task, lastResult, currentVars);

            Map<String, Object> resultData = new HashMap<>(lastResult.data());
            resultData.put("attempts", attempts);
            resultData.put("failed", !lastResult.success());
            resultData.put("changed", lastResult.changed());
            results.add(resultData);

            if (task.register() != null && variableManager != null) {
                // Update variable manager so it's available for other hosts (if needed) and current host
                variableManager.registerVariable(host.name(), task.register(), resultData);
                // Update local currentVars for next iteration's until check
                currentVars.put(task.register(), resultData);
            }

            Map<String, Object> evalVars = new HashMap<>(currentVars);
            evalVars.putAll(lastResult.data());
            Object untilResult = variableResolver.resolveValue(variableResolver.wrapInJinja(task.until()), evalVars);

            if (Truthiness.isTrue(untilResult)) {
                break;
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

        boolean anyChanged = results.stream().anyMatch(r -> Boolean.TRUE.equals(r.get("changed")));

        // Final evaluation of until condition
        Map<String, Object> finalEvalVars = new HashMap<>(currentVars);
        finalEvalVars.putAll(lastResult.data());
        Object finalUntilResult = variableResolver.resolveValue(variableResolver.wrapInJinja(task.until()), finalEvalVars);

        boolean metUntil = Truthiness.isTrue(finalUntilResult);
        boolean success = lastResult.success() && metUntil;
        String message = lastResult.message();

        if (!metUntil) {
            message = "Until condition not met after " + task.retries() + " retries";
        }

        Map<String, Object> finalData = new HashMap<>(lastResult.data());
        finalData.put("attempts", attempts);
        finalData.put("results", results);
        finalData.put("failed", !success);
        finalData.put("changed", anyChanged);

        return new TaskResult(success, anyChanged, message, finalData);
    }

    private TaskResult executeSingleTask(Play play, Host host, Task task, Map<String, Object> variables, VariableManager variableManager, boolean inheritedCheckMode, List<Object> inheritedEnvironments, Map<String, Object> blockVars, List<Role> activeRoles, Map<String, Object> includeParams, Connection connection, ConnectionFactory connectionFactory) {
        if (!variableResolver.isWhenConditionMet(task.when(), variables)) {
            return TaskResult.skipped("Skipped due to when condition");
        }

        Map<String, Object> resolvedArgs = new HashMap<>(variableResolver.resolve(task.args(), variables));

        // Handle 'omit'
        resolvedArgs.entrySet().removeIf(entry -> entry.getValue() == VariableManager.OMIT);

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
                // We resolve variables for the delegated host if possible, including play context
                Map<String, Object> delegatedVars = variableManager != null ? variableManager.getVariablesForHost(resolvedDelegateTo, play) : variables;
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

        Task resolvedTask = new Task(task.name(), task.action(), resolvedArgs, task.vars(), task.when(), task.register(), task.loop(), task.loopControl(), task.notifications(), task.failedWhen(), task.changedWhen(), task.ignoreErrors(),
                task.until(), task.retries(), task.delay(), resolvedDelegateTo, task.delegateFacts(), task.runOnce(), task.ignoreUnreachable(), task.block(), task.rescue(), task.always(),
                task.become(), task.becomeMethod(), task.becomeUser(), task.becomeFlags(), task.checkMode(), task.environment(), task.tags(), task.listen(), task.anyErrorsFatal(),
                task.maxFailPercentage(), task.throttle(), task.asyncVal(), task.poll());

        if (resolvedTask.asyncVal() > 0) {
            String jid = java.util.UUID.randomUUID().toString();
            final Connection finalConnection = effectiveConnection;
            final Map<String, String> finalEnv = variableResolver.resolveEnvironment(play, task, variables, inheritedEnvironments);
            final BecomeContext finalBecome = variableResolver.resolveBecomeContext(play, resolvedTask, variables);
            final List<String> finalCollPaths = collectionPaths;

            AsyncJob job = asyncJobManager.submit(jid, resolvedTask.asyncVal(), () -> {
                setCurrentCollectionPaths(finalCollPaths);
                try {
                    return executeWithContext(resolvedTask, finalBecome, finalConnection, finalEnv);
                } finally {
                    clearCurrentCollectionPaths();
                }
            });

            if (resolvedTask.poll() > 0) {
                // Polling logic
                long end = System.currentTimeMillis() + resolvedTask.asyncVal() * 1000L;
                while (System.currentTimeMillis() < end) {
                    if (asyncJobManager.isCompleted(jid)) {
                        AsyncJob completedJob = asyncJobManager.getJob(jid);
                        return new TaskResult(
                                !Boolean.TRUE.equals(completedJob.result().get("failed")),
                                Boolean.TRUE.equals(completedJob.result().get("changed")),
                                (String) completedJob.result().get("msg"),
                                completedJob.result()
                        );
                    }
                    try {
                        Thread.sleep(resolvedTask.poll() * 1000L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                return TaskResult.failure("Async task timed out during polling");
            } else {
                // Fire and forget
                return TaskResult.success(false, Map.of(
                        "ansible_job_id", jid,
                        "started", job.started(),
                        "finished", 0,
                        "results_file", job.resultsFile()
                ));
            }
        }

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
            Map<String, String> resolvedEnvironment = variableResolver.resolveEnvironment(play, task, variables, inheritedEnvironments);

            setCurrentVariableManager(variableManager);
            try {
                // Action Plugin detection
                String actionName = resolvedTask.action();
                if (actionName != null) {
                    if (actionName.startsWith("ansible.builtin.")) {
                        actionName = actionName.substring("ansible.builtin.".length());
                    } else if (actionName.startsWith("ansible.legacy.")) {
                        actionName = actionName.substring("ansible.legacy.".length());
                    }
                }

                if (actionName != null && isActionPlugin(resolvedTask.action()) && !modules.containsKey(actionName)) {
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

            TaskResult result = execute(resolvedTask, becomeContext, effectiveConnection, resolvedEnvironment);
            if (resolvedDelegateTo != null) {
                Map<String, Object> dataWithDelegate = new HashMap<>(result.data());
                dataWithDelegate.put("_ansible_delegated_host", resolvedDelegateTo);
                result = new TaskResult(result.success(), result.changed(), result.message(), dataWithDelegate);
            }
            return result;
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
        String baseName = action;
        if (baseName.startsWith("ansible.builtin.")) {
            baseName = baseName.substring("ansible.builtin.".length());
        } else if (baseName.startsWith("ansible.legacy.")) {
            baseName = baseName.substring("ansible.legacy.".length());
        }

        final String finalBaseName = baseName;
        if (WELL_KNOWN_ACTION_PLUGINS.contains(finalBaseName)) {
            return true;
        }
        return actionPluginCache.computeIfAbsent(finalBaseName, a -> {
            List<String> sitePackages = PythonEnv.getSitePackagesFromEnv();
            for (String path : sitePackages) {
                File actionDir = new File(path, "ansible/plugins/action");
                if (actionDir.exists() && actionDir.isDirectory()) {
                    File actionFile = new File(actionDir, finalBaseName + ".py");
                    if (actionFile.exists()) {
                        return true;
                    }
                }
            }

            // Search in collection paths
            if (finalBaseName.contains(".")) {
                String[] parts = finalBaseName.split("\\.");
                if (parts.length >= 3) {
                    String namespace = parts[0];
                    String collection = parts[1];
                    String act = parts[2];
                    for (String path : collectionPaths) {
                        File actionFile = new File(path, "ansible_collections/" + namespace + "/" + collection + "/plugins/action/" + act + ".py");
                        if (actionFile.exists()) {
                            return true;
                        }
                    }
                }
            }

            return false;
        });
    }

    private TaskResult executeLoopTask(Play play, Host host, Task task, VariableManager variableManager, Map<String, Object> allVars, boolean inheritedCheckMode, List<Object> inheritedEnvironments, Map<String, Object> blockVars, List<Role> activeRoles, Map<String, Object> includeParams, Connection connection, ConnectionFactory connectionFactory) {
        List<?> items = variableResolver.resolveLoopItems(task.loop(), allVars);
        if (items == null) {
            return TaskResult.failure("loop must be a list or a template that resolves to a list");
        }

        List<Map<String, Object>> loopResults = new ArrayList<>();
        boolean anyFailed = false;
        boolean anyChanged = false;
        boolean allSkipped = true;

        String indexVar = (String) task.loopControl().get("index_var");
        Object pauseObj = task.loopControl().get("pause");
        int pauseSeconds = 0;
        if (pauseObj instanceof Number n) {
            pauseSeconds = n.intValue();
        } else if (pauseObj instanceof String s) {
            try {
                pauseSeconds = Integer.parseInt(s);
            } catch (NumberFormatException ignored) {}
        }

        int index = 0;
        for (Object item : items) {
            if (index > 0 && pauseSeconds > 0) {
                try {
                    Thread.sleep(pauseSeconds * 1000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            Map<String, Object> iterationVars = new HashMap<>(allVars);
            if (indexVar != null) {
                iterationVars.put(indexVar, index);
            }
            String loopVar = (String) task.loopControl().getOrDefault("loop_var", "item");
            iterationVars.put(loopVar, item);

            TaskResult result = executeLoopIteration(play, host, task, item, iterationVars, variableManager, inheritedCheckMode, inheritedEnvironments, blockVars, activeRoles, includeParams, connection, connectionFactory);

            Map<String, Object> resultData = buildIterationResultData(result, item, iterationVars, task.loopControl());
            loopResults.add(resultData);

            if (!result.success() && !result.isSkipped()) anyFailed = true;
            if (result.changed()) anyChanged = true;
            if (!result.isSkipped()) allSkipped = false;
            index++;
        }

        return buildFinalLoopResult(loopResults, anyFailed, anyChanged, allSkipped);
    }

    private TaskResult executeLoopIteration(Play play, Host host, Task task, Object item, Map<String, Object> iterationVars, VariableManager variableManager, boolean inheritedCheckMode, List<Object> inheritedEnvironments, Map<String, Object> blockVars, List<Role> activeRoles, Map<String, Object> includeParams, Connection connection, ConnectionFactory connectionFactory) {
        TaskResult result = executeSingleTask(play, host, task, iterationVars, variableManager, inheritedCheckMode, inheritedEnvironments, blockVars, activeRoles, includeParams, connection, connectionFactory);
        if (result != null && task.until() == null && !result.isSkipped()) {
            result = evaluateResultCustomization(task, result, iterationVars);
        }
        return result;
    }

    private Map<String, Object> buildIterationResultData(TaskResult result, Object item, Map<String, Object> iterationVars, Map<String, Object> loopControl) {
        Map<String, Object> resultData = new HashMap<>(result.data());
        resultData.put("item", item);
        resultData.put("changed", result.changed());
        resultData.put("failed", !result.success());
        if (result.isSkipped()) {
            resultData.put("skipped", true);
        }

        String loopVar = (String) loopControl.get("loop_var");
        if (loopVar != null) {
            resultData.put(loopVar, item);
        }
        String indexVar = (String) loopControl.get("index_var");
        if (indexVar != null) {
            resultData.put(indexVar, iterationVars.get(indexVar));
        }

        String label = (String) loopControl.get("label");
        if (label != null) {
            Object resolvedLabel = variableResolver.resolveValue(variableResolver.wrapInJinja(label), iterationVars);
            resultData.put("_ansible_item_label", resolvedLabel);
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
        Map<String, Object> dataForEval = new HashMap<>(result.data());
        dataForEval.put("changed", changed);
        dataForEval.put("failed", !success);
        evalVars.putAll(dataForEval);

        if (task.register() != null) {
            evalVars.put(task.register(), dataForEval);
        }

        if (task.failedWhen() != null) {
            List<Object> conditions;
            if (task.failedWhen() instanceof List<?> list) {
                conditions = (List<Object>) list;
            } else {
                conditions = List.of(task.failedWhen());
            }

            boolean allFailed = true;
            for (Object condition : conditions) {
                Object conditionResult = variableResolver.resolveValue(variableResolver.wrapInJinja(condition), evalVars);
                if (!Truthiness.isTrue(conditionResult)) {
                    allFailed = false;
                    break;
                }
            }
            success = !allFailed;
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
        String actionName = task.action();
        if (actionName.startsWith("ansible.builtin.")) {
            actionName = actionName.substring("ansible.builtin.".length());
        } else if (actionName.startsWith("ansible.legacy.")) {
            actionName = actionName.substring("ansible.legacy.".length());
        }

        if ("meta".equals(actionName)) {
            return TaskResult.success(false, Map.of("meta", task.args().getOrDefault("_raw_params", ""), "changed", false));
        }

        if (isActionPlugin(task.action()) && !modules.containsKey(actionName)) {
            return executeActionPlugin(task, becomeContext, getCurrentConnection(), environment, Map.of());
        }

        org.example.ansible.module.Module module = modules.get(actionName);
        if (module == null) {
            module = new PythonModule(task.action());
        }
        try {
            return module.execute(task.args(), becomeContext, context);
        } catch (Exception e) {
            return TaskResult.failure("Execution failed: " + e.getMessage());
        }
    }

    @Override
    public TaskResult execute(Task task, BecomeContext becomeContext, Connection connection, Map<String, String> environment) {
        return executeWithContext(task, becomeContext, connection, environment);
    }

    private TaskResult executeWithContext(Task task, BecomeContext becomeContext, Connection connection, Map<String, String> environment) {
        setCurrentConnection(connection);
        setCurrentEnvironment(environment);
        setCurrentBecomeContext(becomeContext);
        setCurrentCollectionPaths(collectionPaths);
        try {
            return execute(task, becomeContext, environment);
        } finally {
            clearCurrentConnection();
            clearCurrentEnvironment();
            clearCurrentBecomeContext();
            clearCurrentCollectionPaths();
        }
    }

    protected TaskResult executeActionPlugin(Task task, BecomeContext becomeContext, Connection connection, Map<String, String> environment, Map<String, Object> taskVars) {
        String actionName = task.action();
        if (actionName.startsWith("ansible.builtin.")) {
            actionName = actionName.substring("ansible.builtin.".length());
        } else if (actionName.startsWith("ansible.legacy.")) {
            actionName = actionName.substring("ansible.legacy.".length());
        }

        setCurrentConnection(connection);
        setCurrentEnvironment(environment);
        setCurrentBecomeContext(becomeContext);
        setCurrentCollectionPaths(collectionPaths);
        try {
            List<String> sitePackages = PythonEnv.getSitePackagesFromEnv();

            context.getBindings("python").putMember("task_executor_java", this);
            context.getBindings("python").putMember("connection_java", connection);
            context.getBindings("python").putMember("become_context_java", becomeContext);
            context.getBindings("python").putMember("environment_java", environment);
            context.getBindings("python").putMember("task_vars_java", taskVars);
            context.getBindings("python").putMember("action_name", actionName);
            context.getBindings("python").putMember("module_args_java", task.args());
            context.getBindings("python").putMember("site_packages_java", sitePackages);
            context.getBindings("python").putMember("collection_paths_java", collectionPaths);

            context.eval(loadResource("ansible_action_launcher.py"));

            org.graalvm.polyglot.Value pythonResult = context.getBindings("python").getMember("result");

            if (pythonResult == null || !pythonResult.isString()) {
                return TaskResult.failure("Action Plugin produced no valid output");
            }

            String output = pythonResult.asString();
            @SuppressWarnings("unchecked")
            Map<String, Object> resultMap = objectMapper.readValue(output, Map.class);

            final boolean failed = Boolean.TRUE.equals(resultMap.get("failed"));
            if (failed) {
                if (resultMap.containsKey("traceback")) {
                    System.err.println(resultMap.get("traceback"));
                }
                return new TaskResult(false, false, "Action Plugin failed: " + resultMap.get("msg"), resultMap);
            }
            return TaskResult.success(resultMap);

        } catch (Throwable t) {
            return TaskResult.failure("Action Plugin execution failed: " + t.getMessage());
        } finally {
            clearCurrentConnection();
            clearCurrentEnvironment();
            clearCurrentBecomeContext();
            clearCurrentCollectionPaths();
        }
    }

    @Override
    public Map<String, Object> execute_from_python(String moduleName, Map<String, Object> moduleArgs, Map<String, Object> taskVars) {
        try {
            // Create a temporary task for the module execution
            Task subTask = new Task(
                    "execute_from_python",
                    moduleName,
                    moduleArgs,
                    null, null, null, null, Map.of(), List.of(), null, null, false,
                    null, 0, 0, null, false, false, false, List.of(), List.of(), List.of(),
                    null, null, null, null, null, null, List.of(), List.of(), null, null, null, 0, 10
            );

            // Execute as a normal module, using the current connection and environment
            // IMPORTANT: We bypass Action Plugin check here to avoid infinite recursion
            TaskResult result = executeModuleDirectly(subTask, getCurrentBecomeContext(), getCurrentEnvironment());
            if (result == null) {
                return Map.of("failed", true, "msg", "Module execution returned null result");
            }
            Map<String, Object> data = new HashMap<>(result.data());
            data.put("failed", !result.success());
            data.put("changed", result.changed());
            if (result.message() != null && !result.message().isEmpty()) {
                data.put("msg", result.message());
            }
            return data;
        } catch (Throwable t) {
            return Map.of("failed", true, "msg", "Execution from Python failed: " + t.getMessage());
        }
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
        asyncJobManager.shutdown();
        if (context != null) {
            context.close();
        }
    }

    /**
     * Executes a module directly without checking for Action Plugins.
     * This is used internally by the Action Plugin bridge.
     */
    private TaskResult executeModuleDirectly(Task task, BecomeContext becomeContext, Map<String, String> environment) {
        String actionName = task.action();
        if (actionName.startsWith("ansible.builtin.")) {
            actionName = actionName.substring("ansible.builtin.".length());
        } else if (actionName.startsWith("ansible.legacy.")) {
            actionName = actionName.substring("ansible.legacy.".length());
        }

        org.example.ansible.module.Module module = modules.get(actionName);
        if (module == null) {
            module = new PythonModule(task.action());
        }
        try {
            return module.execute(task.args(), becomeContext, context);
        } catch (Exception e) {
            return TaskResult.failure("Execution failed: " + e.getMessage());
        }
    }
}
