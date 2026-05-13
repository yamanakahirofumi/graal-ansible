package org.example.ansible.engine;

import com.hubspot.jinjava.Jinjava;
import com.hubspot.jinjava.interpret.JinjavaInterpreter;
import com.hubspot.jinjava.lib.fn.ELFunctionDefinition;
import org.example.ansible.connection.BecomeContext;
import org.example.ansible.engine.filter.B64DecodeFilter;
import org.example.ansible.engine.filter.B64EncodeFilter;
import org.example.ansible.engine.filter.BasenameFilter;
import org.example.ansible.engine.filter.BoolFilter;
import org.example.ansible.engine.filter.CombineFilter;
import org.example.ansible.engine.filter.DefaultFilter;
import org.example.ansible.engine.filter.Dict2ItemsFilter;
import org.example.ansible.engine.filter.DirnameFilter;
import org.example.ansible.engine.filter.FlattenFilter;
import org.example.ansible.engine.filter.IpAddrFilter;
import org.example.ansible.engine.filter.Items2DictFilter;
import org.example.ansible.engine.filter.MandatoryFilter;
import org.example.ansible.engine.filter.QuoteFilter;
import org.example.ansible.engine.filter.RealpathFilter;
import org.example.ansible.engine.filter.RegexReplaceFilter;
import org.example.ansible.engine.filter.SplitextFilter;
import org.example.ansible.engine.filter.TernaryFilter;
import org.example.ansible.engine.filter.ToJsonFilter;
import org.example.ansible.engine.filter.ToYamlFilter;
import org.example.ansible.engine.filter.UniqueFilter;
import org.example.ansible.engine.lookup.DictLookup;
import org.example.ansible.engine.lookup.EnvLookup;
import org.example.ansible.engine.lookup.FileLookup;
import org.example.ansible.engine.lookup.Lookup;
import org.example.ansible.engine.lookup.PipeLookup;
import org.example.ansible.engine.lookup.TemplateLookup;
import org.example.ansible.util.Truthiness;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Variable resolver using Jinjava for Jinja2 compatibility.
 * It operates on the Control Node (管理ノード).
 */
public class VariableResolver {
    private final Jinjava jinjava;
    private final Map<String, Lookup> lookupPlugins = new HashMap<>();

    public VariableResolver() {
        this.jinjava = new Jinjava();
        registerFilters();
        registerLookups();
    }

    private void registerLookups() {
        registerLookup(new FileLookup());
        registerLookup(new EnvLookup());
        registerLookup(new TemplateLookup(jinjava));
        registerLookup(new PipeLookup());
        registerLookup(new DictLookup());

        try {
            jinjava.getGlobalContext().registerFunction(new ELFunctionDefinition("", "lookup",
                    VariableResolver.class.getDeclaredMethod("lookup", String.class, Object[].class)));
            jinjava.getGlobalContext().registerFunction(new ELFunctionDefinition("", "query",
                    VariableResolver.class.getDeclaredMethod("query", String.class, Object[].class)));
        } catch (NoSuchMethodException e) {
            throw new RuntimeException("Failed to register lookup functions", e);
        }
    }

    private void registerLookup(Lookup lookup) {
        lookupPlugins.put(lookup.getName(), lookup);
    }

    /**
     * Entry point for lookup() function in Jinja2.
     */
    public static Object lookup(String name, Object... args) {
        List<Object> results = query(name, args);
        return results.stream()
                .map(Object::toString)
                .collect(Collectors.joining(","));
    }

    /**
     * Entry point for query() function in Jinja2.
     */
    @SuppressWarnings("unchecked")
    public static List<Object> query(String name, Object... args) {
        JinjavaInterpreter interpreter = JinjavaInterpreter.getCurrent();
        Map<String, Object> variables = interpreter.getContext();

        // The 'this' instance is not directly available in static methods called from Jinjava.
        // We need a way to get the lookup plugins. For now, we can use a thread-local or
        // re-instantiate, but re-instantiating is bad.
        // Better: register lookup plugins as a global object in Jinjava context.
        VariableResolver resolver = (VariableResolver) variables.get("__ansible_resolver");
        if (resolver == null) {
            return List.of();
        }

        Lookup plugin = resolver.lookupPlugins.get(name);
        if (plugin == null) {
            throw new RuntimeException("Lookup plugin not found: " + name);
        }

        List<String> terms = new ArrayList<>();
        Map<String, Object> kwargs = new HashMap<>();

        for (Object arg : args) {
            if (arg instanceof Map) {
                kwargs.putAll((Map<String, Object>) arg);
            } else if (arg != null) {
                terms.add(arg.toString());
            }
        }

        return plugin.run(terms, variables, kwargs);
    }

    private void registerFilters() {
        jinjava.getGlobalContext().registerFilter(new DefaultFilter());
        jinjava.getGlobalContext().registerFilter(new IpAddrFilter());
        jinjava.getGlobalContext().registerFilter(new Dict2ItemsFilter());
        jinjava.getGlobalContext().registerFilter(new BoolFilter());
        jinjava.getGlobalContext().registerFilter(new ToJsonFilter());
        jinjava.getGlobalContext().registerFilter(new ToYamlFilter());
        jinjava.getGlobalContext().registerFilter(new CombineFilter());
        jinjava.getGlobalContext().registerFilter(new RegexReplaceFilter());
        jinjava.getGlobalContext().registerFilter(new QuoteFilter());
        jinjava.getGlobalContext().registerFilter(new B64EncodeFilter());
        jinjava.getGlobalContext().registerFilter(new B64DecodeFilter());
        jinjava.getGlobalContext().registerFilter(new MandatoryFilter());
        jinjava.getGlobalContext().registerFilter(new BasenameFilter());
        jinjava.getGlobalContext().registerFilter(new DirnameFilter());
        jinjava.getGlobalContext().registerFilter(new SplitextFilter());
        jinjava.getGlobalContext().registerFilter(new RealpathFilter());
        jinjava.getGlobalContext().registerFilter(new TernaryFilter());
        jinjava.getGlobalContext().registerFilter(new FlattenFilter());
        jinjava.getGlobalContext().registerFilter(new Items2DictFilter());
        jinjava.getGlobalContext().registerFilter(new UniqueFilter());
    }

    /**
     * Resolves variables in a map of arguments.
     *
     * @param args      The arguments to resolve.
     * @param variables The variable map to use for resolution.
     * @return A new map with resolved values.
     */
    public Map<String, Object> resolve(Map<String, Object> args, Map<String, Object> variables) {
        if (args == null) return null;
        Map<String, Object> resolved = new HashMap<>();
        for (Map.Entry<String, Object> entry : args.entrySet()) {
            resolved.put(entry.getKey(), resolveValue(entry.getValue(), variables));
        }
        return java.util.Collections.unmodifiableMap(resolved);
    }

    /**
     * Resolves variables in a single value.
     *
     * @param value     The value to resolve.
     * @param variables The variable map.
     * @return The resolved value.
     */
    @SuppressWarnings("unchecked")
    public Object resolveValue(Object value, Map<String, Object> variables) {
        if (value instanceof String str) {
            return resolveString(str, variables);
        } else if (value instanceof Map<?, ?> map) {
            return resolve((Map<String, Object>) map, variables);
        } else if (value instanceof List<?> list) {
            return list.stream()
                    .map(item -> resolveValue(item, variables))
                    .collect(Collectors.toList());
        }
        return value;
    }

    private Object resolveString(String input, Map<String, Object> variables) {
        if (input == null) return null;
        if (!input.contains("{{") && !input.contains("{%") && !input.contains("{#")) {
            return input;
        }

        Map<String, Object> context = new HashMap<>(variables);
        context.put("__ansible_resolver", this);

        // We need to use an interpreter that knows about our context
        JinjavaInterpreter interpreter = new JinjavaInterpreter(jinjava, jinjava.getGlobalContext(), jinjava.getGlobalConfig());
        JinjavaInterpreter.pushCurrent(interpreter);
        try {
            interpreter.getContext().putAll(context);

        // If the entire string is just a single {{ expr }}, we try to return the raw object
        String trimmed = input.trim();
        if (trimmed.startsWith("{{") && trimmed.endsWith("}}")) {
            String expr = trimmed.substring(2, trimmed.length() - 2).trim();
            if (!expr.contains("{{")) { // Not nested
                try {
                    // Use a temporary variable to capture the result of the expression evaluation
                    // This handles filters and complex expressions correctly
                    String tempVarName = "__ansible_temp_var_" + System.nanoTime();
                    String renderScript = "{% set " + tempVarName + " = " + expr + " %}";

                    // We must use the SAME interpreter that has the resolver context
                    interpreter.render(renderScript);
                    Object resolved = interpreter.getContext().get(tempVarName);
                    // Check if it's explicitly set to something (even null)
                    if (interpreter.getContext().containsKey(tempVarName)) {
                        return resolved;
                    }
                } catch (Exception e) {
                    // Fallback to standard rendering if resolution fails
                }
            }
        }

        Object rendered = interpreter.render(input);
        if (!interpreter.getErrors().isEmpty()) {
            // For lookups and queries, we want to throw the exception if it's from a filter or lookup
            // But for standard strings that might be invalid jinja (like in NegativeIntegrationTest),
            // we should fall back to returning the original string to maintain backward compatibility.
            com.hubspot.jinjava.interpret.TemplateError error = interpreter.getErrors().get(0);
            if (error.getException() instanceof RuntimeException re) {
                // If it's a RuntimeException from our code (e.g. MandatoryFilter), throw it
                throw re;
            }
            // Otherwise, check if it's a single expression.
            // If it's not a single expression, we fall back to returning the input string.
            String errTrimmed = input.trim();
            if (!(errTrimmed.startsWith("{{") && errTrimmed.endsWith("}}"))) {
                return input;
            }
            // If it's a single expression and it failed, we throw.
            throw new RuntimeException(error.getMessage(), error.getException());
        }

        if (rendered instanceof String s) {
            if ("true".equals(s)) return true;
            if ("false".equals(s)) return false;
        }

        return rendered;
        } finally {
            JinjavaInterpreter.popCurrent();
        }
    }

    /**
     * Evaluates a 'when' condition.
     *
     * @param when      The condition object (String or List).
     * @param variables The variable map.
     * @return true if conditions are met, false otherwise.
     */
    public boolean isWhenConditionMet(Object when, Map<String, Object> variables) {
        if (when == null) {
            return true;
        }

        List<String> conditions;
        if (when instanceof List<?> list) {
            conditions = list.stream().filter(String.class::isInstance).map(String.class::cast).toList();
        } else if (when instanceof String s) {
            conditions = List.of(s);
        } else {
            conditions = List.of(when.toString());
        }

        for (String condition : conditions) {
            Object conditionResult = resolveValue(wrapInJinja(condition), variables);
            if (!Truthiness.isTrue(conditionResult)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Resolves check mode status.
     *
     * @param checkMode      The check_mode setting.
     * @param variables      The variable map.
     * @param inheritedValue The value inherited from parent context.
     * @return Resolved check mode status.
     */
    public boolean resolveCheckMode(Object checkMode, Map<String, Object> variables, boolean inheritedValue) {
        if (checkMode == null) {
            return inheritedValue;
        }
        Object resolved = resolveValue(checkMode, variables);
        return Truthiness.isTrue(resolved);
    }

    /**
     * Resolves the become context for a task.
     *
     * @param play      The play context.
     * @param task      The task.
     * @param variables The variable map.
     * @return The resolved become context.
     */
    public BecomeContext resolveBecomeContext(Play play, Task task, Map<String, Object> variables) {
        Object becomeObj = task.become() != null ? task.become() : (play.become() != null ? play.become() : variables.get("ansible_become"));
        boolean become = Truthiness.isTrue(resolveValue(becomeObj, variables));

        Object methodObj = task.becomeMethod() != null ? task.becomeMethod() : (play.becomeMethod() != null ? play.becomeMethod() : variables.get("ansible_become_method"));
        Object resolvedMethod = resolveValue(methodObj != null ? methodObj : "sudo", variables);

        Object userObj = task.becomeUser() != null ? task.becomeUser() : (play.becomeUser() != null ? play.becomeUser() : variables.get("ansible_become_user"));
        Object resolvedUser = resolveValue(userObj != null ? userObj : "root", variables);

        Object flagsObj = task.becomeFlags() != null ? task.becomeFlags() : (play.becomeFlags() != null ? play.becomeFlags() : variables.get("ansible_become_flags"));
        Object resolvedFlags = resolveValue(flagsObj != null ? flagsObj : "", variables);

        Object passObj = variables.get("ansible_become_password");
        if (passObj == null) {
            passObj = variables.get("ansible_become_pass");
        }
        Object resolvedPass = resolveValue(passObj, variables);

        return new BecomeContext(
                become,
                resolvedMethod != null ? resolvedMethod.toString() : "sudo",
                resolvedUser != null ? resolvedUser.toString() : "root",
                resolvedFlags != null ? resolvedFlags.toString() : "",
                resolvedPass != null ? resolvedPass.toString() : null
        );
    }

    /**
     * Resolves environment variables for a task.
     *
     * @param play                  The play context.
     * @param task                  The task.
     * @param variables             The variable map.
     * @param inheritedEnvironments List of inherited environment objects.
     * @return The resolved environment map.
     */
    @SuppressWarnings("unchecked")
    public Map<String, String> resolveEnvironment(Play play, Task task, Map<String, Object> variables, List<Object> inheritedEnvironments) {
        Map<String, Object> mergedEnv = new HashMap<>();

        List<Object> envSources = new ArrayList<>();
        if (play.environment() != null) {
            envSources.add(play.environment());
        }
        if (inheritedEnvironments != null) {
            envSources.addAll(inheritedEnvironments);
        }
        if (task.environment() != null) {
            envSources.add(task.environment());
        }

        for (Object source : envSources) {
            if (source == null) continue;
            Object resolved = resolveValue(source, variables);
            if (resolved instanceof Map<?, ?> map) {
                mergedEnv.putAll((Map<String, Object>) map);
            }
        }

        Map<String, String> result = new HashMap<>();
        for (Map.Entry<String, Object> entry : mergedEnv.entrySet()) {
            Object value = resolveValue(entry.getValue(), variables);
            result.put(entry.getKey(), value != null ? value.toString() : "");
        }
        return result;
    }

    /**
     * Resolves loop items from a loop object (List or template string).
     *
     * @param loop      The loop object to resolve.
     * @param variables The variable map.
     * @return A list of resolved loop items, or null if resolution fails.
     */
    public List<?> resolveLoopItems(Object loop, Map<String, Object> variables) {
        Object resolved = loop;
        if (loop instanceof String str) {
            resolved = resolveValue(wrapInJinja(str), variables);
        }

        if (resolved instanceof List<?> items) {
            return items;
        }
        return null;
    }

    /**
     * Wraps an expression in Jinja2 delimiters if not already wrapped.
     *
     * @param expression The expression to wrap.
     * @return The wrapped expression.
     */
    public String wrapInJinja(Object expression) {
        if (expression instanceof String s) {
            if (s.contains("{{")) return s;
            return "{{ " + s + " }}";
        }
        return expression != null ? expression.toString() : "";
    }
}
