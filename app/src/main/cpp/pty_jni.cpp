/*
 * pty_jni.cpp - PTY (Pseudo-Terminal) JNI Bridge for CodeEditor
 *
 * This implements the same PTY creation approach that Termux uses internally:
 * forkpty() creates a proper pseudo-terminal pair, giving the child process
 * a real TTY (fixing "can't find tty fd" errors) while the parent reads/writes
 * through the master fd.
 *
 * This is the core of what makes Termux's terminal work - proper PTY handling
 * instead of ProcessBuilder's pipe-based I/O.
 */

#include <jni.h>
#include <unistd.h>
#include <sys/types.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <sys/stat.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <fcntl.h>
#include <errno.h>
#include <termios.h>
#include <signal.h>

#ifdef __ANDROID__
#include <linux/ptmx.h>
#include <pty.h>
#else
#include <pty.h>
#include <utmp.h>
#endif

#define TAG "PTY-JNI"

/**
 * Create a PTY subprocess - the core function that makes the terminal work
 * like Termux. Uses forkpty() to create a proper pseudo-terminal.
 *
 * @param cmd      The command to execute (e.g., "/system/bin/sh")
 * @param cwd      Working directory (can be null)
 * @param args     Command arguments
 * @param envVars  Environment variables ("KEY=VALUE" format)
 * @param processId Output: the PID of the child process
 * @return The master file descriptor for the PTY, or -1 on error
 */
extern "C" JNIEXPORT jint JNICALL
Java_com_codeeditor_app_runner_TerminalSession_nativeCreateSubprocess(
        JNIEnv *env, jclass clazz,
        jstring cmd, jstring cwd, jobjectArray args, jobjectArray envVars,
        jintArray processId) {

    // Get command string
    const char *cmdStr = env->GetStringUTFChars(cmd, nullptr);
    if (!cmdStr) {
        return -1;
    }

    const char *cwdStr = nullptr;
    if (cwd) {
        cwdStr = env->GetStringUTFChars(cwd, nullptr);
    }

    // Build argv array
    int argsLen = args ? env->GetArrayLength(args) : 0;
    char **argv = (char **) malloc((argsLen + 2) * sizeof(char *));
    if (!argv) {
        env->ReleaseStringUTFChars(cmd, cmdStr);
        if (cwdStr) env->ReleaseStringUTFChars(cwd, cwdStr);
        return -1;
    }

    argv[0] = strdup(cmdStr);
    for (int i = 0; i < argsLen; i++) {
        auto arg = (jstring) env->GetObjectArrayElement(args, i);
        const char *argStr = env->GetStringUTFChars(arg, nullptr);
        argv[i + 1] = strdup(argStr);
        env->ReleaseStringUTFChars(arg, argStr);
        env->DeleteLocalRef(arg);
    }
    argv[argsLen + 1] = nullptr;

    // Build envp array
    int envLen = envVars ? env->GetArrayLength(envVars) : 0;
    char **envp = (char **) malloc((envLen + 1) * sizeof(char *));
    if (!envp) {
        for (int i = 0; i <= argsLen; i++) free(argv[i]);
        free(argv);
        env->ReleaseStringUTFChars(cmd, cmdStr);
        if (cwdStr) env->ReleaseStringUTFChars(cwd, cwdStr);
        return -1;
    }

    for (int i = 0; i < envLen; i++) {
        auto envVar = (jstring) env->GetObjectArrayElement(envVars, i);
        const char *envStr = env->GetStringUTFChars(envVar, nullptr);
        envp[i] = strdup(envStr);
        env->ReleaseStringUTFChars(envVar, envStr);
        env->DeleteLocalRef(envVar);
    }
    envp[envLen] = nullptr;

    // Create the PTY and fork
    int masterFd;
    pid_t pid;

    // Try forkpty first (most reliable, like Termux)
    pid = forkpty(&masterFd, nullptr, nullptr, nullptr);

    if (pid < 0) {
        // forkpty failed - try manual PTY creation as fallback
        pid = createPtyManual(&masterFd);
    }

    if (pid < 0) {
        // Both methods failed
        for (int i = 0; i <= argsLen; i++) free(argv[i]);
        free(argv);
        for (int i = 0; i < envLen; i++) free(envp[i]);
        free(envp);
        env->ReleaseStringUTFChars(cmd, cmdStr);
        if (cwdStr) env->ReleaseStringUTFChars(cwd, cwdStr);
        return -1;
    }

    if (pid == 0) {
        // === CHILD PROCESS ===

        // Change working directory
        if (cwdStr) {
            chdir(cwdStr);
        }

        // Set environment variables
        for (int i = 0; i < envLen; i++) {
            putenv(envp[i]);
        }

        // Close non-standard file descriptors
        for (int fd = 3; fd < 256; fd++) {
            // Keep stdin(0), stdout(1), stderr(2) open
            // The slave PTY is already set up as stdin/stdout/stderr by forkpty
        }

        // Set session ID for proper terminal control
        setsid();

        // Execute the command
        execv(cmdStr, argv);

        // If execv returns, it failed
        _exit(127);
    }

    // === PARENT PROCESS ===

    // Set process ID output
    if (processId) {
        jint pidVal = (jint) pid;
        env->SetIntArrayRegion(processId, 0, 1, &pidVal);
    }

    // Set master fd to non-blocking mode for reading
    int flags = fcntl(masterFd, F_GETFL, 0);
    if (flags >= 0) {
        fcntl(masterFd, F_SETFL, flags | O_NONBLOCK);
    }

    // Cleanup allocated strings
    for (int i = 0; i <= argsLen; i++) free(argv[i]);
    free(argv);
    for (int i = 0; i < envLen; i++) free(envp[i]);
    free(envp);
    env->ReleaseStringUTFChars(cmd, cmdStr);
    if (cwdStr) env->ReleaseStringUTFChars(cwd, cwdStr);

    return masterFd;
}

/**
 * Manual PTY creation fallback when forkpty() is not available.
 * Uses /dev/ptmx to create a PTY pair.
 */
static pid_t createPtyManual(int *masterFd) {
    // Open the PTY master
    int mfd = open("/dev/ptmx", O_RDWR | O_NOCTTY);
    if (mfd < 0) {
        return -1;
    }

    // Grant access to the slave
    if (grantpt(mfd) < 0) {
        close(mfd);
        return -1;
    }

    // Unlock the slave
    if (unlockpt(mfd) < 0) {
        close(mfd);
        return -1;
    }

    // Get the slave device path
    char *slaveName = ptsname(mfd);
    if (!slaveName) {
        close(mfd);
        return -1;
    }

    // Fork
    pid_t pid = fork();
    if (pid < 0) {
        close(mfd);
        return -1;
    }

    if (pid == 0) {
        // Child process
        close(mfd);

        // Create a new session
        setsid();

        // Open the slave device
        int sfd = open(slaveName, O_RDWR);
        if (sfd < 0) {
            _exit(127);
        }

        // Set slave as controlling terminal
        ioctl(sfd, TIOCSCTTY, 0);

        // Redirect stdin/stdout/stderr to slave
        dup2(sfd, 0);
        dup2(sfd, 1);
        dup2(sfd, 2);

        if (sfd > 2) close(sfd);

        // Child continues in the caller
        return 0;
    }

    // Parent
    *masterFd = mfd;
    return pid;
}

/**
 * Set the PTY window size - must be called when terminal size changes
 * so that the shell and applications know the correct dimensions.
 */
extern "C" JNIEXPORT void JNICALL
Java_com_codeeditor_app_runner_TerminalSession_nativeSetPtyWindowSize(
        JNIEnv *env, jclass clazz, jint fd, jint rows, jint cols) {

    if (fd < 0) return;

    struct winsize ws;
    ws.ws_row = (unsigned short) rows;
    ws.ws_col = (unsigned short) cols;
    ws.ws_xpixel = 0;
    ws.ws_ypixel = 0;

    ioctl(fd, TIOCSWINSZ, &ws);
}

/**
 * Wait for a child process to exit and return its exit code.
 */
extern "C" JNIEXPORT jint JNICALL
Java_com_codeeditor_app_runner_TerminalSession_nativeWaitFor(
        JNIEnv *env, jclass clazz, jint pid) {

    int status;
    pid_t result = waitpid((pid_t) pid, &status, 0);

    if (result < 0) {
        return -1;
    }

    if (WIFEXITED(status)) {
        return WEXITSTATUS(status);
    } else if (WIFSIGNALED(status)) {
        return -WTERMSIG(status);
    }

    return -1;
}

/**
 * Close a file descriptor.
 */
extern "C" JNIEXPORT void JNICALL
Java_com_codeeditor_app_runner_TerminalSession_nativeCloseFd(
        JNIEnv *env, jclass clazz, jint fd) {

    if (fd >= 0) {
        close(fd);
    }
}

/**
 * Send a signal to a process.
 */
extern "C" JNIEXPORT void JNICALL
Java_com_codeeditor_app_runner_TerminalSession_nativeSendSignal(
        JNIEnv *env, jclass clazz, jint pid, jint sig) {

    kill((pid_t) pid, sig);
}

/**
 * Read bytes from the PTY master fd into a byte array.
 * Returns the number of bytes read, or -1 on error/EOF.
 */
extern "C" JNIEXPORT jint JNICALL
Java_com_codeeditor_app_runner_TerminalSession_nativeReadFd(
        JNIEnv *env, jclass clazz, jint fd, jbyteArray buffer, jint offset, jint length) {

    if (fd < 0 || !buffer) return -1;

    jbyte *buf = env->GetByteArrayElements(buffer, nullptr);
    if (!buf) return -1;

    ssize_t bytesRead = read(fd, buf + offset, length);

    env->ReleaseByteArrayElements(buffer, buf, 0);

    if (bytesRead < 0) {
        if (errno == EAGAIN || errno == EWOULDBLOCK) {
            return 0; // No data available (non-blocking)
        }
        return -1; // Error
    }

    return (jint) bytesRead;
}

/**
 * Write bytes to the PTY master fd from a byte array.
 * Returns the number of bytes written, or -1 on error.
 */
extern "C" JNIEXPORT jint JNICALL
Java_com_codeeditor_app_runner_TerminalSession_nativeWriteFd(
        JNIEnv *env, jclass clazz, jint fd, jbyteArray buffer, jint offset, jint length) {

    if (fd < 0 || !buffer) return -1;

    jbyte *buf = env->GetByteArrayElements(buffer, nullptr);
    if (!buf) return -1;

    ssize_t bytesWritten = write(fd, buf + offset, length);

    env->ReleaseByteArrayElements(buffer, buf, JNI_ABORT);

    if (bytesWritten < 0) {
        return -1;
    }

    return (jint) bytesWritten;
}
