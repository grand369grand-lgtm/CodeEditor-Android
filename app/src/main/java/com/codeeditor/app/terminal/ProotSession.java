package com.codeeditor.app.terminal;

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
 * ProotSession - Manages an interactive Linux shell session using proot.
 * This provides a real Ubuntu/Debian/Alpine Linux environment on Android
 * without requiring root access, similar to Termux's proot-distro.
 *
 * Features:
 * - Full Linux environment via proot (Ubuntu, Debian, Alpine)
 * - Interactive shell with bash/sh
 * - Package management (apt, apk, etc.)
 * - Real filesystem with /usr, /etc, /home, etc.
 * - Bidirectional I/O with the shell process
 * - Ctrl+C/D/Z signal support
 * - Working directory management
 * - Environment variable support
 * - ANSI color output streaming
 */
public class ProotSession {

    private static final String TAG = "ProotSession";

    private Process process;
    private OutputStream processStdin;
    private BufferedReader processStdout;
    private BufferedReader processStderr;

    private final ExecutorService readExecutor;
    private final Handler mainHandler;
    private final SessionCallback callback;
    private final UbuntuManager ubuntuManager;
    private final String distro;

    private volatile boolean isRunning = false;
    private volatile boolean isDestroyed = false;

    /**
     * Callback interface for session events.
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
     * Create a new proot session.
     *
     * @param ubuntuManager The UbuntuManager instance
     * @param distro        The distribution to run (ubuntu, debian, alpine)
     * @param callback      Callback for output and events
     */
    public ProotSession(UbuntuManager ubuntuManager, String distro, SessionCallback callback) {
        this.ubuntuManager = ubuntuManager;
        this.distro = distro;
        this.callback = callback;
        this.readExecutor = Executors.newFixedThreadPool(3);
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    /**
     * Start the proot session.
     */
    public void start() {
        if (isRunning || isDestroyed) return;

        if (!ubuntuManager.isDistroInstalled()) {
            notifyError("Linux distribution not installed. Please install it first.");
            return;
        }

        if (!ubuntuManager.isProotAvailable()) {
            notifyError("proot binary not available. Please install it first.");
            return;
        }

        try {
            String[] command = ubuntuManager.buildProotCommand(null);
            String[] environment = ubuntuManager.buildProotEnvironment();

            Log.d(TAG, "Starting proot session:");
            Log.d(TAG, "  Command: " + String.join(" ", command));
            Log.d(TAG, "  Rootfs: " + ubuntuManager.getRootfsDir().getAbsolutePath());

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.environment().clear();

            // Set environment variables
            for (String envVar : environment) {
                int eq = envVar.indexOf('=');
                if (eq > 0) {
                    pb.environment().put(
                            envVar.substring(0, eq),
                            envVar.substring(eq + 1)
                    );
                }
            }

            // Inherit some essential system vars
            String systemPath = System.getenv("PATH");
            if (systemPath != null) {
                pb.environment().put("SYSTEM_PATH", systemPath);
            }
            pb.environment().put("PROOT_NO_SECCOMP", "1");
            pb.environment().put("HOME", "/root");
            pb.environment().put("TERM", "xterm-256color");

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

            Log.d(TAG, "Proot session started successfully");

        } catch (IOException e) {
            Log.e(TAG, "Failed to start proot session", e);

            // Fallback: try with a simpler proot invocation
            try {
                startFallbackSession();
            } catch (IOException e2) {
                notifyError("Failed to start proot session: " + e.getMessage() +
                        "\n\nFallback also failed: " + e2.getMessage() +
                        "\n\nMake sure proot is properly installed.");
            }
        }
    }

    /**
     * Fallback session: try a simpler proot invocation.
     */
    private void startFallbackSession() throws IOException {
        String prootPath = ubuntuManager.getProotBin().getAbsolutePath();
        String rootfsPath = ubuntuManager.getRootfsDir().getAbsolutePath();

        // Simple proot command: proot -r rootfs /bin/sh
        String[] simpleCommand = {
                prootPath,
                "-r", rootfsPath,
                "-b", "/dev",
                "-b", "/proc",
                "-b", "/sys",
                "-b", "/sdcard",
                "/bin/sh"
        };

        Log.d(TAG, "Trying fallback proot command: " + String.join(" ", simpleCommand));

        ProcessBuilder pb = new ProcessBuilder(simpleCommand);
        pb.environment().put("HOME", "/root");
        pb.environment().put("TERM", "xterm-256color");
        pb.environment().put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        pb.environment().put("PROOT_NO_SECCOMP", "1");
        pb.environment().put("USER", "root");
        pb.redirectErrorStream(false);

        process = pb.start();
        processStdin = process.getOutputStream();
        processStdout = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8));
        processStderr = new BufferedReader(new InputStreamReader(
                process.getErrorStream(), StandardCharsets.UTF_8));

        isRunning = true;

        readExecutor.execute(this::readStdout);
        readExecutor.execute(this::readStderr);
        readExecutor.execute(this::monitorProcess);

        Log.d(TAG, "Fallback proot session started");
    }

    /**
     * Start a session running a specific command.
     */
    public void startWithCommand(String command) {
        if (isRunning || isDestroyed) return;

        if (!ubuntuManager.isDistroInstalled()) {
            notifyError("Linux distribution not installed");
            return;
        }

        if (!ubuntuManager.isProotAvailable()) {
            notifyError("proot binary not available");
            return;
        }

        try {
            String[] cmd = ubuntuManager.buildProotCommand(command);
            String[] environment = ubuntuManager.buildProotEnvironment();

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.environment().clear();

            for (String envVar : environment) {
                int eq = envVar.indexOf('=');
                if (eq > 0) {
                    pb.environment().put(
                            envVar.substring(0, eq),
                            envVar.substring(eq + 1)
                    );
                }
            }

            pb.environment().put("PROOT_NO_SECCOMP", "1");
            pb.redirectErrorStream(false);

            process = pb.start();
            processStdin = process.getOutputStream();
            processStdout = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8));
            processStderr = new BufferedReader(new InputStreamReader(
                    process.getErrorStream(), StandardCharsets.UTF_8));

            isRunning = true;

            readExecutor.execute(this::readStdout);
            readExecutor.execute(this::readStderr);
            readExecutor.execute(this::monitorProcess);

        } catch (IOException e) {
            Log.e(TAG, "Failed to start proot session with command", e);
            notifyError("Failed to start: " + e.getMessage());
        }
    }

    /**
     * Write a command to the shell's stdin.
     */
    public void write(String command) {
        if (!isRunning || processStdin == null) return;

        try {
            OutputStreamWriter writer = new OutputStreamWriter(processStdin, StandardCharsets.UTF_8);
            writer.write(command);
            writer.flush();
        } catch (IOException e) {
            Log.e(TAG, "Failed to write to proot stdin", e);
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
     * Send Ctrl+C (SIGINT) to the shell process.
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
     * Change the working directory.
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
     * Destroy the session and clean up resources.
     */
    public void destroy() {
        isDestroyed = true;
        isRunning = false;

        try {
            if (processStdin != null) processStdin.close();
        } catch (IOException ignored) {}

        try {
            if (processStdout != null) processStdout.close();
        } catch (IOException ignored) {}

        try {
            if (processStderr != null) processStderr.close();
        } catch (IOException ignored) {}

        if (process != null) {
            process.destroy();
            // Force kill if process doesn't exit
            try {
                Thread.sleep(100);
                process.destroyForcibly();
            } catch (InterruptedException ignored) {}
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
                Log.d(TAG, "proot stdout read ended: " + e.getMessage());
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
                notifyOutput(text);  // Mix stderr into output
            }
        } catch (IOException e) {
            if (!isDestroyed) {
                Log.d(TAG, "proot stderr read ended: " + e.getMessage());
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
    // Private: Helpers
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

    private String escapeShellArg(String arg) {
        if (arg == null) return "''";
        return "'" + arg.replace("'", "'\\''") + "'";
    }
}
