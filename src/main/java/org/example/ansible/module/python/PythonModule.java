package org.example.ansible.module.python;

import org.example.ansible.engine.TaskResult;
import org.example.ansible.module.Module;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.HashMap;
import com.fasterxml.jackson.databind.ObjectMapper;

public class PythonModule implements Module {
    private final String moduleName;
    private final String scriptContent;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PythonModule(String moduleName, String scriptContent) {
        this.moduleName = moduleName;
        this.scriptContent = scriptContent;
    }

    /**
     * Executes the module with the given arguments.
     * This implementation uses GraalPy to run Python module code.
     *
     * @param args The module arguments (e.g., src, dest, content).
     * @return The result of the execution.
     */
    @Override
    public TaskResult execute(Map<String, Object> args) {
        try (Context context = Context.newBuilder("python")
                .allowAllAccess(true)
                .build()) {
            
            // モジュール引数をJSON文字列に変換
            String argsJson = objectMapper.writeValueAsString(args);
            
            // Python側で引数を読み込み、モジュールを実行するラッパースクリプト
            // Ansibleモジュールは通常標準入力から引数を受け取り、標準出力にJSONを出す
            String wrapperScript = 
                "import json\n" +
                "import sys\n" +
                "from io import StringIO\n" +
                "\n" +
                "def run_module():\n" +
                "    args = json.loads(r'''" + argsJson + "''')\n" +
                "    # モジュールのメイン処理を呼び出すための擬似的な環境構築\n" +
                "    # 本来は ansible.module_utils が必要だが、動作確認用として最小限に留める\n" +
                "    \n" +
                "    # 出力をキャプチャ\n" +
                "    old_stdout = sys.stdout\n" +
                "    sys.stdout = mystdout = StringIO()\n" +
                "    \n" +
                "    try:\n" +
                "        # ここで本来のモジュールコードを実行\n" +
                "        # 今回は簡略化のため、モジュールコード自体が args を処理するように期待する\n" +
                "        module_code = r'''" + scriptContent + "'''\n" +
                "        module_globals = {'complex_args': args, 'ansible_module_results': {}}\n" +
                "        exec(module_code, module_globals)\n" +
                "        # モジュールが結果を標準出力に出すと仮定\n" +
                "        return mystdout.getvalue()\n" +
                "    finally:\n" +
                "        sys.stdout = old_stdout\n" +
                "\n" +
                "result = run_module()";

            context.eval("python", wrapperScript);
            Value pythonResult = context.getPolyglotBindings().getMember("result");
            if (pythonResult == null) {
                // bindings にない場合はグローバルスコープから探す
                pythonResult = context.getBindings("python").getMember("result");
            }

            if (pythonResult == null || !pythonResult.isString()) {
                return TaskResult.failure("Module produced no valid output (result is not a string)");
            }
            String output = pythonResult.asString();
            
            if (output == null || output.isBlank()) {
                return TaskResult.failure("Module produced no output");
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> resultMap = objectMapper.readValue(output, Map.class);
            
            boolean failed = resultMap.containsKey("failed") && (boolean) resultMap.get("failed");
            if (failed) {
                return TaskResult.failure(resultMap.getOrDefault("msg", "Module failed").toString());
            }

            return TaskResult.success(resultMap);
            
        } catch (Exception e) {
            return TaskResult.failure("GraalPy execution failed: " + e.getMessage());
        }
    }
}
