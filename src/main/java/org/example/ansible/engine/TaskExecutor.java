package org.example.ansible.engine;

import org.example.ansible.connection.BecomeContext;
import org.example.ansible.connection.Connection;
import org.example.ansible.inventory.Host;
import org.example.ansible.util.OSHandler;
import org.example.ansible.util.OSHandlerFactory;
import org.example.ansible.util.Truthiness;
import org.graalvm.polyglot.Context;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * TaskExecutor represents a Worker Process that executes individual tasks.
 * It handles variable resolution, loops, and conditional execution.
 * It operates on the Control Node (管理ノード).
 */
public class TaskExecutor implements ITaskExecutor {

    private static final ThreadLocal<Connection> currentConnection = new ThreadLocal<>();
    private static final ThreadLocal<Map<String, String>> currentEnvironment = new ThreadLocal<>();

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

    private final Map<String, org.example.ansible.module.Module> modules = new HashMap<>();
    private final OSHandler osHandler;
    private final Context context;
    private final VariableResolver variableResolver = new VariableResolver();

    public TaskExecutor() {
        this(OSHandlerFactory.getHandler());
    }

    public TaskExecutor(OSHandler osHandler) {
        this.osHandler = osHandler;
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

    public void registerModule(String action, org.example.ansible.module.Module module) {
        modules.put(action, module);
    }

    @Override
    public TaskResult execute(Play play, Host host, Task task, VariableManager variableManager, boolean inheritedCheckMode, Object inheritedEnvironment) {
        Map<String, Object> allVars = variableManager.getAllVariables(play, host, task);

        if (task.loop() != null) {
            return executeLoopTask(play, host, task, variableManager, allVars, inheritedCheckMode, inheritedEnvironment);
        } else {
            TaskResult result = executeSingleTask(play, host, task, allVars, variableManager, inheritedCheckMode, inheritedEnvironment);
            if (result != null) {
                return evaluateResultCustomization(task, result, allVars);
            }
            return result;
        }
    }

    private TaskResult executeSingleTask(Play play, Host host, Task task, Map<String, Object> variables, VariableManager variableManager, boolean inheritedCheckMode, Object inheritedEnvironment) {
        if (!variableResolver.isWhenConditionMet(task.when(), variables)) {
            return new TaskResult(true, false, "Skipped due to when condition", Map.of("skipped", true));
        }

        Map<String, Object> resolvedArgs = new HashMap<>(variableResolver.resolve(task.args(), variables));
        boolean effectiveCheckMode = variableResolver.resolveCheckMode(task.checkMode(), variables, inheritedCheckMode);

        if (effectiveCheckMode) {
            resolvedArgs.put("_ansible_check_mode", true);
        }

        String resolvedDelegateTo = null;
        if (task.delegateTo() != null) {
            Object resolved = variableResolver.resolveValue(variableResolver.wrapInJinja(task.delegateTo()), variables);
            resolvedDelegateTo = resolved != null ? resolved.toString() : null;
        }

        Task resolvedTask = new Task(task.name(), task.action(), resolvedArgs, task.vars(), task.when(), task.register(), task.loop(), task.notifications(), task.failedWhen(), task.changedWhen(), task.ignoreErrors(),
                task.until(), task.retries(), task.delay(), resolvedDelegateTo, task.delegateFacts(), task.runOnce(), task.ignoreUnreachable(), task.block(), task.rescue(), task.always(),
                task.become(), task.becomeMethod(), task.becomeUser(), task.becomeFlags(), task.checkMode(), task.environment());

        if ("meta".equals(resolvedTask.action())) {
            return TaskResult.success(false, Map.of("meta", resolvedTask.args().getOrDefault("_raw_params", ""), "changed", false));
        }

        // Action Plugin detection (simplified skeleton)
        if (isActionPlugin(resolvedTask.action())) {
            // In the future, this would call Action Plugin launcher on Control Node
        }

        BecomeContext becomeContext = variableResolver.resolveBecomeContext(play, resolvedTask, variables);
        Map<String, String> resolvedEnvironment = variableResolver.resolveEnvironment(play, task, variables, inheritedEnvironment);

        if (task.until() == null) {
            return execute(resolvedTask, becomeContext, new org.example.ansible.connection.LocalConnection(), resolvedEnvironment);
        }

        // Retry logic
        TaskResult lastResult = null;
        for (int i = 0; i < task.retries(); i++) {
            lastResult = execute(resolvedTask, becomeContext, new org.example.ansible.connection.LocalConnection(), resolvedEnvironment);

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
    }

    private boolean isActionPlugin(String action) {
        // Simple skeleton for future Action Plugin detection
        return List.of("copy", "template", "debug").contains(action);
    }

    private TaskResult executeLoopTask(Play play, Host host, Task task, VariableManager variableManager, Map<String, Object> allVars, boolean inheritedCheckMode, Object inheritedEnvironment) {
        List<?> items = resolveLoopItems(task.loop(), allVars);
        if (items == null) {
            return TaskResult.failure("loop must be a list or a template that resolves to a list");
        }

        List<Map<String, Object>> loopResults = new ArrayList<>();
        boolean anyFailed = false;
        boolean anyChanged = false;
        boolean allSkipped = true;

        for (Object item : items) {
            TaskResult result = executeLoopIteration(play, host, task, item, allVars, variableManager, inheritedCheckMode, inheritedEnvironment);

            Map<String, Object> resultData = buildIterationResultData(result, item);
            loopResults.add(resultData);

            if (!result.success() && !isSkipped(result)) anyFailed = true;
            if (result.changed()) anyChanged = true;
            if (!isSkipped(result)) allSkipped = false;
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

    private TaskResult executeLoopIteration(Play play, Host host, Task task, Object item, Map<String, Object> allVars, VariableManager variableManager, boolean inheritedCheckMode, Object inheritedEnvironment) {
        Map<String, Object> iterationVars = new HashMap<>(allVars);
        iterationVars.put("item", item);

        TaskResult result = executeSingleTask(play, host, task, iterationVars, variableManager, inheritedCheckMode, inheritedEnvironment);
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
        if (isSkipped(result)) {
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
        if (isSkipped(result)) return result;

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
        try {
            return execute(task, becomeContext, environment);
        } finally {
            clearCurrentConnection();
            clearCurrentEnvironment();
        }
    }


    private boolean isSkipped(TaskResult result) {
        return result != null && Boolean.TRUE.equals(result.data().get("skipped"));
    }

    @Override
    public void close() {
        if (context != null) {
            context.close();
        }
    }
}
