package com.codeeditor.app.runner;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * TerminalSession - Manages an interactive shell process with proper PTY support.
 *
 * This uses the same PTY (Pseudo-Terminal) approach that Termux uses internally:
 * forkpty() creates a proper pseudo-terminal pair, giving the child process
 * a real TTY. This fixes "can't find tty fd" errors that occur with
 * ProcessBuilder's pipe-based I/O.
 *
 * Key differences from the old ProcessBuilder approach:
 * - Uses forkpty() via JNI for proper PTY creation (like Termux)
 * - Shell gets a real TTY (no "can't find tty fd" errors)
 * - Full ANSI/VT100 escape sequence support from the shell
 * - Tab completion, arrow keys, cursor movement all work properly
 * - Ctrl+C/D/Z send real terminal signals
 * - Window size changes are properly propagated to the shell
 *
 * Architecture:
 * [TerminalActivity] ↔ [TerminalSession] ↔ [PTY JNI (forkpty)] ↔ [Shell Process]
 *       ↕                     ↕
 *   WebView UI          PTY master fd
 *   (rendering)         (read/write)
 */
public class TerminalSession {

    private static final String TAG = "TerminalSession";

    // Default shell paths to try (in order of preference)
    private static final String[] SHELL_PATHS = {
            "/system/bin/sh",
            "/bin/sh",
            "/usr/bin/sh"
    };

    // =====================================================================
    // Native Methods - PTY support via JNI (like Termux)
    // =====================================================================

    static {
        System.loadLibrary("pty_support");
    }

    /**
     * Create a subprocess with a PTY (forkpty).
     * Returns the master file descriptor, or -1 on error.
     * The processId array will be filled with the child PID.
     */
    private static native int nativeCreateSubprocess(
            String cmd, String cwd, String[] args, String[] envVars, int[] processId);

    /**
     * Set the PTY window size (rows x cols).
     * Must be called when terminal dimensions change.
     */
    private static native void nativeSetPtyWindowSize(int fd, int rows, int cols);

    /**
     * Wait for a process to exit and return its exit code.
     */
    private static native int nativeWaitFor(int pid);

    /**
     * Close a file descriptor.
     */
    private static native void nativeCloseFd(int fd);

    /**
     * Send a signal to a process.
     */
    private static native void nativeSendSignal(int pid, int sig);

    /**
     * Read bytes from a file descriptor into a byte array.
     * Returns number of bytes read, 0 if no data, -1 on error/EOF.
     */
    private static native int nativeReadFd(int fd, byte[] buffer, int offset, int length);

    /**
     * Write bytes to a file descriptor from a byte array.
     * Returns number of bytes written, or -1 on error.
     */
    private static native int nativeWriteFd(int fd, byte[] buffer, int offset, int length);

    // =====================================================================
    // Session State
    // =====================================================================

    private int masterFd = -1;
    private int processPid = -1;
    private volatile boolean isRunning = false;
    private volatile boolean isDestroyed = false;

    private final SessionCallback callback;
    private final String shellCommand;
    private final String workingDirectory;
    private final String[] arguments;
    private final String[] environment;
    private final ExecutorService ioExecutor;
    private final Handler mainHandler;

    private int terminalRows = 24;
    private int terminalCols = 80;

    /**
     * Callback interface for terminal session events.
     */
    public interface SessionCallback {
        /** Called when new output bytes are available from the shell */
        void onOutput(byte[] data, int length);

        /** Called when the shell process exits */
        void onSessionExit(int exitCode);

        /** Called when there is an error with the session */
        void onError(String error);
    }

    /**
     * Create a new terminal session with default shell.
     *
     * @param workingDirectory The directory where the shell starts
     * @param environment      Additional environment variables (format: "KEY=VALUE")
     * @param callback         Callback for output and events
     */
    public TerminalSession(String workingDirectory, String[] environment, SessionCallback callback) {
        this(null, workingDirectory, null, environment, callback);
    }

    /**
     * Create a new terminal session with a specific command.
     *
     * @param shellCommand     The command to execute (null for default shell)
     * @param workingDirectory The directory where the shell starts
     * @param arguments        Command arguments (null for default)
     * @param environment      Additional environment variables (format: "KEY=VALUE")
     * @param callback         Callback for output and events
     */
    public TerminalSession(String shellCommand, String workingDirectory,
                           String[] arguments, String[] environment, SessionCallback callback) {
        this.shellCommand = shellCommand;
        this.workingDirectory = workingDirectory;
        this.arguments = arguments;
        this.environment = environment;
        this.callback = callback;
        this.ioExecutor = Executors.newFixedThreadPool(2);
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    /**
     * Start the terminal session with proper PTY support.
     * This uses forkpty() to create a real pseudo-terminal, just like Termux.
     */
    public void start() {
        if (isRunning || isDestroyed) return;

        try {
            // Determine the shell command
            String cmd = shellCommand;
            if (cmd == null) {
                cmd = findShell();
            }

            // Build environment
            String[] env = buildEnvironment();

            // Create the PTY subprocess via JNI
            int[] pidOut = new int[1];
            masterFd = nativeCreateSubprocess(cmd, workingDirectory, arguments, env, pidOut);

            if (masterFd < 0) {
                notifyError("Failed to create PTY subprocess. forkpty() returned " + masterFd);
                return;
            }

            processPid = pidOut[0];
            isRunning = true;

            Log.d(TAG, "PTY session started: pid=" + processPid + ", masterFd=" + masterFd);

            // Set initial window size
            nativeSetPtyWindowSize(masterFd, terminalRows, terminalCols);

            // Start reading from PTY in background thread
            ioExecutor.execute(this::readPtyOutput);

            // Monitor process exit
            ioExecutor.execute(this::monitorProcess);

        } catch (Exception e) {
            Log.e(TAG, "Failed to start PTY session", e);
            notifyError("Failed to start terminal: " + e.getMessage());
        }
    }

    /**
     * Write a string to the shell's stdin (via PTY master fd).
     *
     * @param data The string to write
     */
    public void write(String data) {
        if (!isRunning || masterFd < 0) return;

        try {
            byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
            int written = nativeWriteFd(masterFd, bytes, 0, bytes.length);
            if (written < 0) {
                Log.w(TAG, "Failed to write to PTY master fd");
            }
        } catch (Exception e) {
            Log.e(TAG, "Write error", e);
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
     * With proper PTY, this sends the actual interrupt signal.
     */
    public void sendCtrlC() {
        if (!isRunning || masterFd < 0) return;
        // Send ETX (Ctrl+C) byte through PTY
        byte[] ctrlC = {3};
        nativeWriteFd(masterFd, ctrlC, 0, 1);
    }

    /**
     * Send Ctrl+D (EOF) to the shell process.
     */
    public void sendCtrlD() {
        if (!isRunning || masterFd < 0) return;
        byte[] ctrlD = {4};
        nativeWriteFd(masterFd, ctrlD, 0, 1);
    }

    /**
     * Send Ctrl+Z (SIGTSTP) to the shell process.
     */
    public void sendCtrlZ() {
        if (!isRunning || masterFd < 0) return;
        byte[] ctrlZ = {26};
        nativeWriteFd(masterFd, ctrlZ, 0, 1);
    }

    /**
     * Send an escape sequence through the PTY.
     */
    public void sendEscapeSequence(String sequence) {
        if (!isRunning || masterFd < 0) return;
        write("\033" + sequence);
    }

    /**
     * Send arrow key input.
     */
    public void sendArrowKey(int direction) {
        // ESC [ A/B/C/D = up/down/right/left
        char code = (char) ('A' + direction);
        byte[] seq = {27, '[', (byte) code}; // ESC [ A/B/C/D
        nativeWriteFd(masterFd, seq, 0, 3);
    }

    /** Arrow key directions */
    public static final int ARROW_UP = 0;
    public static final int ARROW_DOWN = 1;
    public static final int ARROW_RIGHT = 2;
    public static final int ARROW_LEFT = 3;

    /**
     * Send special key through PTY.
     */
    public void sendKey(String key) {
        if (!isRunning || masterFd < 0) return;

        switch (key) {
            case "ESC":
                byte[] esc = {27};
                nativeWriteFd(masterFd, esc, 0, 1);
                break;
            case "TAB":
                byte[] tab = {9};
                nativeWriteFd(masterFd, tab, 0, 1);
                break;
            case "ENTER":
                byte[] enter = {13};
                nativeWriteFd(masterFd, enter, 0, 1);
                break;
            case "BACKSPACE":
                byte[] bs = {127};
                nativeWriteFd(masterFd, bs, 0, 1);
                break;
            case "HOME":
                write("\033[H");
                break;
            case "END":
                write("\033[F");
                break;
            case "DEL":
                write("\033[3~");
                break;
            case "PGUP":
                write("\033[5~");
                break;
            case "PGDN":
                write("\033[6~");
                break;
        }
    }

    /**
     * Change the working directory of the shell.
     */
    public void changeDirectory(String path) {
        executeCommand("cd " + escapeShellArg(path));
    }

    /**
     * Update the terminal window size.
     * Must be called when the terminal view dimensions change.
     */
    public void setTerminalSize(int rows, int cols) {
        this.terminalRows = rows;
        this.terminalCols = cols;
        if (masterFd >= 0) {
            nativeSetPtyWindowSize(masterFd, rows, cols);
        }
    }

    /**
     * Check if the session is currently running.
     */
    public boolean isRunning() {
        return isRunning;
    }

    /**
     * Get the process ID.
     */
    public int getProcessPid() {
        return processPid;
    }

    /**
     * Destroy the session and clean up resources.
     */
    public void destroy() {
        if (isDestroyed) return;
        isDestroyed = true;
        isRunning = false;

        // Close the PTY master fd
        if (masterFd >= 0) {
            nativeCloseFd(masterFd);
            masterFd = -1;
        }

        // Kill the process if still running
        if (processPid > 0) {
            nativeSendSignal(processPid, 9); // SIGKILL
            processPid = -1;
        }

        ioExecutor.shutdownNow();
        Log.d(TAG, "PTY session destroyed");
    }

    // =====================================================================
    // Private: Background Threads
    // =====================================================================

    /**
     * Read output from the PTY master fd in a background thread.
     * This is the main output loop - it reads bytes from the PTY and
     * delivers them to the callback for rendering in the WebView.
     */
    private void readPtyOutput() {
        byte[] buffer = new byte[4096];

        while (!isDestroyed && isRunning) {
            try {
                int bytesRead = nativeReadFd(masterFd, buffer, 0, buffer.length);

                if (bytesRead < 0) {
                    // EOF or error - session ended
                    Log.d(TAG, "PTY read returned " + bytesRead + " - session ended");
                    isRunning = false;
                    break;
                }

                if (bytesRead > 0) {
                    // Copy the data and deliver to callback
                    byte[] data = new byte[bytesRead];
                    System.arraycopy(buffer, 0, data, 0, bytesRead);
                    notifyOutput(data, bytesRead);
                } else {
                    // No data available (non-blocking) - sleep briefly
                    Thread.sleep(10);
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                if (!isDestroyed) {
                    Log.e(TAG, "PTY read error", e);
                }
                break;
            }
        }
    }

    /**
     * Monitor the child process for exit.
     */
    private void monitorProcess() {
        try {
            // Wait a bit for the process to start
            Thread.sleep(500);

            while (!isDestroyed && isRunning) {
                if (processPid > 0) {
                    // Try non-blocking wait
                    int exitCode = nativeWaitFor(processPid);
                    if (exitCode >= 0 || exitCode == -9) {
                        // Process has exited
                        isRunning = false;
                        Log.d(TAG, "Process exited with code: " + exitCode);
                        notifyExit(exitCode);
                        break;
                    }
                }
                Thread.sleep(500);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // =====================================================================
    // Private: Helper Methods
    // =====================================================================

    /**
     * Find an available shell binary.
     */
    private String findShell() {
        // Check for bash first (better shell experience)
        String[] preferredShells = {
                "/system/bin/bash",
                "/bin/bash",
                "/system/bin/sh",
                "/bin/sh"
        };

        for (String shell : preferredShells) {
            File f = new File(shell);
            if (f.exists() && f.canExecute()) {
                return shell;
            }
        }

        // Fallback
        return "/system/bin/sh";
    }

    /**
     * Build the environment variables for the shell process.
     */
    private String[] buildEnvironment() {
        List<String> envList = new ArrayList<>();

        // Essential terminal environment (like Termux)
        envList.add("TERM=xterm-256color");
        envList.add("TERM_PROGRAM=CodeEditor-Terminal");
        envList.add("COLORTERM=truecolor");
        envList.add("LANG=en_US.UTF-8");
        envList.add("LC_ALL=en_US.UTF-8");

        // Home directory
        String home = workingDirectory;
        if (home == null) {
            home = System.getenv("HOME");
            if (home == null) home = "/data/data/com.codeeditor.app";
        }
        envList.add("HOME=" + home);

        // PATH - include system paths and our app's bin directory
        String systemPath = System.getenv("PATH");
        String appBinPath = "/data/data/com.codeeditor.app/files/usr/bin";
        String path = appBinPath + ":" +
                "/system/bin:/system/xbin:/usr/bin:/usr/local/bin";
        if (systemPath != null) {
            path = appBinPath + ":" + systemPath;
        }
        envList.add("PATH=" + path);

        // Shell
        envList.add("SHELL=/system/bin/sh");

        // User
        envList.add("USER=root");
        envList.add("LOGNAME=root");

        // Android-specific
        envList.add("ANDROID_ROOT=" + System.getenv("ANDROID_ROOT"));
        envList.add("ANDROID_DATA=" + System.getenv("ANDROID_DATA"));

        // TMPDIR
        String tmpDir = System.getenv("TMPDIR");
        if (tmpDir == null) {
            tmpDir = home + "/tmp";
            new File(tmpDir).mkdirs();
        }
        envList.add("TMPDIR=" + tmpDir);

        // Add custom environment variables
        if (environment != null) {
            for (String envVar : environment) {
                // Override existing vars if specified
                String key = envVar.contains("=") ? envVar.substring(0, envVar.indexOf('=')) : "";
                envList.removeIf(e -> e.startsWith(key + "="));
                envList.add(envVar);
            }
        }

        return envList.toArray(new String[0]);
    }

    /**
     * Escape a shell argument by wrapping in single quotes.
     */
    private String escapeShellArg(String arg) {
        if (arg == null) return "''";
        return "'" + arg.replace("'", "'\\''") + "'";
    }

    // =====================================================================
    // Private: Notification Helpers
    // =====================================================================

    private void notifyOutput(byte[] data, int length) {
        if (callback != null) {
            mainHandler.post(() -> callback.onOutput(data, length));
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
}
