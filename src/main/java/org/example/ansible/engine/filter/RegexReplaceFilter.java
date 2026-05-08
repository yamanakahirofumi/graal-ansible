package org.example.ansible.engine.filter;

import com.hubspot.jinjava.interpret.JinjavaInterpreter;
import com.hubspot.jinjava.lib.filter.Filter;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

/**
 * Filter that performs regex replacement.
 */
public class RegexReplaceFilter implements Filter {
    @Override
    public Object filter(Object var, JinjavaInterpreter interpreter, String... args) {
        if (var == null) return null;
        String input = var.toString();
        if (args.length < 2) return input;
        String regex = args[0];
        String replacement = args[1];

        int flags = 0;
        if (args.length >= 3 && "true".equalsIgnoreCase(args[2])) {
            flags |= Pattern.CASE_INSENSITIVE;
        }
        if (args.length >= 4 && "true".equalsIgnoreCase(args[3])) {
            flags |= Pattern.MULTILINE;
        }

        try {
            Pattern pattern = Pattern.compile(regex, flags);
            Matcher matcher = pattern.matcher(input);
            return matcher.replaceAll(replacement);
        } catch (Exception e) {
            return input;
        }
    }

    @Override
    public String getName() {
        return "regex_replace";
    }
}
