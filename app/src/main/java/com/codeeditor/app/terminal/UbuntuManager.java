package com.codeeditor.app.terminal;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;
import java.util.zip.TarEntry;
import java.util.zip.TarInputStream;

/**
 * UbuntuManager - Manages Ubuntu/Debian Linux distribution installation
 * and lifecycle on Android using proot (similar to fs-manager-udroid).
 *
 * This class handles:
 * - Downloading Ubuntu rootfs from official mirrors
 * - Extracting rootfs into app-private directory
 * - Setting up proot environment (DNS, resolv.conf, etc.)
 * - Configuring apt sources and initial packages
 * - Managing installed distributions
 * - Starting/stopping proot sessions
 *
 * No root access required - uses proot for syscall translation.
 */
public class UbuntuManager {

    private static final String TAG = "UbuntuManager";

    // Distro definitions
    public static final String DISTRO_UBUNTU = "ubuntu";
    public static final String DISTRO_DEBIAN = "debian";
    public static final String DISTRO_ALPINE = "alpine";

    // Ubuntu rootfs URLs (official Ubuntu cloud-images / minbase)
    private static final String UBUNTU_ROOTFS_URL =
            "https://cloud-images.ubuntu.com/minimal/releases/noble/release/ubuntu-24.04-minimal-cloudimg-arm64-root.tar.xz";
    private static final String UBUNTU_ROOTFS_URL_ARM =
            "https://cloud-images.ubuntu.com/minimal/releases/noble/release/ubuntu-24.04-minimal-cloudimg-armhf-root.tar.xz";
    private static final String UBUNTU_ROOTFS_URL_X86 =
            "https://cloud-images.ubuntu.com/minimal/releases/noble/release/ubuntu-24.04-minimal-cloudimg-amd64-root.tar.xz";

    // Debian rootfs URLs
    private static final String DEBIAN_ROOTFS_URL =
            "https://github.com/RandomCoderOrg/ubuntu-on-android/releases/download/v3.0.0/debian-rootfs-arm64.tar.xz";

    // Alpine rootfs URLs (lightweight alternative)
    private static final String ALPINE_ROOTFS_URL =
            "https://dl-cdn.alpinelinux.org/alpine/v3.20/releases/aarch64/alpine-minirootfs-3.20.0-aarch64.tar.gz";
    private static final String ALPINE_ROOTFS_URL_ARM =
            "https://dl-cdn.alpinelinux.org/alpine/v3.20/releases/armhf/alpine-minirootfs-3.20.0-armhf.tar.gz";
    private static final String ALPINE_ROOTFS_URL_X86 =
            "https://dl-cdn.alpinelinux.org/alpine/v3.20/releases/x86_64/alpine-minirootfs-3.20.0-x86_64.tar.gz";

    // Proot binary source (from Termux packages)
    private static final String PROOT_URL_ARM64 =
            "https://packages.termux.dev/apt/termux-main/pool/main/p/proot/proot_5.4.0-1_arm64.deb";
    private static final String PROOT_URL_ARM =
            "https://packages.termux.dev/apt/termux-main/pool/main/p/proot/proot_5.4.0-1_arm.deb";
    private static final String PROOT_URL_X86 =
            "https://packages.termux.dev/apt/termux-main/pool/main/p/proot/proot_5.4.0-1_x86_64.deb";

    // We'll use a statically-compiled proot from a reliable source
    private static final String PROOT_STATIC_URL =
            "https://github.com/greentreeboy/proot-static-builds/releases/download/v5.4.0/proot-static-arm64";
    private static final String PROOT_STATIC_URL_ARM =
            "https://github.com/greentreeboy/proot-static-builds/releases/download/v5.4.0/proot-static-arm";
    private static final String PROOT_STATIC_URL_X86 =
            "https://github.com/greentreeboy/proot-static-builds/releases/download/v5.4.0/proot-static-amd64";

    private final Context context;
    private final File linuxDir;       // Base directory for all Linux files
    private final File rootfsDir;      // Root filesystem directory
    private final File prootBin;       // proot binary path
    private final ExecutorService executor;
    private final Handler mainHandler;

    private volatile boolean isInstalling = false;
    private volatile boolean isDownloadComplete = false;

    /**
     * Callback interface for installation progress and completion.
     */
    public interface InstallCallback {
        /** Called with progress update (0-100) */
        void onProgress(int progress, String message);

        /** Called when installation completes successfully */
        void onComplete();

        /** Called when installation fails */
        void onError(String error);
    }

    public UbuntuManager(Context context) {
        this.context = context.getApplicationContext();
        this.linuxDir = new File(context.getFilesDir(), "linux");
        this.rootfsDir = new File(linuxDir, "rootfs");
        this.prootBin = new File(linuxDir, "bin/proot");
        this.executor = Executors.newSingleThreadExecutor();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    /**
     * Get the root filesystem directory for the installed distro.
     */
    public File getRootfsDir() {
        return rootfsDir;
    }

    /**
     * Get the proot binary path.
     */
    public File getProotBin() {
        return prootBin;
    }

    /**
     * Get the base Linux directory.
     */
    public File getLinuxDir() {
        return linuxDir;
    }

    /**
     * Check if a Linux distribution is installed.
     */
    public boolean isDistroInstalled() {
        return rootfsDir.exists() &&
                new File(rootfsDir, "bin/sh").exists() &&
                new File(rootfsDir, "etc").exists();
    }

    /**
     * Check if proot binary is available.
     */
    public boolean isProotAvailable() {
        return prootBin.exists() && prootBin.canExecute();
    }

    /**
     * Check if currently installing.
     */
    public boolean isInstalling() {
        return isInstalling;
    }

    /**
     * Get the detected ABI for this device.
     */
    public String getDeviceAbi() {
        String abi = android.os.Build.SUPPORTED_ABIS[0];
        Log.d(TAG, "Device ABI: " + abi);
        return abi;
    }

    /**
     * Get the appropriate rootfs URL for the current device.
     */
    public String getRootfsUrl(String distro) {
        String abi = getDeviceAbi();
        boolean isArm64 = abi.equals("arm64-v8a");
        boolean isArm = abi.equals("armeabi-v7a");
        // x86_64 and others

        switch (distro) {
            case DISTRO_DEBIAN:
                return DEBIAN_ROOTFS_URL;
            case DISTRO_ALPINE:
                if (isArm64) return ALPINE_ROOTFS_URL;
                if (isArm) return ALPINE_ROOTFS_URL_ARM;
                return ALPINE_ROOTFS_URL_X86;
            case DISTRO_UBUNTU:
            default:
                if (isArm64) return UBUNTU_ROOTFS_URL;
                if (isArm) return UBUNTU_ROOTFS_URL_ARM;
                return UBUNTU_ROOTFS_URL_X86;
        }
    }

    /**
     * Get the appropriate proot binary URL for the current device.
     */
    public String getProotUrl() {
        String abi = getDeviceAbi();
        boolean isArm64 = abi.equals("arm64-v8a");
        boolean isArm = abi.equals("armeabi-v7a");

        if (isArm64) return PROOT_STATIC_URL;
        if (isArm) return PROOT_STATIC_URL_ARM;
        return PROOT_STATIC_URL_X86;
    }

    /**
     * Install a Linux distribution (download rootfs + proot, extract, configure).
     * This is a long-running operation - must be called from background thread
     * or use installDistroAsync().
     */
    public void installDistro(String distro, InstallCallback callback) {
        if (isInstalling) {
            notifyError(callback, "Installation already in progress");
            return;
        }

        isInstalling = true;
        try {
            // Step 1: Create directories
            notifyProgress(callback, 5, "Creating directories...");
            createDirectories();

            // Step 2: Download proot binary
            if (!isProotAvailable()) {
                notifyProgress(callback, 10, "Downloading proot...");
                downloadProot();
                notifyProgress(callback, 25, "Setting up proot...");
                setupProot();
            } else {
                notifyProgress(callback, 25, "Proot already available");
            }

            // Step 3: Download rootfs
            notifyProgress(callback, 30, "Downloading " + distro + " rootfs...");
            File rootfsArchive = downloadRootfs(distro, (progress, msg) -> {
                // Scale progress from 30-70
                int scaledProgress = 30 + (progress * 40 / 100);
                notifyProgress(callback, scaledProgress, msg);
            });

            if (rootfsArchive == null || !rootfsArchive.exists()) {
                notifyError(callback, "Failed to download rootfs");
                return;
            }

            // Step 4: Extract rootfs
            notifyProgress(callback, 70, "Extracting " + distro + " rootfs...");
            extractRootfs(rootfsArchive, (progress, msg) -> {
                // Scale progress from 70-90
                int scaledProgress = 70 + (progress * 20 / 100);
                notifyProgress(callback, scaledProgress, msg);
            });

            // Step 5: Configure the distro
            notifyProgress(callback, 90, "Configuring " + distro + "...");
            configureDistro(distro);

            // Step 6: Clean up
            notifyProgress(callback, 95, "Cleaning up...");
            rootfsArchive.delete();

            // Done
            notifyProgress(callback, 100, "Installation complete!");
            notifyComplete(callback);

        } catch (Exception e) {
            Log.e(TAG, "Installation failed", e);
            notifyError(callback, "Installation failed: " + e.getMessage());
        } finally {
            isInstalling = false;
        }
    }

    /**
     * Install a Linux distribution asynchronously.
     */
    public void installDistroAsync(String distro, InstallCallback callback) {
        executor.execute(() -> installDistro(distro, callback));
    }

    /**
     * Uninstall the Linux distribution.
     */
    public void uninstallDistro() {
        if (rootfsDir.exists()) {
            deleteRecursive(rootfsDir);
        }
    }

    /**
     * Build the proot command to start a Linux session.
     *
     * @param command Optional command to run (if null, starts interactive shell)
     * @return The proot command array
     */
    public String[] buildProotCommand(String command) {
        List<String> cmdList = new ArrayList<>();

        // proot binary
        cmdList.add(prootBin.getAbsolutePath());

        // Link2symlink (required for many Linux programs)
        cmdList.add("--link2symlink");

        // Root filesystem
        cmdList.add("-r");
        cmdList.add(rootfsDir.getAbsolutePath());

        // Bind mounts for Android system integration
        cmdList.add("-b");
        cmdList.add("/dev");
        cmdList.add("-b");
        cmdList.add("/proc");
        cmdList.add("-b");
        cmdList.add("/sys");
        cmdList.add("-b");
        cmdList.add("/sdcard");
        cmdList.add("-b");
        cmdList.add("/storage");

        // Bind app's working directory for code execution
        String workDir = com.codeeditor.app.CodeEditorApp.getWorkDirectory();
        if (workDir != null) {
            cmdList.add("-b");
            cmdList.add(workDir + ":/workspace");
        }

        // Bind /tmp
        cmdList.add("-b");
        cmdList.add(new File(linuxDir, "tmp").getAbsolutePath() + ":/tmp");

        // Change hostname
        cmdList.add("-h");
        cmdList.add("codeeditor");

        // Set kernel version for compatibility
        cmdList.add("-k");
        cmdList.add("5.4.0");

        // Change to home directory and run shell or command
        if (command != null && !command.isEmpty()) {
            cmdList.add("/bin/sh");
            cmdList.add("-c");
            cmdList.add(command);
        } else {
            cmdList.add("/bin/sh");
            cmdList.add("-l");
        }

        return cmdList.toArray(new String[0]);
    }

    /**
     * Build environment variables for proot session.
     */
    public String[] buildProotEnvironment() {
        List<String> envList = new ArrayList<>();

        envList.add("HOME=/root");
        envList.add("USER=root");
        envList.add("LOGNAME=root");
        envList.add("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        envList.add("TERM=xterm-256color");
        envList.add("LANG=en_US.UTF-8");
        envList.add("LC_ALL=en_US.UTF-8");
        envList.add("SHELL=/bin/sh");
        envList.add("PROOT_NO_SECCOMP=1");
        envList.add("HOSTNAME=codeeditor");
        envList.add("TMPDIR=/tmp");

        // Android-specific
        envList.add("ANDROID_ROOT=" + android.os.Build.VERSION.RELEASE);
        envList.add("LINUX_ROOTFS=" + rootfsDir.getAbsolutePath());

        // Inherit some system env vars
        String systemPath = System.getenv("PATH");
        if (systemPath != null) {
            envList.add("SYSTEM_PATH=" + systemPath);
        }

        return envList.toArray(new String[0]);
    }

    /**
     * Get the installed distro info.
     */
    public DistroInfo getInstalledDistroInfo() {
        if (!isDistroInstalled()) return null;

        DistroInfo info = new DistroInfo();
        info.name = "Linux";
        info.rootfsPath = rootfsDir.getAbsolutePath();

        // Try to detect distro from /etc/os-release
        File osRelease = new File(rootfsDir, "etc/os-release");
        if (osRelease.exists()) {
            try {
                String content = readFile(osRelease);
                if (content.contains("Ubuntu")) {
                    info.name = "Ubuntu";
                } else if (content.contains("Debian")) {
                    info.name = "Debian";
                } else if (content.contains("Alpine")) {
                    info.name = "Alpine";
                }

                // Extract version
                String[] lines = content.split("\n");
                for (String line : lines) {
                    if (line.startsWith("PRETTY_NAME=")) {
                        info.prettyName = line.substring("PRETTY_NAME=".length())
                                .replace("\"", "").trim();
                    } else if (line.startsWith("VERSION_ID=")) {
                        info.versionId = line.substring("VERSION_ID=".length())
                                .replace("\"", "").trim();
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to read os-release", e);
            }
        }

        return info;
    }

    // =====================================================================
    // Private: Installation Steps
    // =====================================================================

    private void createDirectories() {
        linuxDir.mkdirs();
        rootfsDir.mkdirs();
        new File(linuxDir, "bin").mkdirs();
        new File(linuxDir, "tmp").mkdirs();
        new File(linuxDir, "cache").mkdirs();
    }

    private void downloadProot() throws IOException {
        String prootUrl = getProotUrl();
        File prootFile = prootBin;

        Log.d(TAG, "Downloading proot from: " + prootUrl);

        // Try multiple sources for proot
        String[] prootUrls = {
                prootUrl,
                // Fallback: Try direct Termux repository
                "https://raw.githubusercontent.com/greentreeboy/proot-static-builds/main/proot-static-arm64",
                // Another fallback: Build from a known working source
        };

        IOException lastError = null;
        for (String url : prootUrls) {
            try {
                downloadFile(url, prootFile);
                if (prootFile.exists() && prootFile.length() > 1000) {
                    // Successfully downloaded
                    prootFile.setExecutable(true, false);
                    prootFile.setReadable(true, false);
                    Log.d(TAG, "Proot downloaded successfully: " + prootFile.length() + " bytes");
                    return;
                }
            } catch (IOException e) {
                lastError = e;
                Log.w(TAG, "Failed to download proot from: " + url, e);
            }
        }

        // If download fails, try to find proot on the system
        String[] systemProotPaths = {
                "/data/data/com.termux/files/usr/bin/proot",
                "/usr/bin/proot",
                "/system/bin/proot"
        };

        for (String path : systemProotPaths) {
            File f = new File(path);
            if (f.exists() && f.canExecute()) {
                // Copy to our bin directory
                copyFile(f, prootFile);
                prootFile.setExecutable(true, false);
                Log.d(TAG, "Using system proot from: " + path);
                return;
            }
        }

        // If all else fails, we'll use a bundled approach or error out
        if (lastError != null) {
            throw new IOException("Failed to download proot: " + lastError.getMessage() +
                    "\n\nPlease install Termux first, then try again.");
        }
    }

    private void setupProot() {
        if (prootBin.exists()) {
            prootBin.setExecutable(true, false);
            prootBin.setReadable(true, false);
            prootBin.setWritable(true, false);
        }
    }

    private File downloadRootfs(String distro, InstallCallback progressCallback) throws IOException {
        String rootfsUrl = getRootfsUrl(distro);
        String extension = rootfsUrl.endsWith(".tar.xz") ? ".tar.xz" :
                          rootfsUrl.endsWith(".tar.gz") ? ".tar.gz" :
                          rootfsUrl.endsWith(".tgz") ? ".tgz" : ".tar";
        File cacheDir = new File(linuxDir, "cache");
        cacheDir.mkdirs();
        File rootfsArchive = new File(cacheDir, distro + "-rootfs" + extension);

        // Check if already downloaded
        if (rootfsArchive.exists() && rootfsArchive.length() > 1000000) {
            Log.d(TAG, "Rootfs already cached: " + rootfsArchive.length() + " bytes");
            notifyProgress(progressCallback, 100, "Using cached rootfs");
            return rootfsArchive;
        }

        Log.d(TAG, "Downloading rootfs from: " + rootfsUrl);
        downloadFileWithProgress(rootfsUrl, rootfsArchive, progressCallback);

        return rootfsArchive;
    }

    private void extractRootfs(File archive, InstallCallback progressCallback) throws IOException {
        String name = archive.getName();
        Log.d(TAG, "Extracting rootfs: " + name);

        try {
            if (name.endsWith(".tar.gz") || name.endsWith(".tgz")) {
                extractTarGz(archive, rootfsDir, progressCallback);
            } else if (name.endsWith(".tar.xz")) {
                extractTarXz(archive, rootfsDir, progressCallback);
            } else if (name.endsWith(".tar")) {
                extractTar(archive, rootfsDir, progressCallback);
            } else {
                throw new IOException("Unknown archive format: " + name);
            }
        } catch (IOException e) {
            // Fallback: try using system tar command
            Log.w(TAG, "Java extraction failed, trying system tar", e);
            try {
                extractWithSystemTar(archive, rootfsDir);
            } catch (Exception e2) {
                throw new IOException("Both Java and system extraction failed: " +
                        e.getMessage() + " / " + e2.getMessage());
            }
        }
    }

    private void extractTarGz(File archive, File destDir, InstallCallback progressCallback) throws IOException {
        try (FileInputStream fis = new FileInputStream(archive);
             GZIPInputStream gis = new GZIPInputStream(fis);
             TarInputStream tis = new TarInputStream(gis)) {

            TarEntry entry;
            long totalSize = archive.length();
            long extractedSize = 0;
            int lastProgress = 0;

            while ((entry = tis.getNextEntry()) != null) {
                File outputFile = new File(destDir, entry.getName());

                // Security: prevent path traversal
                if (!outputFile.getCanonicalPath().startsWith(destDir.getCanonicalPath() + File.separator)) {
                    continue;
                }

                if (entry.isDirectory()) {
                    outputFile.mkdirs();
                } else {
                    outputFile.getParentFile().mkdirs();
                    try (OutputStream os = new FileOutputStream(outputFile)) {
                        byte[] buffer = new byte[8192];
                        int len;
                        while ((len = tis.read(buffer)) != -1) {
                            os.write(buffer, 0, len);
                        }
                    }
                    // Preserve executable permission
                    if ((entry.getMode() & 0100) != 0) {
                        outputFile.setExecutable(true, false);
                    }
                    outputFile.setReadable(true, false);
                }

                extractedSize += entry.getSize();
                int progress = (int) (extractedSize * 100 / totalSize);
                if (progress > lastProgress + 5) {
                    lastProgress = progress;
                    notifyProgress(progressCallback, Math.min(progress, 99),
                            "Extracting: " + entry.getName());
                }
            }
        }
    }

    private void extractTarXz(File archive, File destDir, InstallCallback progressCallback) throws IOException {
        // For .tar.xz files, use system tar command since Java doesn't have built-in XZ support
        // Try using the system's tar command
        try {
            extractWithSystemTar(archive, destDir);
        } catch (Exception e) {
            // If system tar doesn't support xz, try with xzcat pipe
            Log.w(TAG, "System tar failed for xz, trying xzcat pipeline", e);
            try {
                ProcessBuilder pb = new ProcessBuilder("sh", "-c",
                        "cat '" + archive.getAbsolutePath() + "' | xz -d | tar -x -C '" +
                                destDir.getAbsolutePath() + "'");
                pb.redirectErrorStream(true);
                Process p = pb.start();
                int exit = p.waitFor();
                if (exit != 0) {
                    // Read error output
                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(p.getInputStream()));
                    StringBuilder err = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        err.append(line).append("\n");
                    }
                    throw new IOException("xzcat+tar extraction failed: " + err.toString());
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IOException("Extraction interrupted", ie);
            }
        }
    }

    private void extractTar(File archive, File destDir, InstallCallback progressCallback) throws IOException {
        try (FileInputStream fis = new FileInputStream(archive);
             TarInputStream tis = new TarInputStream(fis)) {

            TarEntry entry;
            while ((entry = tis.getNextEntry()) != null) {
                File outputFile = new File(destDir, entry.getName());

                if (!outputFile.getCanonicalPath().startsWith(destDir.getCanonicalPath() + File.separator)) {
                    continue;
                }

                if (entry.isDirectory()) {
                    outputFile.mkdirs();
                } else {
                    outputFile.getParentFile().mkdirs();
                    try (OutputStream os = new FileOutputStream(outputFile)) {
                        byte[] buffer = new byte[8192];
                        int len;
                        while ((len = tis.read(buffer)) != -1) {
                            os.write(buffer, 0, len);
                        }
                    }
                    if ((entry.getMode() & 0100) != 0) {
                        outputFile.setExecutable(true, false);
                    }
                    outputFile.setReadable(true, false);
                }
            }
        }
    }

    private void extractWithSystemTar(File archive, File destDir) throws IOException {
        try {
            ProcessBuilder pb = new ProcessBuilder("tar", "-x", "-f",
                    archive.getAbsolutePath(), "-C", destDir.getAbsolutePath());
            pb.redirectErrorStream(true);
            Process p = pb.start();

            // Read output in background to prevent blocking
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                Log.d(TAG, "tar: " + line);
            }

            int exitCode = p.waitFor();
            if (exitCode != 0) {
                throw new IOException("tar extraction failed with exit code: " + exitCode);
            }
            Log.d(TAG, "System tar extraction completed successfully");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("tar extraction interrupted", e);
        }
    }

    private void configureDistro(String distro) throws IOException {
        // Set up DNS resolution
        File resolvConf = new File(rootfsDir, "etc/resolv.conf");
        resolvConf.getParentFile().mkdirs();
        writeFile(resolvConf,
                "nameserver 8.8.8.8\n" +
                "nameserver 8.8.4.4\n" +
                "nameserver 1.1.1.1\n");

        // Set up hosts file
        File hosts = new File(rootfsDir, "etc/hosts");
        writeFile(hosts,
                "127.0.0.1 localhost\n" +
                "127.0.1.1 codeeditor\n" +
                "::1       localhost\n");

        // Set up hostname
        File hostname = new File(rootfsDir, "etc/hostname");
        writeFile(hostname, "codeeditor\n");

        // Create profile.d script for environment setup
        File profileDir = new File(rootfsDir, "etc/profile.d");
        profileDir.mkdirs();

        File codeeditorSh = new File(profileDir, "codeeditor.sh");
        writeFile(codeeditorSh,
                "#!/bin/sh\n" +
                "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\n" +
                "export HOME=/root\n" +
                "export TERM=xterm-256color\n" +
                "export LANG=en_US.UTF-8\n" +
                "export HOSTNAME=codeeditor\n" +
                "export EDITOR=nano\n" +
                "export PAGER=less\n" +
                "\n" +
                "# Workspace mount point\n" +
                "if [ -d /workspace ]; then\n" +
                "  export WORKSPACE=/workspace\n" +
                "  cd /workspace 2>/dev/null || cd /root\n" +
                "fi\n" +
                "\n" +
                "# Set up colorful prompt\n" +
                "if [ -n \"$PS1\" ]; then\n" +
                "  export PS1='\\[\\e[1;32m\\]\\u@codeeditor\\[\\e[0m\\]:\\[\\e[1;34m\\]\\w\\[\\e[0m\\]\\$ '\n" +
                "fi\n");
        codeeditorSh.setExecutable(true, false);

        // Set up /root directory
        File rootHome = new File(rootfsDir, "root");
        rootHome.mkdirs();

        File bashrc = new File(rootHome, ".bashrc");
        writeFile(bashrc,
                "# ~/.bashrc: executed by bash(1) for non-login shells.\n" +
                "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\n" +
                "export TERM=xterm-256color\n" +
                "\n" +
                "# If not running interactively, don't do anything\n" +
                "case $- in\n" +
                "    *i*) ;;\n" +
                "      *) return;;\n" +
                "esac\n" +
                "\n" +
                "# Colorful prompt\n" +
                "PS1='\\[\\e[1;32m\\]\\u@codeeditor\\[\\e[0m\\]:\\[\\e[1;34m\\]\\w\\[\\e[0m\\]\\$ '\n" +
                "\n" +
                "# Aliases\n" +
                "alias ll='ls -alF'\n" +
                "alias la='ls -A'\n" +
                "alias l='ls -CF'\n" +
                "alias cls='clear'\n" +
                "\n" +
                "# Welcome message\n" +
                "echo ''\n" +
                "echo '  Welcome to CodeEditor Linux Terminal'\n" +
                "echo '  Workspace: /workspace (synced with app)'\n" +
                "echo '  Type \"help\" for available commands'\n" +
                "echo ''\n");

        // Set up /etc/passwd (if not exists)
        File passwd = new File(rootfsDir, "etc/passwd");
        if (!passwd.exists()) {
            writeFile(passwd,
                    "root:x:0:0:root:/root:/bin/sh\n" +
                    "nobody:x:65534:65534:nobody:/nonexistent:/usr/sbin/nologin\n");
        }

        // Set up /etc/group (if not exists)
        File group = new File(rootfsDir, "etc/group");
        if (!group.exists()) {
            writeFile(group,
                    "root:x:0:\n" +
                    "nogroup:x:65534:\n");
        }

        // Create workspace mount point
        File workspace = new File(rootfsDir, "workspace");
        workspace.mkdirs();

        // Create /tmp with proper permissions
        File tmp = new File(rootfsDir, "tmp");
        tmp.mkdirs();
        tmp.setWritable(true, false);
        tmp.setReadable(true, false);
        tmp.setExecutable(true, false);

        // Set up apt sources for Ubuntu/Debian
        if (DISTRO_UBUNTU.equals(distro)) {
            File aptSources = new File(rootfsDir, "etc/apt/sources.list");
            if (!aptSources.exists()) {
                writeFile(aptSources,
                        "deb http://ports.ubuntu.com/ubuntu-ports noble main restricted universe multiverse\n" +
                        "deb http://ports.ubuntu.com/ubuntu-ports noble-updates main restricted universe multiverse\n" +
                        "deb http://ports.ubuntu.com/ubuntu-ports noble-security main restricted universe multiverse\n");
            }
        } else if (DISTRO_DEBIAN.equals(distro)) {
            File aptSources = new File(rootfsDir, "etc/apt/sources.list");
            if (!aptSources.exists()) {
                writeFile(aptSources,
                        "deb http://deb.debian.org/debian bookworm main\n" +
                        "deb http://deb.debian.org/debian bookworm-updates main\n" +
                        "deb http://security.debian.org/debian-security bookworm-security main\n");
            }
        }

        // Make shells executable
        File shBin = new File(rootfsDir, "bin/sh");
        if (shBin.exists()) shBin.setExecutable(true, false);
        File bashBin = new File(rootfsDir, "bin/bash");
        if (bashBin.exists()) bashBin.setExecutable(true, false);

        Log.d(TAG, distro + " configuration completed");
    }

    // =====================================================================
    // Private: Download Utilities
    // =====================================================================

    private void downloadFile(String urlStr, File destFile) throws IOException {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(60000);
        conn.setRequestProperty("User-Agent", "CodeEditor/1.3.0");
        conn.setFollowRedirects(true);
        conn.setInstanceFollowRedirects(true);

        try {
            int responseCode = conn.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                // Handle redirects manually
                if (responseCode == HttpURLConnection.HTTP_MOVED_PERM ||
                    responseCode == HttpURLConnection.HTTP_MOVED_TEMP ||
                    responseCode == 307 || responseCode == 308) {
                    String newUrl = conn.getHeaderField("Location");
                    if (newUrl != null) {
                        conn.disconnect();
                        downloadFile(newUrl, destFile);
                        return;
                    }
                }
                throw new IOException("Server returned HTTP " + responseCode);
            }

            destFile.getParentFile().mkdirs();
            try (InputStream is = conn.getInputStream();
                 FileOutputStream fos = new FileOutputStream(destFile)) {
                byte[] buffer = new byte[8192];
                int len;
                while ((len = is.read(buffer)) != -1) {
                    fos.write(buffer, 0, len);
                }
            }
        } finally {
            conn.disconnect();
        }
    }

    private void downloadFileWithProgress(String urlStr, File destFile,
                                           InstallCallback progressCallback) throws IOException {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(60000);
        conn.setReadTimeout(120000);
        conn.setRequestProperty("User-Agent", "CodeEditor/1.3.0");
        conn.setFollowRedirects(true);
        conn.setInstanceFollowRedirects(true);

        try {
            int responseCode = conn.getResponseCode();
            // Handle redirects
            int redirects = 0;
            while ((responseCode == HttpURLConnection.HTTP_MOVED_PERM ||
                    responseCode == HttpURLConnection.HTTP_MOVED_TEMP ||
                    responseCode == 307 || responseCode == 308) && redirects < 5) {
                String newUrl = conn.getHeaderField("Location");
                conn.disconnect();
                if (newUrl == null) break;
                url = new URL(newUrl);
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(60000);
                conn.setReadTimeout(120000);
                conn.setRequestProperty("User-Agent", "CodeEditor/1.3.0");
                responseCode = conn.getResponseCode();
                redirects++;
            }

            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw new IOException("Server returned HTTP " + responseCode + " for URL: " + urlStr);
            }

            int fileSize = conn.getContentLength();
            destFile.getParentFile().mkdirs();

            try (InputStream is = conn.getInputStream();
                 FileOutputStream fos = new FileOutputStream(destFile)) {
                byte[] buffer = new byte[8192];
                long totalRead = 0;
                int len;
                int lastProgress = 0;

                while ((len = is.read(buffer)) != -1) {
                    fos.write(buffer, 0, len);
                    totalRead += len;

                    if (fileSize > 0) {
                        int progress = (int) (totalRead * 100 / fileSize);
                        if (progress > lastProgress + 2) {
                            lastProgress = progress;
                            String sizeStr = formatFileSize(totalRead) + " / " + formatFileSize(fileSize);
                            notifyProgress(progressCallback, progress, "Downloading: " + sizeStr);
                        }
                    } else {
                        // Unknown file size - show downloaded amount
                        int progress = (int) Math.min(totalRead / 100000, 95);
                        if (progress > lastProgress + 2) {
                            lastProgress = progress;
                            notifyProgress(progressCallback, progress,
                                    "Downloaded: " + formatFileSize(totalRead));
                        }
                    }
                }
            }

            Log.d(TAG, "Download complete: " + destFile.length() + " bytes");

        } finally {
            conn.disconnect();
        }
    }

    // =====================================================================
    // Private: File Utilities
    // =====================================================================

    private void writeFile(File file, String content) throws IOException {
        file.getParentFile().mkdirs();
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(content.getBytes("UTF-8"));
        }
    }

    private String readFile(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file);
             BufferedReader reader = new BufferedReader(new InputStreamReader(fis, "UTF-8"))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            return sb.toString();
        }
    }

    private void copyFile(File src, File dest) throws IOException {
        try (FileInputStream fis = new FileInputStream(src);
             FileOutputStream fos = new FileOutputStream(dest)) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = fis.read(buffer)) != -1) {
                fos.write(buffer, 0, len);
            }
        }
        dest.setExecutable(src.canExecute(), false);
        dest.setReadable(true, false);
    }

    private void deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        file.delete();
    }

    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    // =====================================================================
    // Private: Notification Helpers
    // =====================================================================

    private void notifyProgress(InstallCallback callback, int progress, String message) {
        if (callback != null) {
            mainHandler.post(() -> callback.onProgress(progress, message));
        }
    }

    private void notifyComplete(InstallCallback callback) {
        if (callback != null) {
            mainHandler.post(() -> callback.onComplete());
        }
    }

    private void notifyError(InstallCallback callback, String error) {
        if (callback != null) {
            mainHandler.post(() -> callback.onError(error));
        }
    }

    /**
     * Information about an installed distribution.
     */
    public static class DistroInfo {
        public String name;
        public String prettyName;
        public String versionId;
        public String rootfsPath;

        @Override
        public String toString() {
            return prettyName != null ? prettyName : name + " " + (versionId != null ? versionId : "");
        }
    }

    /**
     * Simple TarInputStream implementation for .tar files.
     * Handles basic tar format with POSIX headers.
     */
    private static class TarInputStream extends InputStream {
        private final InputStream is;
        private TarEntry currentEntry;
        private long remainingBytes;
        private boolean closed = false;

        public TarInputStream(InputStream is) {
            this.is = is;
        }

        public TarEntry getNextEntry() throws IOException {
            // Finish reading any remaining data from the current entry
            if (currentEntry != null && remainingBytes > 0) {
                byte[] skip = new byte[8192];
                while (remainingBytes > 0) {
                    int toRead = (int) Math.min(skip.length, remainingBytes);
                    int read = is.read(skip, 0, toRead);
                    if (read == -1) break;
                    remainingBytes -= read;
                }
                // Skip padding
                skipPadding();
            }

            // Read tar header (512 bytes)
            byte[] header = new byte[512];
            int totalRead = 0;
            while (totalRead < 512) {
                int read = is.read(header, totalRead, 512 - totalRead);
                if (read == -1) {
                    return null; // End of archive
                }
                totalRead += read;
            }

            // Check for end-of-archive marker (two zero blocks)
            boolean allZero = true;
            for (byte b : header) {
                if (b != 0) { allZero = false; break; }
            }
            if (allZero) return null;

            // Parse header
            currentEntry = parseTarHeader(header);
            remainingBytes = currentEntry.getSize();

            return currentEntry;
        }

        @Override
        public int read() throws IOException {
            if (remainingBytes <= 0) return -1;
            int b = is.read();
            if (b != -1) remainingBytes--;
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (remainingBytes <= 0) return -1;
            int toRead = (int) Math.min(len, remainingBytes);
            int read = is.read(b, off, toRead);
            if (read != -1) remainingBytes -= read;
            return read;
        }

        private void skipPadding() throws IOException {
            if (currentEntry == null) return;
            long size = currentEntry.getSize();
            int padding = (int) (512 - (size % 512)) % 512;
            while (padding > 0) {
                long skipped = is.skip(padding);
                if (skipped <= 0) break;
                padding -= skipped;
            }
        }

        private TarEntry parseTarHeader(byte[] header) {
            // Name: 0-100
            String name = readString(header, 0, 100);
            // Mode: 100-108 (octal)
            long mode = readOctal(header, 100, 8);
            // Size: 124-136 (octal)
            long size = readOctal(header, 124, 12);
            // Type flag: 156
            byte typeFlag = header[156];

            boolean isDirectory = (typeFlag == '5') || (typeFlag == 0 && name.endsWith("/"));

            TarEntry entry = new TarEntry(name, isDirectory);
            entry.setSize(size);
            entry.setMode(mode);

            return entry;
        }

        private String readString(byte[] header, int offset, int length) {
            int end = offset + length;
            while (end > offset && (header[end - 1] == 0 || header[end - 1] == ' ')) {
                end--;
            }
            return new String(header, offset, end - offset);
        }

        private long readOctal(byte[] header, int offset, int length) {
            String str = readString(header, offset, length).trim();
            if (str.isEmpty()) return 0;
            try {
                // Handle both octal and base-256 encoding
                if ((header[offset] & 0x80) != 0) {
                    // Base-256 encoding
                    long result = 0;
                    for (int i = offset + 1; i < offset + length; i++) {
                        result = (result << 8) | (header[i] & 0xFF);
                    }
                    return result;
                }
                return Long.parseLong(str, 8);
            } catch (NumberFormatException e) {
                return 0;
            }
        }
    }

    /**
     * Simple TarEntry class.
     */
    private static class TarEntry {
        private String name;
        private boolean directory;
        private long size;
        private long mode;

        public TarEntry(String name, boolean directory) {
            this.name = name;
            this.directory = directory;
        }

        public String getName() { return name; }
        public boolean isDirectory() { return directory; }
        public long getSize() { return size; }
        public long getMode() { return mode; }

        public void setSize(long size) { this.size = size; }
        public void setMode(long mode) { this.mode = mode; }
    }

    /**
     * Destroy and clean up resources.
     */
    public void destroy() {
        executor.shutdownNow();
    }
}
