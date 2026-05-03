package com.codeeditor.app.runner;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Terminal Session - Manages an interactive shell process.
 * Provides bidirectional I/O with a system shell (/system/bin/sh),
 * similar to how Termux works. Supports command execution, output
 * streaming, and process lifecycle management.
 *
 * Features:
 * - Interactive shell session with /system/bin/sh
 * - Real-time output streaming (stdout + stderr)
 * - Write commands to shell stdin
 * - Session lifecycle (create, destroy)
 * - Working directory management
 * - Environment variable support
 * - Callback interface for output delivery
 */
public class TerminalSession {

    private static final String TAG = "TerminalSession";

    /** Shell command to execute */
    private static final String[] SHELL_COMMAND = {"/system/bin/sh", "-i"};

    private Process process;
    private OutputStream processStdin;
    private BufferedReader processStdout;
    private BufferedReader processStderr;

    private final ExecutorService readExecutor;
    private final Handler mainHandler;
    private final SessionCallback callback;
    private final String workingDirectory;
    private final String[] environment;

    private volatile boolean isRunning = false;
    private volatile boolean isDestroyed = false;

    /**
     * Callback interface for terminal session events.
     */
    public interface SessionCallback {
        /** Called when new output is available from the shell */
        void onOutput(String text);

        /** Called when the shell process exits */
        void onSessionExit(int exitCode);

        /** Called when there is an error with the session */
        void onError(String error);
    }

    /**
     * Create a new terminal session.
     *
     * @param workingDirectory The directory where the shell starts
     * @param environment      Additional environment variables (format: "KEY=VALUE")
     * @param callback         Callback for output and events
     */
    public TerminalSession(String workingDirectory, String[] environment, SessionCallback callback) {
        this.workingDirectory = workingDirectory;
        this.environment = environment;
        this.callback = callback;
        this.readExecutor = Executors.newFixedThreadPool(2);
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    /**
     * Start the shell session.
     */
    public void start() {
        if (isRunning || isDestroyed) return;

        try {
            ProcessBuilder pb = new ProcessBuilder(SHELL_COMMAND);

            // Set working directory
            if (workingDirectory != null) {
                File dir = new File(workingDirectory);
                if (dir.exists() && dir.isDirectory()) {
                    pb.directory(dir);
                }
            }

            // Set environment variables
            if (environment != null) {
                for (String envVar : environment) {
                    int eq = envVar.indexOf('=');
                    if (eq > 0) {
                        pb.environment().put(
                                envVar.substring(0, eq),
                                envVar.substring(eq + 1)
                        );
                    }
                }
            }

            // Add useful default environment
            pb.environment().put("TERM", "xterm-256color");
            pb.environment().put("HOME", workingDirectory != null ? workingDirectory : "/");
            pb.environment().put("PATH", System.getenv("PATH") + ":/system/bin:/system/xbin:/data/data/com.termux/files/usr/bin");
            pb.environment().put("LANG", "en_US.UTF-8");

            pb.redirectErrorStream(false);

            process = pb.start();
            processStdin = process.getOutputStream();
            processStdout = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8));
            processStderr = new BufferedReader(new InputStreamReader(
                    process.getErrorStream(), StandardCharsets.UTF_8));

            isRunning = true;

            // Start reading stdout in background
            readExecutor.execute(this::readStdout);

            // Start reading stderr in background
            readExecutor.execute(this::readStderr);

            // Monitor process exit
            readExecutor.execute(this::monitorProcess);

        } catch (IOException e) {
            Log.e(TAG, "Failed to start shell session", e);
            notifyError("Failed to start shell: " + e.getMessage());
        }
    }

    /**
     * Write a command to the shell's stdin.
     *
     * @param command The command to execute (should end with \n)
     */
    public void write(String command) {
        if (!isRunning || processStdin == null) return;

        try {
            OutputStreamWriter writer = new OutputStreamWriter(processStdin, StandardCharsets.UTF_8);
            writer.write(command);
            writer.flush();
        } catch (IOException e) {
            Log.e(TAG, "Failed to write to shell stdin", e);
            notifyError("Write error: " + e.getMessage());
        }
    }

    /**
     * Write a command and append newline.
     */
    public void executeCommand(String command) {
        write(command + "\n");
    }

    /**
     * Send Ctrl+C to the shell process.
     */
    public void sendCtrlC() {
        if (!isRunning || processStdin == null) return;
        try {
            processStdin.write(3); // ETX = Ctrl+C
            processStdin.flush();
        } catch (IOException e) {
            Log.w(TAG, "Failed to send Ctrl+C", e);
        }
    }

    /**
     * Send Ctrl+D (EOF) to the shell process.
     */
    public void sendCtrlD() {
        if (!isRunning || processStdin == null) return;
        try {
            processStdin.write(4); // EOT = Ctrl+D
            processStdin.flush();
        } catch (IOException e) {
            Log.w(TAG, "Failed to send Ctrl+D", e);
        }
    }

    /**
     * Send Ctrl+Z (SIGTSTP) to the shell process.
     */
    public void sendCtrlZ() {
        if (!isRunning || processStdin == null) return;
        try {
            processStdin.write(26); // SUB = Ctrl+Z
            processStdin.flush();
        } catch (IOException e) {
            Log.w(TAG, "Failed to send Ctrl+Z", e);
        }
    }

    /**
     * Send a signal to interrupt the current process.
     */
    public void interrupt() {
        sendCtrlC();
    }

    /**
     * Change the working directory of the shell.
     */
    public void changeDirectory(String path) {
        executeCommand("cd " + escapeShellArg(path));
    }

    /**
     * Check if the session is currently running.
     */
    public boolean isRunning() {
        return isRunning;
    }

    /**
     * Destroy the shell session and clean up resources.
     */
    public void destroy() {
        isDestroyed = true;
        isRunning = false;

        try {
            if (processStdin != null) {
                processStdin.close();
            }
        } catch (IOException ignored) {}

        try {
            if (processStdout != null) {
                processStdout.close();
            }
        } catch (IOException ignored) {}

        try {
            if (processStderr != null) {
                processStderr.close();
            }
        } catch (IOException ignored) {}

        if (process != null) {
            process.destroy();
        }

        readExecutor.shutdownNow();
    }

    // =====================================================================
    // Private: Background Reading Threads
    // =====================================================================

    private void readStdout() {
        try {
            char[] buffer = new char[4096];
            int count;
            while ((count = processStdout.read(buffer)) != -1) {
                if (isDestroyed) break;
                String text = new String(buffer, 0, count);
                notifyOutput(text);
            }
        } catch (IOException e) {
            if (!isDestroyed) {
                Log.d(TAG, "stdout read ended: " + e.getMessage());
            }
        }
    }

    private void readStderr() {
        try {
            char[] buffer = new char[4096];
            int count;
            while ((count = processStderr.read(buffer)) != -1) {
                if (isDestroyed) break;
                String text = new String(buffer, 0, count);
                notifyOutput(text);
            }
        } catch (IOException e) {
            if (!isDestroyed) {
                Log.d(TAG, "stderr read ended: " + e.getMessage());
            }
        }
    }

    private void monitorProcess() {
        try {
            int exitCode = process.waitFor();
            isRunning = false;
            notifyExit(exitCode);
        } catch (InterruptedException e) {
            Log.w(TAG, "Process monitor interrupted", e);
            isRunning = false;
        }
    }

    // =====================================================================
    // Private: Notification Helpers
    // =====================================================================

    private void notifyOutput(String text) {
        if (callback != null) {
            mainHandler.post(() -> callback.onOutput(text));
        }
    }

    private void notifyExit(int exitCode) {
        if (callback != null) {
            mainHandler.post(() -> callback.onSessionExit(exitCode));
        }
    }

    private void notifyError(String error) {
        if (callback != null) {
            mainHandler.post(() -> callback.onError(error));
        }
    }

    /**
     * Escape a shell argument by wrapping in single quotes.
     */
    private String escapeShellArg(String arg) {
        if (arg == null) return "''";
        return "'" + arg.replace("'", "'\\''") + "'";
    }
}
