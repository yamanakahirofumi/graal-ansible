package org.example.ansible.engine.filter;

import com.hubspot.jinjava.interpret.JinjavaInterpreter;
import com.hubspot.jinjava.lib.filter.Filter;

import java.util.regex.Pattern;

/**
 * Filter for replacing substrings using regular expressions.
 * Usage: {{ value | regex_replace('pattern', 'replacement') }}
 */
public class RegexReplaceFilter implements Filter {
    @Override
    public Object filter(Object var, JinjavaInterpreter interpreter, String... args) {
        if (var == null) return null;
        if (args.length < 1) return var;

        String pattern = args[0];
        String replacement = args.length > 1 ? args[1] : "";
        String input = var.toString();

        try {
            return input.replaceAll(pattern, replacement);
        } catch (Exception e) {
            return input;
        }
    }

    @Override
    public String getName() {
        return "regex_replace";
    }
}
