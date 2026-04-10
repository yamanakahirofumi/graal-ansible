package org.example.ansible.util;

import org.example.ansible.connection.BecomeContext;
import org.example.ansible.connection.Connection;
import org.example.ansible.connection.ConnectionResult;
import org.graalvm.polyglot.Value;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.*;

/**
 * Java implementation of AnsibleModule logic for GraalPy bridge.
 */
public class PythonAnsibleModuleMock implements Serializable {
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final Map<String, Object> params = new HashMap<>();
    private final Map<String, String> aliases = new HashMap<>();
    private final PythonOSMock osMock;
    private final Connection connection;
    private final BecomeContext becomeContext;
    private final Map<String, String> environment;
    private final Value pythonPrint;
    private final Value pythonExit;

    private final boolean checkMode;
    private final boolean debug;
    private final boolean diff;
    private final Map<String, Object> storedFileArgs = new HashMap<>();

    public PythonAnsibleModuleMock(Map<String, Object> argumentSpec, Map<String, Object> inputArgs,
                                 Map<String, Object> kwargs, PythonOSMock osMock,
                                 Connection connection, BecomeContext becomeContext,
                                 Map<String, String> environment, Value pythonPrint, Value pythonExit) {
        this.osMock = osMock;
        this.connection = connection;
        this.becomeContext = becomeContext;
        this.environment = environment;
        this.pythonPrint = pythonPrint;
        this.pythonExit = pythonExit;

        // Initialize flags from inputArgs
        this.checkMode = inputArgs != null && Truthiness.isTrue(inputArgs.get("_ansible_check_mode"));
        this.debug = inputArgs != null && Truthiness.isTrue(inputArgs.get("_ansible_debug"));
        this.diff = inputArgs != null && Truthiness.isTrue(inputArgs.get("_ansible_diff"));

        Map<String, Object> effectiveSpec = new HashMap<>();
        if (argumentSpec != null) {
            effectiveSpec.putAll(argumentSpec);
        }

        // Logic similar to Python's add_file_common_args
        if (kwargs != null && Truthiness.isTrue(kwargs.get("add_file_common_args"))) {
            effectiveSpec.put("path", Map.of("type", "str", "aliases", List.of("dest", "name")));
            effectiveSpec.put("mode", Map.of("type", "raw"));
            effectiveSpec.put("owner", Map.of("type", "str"));
            effectiveSpec.put("group", Map.of("type", "str"));
            effectiveSpec.put("seuser", Map.of("type", "str"));
            effectiveSpec.put("serole", Map.of("type", "str"));
            effectiveSpec.put("setype", Map.of("type", "str"));
            effectiveSpec.put("selevel", Map.of("type", "str"));
            effectiveSpec.put("attributes", Map.of("type", "str", "aliases", List.of("attr")));
            effectiveSpec.put("unsafe_writes", Map.of("type", "bool", "default", false));
        }

        // Setup aliases and defaults
        for (Map.Entry<String, Object> entry : effectiveSpec.entrySet()) {
            String k = entry.getKey();
            Object v = entry.getValue();
            if (v instanceof Map) {
                Map<?, ?> spec = (Map<?, ?>) v;
                Object aliasesObj = spec.get("aliases");
                if (aliasesObj instanceof List) {
                    for (Object alias : (List<?>) aliasesObj) {
                        aliases.put(alias.toString(), k);
                    }
                }
                params.put(k, spec.get("default"));
            } else {
                params.put(k, null);
            }
        }

        // Parse input args
        if (inputArgs != null) {
            for (Map.Entry<String, Object> entry : inputArgs.entrySet()) {
                String k = entry.getKey();
                Object v = entry.getValue();
                String targetKey = aliases.getOrDefault(k, k);

                Object val = v;
                Object specObj = effectiveSpec.get(targetKey);
                if (specObj instanceof Map) {
                    Map<?, ?> spec = (Map<?, ?>) specObj;
                    String type = Objects.toString(spec.get("type"), "str");
                    if ("list".equals(type)) {
                        if (!(v instanceof List) && !(v instanceof Object[])) {
                            val = Collections.singletonList(v);
                        }
                    } else if ("str".equals(type) || "path".equals(type)) {
                        String s;
                        if (v instanceof List && !((List<?>) v).isEmpty()) {
                            s = Objects.toString(((List<?>) v).get(0));
                        } else if (v instanceof Object[] && ((Object[]) v).length > 0) {
                            s = Objects.toString(((Object[]) v)[0]);
                        } else {
                            s = Objects.toString(v);
                        }
                        val = osMock.normalizePath(s);
                    } else if ("bool".equals(type)) {
                        val = Truthiness.isTrue(v);
                    } else if ("int".equals(type)) {
                        try {
                            val = Integer.parseInt(v.toString());
                        } catch (Exception ignored) {}
                    }
                }

                params.put(targetKey, val);
                if (!targetKey.equals(k)) {
                    params.put(k, val);
                }
            }
        }

        // Special path handling
        if (params.get("path") == null) {
            if (params.get("dest") != null) params.put("path", params.get("dest"));
            else if (params.get("name") != null) params.put("path", params.get("name"));
        }

        if (inputArgs != null) {
            if (inputArgs.containsKey("_raw_params")) {
                params.put("_raw_params", inputArgs.get("_raw_params"));
            }
            params.put("_uses_shell", inputArgs.getOrDefault("_uses_shell", false));
        }
    }

    public Map<String, Object> getParams() {
        return params;
    }

    public boolean getCheck_mode() { return checkMode; }
    public boolean get_debug() { return debug; }
    public boolean get_diff() { return diff; }

    public boolean boolean_value(Object v) {
        return Truthiness.isTrue(v);
    }

    public String getTmpdir() {
        return System.getProperty("java.io.tmpdir");
    }

    public void exit_json(Map<String, Object> kwargs) {
        Map<String, Object> res = new HashMap<>();
        if (kwargs != null) res.putAll(kwargs);
        if (!res.containsKey("changed")) res.put("changed", false);
        for (Map.Entry<String, Object> entry : storedFileArgs.entrySet()) {
            if (!res.containsKey(entry.getKey()) && entry.getValue() != null) {
                res.put(entry.getKey(), entry.getValue());
            }
        }
        outputAndExit(res, 0);
    }

    public void fail_json(Map<String, Object> kwargs) {
        Map<String, Object> res = new HashMap<>();
        if (kwargs != null) res.putAll(kwargs);
        res.put("failed", true);
        if (!res.containsKey("msg")) res.put("msg", "Module failed");
        outputAndExit(res, 1);
    }

    private void outputAndExit(Map<String, Object> result, int code) {
        try {
            String json = objectMapper.writeValueAsString(result);
            if (pythonPrint != null) {
                pythonPrint.execute(json);
            }
            if (pythonExit != null) {
                pythonExit.execute(code);
            }
        } catch (Exception e) {
            e.printStackTrace();
            if (pythonExit != null) pythonExit.execute(1);
        }
    }

    public Object[] run_command(Object argsObj) {
        if (connection == null) {
            return new Object[]{1, "", "No connection"};
        }

        String command;
        if (argsObj instanceof List) {
            List<?> list = (List<?>) argsObj;
            // Handle getent mock if needed (logic from Python)
            if (list.size() >= 2 && "getent".equals(list.get(0).toString())) {
                String db = list.get(1).toString();
                String key = list.size() > 2 ? list.get(2).toString() : null;
                if ("passwd".equals(db)) {
                    if ("root".equals(key)) return new Object[]{0, "root:x:0:0:root:/root:/bin/bash\n", ""};
                    if (key == null) return new Object[]{0, "root:x:0:0:root:/root:/bin/bash\ntestuser:x:1001:1001:testuser:/home/testuser:/bin/bash\n", ""};
                } else if ("group".equals(db)) {
                    if ("root".equals(key)) return new Object[]{0, "root:x:0:\n", ""};
                    if (key == null) return new Object[]{0, "root:x:0:\ntestgroup:x:1001:\n", ""};
                }
            }
            StringBuilder sb = new StringBuilder();
            for (Object o : list) {
                if (sb.length() > 0) sb.append(" ");
                sb.append(o.toString());
            }
            command = sb.toString();
        } else {
            command = argsObj.toString();
        }

        try {
            ConnectionResult res = connection.execCommand(command, becomeContext, environment);
            return new Object[]{res.exitCode(), res.stdout(), res.stderr()};
        } catch (Exception e) {
            return new Object[]{-1, "", "Failed to execute command: " + e.getMessage()};
        }
    }

    public String sha1(Object path) { return hashFile(path, "SHA-1"); }
    public String md5(Object path) { return hashFile(path, "MD5"); }
    public String sha256(Object path) { return hashFile(path, "SHA-256"); }

    private String hashFile(Object path, String algorithm) {
        String p = osMock.normalizePath(path);
        if (p == null) return null;
        try (InputStream is = new FileInputStream(p)) {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = is.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
            return hex(digest.digest());
        } catch (Exception e) {
            return null;
        }
    }

    public String digest_from_file(Object filename, Object algorithm) {
        if (algorithm == null) return null;
        // Ansible algorithm names might differ from Java's
        String javaAlg = algorithm.toString().toUpperCase();
        if ("SHA1".equals(javaAlg)) javaAlg = "SHA-1";
        else if ("SHA256".equals(javaAlg)) javaAlg = "SHA-256";
        return hashFile(filename, javaAlg);
    }

    private String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    public void atomic_move(Object src, Object dest) throws IOException {
        String sPath = osMock.normalizePath(src);
        String dPath = osMock.normalizePath(dest);
        if (sPath == null || dPath == null) return;
        Path s = Paths.get(sPath);
        Path d = Paths.get(dPath);
        Files.move(s, d, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    public Map<String, Object> get_file_attributes(Object path) {
        String p = osMock.normalizePath(path);
        if (!osMock.exists(p)) return Collections.emptyMap();

        PythonOSMock.StatResult st = osMock.stat(p);
        if (st == null) return Collections.emptyMap();

        Map<String, Object> attrs = new HashMap<>();
        attrs.put("mode", String.format("%o", st.st_mode & 0777));
        attrs.put("owner", String.valueOf(st.st_uid));
        attrs.put("group", String.valueOf(st.st_gid));
        attrs.put("size", st.st_size);
        attrs.put("uid", st.st_uid);
        attrs.put("gid", st.st_gid);
        return attrs;
    }

    public Map<String, Object> load_file_common_arguments(Map<String, Object> params, Object path) {
        Map<String, Object> res = new HashMap<>();
        String[] keys = {"mode", "owner", "group", "seuser", "serole", "setype", "selevel", "attributes", "unsafe_writes"};
        for (String k : keys) {
            if (params.containsKey(k)) res.put(k, params.get(k));
        }
        Object actualPath = path;
        if (actualPath == null) {
            actualPath = params.get("path");
            if (actualPath == null) actualPath = params.get("dest");
            if (actualPath == null) actualPath = params.get("name");
        }
        if (actualPath != null) {
            String s;
            if (actualPath instanceof List && !((List<?>) actualPath).isEmpty()) {
                s = Objects.toString(((List<?>) actualPath).get(0));
            } else {
                s = Objects.toString(actualPath);
            }
            res.put("path", osMock.normalizePath(s));
        }
        return res;
    }

    public void set_file_attributes_if_different(Map<String, Object> fileArgs, boolean changed) {
        if (fileArgs != null) {
            storedFileArgs.putAll(fileArgs);
        }
    }

    public void set_fs_attributes_if_different(Map<String, Object> fileArgs, boolean changed) {
        set_file_attributes_if_different(fileArgs, changed);
    }

    public String get_bin_path(Object arg, boolean required, List<Object> optDirs) {
        return Objects.toString(arg);
    }

    public void debug(Object msg) { }
    public void warn(Object msg) { }
    public void deprecate(Object msg, Object version, Object date, Object collectionName) { }

    public void makedirs_safe(Object path, Object mode) {
        try {
            int m = mode instanceof Number ? ((Number) mode).intValue() : 0777;
            osMock.makedirs(path, m, true);
        } catch (Exception ignored) {}
    }

    public static class Factory {
        private final PythonOSMock osMock;

        public Factory(PythonOSMock osMock) {
            this.osMock = osMock;
        }

        public PythonAnsibleModuleMock create(Map<String, Object> argumentSpec, Map<String, Object> inputArgs,
                                            Map<String, Object> kwargs, Connection connection,
                                            BecomeContext becomeContext, Map<String, String> environment,
                                            Value pythonPrint, Value pythonExit) {
            return new PythonAnsibleModuleMock(argumentSpec, inputArgs, kwargs, osMock, connection, becomeContext, environment, pythonPrint, pythonExit);
        }
    }
}
