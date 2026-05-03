package com.codeeditor.app.terminal;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ProotSession - Manages an interactive Linux shell session using proot
 * with proper PTY support (like Termux).
 *
 * This version uses forkpty() via JNI to create a proper pseudo-terminal,
 * which fixes the "can't find tty fd" error that occurred with the old
 * ProcessBuilder approach.
 *
 * With proper PTY support:
 * - proot gets a real TTY (no "can't find tty fd" errors)
 * - Full ANSI color support from the Linux shell
 * - Tab completion, arrow keys work properly
 * - Ctrl+C/D/Z send real terminal signals
 * - Interactive programs (vim, nano, htop) work correctly
 */
public class ProotSession {

    private static final String TAG = "ProotSession";

    // Load PTY support library (same as TerminalSession)
    static {
        System.loadLibrary("pty_support");
    }

    // Native PTY methods (shared with TerminalSession)
    private static native int nativeCreateSubprocess(
            String cmd, String cwd, String[] args, String[] envVars, int[] processId);
    private static native void nativeSetPtyWindowSize(int fd, int rows, int cols);
    private static native int nativeWaitFor(int pid);
    private static native void nativeCloseFd(int fd);
    private static native void nativeSendSignal(int pid, int sig);
    private static native int nativeReadFd(int fd, byte[] buffer, int offset, int length);
    private static native int nativeWriteFd(int fd, byte[] buffer, int offset, int length);

    // Session state
    private int masterFd = -1;
    private int processPid = -1;
    private volatile boolean isRunning = false;
    private volatile boolean isDestroyed = false;

    private final SessionCallback callback;
    private final UbuntuManager ubuntuManager;
    private final String distro;
    private final ExecutorService ioExecutor;
    private final Handler mainHandler;

    private int terminalRows = 24;
    private int terminalCols = 80;

    /**
     * Callback interface for session events.
     */
    public interface SessionCallback {
        /** Called when new output bytes are available from the proot shell */
        void onOutput(byte[] data, int length);

        /** Called when the shell process exits */
        void onSessionExit(int exitCode);

        /** Called when there is an error with the session */
        void onError(String error);
    }

    /**
     * Create a new proot session with PTY support.
     *
     * @param ubuntuManager The UbuntuManager instance
     * @param distro        The distribution to run (ubuntu, debian, alpine, udroid)
     * @param callback      Callback for output and events
     */
    public ProotSession(UbuntuManager ubuntuManager, String distro, SessionCallback callback) {
        this.ubuntuManager = ubuntuManager;
        this.distro = distro;
        this.callback = callback;
        this.ioExecutor = Executors.newFixedThreadPool(2);
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    /**
     * Start the proot session with proper PTY support.
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
            String[] environment = buildProotEnvironment();

            Log.d(TAG, "Starting proot PTY session:");
            Log.d(TAG, "  Command: " + String.join(" ", command));
            Log.d(TAG, "  Rootfs: " + ubuntuManager.getRootfsDir().getAbsolutePath());

            // Create PTY subprocess via JNI (like Termux)
            int[] pidOut = new int[1];
            String cmd = command[0];

            // Build args array (everything after the command)
            String[] args = new String[command.length - 1];
            System.arraycopy(command, 1, args, 0, args.length);

            masterFd = nativeCreateSubprocess(cmd, null, args, environment, pidOut);

            if (masterFd < 0) {
                // PTY creation failed - try fallback with simpler command
                Log.w(TAG, "PTY creation failed, trying fallback proot command");
                startFallbackSession();
                return;
            }

            processPid = pidOut[0];
            isRunning = true;

            Log.d(TAG, "Proot PTY session started: pid=" + processPid + ", masterFd=" + masterFd);

            // Set initial window size
            nativeSetPtyWindowSize(masterFd, terminalRows, terminalCols);

            // Start reading from PTY
            ioExecutor.execute(this::readPtyOutput);

            // Monitor process exit
            ioExecutor.execute(this::monitorProcess);

        } catch (Exception e) {
            Log.e(TAG, "Failed to start proot PTY session", e);
            notifyError("Failed to start proot: " + e.getMessage());
        }
    }

    /**
     * Fallback session with simpler proot invocation.
     */
    private void startFallbackSession() {
        try {
            String prootPath = ubuntuManager.getProotBin().getAbsolutePath();
            String rootfsPath = ubuntuManager.getRootfsDir().getAbsolutePath();

            // Simple proot command
            String cmd = prootPath;
            String[] args = {
                    "-r", rootfsPath,
                    "-b", "/dev",
                    "-b", "/proc",
                    "-b", "/sys",
                    "-b", "/sdcard",
                    "/bin/sh"
            };

            String[] env = {
                    "HOME=/root",
                    "USER=root",
                    "TERM=xterm-256color",
                    "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                    "PROOT_NO_SECCOMP=1",
                    "LANG=en_US.UTF-8"
            };

            int[] pidOut = new int[1];
            masterFd = nativeCreateSubprocess(cmd, null, args, env, pidOut);

            if (masterFd < 0) {
                notifyError("Failed to create PTY for proot session. masterFd=" + masterFd);
                return;
            }

            processPid = pidOut[0];
            isRunning = true;

            Log.d(TAG, "Fallback proot PTY session started: pid=" + processPid);

            nativeSetPtyWindowSize(masterFd, terminalRows, terminalCols);
            ioExecutor.execute(this::readPtyOutput);
            ioExecutor.execute(this::monitorProcess);

        } catch (Exception e) {
            Log.e(TAG, "Fallback proot session also failed", e);
            notifyError("Failed to start proot: " + e.getMessage());
        }
    }

    /**
     * Start a udroid session with proper PTY support.
     */
    public void startUdroidSession() {
        if (isRunning || isDestroyed) return;

        if (!ubuntuManager.isUdroidInstalled()) {
            notifyError("udroid not installed. Please install it first.");
            return;
        }

        if (!ubuntuManager.isProotAvailable()) {
            notifyError("proot binary not available.");
            return;
        }

        try {
            String[] command = ubuntuManager.buildUdroidLoginCommand();
            String[] environment = buildProotEnvironment();

            // Add udroid-specific environment
            List<String> envList = new ArrayList<>();
            for (String e : environment) envList.add(e);
            envList.add("DISPLAY=:0");
            envList.add("PULSE_SERVER=tcp:127.0.0.1:4713");
            envList.add("XDG_RUNTIME_DIR=/tmp/runtime-root");

            String cmd = command[0];
            String[] args = new String[command.length - 1];
            System.arraycopy(command, 1, args, 0, args.length);

            int[] pidOut = new int[1];
            masterFd = nativeCreateSubprocess(cmd, null, args,
                    envList.toArray(new String[0]), pidOut);

            if (masterFd < 0) {
                notifyError("Failed to create PTY for udroid session");
                return;
            }

            processPid = pidOut[0];
            isRunning = true;

            Log.d(TAG, "udroid PTY session started: pid=" + processPid);

            nativeSetPtyWindowSize(masterFd, terminalRows, terminalCols);
            ioExecutor.execute(this::readPtyOutput);
            ioExecutor.execute(this::monitorProcess);

        } catch (Exception e) {
            Log.e(TAG, "Failed to start udroid session", e);
            notifyError("Failed to start udroid: " + e.getMessage());
        }
    }

    /**
     * Write a string to the proot shell via PTY.
     */
    public void write(String command) {
        if (!isRunning || masterFd < 0) return;

        try {
            byte[] bytes = command.getBytes(StandardCharsets.UTF_8);
            nativeWriteFd(masterFd, bytes, 0, bytes.length);
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
     * Send Ctrl+C through the PTY.
     */
    public void sendCtrlC() {
        if (!isRunning || masterFd < 0) return;
        byte[] ctrlC = {3};
        nativeWriteFd(masterFd, ctrlC, 0, 1);
    }

    /**
     * Send Ctrl+D through the PTY.
     */
    public void sendCtrlD() {
        if (!isRunning || masterFd < 0) return;
        byte[] ctrlD = {4};
        nativeWriteFd(masterFd, ctrlD, 0, 1);
    }

    /**
     * Send Ctrl+Z through the PTY.
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
        write("\033" + sequence);
    }

    /**
     * Update terminal size.
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
     * Destroy the session and clean up resources.
     */
    public void destroy() {
        if (isDestroyed) return;
        isDestroyed = true;
        isRunning = false;

        if (masterFd >= 0) {
            nativeCloseFd(masterFd);
            masterFd = -1;
        }

        if (processPid > 0) {
            nativeSendSignal(processPid, 9);
            processPid = -1;
        }

        ioExecutor.shutdownNow();
        Log.d(TAG, "Proot PTY session destroyed");
    }

    // =====================================================================
    // Private: Background Threads
    // =====================================================================

    private void readPtyOutput() {
        byte[] buffer = new byte[4096];

        while (!isDestroyed && isRunning) {
            try {
                int bytesRead = nativeReadFd(masterFd, buffer, 0, buffer.length);

                if (bytesRead < 0) {
                    Log.d(TAG, "PTY read returned " + bytesRead + " - session ended");
                    isRunning = false;
                    break;
                }

                if (bytesRead > 0) {
                    byte[] data = new byte[bytesRead];
                    System.arraycopy(buffer, 0, data, 0, bytesRead);
                    notifyOutput(data, bytesRead);
                } else {
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

    private void monitorProcess() {
        try {
            Thread.sleep(500);
            while (!isDestroyed && isRunning) {
                if (processPid > 0) {
                    int exitCode = nativeWaitFor(processPid);
                    if (exitCode >= 0 || exitCode == -9) {
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
    // Private: Helpers
    // =====================================================================

    private String[] buildProotEnvironment() {
        List<String> envList = new ArrayList<>();

        envList.add("HOME=/root");
        envList.add("USER=root");
        envList.add("LOGNAME=root");
        envList.add("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        envList.add("TERM=xterm-256color");
        envList.add("COLORTERM=truecolor");
        envList.add("LANG=en_US.UTF-8");
        envList.add("LC_ALL=en_US.UTF-8");
        envList.add("SHELL=/bin/bash");
        envList.add("PROOT_NO_SECCOMP=1");
        envList.add("HOSTNAME=codeeditor");
        envList.add("TMPDIR=/tmp");
        envList.add("DISPLAY=:0");
        envList.add("LINUX_ROOTFS=" + ubuntuManager.getRootfsDir().getAbsolutePath());

        String systemPath = System.getenv("PATH");
        if (systemPath != null) {
            envList.add("SYSTEM_PATH=" + systemPath);
        }

        return envList.toArray(new String[0]);
    }

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
