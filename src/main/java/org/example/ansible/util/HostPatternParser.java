package org.example.ansible.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * HostPatternParser provides utility methods to handle Bracket-Aware Splitting
 * and Range Pattern Expansion for host execution pattern matching.
 */
public class HostPatternParser {

    /**
     * Splits pattern strings on ',' or ':' only when outside of brackets '[...]'.
     *
     * @param pattern The pattern string to split.
     * @return A list of split sub-patterns.
     */
    public static List<String> splitBracketAware(String pattern) {
        if (pattern == null) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inBracket = false;
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '[') {
                inBracket = true;
                current.append(c);
            } else if (c == ']') {
                inBracket = false;
                current.append(c);
            } else if ((c == ',' || c == ':') && !inBracket) {
                String s = current.toString().trim();
                if (!s.isEmpty()) {
                    result.add(s);
                }
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        String s = current.toString().trim();
        if (!s.isEmpty()) {
            result.add(s);
        }
        return result;
    }

    /**
     * Recursively parses and expands brackets `[...]` containing numerical (with optional zero-padding)
     * and alphabetical ranges.
     *
     * @param pattern The pattern string to expand.
     * @return A list of expanded pattern strings.
     */
    public static List<String> expandPattern(String pattern) {
        if (pattern == null) {
            return Collections.emptyList();
        }
        int openIdx = pattern.indexOf('[');
        if (openIdx == -1) {
            return List.of(pattern);
        }
        int closeIdx = pattern.indexOf(']', openIdx);
        if (closeIdx == -1) {
            // Unmatched bracket, treat as normal string
            return List.of(pattern);
        }

        String prefix = pattern.substring(0, openIdx);
        String inside = pattern.substring(openIdx + 1, closeIdx);
        String suffix = pattern.substring(closeIdx + 1);

        // Check if inside matches numeric range: e.g. "01:05" or "1-5"
        java.util.regex.Pattern numPattern = java.util.regex.Pattern.compile("^(\\d+)([:-])(\\d+)$");
        java.util.regex.Matcher numMatcher = numPattern.matcher(inside);
        if (numMatcher.matches()) {
            String startStr = numMatcher.group(1);
            String endStr = numMatcher.group(3);

            int startVal = Integer.parseInt(startStr);
            int endVal = Integer.parseInt(endStr);

            boolean zeroPad = startStr.length() >= 2 && startStr.startsWith("0");
            int padLength = startStr.length();

            List<String> expanded = new ArrayList<>();
            int step = (startVal <= endVal) ? 1 : -1;
            int current = startVal;
            while (true) {
                String valStr;
                if (zeroPad) {
                    valStr = String.format("%0" + padLength + "d", current);
                } else {
                    valStr = String.valueOf(current);
                }
                expanded.add(prefix + valStr + suffix);
                if (current == endVal) {
                    break;
                }
                current += step;
            }

            // Recursively expand combinations in the generated strings
            List<String> finalResult = new ArrayList<>();
            for (String exp : expanded) {
                finalResult.addAll(expandPattern(exp));
            }
            return finalResult;
        }

        // Check if inside matches alpha range: e.g. "a:e" or "A-E"
        java.util.regex.Pattern alphaPattern = java.util.regex.Pattern.compile("^([a-zA-Z])([:-])([a-zA-Z])$");
        java.util.regex.Matcher alphaMatcher = alphaPattern.matcher(inside);
        if (alphaMatcher.matches()) {
            char startChar = alphaMatcher.group(1).charAt(0);
            char endChar = alphaMatcher.group(3).charAt(0);

            List<String> expanded = new ArrayList<>();
            int step = (startChar <= endChar) ? 1 : -1;
            char current = startChar;
            while (true) {
                expanded.add(prefix + current + suffix);
                if (current == endChar) {
                    break;
                }
                current += step;
            }

            // Recursively expand combinations in the generated strings
            List<String> finalResult = new ArrayList<>();
            for (String exp : expanded) {
                finalResult.addAll(expandPattern(exp));
            }
            return finalResult;
        }

        // If it did not match any range, expand the suffix recursively
        List<String> suffixExpanded = expandPattern(suffix);
        List<String> finalResult = new ArrayList<>();
        for (String s : suffixExpanded) {
            finalResult.add(prefix + "[" + inside + "]" + s);
        }
        return finalResult;
    }
}
