package com.codeeditor.app.runner;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.codeeditor.app.utils.FileUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Native Runner - Executes code using C++ NDK and system interpreters/compilers.
 * Supports Python, C++, and Java code execution.
 * Uses JNI for C++ operations and ProcessBuilder for system commands.
 */
public class NativeRunner {

    private static final String TAG = "NativeRunner";
    private final Context context;
    private final ExecutorService executor;
    private final Handler mainHandler;

    // Load native library
    static {
        System.loadLibrary("code_runner");
    }

    // JNI method declarations
    /**
     * Compile and execute C++ code via NDK.
     * Returns the output or error message.
     */
    public native String executeCppNative(String code, String workDir);

    /**
     * Execute Python code via NDK-embedded interpreter.
     * Returns the output or error message.
     */
    public native String executePythonNative(String code, String workDir);

    /**
     * Get the version of the native library.
     */
    public native String getNativeVersion();

    /**
     * Initialize the native execution environment.
     */
    public native boolean initNativeEnvironment(String dataDir);

    public NativeRunner(Context context) {
        this.context = context;
        this.executor = Executors.newSingleThreadExecutor();
        this.mainHandler = new Handler(Looper.getMainLooper());

        // Initialize native environment
        try {
            initNativeEnvironment(context.getApplicationInfo().dataDir);
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize native environment", e);
        }
    }

    /**
     * Execute code based on language type.
     * Routes to the appropriate execution method.
     *
     * @param language The programming language (python, cpp, java)
     * @param code     The source code to execute
     * @param filePath Optional file path (for Java class resolution)
     * @return Execution output or error
     */
    public String executeCode(String language, String code, String filePath) {
        String workDir = com.codeeditor.app.CodeEditorApp.getWorkDirectory();

        switch (language.toLowerCase()) {
            case "python":
            case "py":
                return executePython(code, workDir);
            case "cpp":
            case "c":
                return executeCpp(code, workDir);
            case "java":
                return executeJava(code, workDir, filePath);
            default:
                return executeAsText(code);
        }
    }

    /**
     * Execute Python code.
     * First tries NDK native execution, falls back to system python3 interpreter.
     */
    private String executePython(String code, String workDir) {
        // Try native execution first
        try {
            String nativeResult = executePythonNative(code, workDir);
            if (nativeResult != null && !nativeResult.isEmpty()) {
                return nativeResult;
            }
        } catch (UnsatisfiedLinkError e) {
            Log.w(TAG, "Native Python execution not available, falling back to system");
        }

        // Fallback: Write to temp file and run with system python3
        return executeWithSystemInterpreter("python3", code, workDir, "py");
    }

    /**
     * Execute C++ code.
     * Compiles using NDK C++ compiler, then runs the binary.
     */
    private String executeCpp(String code, String workDir) {
        // Try native compilation and execution
        try {
            String nativeResult = executeCppNative(code, workDir);
            if (nativeResult != null && !nativeResult.isEmpty()) {
                return nativeResult;
            }
        } catch (UnsatisfiedLinkError e) {
            Log.w(TAG, "Native C++ execution not available, falling back to system");
        }

        // Fallback: Write to temp file and compile with g++
        return executeCppWithSystem(code, workDir);
    }

    /**
     * Execute Java code.
     * Compiles with javac and runs with java.
     */
    private String executeJava(String code, String workDir, String filePath) {
        // Extract class name from code or file path
        String className = "Main";
        if (filePath != null) {
            File f = new File(filePath);
            String name = f.getName();
            if (name.endsWith(".java")) {
                className = name.substring(0, name.length() - 5);
            }
        }

        // Try to extract class name from code
        String classMatch = extractClassName(code);
        if (classMatch != null) {
            className = classMatch;
        }

        // Write Java source file
        String javaFile = workDir + "/" + className + ".java";
        FileUtils.writeFile(javaFile, code);

        StringBuilder output = new StringBuilder();

        // Compile
        ProcessBuilder compilePb = new ProcessBuilder("javac", javaFile);
        compilePb.directory(new File(workDir));
        compilePb.redirectErrorStream(true);
        try {
            Process compileProcess = compilePb.start();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(compileProcess.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
            int compileResult = compileProcess.waitFor();

            if (compileResult != 0) {
                return "Compilation Error:\n" + output.toString();
            }
        } catch (Exception e) {
            return "Compilation Failed:\n" + e.getMessage() +
                    "\n\nNote: JDK is required to compile Java code. " +
                    "Install a Java compiler on your device.";
        }

        // Run
        output.setLength(0);
        ProcessBuilder runPb = new ProcessBuilder("java", "-cp", workDir, className);
        runPb.directory(new File(workDir));
        runPb.redirectErrorStream(true);
        try {
            Process runProcess = runPb.start();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(runProcess.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
            int runResult = runProcess.waitFor();
            if (runResult != 0) {
                output.append("\nProcess exited with code: ").append(runResult);
            }
        } catch (Exception e) {
            output.append("Runtime Error:\n").append(e.getMessage());
        }

        return output.toString();
    }

    /**
     * Execute code as plain text (no compilation/interpretation).
     */
    private String executeAsText(String code) {
        return "Preview Mode:\n\n" + code +
                "\n\n---\nThis file type does not support execution.\n" +
                "Supported languages: Python, C++, Java";
    }

    /**
     * Execute code using a system interpreter (python3, etc.)
     */
    private String executeWithSystemInterpreter(String interpreter, String code,
                                                 String workDir, String extension) {
        String tempFile = workDir + "/temp_run." + extension;
        FileUtils.writeFile(tempFile, code);

        StringBuilder output = new StringBuilder();
        ProcessBuilder pb = new ProcessBuilder(interpreter, tempFile);
        pb.directory(new File(workDir));
        pb.redirectErrorStream(true);

        try {
            Process process = pb.start();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                output.append("\nProcess exited with code: ").append(exitCode);
            }
        } catch (Exception e) {
            return "Execution Failed:\n" + e.getMessage() +
                    "\n\nNote: " + interpreter + " is required. " +
                    "Install it on your device or use the built-in NDK runner.";
        }

        // Clean up temp file
        new File(tempFile).delete();

        return output.toString();
    }

    /**
     * Compile and run C++ code using system g++ compiler.
     */
    private String executeCppWithSystem(String code, String workDir) {
        String sourceFile = workDir + "/temp_run.cpp";
        String outputFile = workDir + "/temp_run_out";
        FileUtils.writeFile(sourceFile, code);

        StringBuilder output = new StringBuilder();

        // Compile
        ProcessBuilder compilePb = new ProcessBuilder("g++", "-o", outputFile, sourceFile);
        compilePb.directory(new File(workDir));
        compilePb.redirectErrorStream(true);
        try {
            Process compileProcess = compilePb.start();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(compileProcess.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
            int compileResult = compileProcess.waitFor();

            if (compileResult != 0) {
                return "Compilation Error:\n" + output.toString();
            }
        } catch (Exception e) {
            return "Compilation Failed:\n" + e.getMessage() +
                    "\n\nNote: g++ compiler is required. " +
                    "Install it on your device or use the built-in NDK compiler.";
        }

        // Run
        output.setLength(0);
        try {
            ProcessBuilder runPb = new ProcessBuilder(outputFile);
            runPb.directory(new File(workDir));
            runPb.redirectErrorStream(true);
            Process runProcess = runPb.start();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(runProcess.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
            int runResult = runProcess.waitFor();
            if (runResult != 0) {
                output.append("\nProcess exited with code: ").append(runResult);
            }
        } catch (Exception e) {
            output.append("Runtime Error:\n").append(e.getMessage());
        }

        // Clean up
        new File(sourceFile).delete();
        new File(outputFile).delete();

        return output.toString();
    }

    /**
     * Extract the public class name from Java source code.
     */
    private String extractClassName(String code) {
        // Match "public class ClassName"
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "public\\s+class\\s+(\\w+)");
        java.util.regex.Matcher matcher = pattern.matcher(code);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    /**
     * Execute code asynchronously and deliver result via callback.
     */
    public void executeCodeAsync(String language, String code, String filePath,
                                  RunCallback callback) {
        executor.execute(() -> {
            String result = executeCode(language, code, filePath);
            mainHandler.post(() -> callback.onResult(result));
        });
    }

    /**
     * Callback interface for asynchronous execution.
     */
    public interface RunCallback {
        void onResult(String output);
    }
}
