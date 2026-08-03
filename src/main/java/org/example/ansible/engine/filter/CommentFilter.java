package org.example.ansible.engine.filter;

import com.hubspot.jinjava.interpret.JinjavaInterpreter;
import com.hubspot.jinjava.lib.filter.Filter;

/**
 * Filter that comments out a string. Supports various styles.
 */
public class CommentFilter implements Filter {
    @Override
    public Object filter(Object var, JinjavaInterpreter interpreter, String... args) {
        if (var == null) return null;
        String text = var.toString();

        String style = "plain";
        if (args != null && args.length > 0 && args[0] != null) {
            style = args[0].toLowerCase();
        }

        String[] lines = text.split("\\r?\\n", -1);
        StringBuilder sb = new StringBuilder();

        switch (style) {
            case "erlang":
                for (int i = 0; i < lines.length; i++) {
                    sb.append("% ").append(lines[i]);
                    if (i < lines.length - 1) {
                        sb.append("\n");
                    }
                }
                break;
            case "c":
            case "cblock":
                sb.append("/*\n");
                for (int i = 0; i < lines.length; i++) {
                    sb.append(" * ").append(lines[i]).append("\n");
                }
                sb.append(" */");
                break;
            case "xml":
                sb.append("<!--\n");
                for (int i = 0; i < lines.length; i++) {
                    sb.append("  ").append(lines[i]).append("\n");
                }
                sb.append("-->");
                break;
            case "plain":
            default:
                for (int i = 0; i < lines.length; i++) {
                    sb.append("# ").append(lines[i]);
                    if (i < lines.length - 1) {
                        sb.append("\n");
                    }
                }
                break;
        }

        return sb.toString();
    }

    @Override
    public String getName() {
        return "comment";
    }
}
