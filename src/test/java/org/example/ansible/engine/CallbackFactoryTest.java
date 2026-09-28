package org.example.ansible.engine;

import org.example.ansible.util.PythonEnv;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class CallbackFactoryTest {

    @Test
    public void testCreateStdoutCallbackBuiltins() {
        Callback defaultCb = CallbackFactory.createStdoutCallback("default", null);
        assertInstanceOf(DefaultCallback.class, defaultCb);

        Callback jsonCb = CallbackFactory.createStdoutCallback("json", null);
        assertInstanceOf(JsonCallback.class, jsonCb);

        Callback minimalCb = CallbackFactory.createStdoutCallback("minimal", null);
        assertInstanceOf(MinimalCallback.class, minimalCb);

        Callback yamlCb = CallbackFactory.createStdoutCallback("yaml", null);
        assertInstanceOf(YamlCallback.class, yamlCb);
    }

    @Test
    public void testParseAnsibleCfgStdoutCallback(@TempDir Path tempDir) throws Exception {
        Path cfgPath = tempDir.resolve("ansible.cfg");
        Files.writeString(cfgPath, "[defaults]\nstdout_callback = minimal\n");

        String parsed = PythonEnv.parseAnsibleCfgStdoutCallback(cfgPath.toFile());
        assertEquals("minimal", parsed);
    }
}
