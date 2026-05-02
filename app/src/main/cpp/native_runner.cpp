#include <jni.h>
#include <string>
#include <cstdlib>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <sstream>
#include <sys/stat.h>
#include <unistd.h>
#include <dirent.h>

/**
 * Native Code Runner - C++ implementation for code execution on Android.
 *
 * This file provides JNI functions that compile and execute C++ code,
 * execute Python code, and manage the native execution environment.
 *
 * The code runner:
 * 1. Writes source code to temporary files in the work directory
 * 2. Compiles C++ code using the NDK compiler (or system g++)
 * 3. Executes compiled binaries or scripts
 * 4. Captures stdout and stderr output
 * 5. Returns the output to Java via JNI
 */

extern "C" {

// ============================================================
// Utility Functions
// ============================================================

/**
 * Execute a system command and capture its output.
 * Returns the combined stdout and stderr as a string.
 */
static std::string executeCommand(const char* command) {
    std::string output;
    FILE* pipe = popen(command, "r");
    if (!pipe) {
        return "Error: Failed to execute command\n";
    }

    char buffer[256];
    while (fgets(buffer, sizeof(buffer), pipe) != nullptr) {
        output += buffer;
    }

    int exitCode = pclose(pipe);
    if (exitCode != 0) {
        output += "\nProcess exited with code: ";
        output += std::to_string(exitCode);
        output += "\n";
    }

    return output;
}

/**
 * Check if a file exists on the filesystem.
 */
static bool fileExists(const std::string& path) {
    struct stat buffer;
    return (stat(path.c_str(), &buffer) == 0);
}

/**
 * Create a directory (including parent directories).
 */
static bool createDirectory(const std::string& path) {
    return (mkdir(path.c_str(), 0755) == 0 || errno == EEXIST);
}

/**
 * Write content to a file.
 */
static bool writeToFile(const std::string& path, const std::string& content) {
    std::ofstream file(path);
    if (!file.is_open()) {
        return false;
    }
    file << content;
    file.close();
    return true;
}

/**
 * Read content from a file.
 */
static std::string readFromFile(const std::string& path) {
    std::ifstream file(path);
    if (!file.is_open()) {
        return "";
    }
    std::stringstream buffer;
    buffer << file.rdbuf();
    return buffer.str();
}

/**
 * Get the NDK compiler path based on the app's native library directory.
 */
static std::string getNdkCompilerPath() {
    // Try common NDK compiler locations
    const char* paths[] = {
        "/system/bin/clang++",
        "/system/bin/g++",
        "/usr/bin/g++",
        "/usr/bin/clang++",
        nullptr
    };

    for (int i = 0; paths[i] != nullptr; i++) {
        if (fileExists(paths[i])) {
            return paths[i];
        }
    }

    return "";
}

// ============================================================
// JNI Functions
// ============================================================

/**
 * Compile and execute C++ code.
 *
 * @param env       JNI environment
 * @param thiz      Java object reference
 * @param code      C++ source code to compile and run
 * @param workDir   Working directory for temp files
 * @return          Execution output or error message
 */
JNIEXPORT jstring JNICALL
Java_com_codeeditor_app_runner_NativeRunner_executeCppNative(
        JNIEnv* env, jobject thiz, jstring code, jstring workDir) {

    const char* sourceCode = env->GetStringUTFChars(code, nullptr);
    const char* workDirectory = env->GetStringUTFChars(workDir, nullptr);

    std::string source(sourceCode);
    std::string workDirStr(workDirectory);
    std::string result;

    // Write source code to temp file
    std::string sourceFile = workDirStr + "/native_run.cpp";
    std::string outputFile = workDirStr + "/native_run_out";

    if (!writeToFile(sourceFile, source)) {
        result = "Error: Failed to write source file\n";
        env->ReleaseStringUTFChars(code, sourceCode);
        env->ReleaseStringUTFChars(workDir, workDirectory);
        return env->NewStringUTF(result.c_str());
    }

    // Try to compile with available compiler
    std::string compiler = getNdkCompilerPath();

    if (!compiler.empty()) {
        // Compile
        std::string compileCmd = compiler + " -o " + outputFile + " " + sourceFile + " 2>&1";
        std::string compileOutput = executeCommand(compileCmd.c_str());

        if (!compileOutput.empty() && !fileExists(outputFile)) {
            result = "Compilation Error:\n" + compileOutput;
        } else if (fileExists(outputFile)) {
            // Execute
            std::string runCmd = outputFile + " 2>&1";
            result = executeCommand(runCmd.c_str());

            // Clean up binary
            remove(outputFile.c_str());
        }
    } else {
        result = "No C++ compiler found on this device.\n"
                 "Install a C++ compiler (g++ or clang++) to compile C++ code.\n"
                 "Alternatively, use the built-in interpreter mode.\n";
    }

    // Clean up source file
    remove(sourceFile.c_str());

    env->ReleaseStringUTFChars(code, sourceCode);
    env->ReleaseStringUTFChars(workDir, workDirectory);

    return env->NewStringUTF(result.c_str());
}

/**
 * Execute Python code.
 *
 * Uses the system Python3 interpreter if available.
 * For embedded Python execution, this could be extended with
 * CPython embedding (Py_Initialize, PyRun_SimpleString, etc.)
 *
 * @param env       JNI environment
 * @param thiz      Java object reference
 * @param code      Python source code
 * @param workDir   Working directory for temp files
 * @return          Execution output or error message
 */
JNIEXPORT jstring JNICALL
Java_com_codeeditor_app_runner_NativeRunner_executePythonNative(
        JNIEnv* env, jobject thiz, jstring code, jstring workDir) {

    const char* sourceCode = env->GetStringUTFChars(code, nullptr);
    const char* workDirectory = env->GetStringUTFChars(workDir, nullptr);

    std::string source(sourceCode);
    std::string workDirStr(workDirectory);
    std::string result;

    // Write source code to temp file
    std::string sourceFile = workDirStr + "/native_run.py";

    if (!writeToFile(sourceFile, source)) {
        result = "Error: Failed to write source file\n";
        env->ReleaseStringUTFChars(code, sourceCode);
        env->ReleaseStringUTFChars(workDir, workDirectory);
        return env->NewStringUTF(result.c_str());
    }

    // Try to run with python3
    const char* interpreters[] = {
        "python3",
        "python",
        "/usr/bin/python3",
        "/usr/local/bin/python3",
        nullptr
    };

    bool executed = false;
    for (int i = 0; interpreters[i] != nullptr; i++) {
        std::string checkCmd = std::string("which ") + interpreters[i] + " 2>/dev/null";
        if (system(checkCmd.c_str()) == 0) {
            std::string runCmd = std::string(interpreters[i]) + " " + sourceFile + " 2>&1";
            result = executeCommand(runCmd.c_str());
            executed = true;
            break;
        }
    }

    if (!executed) {
        result = "Python interpreter not found on this device.\n"
                 "Install Python3 to execute Python code.\n"
                 "You can install it via Termux: pkg install python\n";
    }

    // Clean up
    remove(sourceFile.c_str());

    env->ReleaseStringUTFChars(code, sourceCode);
    env->ReleaseStringUTFChars(workDir, workDirectory);

    return env->NewStringUTF(result.c_str());
}

/**
 * Get the version of the native library.
 */
JNIEXPORT jstring JNICALL
Java_com_codeeditor_app_runner_NativeRunner_getNativeVersion(
        JNIEnv* env, jobject thiz) {
    return env->NewStringUTF("1.0.0");
}

/**
 * Initialize the native execution environment.
 * Creates necessary directories and checks for available tools.
 *
 * @param env       JNI environment
 * @param thiz      Java object reference
 * @param dataDir   App's data directory
 * @return          true if initialization succeeded
 */
JNIEXPORT jboolean JNICALL
Java_com_codeeditor_app_runner_NativeRunner_initNativeEnvironment(
        JNIEnv* env, jobject thiz, jstring dataDir) {

    const char* dataDirectory = env->GetStringUTFChars(dataDir, nullptr);
    std::string dataDirStr(dataDirectory);

    // Create native working directories
    std::string nativeDir = dataDirStr + "/native";
    std::string tempDir = dataDirStr + "/native/temp";
    std::string cacheDir = dataDirStr + "/native/cache";

    bool success = true;
    success &= createDirectory(nativeDir);
    success &= createDirectory(tempDir);
    success &= createDirectory(cacheDir);

    env->ReleaseStringUTFChars(dataDir, dataDirectory);

    return success ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
