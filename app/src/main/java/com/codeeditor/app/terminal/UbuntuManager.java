package com.codeeditor.app.terminal;

import android.content.Context;
import android.content.SharedPreferences;
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
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;

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
 * - udroid integration for Ubuntu Jammy with XFCE4 desktop
 *
 * No root access required - uses proot for syscall translation.
 */
public class UbuntuManager {

    private static final String TAG = "UbuntuManager";
    private static final String PREFS_NAME = "codeeditor_linux";
    private static final String KEY_UDROID_INSTALLED = "udroid_installed";
    private static final String KEY_AUTO_LOGIN = "auto_login";
    private static final String KEY_INSTALLED_DISTRO = "installed_distro";

    // Distro definitions
    public static final String DISTRO_UBUNTU = "ubuntu";
    public static final String DISTRO_DEBIAN = "debian";
    public static final String DISTRO_ALPINE = "alpine";
    public static final String DISTRO_UDROID = "udroid"; // Ubuntu Jammy with XFCE4

    // Ubuntu rootfs URLs (official Ubuntu cloud-images)
    private static final String UBUNTU_ROOTFS_URL =
            "https://cloud-images.ubuntu.com/minimal/releases/noble/release/ubuntu-24.04-minimal-cloudimg-arm64-root.tar.xz";
    private static final String UBUNTU_ROOTFS_URL_ARM =
            "https://cloud-images.ubuntu.com/minimal/releases/noble/release/ubuntu-24.04-minimal-cloudimg-armhf-root.tar.xz";
    private static final String UBUNTU_ROOTFS_URL_X86 =
            "https://cloud-images.ubuntu.com/minimal/releases/noble/release/ubuntu-24.04-minimal-cloudimg-amd64-root.tar.xz";

    // Ubuntu Jammy (22.04) rootfs for udroid - from RandomCoderOrg
    private static final String UDROID_JAMMY_ROOTFS_URL =
            "https://github.com/RandomCoderOrg/ubuntu-on-android/releases/download/v3.0.0/ubuntu-jammy-core-arm64.tar.xz";
    private static final String UDROID_JAMMY_ROOTFS_URL_ARM =
            "https://github.com/RandomCoderOrg/ubuntu-on-android/releases/download/v3.0.0/ubuntu-jammy-core-armhf.tar.xz";
    private static final String UDROID_JAMMY_ROOTFS_URL_X86 =
            "https://github.com/RandomCoderOrg/ubuntu-on-android/releases/download/v3.0.0/ubuntu-jammy-core-amd64.tar.xz";

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

    // Proot binary from Termux packages (.deb format) - MOST RELIABLE
    private static final String PROOT_DEB_URL_ARM64 =
            "https://packages.termux.dev/apt/termux-main/pool/main/p/proot/proot_5.4.0-1_arm64.deb";
    private static final String PROOT_DEB_URL_ARM =
            "https://packages.termux.dev/apt/termux-main/pool/main/p/proot/proot_5.4.0-1_arm.deb";
    private static final String PROOT_DEB_URL_X86 =
            "https://packages.termux.dev/apt/termux-main/pool/main/p/proot/proot_5.4.0-1_x86_64.deb";

    // Alternative proot sources (static binaries)
    private static final String PROOT_STATIC_ALT_ARM64 =
            "https://github.com/nicm/proot-static-builds/releases/download/v5.4.0/proot-v5.4.0-static-arm64";
    private static final String PROOT_STATIC_ALT_ARM =
            "https://github.com/nicm/proot-static-builds/releases/download/v5.4.0/proot-v5.4.0-static-arm";
    private static final String PROOT_STATIC_ALT_X86 =
            "https://github.com/nicm/proot-static-builds/releases/download/v5.4.0/proot-v5.4.0-static-amd64";

    // Original static URLs (may be broken - kept as last fallback)
    private static final String PROOT_STATIC_URL =
            "https://github.com/greentreeboy/proot-static-builds/releases/download/v5.4.0/proot-static-arm64";
    private static final String PROOT_STATIC_URL_ARM =
            "https://github.com/greentreeboy/proot-static-builds/releases/download/v5.4.0/proot-static-arm";
    private static final String PROOT_STATIC_URL_X86 =
            "https://github.com/greentreeboy/proot-static-builds/releases/download/v5.4.0/proot-static-amd64";

    private final Context context;
    private final File linuxDir;
    private final File rootfsDir;
    private final File prootBin;
    private final ExecutorService executor;
    private final Handler mainHandler;
    private final SharedPreferences prefs;

    private volatile boolean isInstalling = false;
    private volatile boolean isDownloadComplete = false;

    public interface InstallCallback {
        void onProgress(int progress, String message);
        void onComplete();
        void onError(String error);
    }

    @FunctionalInterface
    public interface ProgressCallback {
        void onProgress(int progress, String message);
    }

    public UbuntuManager(Context context) {
        this.context = context.getApplicationContext();
        this.linuxDir = new File(context.getFilesDir(), "linux");
        this.rootfsDir = new File(linuxDir, "rootfs");
        this.prootBin = new File(linuxDir, "bin/proot");
        this.executor = Executors.newSingleThreadExecutor();
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public File getRootfsDir() { return rootfsDir; }
    public File getProotBin() { return prootBin; }
    public File getLinuxDir() { return linuxDir; }

    public boolean isDistroInstalled() {
        return rootfsDir.exists() &&
                new File(rootfsDir, "bin/sh").exists() &&
                new File(rootfsDir, "etc").exists();
    }

    public boolean isProotAvailable() {
        return prootBin.exists() && prootBin.canExecute();
    }

    public boolean isInstalling() { return isInstalling; }

    // ===== udroid Integration =====

    public boolean isUdroidInstalled() {
        return prefs.getBoolean(KEY_UDROID_INSTALLED, false) && isDistroInstalled();
    }

    public void setUdroidInstalled(boolean installed) {
        prefs.edit().putBoolean(KEY_UDROID_INSTALLED, installed).apply();
    }

    public boolean isAutoLoginEnabled() {
        return prefs.getBoolean(KEY_AUTO_LOGIN, true);
    }

    public void setAutoLogin(boolean enabled) {
        prefs.edit().putBoolean(KEY_AUTO_LOGIN, enabled).apply();
    }

    public String getInstalledDistro() {
        return prefs.getString(KEY_INSTALLED_DISTRO, null);
    }

    private void setInstalledDistro(String distro) {
        prefs.edit().putString(KEY_INSTALLED_DISTRO, distro).apply();
    }

    /**
     * Build the proot command for udroid login (Ubuntu Jammy with XFCE4).
     */
    public String[] buildUdroidLoginCommand() {
        List<String> cmdList = new ArrayList<>();

        cmdList.add(prootBin.getAbsolutePath());
        cmdList.add("--link2symlink");
        cmdList.add("-r");
        cmdList.add(rootfsDir.getAbsolutePath());

        // Bind mounts
        cmdList.add("-b"); cmdList.add("/dev");
        cmdList.add("-b"); cmdList.add("/dev/pts");
        cmdList.add("-b"); cmdList.add("/proc");
        cmdList.add("-b"); cmdList.add("/sys");
        cmdList.add("-b"); cmdList.add("/sdcard");
        cmdList.add("-b"); cmdList.add("/storage");

        String workDir = com.codeeditor.app.CodeEditorApp.getWorkDirectory();
        if (workDir != null) {
            cmdList.add("-b");
            cmdList.add(workDir + ":/workspace");
        }

        cmdList.add("-b");
        cmdList.add(new File(linuxDir, "tmp").getAbsolutePath() + ":/tmp");

        cmdList.add("-h");
        cmdList.add("codeeditor");
        cmdList.add("-k");
        cmdList.add("5.4.0");

        // Run bash login shell for udroid
        File bashBin = new File(rootfsDir, "bin/bash");
        if (bashBin.exists()) {
            cmdList.add("/bin/bash");
            cmdList.add("-l");
        } else {
            cmdList.add("/bin/sh");
            cmdList.add("-l");
        }

        return cmdList.toArray(new String[0]);
    }

    // ===== Device Detection =====

    public String getDeviceAbi() {
        String abi = android.os.Build.SUPPORTED_ABIS[0];
        Log.d(TAG, "Device ABI: " + abi);
        return abi;
    }

    public String getRootfsUrl(String distro) {
        String abi = getDeviceAbi();
        boolean isArm64 = abi.equals("arm64-v8a");
        boolean isArm = abi.equals("armeabi-v7a");

        switch (distro) {
            case DISTRO_UDROID:
                if (isArm64) return UDROID_JAMMY_ROOTFS_URL;
                if (isArm) return UDROID_JAMMY_ROOTFS_URL_ARM;
                return UDROID_JAMMY_ROOTFS_URL_X86;
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

    // ===== Installation =====

    public void installDistro(String distro, InstallCallback callback) {
        if (isInstalling) {
            notifyError(callback, "Installation already in progress");
            return;
        }

        isInstalling = true;
        try {
            notifyProgress(callback, 5, "Creating directories...");
            createDirectories();

            if (!isProotAvailable()) {
                notifyProgress(callback, 10, "Downloading proot...");
                downloadProot();
                notifyProgress(callback, 25, "Setting up proot...");
                setupProot();
            } else {
                notifyProgress(callback, 25, "Proot already available");
            }

            String distroToDownload = DISTRO_UDROID.equals(distro) ? DISTRO_UBUNTU : distro;
            String rootfsUrl = getRootfsUrl(distro);

            notifyProgress(callback, 30, "Downloading " + distro + " rootfs...");
            File rootfsArchive = downloadRootfs(distro, (progress, msg) -> {
                int scaledProgress = 30 + (progress * 40 / 100);
                notifyProgress(callback, scaledProgress, msg);
            });

            if (rootfsArchive == null || !rootfsArchive.exists()) {
                notifyError(callback, "Failed to download rootfs");
                return;
            }

            notifyProgress(callback, 70, "Extracting " + distro + " rootfs...");
            extractRootfs(rootfsArchive, (progress, msg) -> {
                int scaledProgress = 70 + (progress * 20 / 100);
                notifyProgress(callback, scaledProgress, msg);
            });

            notifyProgress(callback, 90, "Configuring " + distro + "...");
            configureDistro(distro);

            if (DISTRO_UDROID.equals(distro)) {
                notifyProgress(callback, 92, "Setting up udroid environment...");
                configureUdroid();
                setUdroidInstalled(true);
            }

            setInstalledDistro(distro);

            notifyProgress(callback, 95, "Cleaning up...");
            rootfsArchive.delete();

            notifyProgress(callback, 100, "Installation complete!");
            notifyComplete(callback);

        } catch (Exception e) {
            Log.e(TAG, "Installation failed", e);
            notifyError(callback, "Installation failed: " + e.getMessage());
        } finally {
            isInstalling = false;
        }
    }

    public void installDistroAsync(String distro, InstallCallback callback) {
        executor.execute(() -> installDistro(distro, callback));
    }

    public void uninstallDistro() {
        if (rootfsDir.exists()) {
            deleteRecursive(rootfsDir);
        }
        setUdroidInstalled(false);
        setInstalledDistro(null);
    }

    // ===== Proot Command Building =====

    public String[] buildProotCommand(String command) {
        List<String> cmdList = new ArrayList<>();

        cmdList.add(prootBin.getAbsolutePath());
        cmdList.add("--link2symlink");
        cmdList.add("-r");
        cmdList.add(rootfsDir.getAbsolutePath());

        cmdList.add("-b"); cmdList.add("/dev");
        cmdList.add("-b"); cmdList.add("/proc");
        cmdList.add("-b"); cmdList.add("/sys");
        cmdList.add("-b"); cmdList.add("/sdcard");
        cmdList.add("-b"); cmdList.add("/storage");

        String workDir = com.codeeditor.app.CodeEditorApp.getWorkDirectory();
        if (workDir != null) {
            cmdList.add("-b");
            cmdList.add(workDir + ":/workspace");
        }

        cmdList.add("-b");
        cmdList.add(new File(linuxDir, "tmp").getAbsolutePath() + ":/tmp");

        cmdList.add("-h");
        cmdList.add("codeeditor");
        cmdList.add("-k");
        cmdList.add("5.4.0");

        if (command != null && !command.isEmpty()) {
            cmdList.add("/bin/sh");
            cmdList.add("-c");
            cmdList.add(command);
        } else {
            File bashBin = new File(rootfsDir, "bin/bash");
            if (bashBin.exists()) {
                cmdList.add("/bin/bash");
                cmdList.add("-l");
            } else {
                cmdList.add("/bin/sh");
                cmdList.add("-l");
            }
        }

        return cmdList.toArray(new String[0]);
    }

    public String[] buildProotEnvironment() {
        List<String> envList = new ArrayList<>();

        envList.add("HOME=/root");
        envList.add("USER=root");
        envList.add("LOGNAME=root");
        envList.add("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        envList.add("TERM=xterm-256color");
        envList.add("LANG=en_US.UTF-8");
        envList.add("LC_ALL=en_US.UTF-8");
        envList.add("SHELL=/bin/bash");
        envList.add("PROOT_NO_SECCOMP=1");
        envList.add("HOSTNAME=codeeditor");
        envList.add("TMPDIR=/tmp");
        envList.add("ANDROID_ROOT=" + android.os.Build.VERSION.RELEASE);
        envList.add("LINUX_ROOTFS=" + rootfsDir.getAbsolutePath());
        envList.add("DISPLAY=:0");

        String systemPath = System.getenv("PATH");
        if (systemPath != null) {
            envList.add("SYSTEM_PATH=" + systemPath);
        }

        return envList.toArray(new String[0]);
    }

    public DistroInfo getInstalledDistroInfo() {
        if (!isDistroInstalled()) return null;

        DistroInfo info = new DistroInfo();
        info.name = "Linux";
        info.rootfsPath = rootfsDir.getAbsolutePath();

        File osRelease = new File(rootfsDir, "etc/os-release");
        if (osRelease.exists()) {
            try {
                String content = readFile(osRelease);
                if (content.contains("Ubuntu")) info.name = "Ubuntu";
                else if (content.contains("Debian")) info.name = "Debian";
                else if (content.contains("Alpine")) info.name = "Alpine";

                String[] lines = content.split("\n");
                for (String line : lines) {
                    if (line.startsWith("PRETTY_NAME=")) {
                        info.prettyName = line.substring("PRETTY_NAME=".length()).replace("\"", "").trim();
                    } else if (line.startsWith("VERSION_ID=")) {
                        info.versionId = line.substring("VERSION_ID=".length()).replace("\"", "").trim();
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to read os-release", e);
            }
        }

        if (isUdroidInstalled()) {
            info.name = "udroid";
            if (info.prettyName == null) info.prettyName = "Ubuntu Jammy (udroid)";
        }

        return info;
    }

    // ===== Private: Installation Steps =====

    private void createDirectories() {
        linuxDir.mkdirs();
        rootfsDir.mkdirs();
        new File(linuxDir, "bin").mkdirs();
        new File(linuxDir, "tmp").mkdirs();
        new File(linuxDir, "cache").mkdirs();
    }

    /**
     * Download proot binary with multiple fallback sources.
     * Priority: Termux .deb (extract binary) > Alternative static builds > Original static > System paths
     */
    private void downloadProot() throws IOException {
        String abi = getDeviceAbi();
        boolean isArm64 = abi.equals("arm64-v8a");
        boolean isArm = abi.equals("armeabi-v7a");

        Log.d(TAG, "Downloading proot for ABI: " + abi);

        // Strategy 1: Extract proot from Termux .deb package (MOST RELIABLE)
        String debUrl = isArm64 ? PROOT_DEB_URL_ARM64 : (isArm ? PROOT_DEB_URL_ARM : PROOT_DEB_URL_X86);
        try {
            Log.d(TAG, "Trying Termux .deb: " + debUrl);
            File debFile = new File(linuxDir, "cache/proot.deb");
            downloadFile(debUrl, debFile);
            if (debFile.exists() && debFile.length() > 1000) {
                Log.d(TAG, "Downloaded .deb: " + debFile.length() + " bytes, extracting...");
                if (extractProotFromDeb(debFile, prootBin)) {
                    prootBin.setExecutable(true, false);
                    prootBin.setReadable(true, false);
                    Log.d(TAG, "Proot extracted from .deb: " + prootBin.length() + " bytes");
                    debFile.delete();
                    return;
                }
                Log.w(TAG, "Failed to extract proot from .deb, trying alternatives");
            }
            debFile.delete();
        } catch (IOException e) {
            Log.w(TAG, "Termux .deb download failed: " + e.getMessage());
        }

        // Strategy 2: Alternative static proot builds (nicm)
        String altUrl = isArm64 ? PROOT_STATIC_ALT_ARM64 : (isArm ? PROOT_STATIC_ALT_ARM : PROOT_STATIC_ALT_X86);
        try {
            Log.d(TAG, "Trying alternative static: " + altUrl);
            downloadFile(altUrl, prootBin);
            if (prootBin.exists() && prootBin.length() > 1000) {
                prootBin.setExecutable(true, false);
                prootBin.setReadable(true, false);
                Log.d(TAG, "Proot from alt static: " + prootBin.length() + " bytes");
                return;
            }
        } catch (IOException e) {
            Log.w(TAG, "Alt static download failed: " + e.getMessage());
        }

        // Strategy 3: Original static builds (may be broken)
        String staticUrl = isArm64 ? PROOT_STATIC_URL : (isArm ? PROOT_STATIC_URL_ARM : PROOT_STATIC_URL_X86);
        try {
            Log.d(TAG, "Trying original static: " + staticUrl);
            downloadFile(staticUrl, prootBin);
            if (prootBin.exists() && prootBin.length() > 1000) {
                prootBin.setExecutable(true, false);
                prootBin.setReadable(true, false);
                Log.d(TAG, "Proot from original static: " + prootBin.length() + " bytes");
                return;
            }
        } catch (IOException e) {
            Log.w(TAG, "Original static download failed: " + e.getMessage());
        }

        // Strategy 4: Try extracting from bundled assets
        try {
            Log.d(TAG, "Trying bundled assets");
            String assetName = "proot-" + abi;
            if (extractAsset(assetName, prootBin)) {
                prootBin.setExecutable(true, false);
                prootBin.setReadable(true, false);
                Log.d(TAG, "Proot from bundled asset: " + prootBin.length() + " bytes");
                return;
            }
        } catch (IOException e) {
            Log.w(TAG, "Bundled asset extraction failed: " + e.getMessage());
        }

        // Strategy 5: Copy from system/Termux paths
        String[] systemProotPaths = {
                "/data/data/com.termux/files/usr/bin/proot",
                "/usr/bin/proot",
                "/system/bin/proot"
        };
        for (String path : systemProotPaths) {
            File f = new File(path);
            if (f.exists() && f.canExecute()) {
                copyFile(f, prootBin);
                prootBin.setExecutable(true, false);
                Log.d(TAG, "Using system proot from: " + path);
                return;
            }
        }

        throw new IOException("Failed to download proot from all sources.\n" +
                "Please ensure you have internet connection.\n" +
                "Tip: Install Termux from F-Droid, then try again.");
    }

    /**
     * Extract proot binary from a Termux .deb package.
     * A .deb file is an ar archive containing data.tar.xz which contains the binary.
     */
    private boolean extractProotFromDeb(File debFile, File outputProot) throws IOException {
        try (FileInputStream fis = new FileInputStream(debFile)) {
            // Read ar archive header
            byte[] magic = new byte[8];
            if (fis.read(magic) != 8) return false;
            String magicStr = new String(magic, "ASCII");
            if (!magicStr.equals("!<arch>\n")) {
                Log.w(TAG, "Not a valid ar archive");
                return false;
            }

            // Iterate through ar entries to find data.tar
            File dataTarFile = null;
            while (true) {
                // ar entry header: 60 bytes
                byte[] header = new byte[60];
                int read = fis.read(header);
                if (read < 60) break;

                String name = new String(header, 0, 16, "ASCII").trim();
                String sizeStr = new String(header, 48, 10, "ASCII").trim();
                long size;
                try {
                    size = Long.parseLong(sizeStr);
                } catch (NumberFormatException e) {
                    break;
                }

                // Skip the 2-byte header end marker
                byte[] endMarker = new byte[2];
                fis.read(endMarker);

                // Check if this is data.tar.xz, data.tar.gz, or data.tar
                boolean isDataTar = name.startsWith("data.tar");
                if (isDataTar) {
                    // Extract data.tar to a temp file
                    dataTarFile = new File(linuxDir, "cache/data.tar");
                    try (FileOutputStream fos = new FileOutputStream(dataTarFile)) {
                        byte[] buffer = new byte[8192];
                        long remaining = size;
                        while (remaining > 0) {
                            int toRead = (int) Math.min(buffer.length, remaining);
                            int bytesRead = fis.read(buffer, 0, toRead);
                            if (bytesRead == -1) break;
                            fos.write(buffer, 0, bytesRead);
                            remaining -= bytesRead;
                        }
                    }
                    break; // Found data.tar
                } else {
                    // Skip this entry
                    long paddedSize = (size + 1) & ~1L; // 2-byte padding
                    long remaining = paddedSize;
                    while (remaining > 0) {
                        long skipped = fis.skip(remaining);
                        if (skipped <= 0) break;
                        remaining -= skipped;
                    }
                }
            }

            if (dataTarFile == null || !dataTarFile.exists()) {
                Log.w(TAG, "data.tar not found in .deb");
                return false;
            }

            // Extract proot binary from data.tar
            boolean success = extractProotFromDataTar(dataTarFile, outputProot);
            dataTarFile.delete();
            return success;

        } catch (Exception e) {
            Log.e(TAG, "Failed to extract proot from .deb", e);
            return false;
        }
    }

    /**
     * Extract the proot binary from data.tar (which may be .xz or .gz compressed).
     */
    private boolean extractProotFromDataTar(File dataTarFile, File outputProot) {
        try {
            String name = dataTarFile.getName();

            // Decompress if needed
            File actualTar = dataTarFile;
            if (name.endsWith(".xz") || dataTarFile.length() > 0) {
                // Check if xz compressed by reading magic bytes
                byte[] magic = new byte[6];
                try (FileInputStream fis = new FileInputStream(dataTarFile)) {
                    fis.read(magic);
                }
                String magicStr = new String(magic, "ASCII");

                if (magicStr.startsWith("\u00FD7zXZ\u00AA") || magicStr.startsWith("\u00FD7zXZ")) {
                    // XZ compressed - use system xz to decompress
                    File decompressed = new File(linuxDir, "cache/data_decompressed.tar");
                    ProcessBuilder pb = new ProcessBuilder("sh", "-c",
                            "xz -d -c '" + dataTarFile.getAbsolutePath() + "' > '" +
                                    decompressed.getAbsolutePath() + "'");
                    pb.redirectErrorStream(true);
                    Process p = pb.start();
                    int exit = p.waitFor();
                    if (exit == 0 && decompressed.exists() && decompressed.length() > 0) {
                        actualTar = decompressed;
                    }
                } else if (magic[0] == 0x1f && magic[1] == (byte) 0x8b) {
                    // GZIP compressed
                    File decompressed = new File(linuxDir, "cache/data_decompressed.tar");
                    try (FileInputStream fis = new FileInputStream(dataTarFile);
                         GZIPInputStream gis = new GZIPInputStream(fis);
                         FileOutputStream fos = new FileOutputStream(decompressed)) {
                        byte[] buffer = new byte[8192];
                        int len;
                        while ((len = gis.read(buffer)) != -1) {
                            fos.write(buffer, 0, len);
                        }
                    }
                    actualTar = decompressed;
                }
            }

            // Now extract proot from the tar archive using system tar
            File extractDir = new File(linuxDir, "cache/extract");
            extractDir.mkdirs();

            ProcessBuilder pb = new ProcessBuilder("tar", "-x", "-f",
                    actualTar.getAbsolutePath(), "-C", extractDir.getAbsolutePath());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            int exitCode = p.waitFor();

            // Find proot binary in extracted files
            File prootInTar = new File(extractDir, "data/data/com.termux/files/usr/bin/proot");
            if (!prootInTar.exists()) {
                // Search for it
                prootInTar = findFileRecursive(extractDir, "proot");
            }

            if (prootInTar != null && prootInTar.exists() && prootInTar.length() > 1000) {
                copyFile(prootInTar, outputProot);
                outputProot.setExecutable(true, false);
                Log.d(TAG, "Extracted proot: " + outputProot.length() + " bytes");
            }

            // Clean up
            deleteRecursive(extractDir);
            if (actualTar != dataTarFile) actualTar.delete();

            return outputProot.exists() && outputProot.length() > 1000;

        } catch (Exception e) {
            Log.e(TAG, "Failed to extract proot from data.tar", e);
            return false;
        }
    }

    /**
     * Find a file by name recursively in a directory.
     */
    private File findFileRecursive(File dir, String name) {
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File f : files) {
            if (f.isFile() && f.getName().equals(name)) return f;
            if (f.isDirectory()) {
                File found = findFileRecursive(f, name);
                if (found != null) return found;
            }
        }
        return null;
    }

    /**
     * Extract a file from assets to the specified destination.
     */
    private boolean extractAsset(String assetName, File dest) throws IOException {
        try (InputStream is = context.getAssets().open(assetName);
             FileOutputStream fos = new FileOutputStream(dest)) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = is.read(buffer)) != -1) {
                fos.write(buffer, 0, len);
            }
            return dest.exists() && dest.length() > 0;
        } catch (IOException e) {
            Log.d(TAG, "Asset '" + assetName + "' not found");
            return false;
        }
    }

    private void setupProot() {
        if (prootBin.exists()) {
            prootBin.setExecutable(true, false);
            prootBin.setReadable(true, false);
            prootBin.setWritable(true, false);
        }
    }

    private File downloadRootfs(String distro, ProgressCallback progressCallback) throws IOException {
        String rootfsUrl = getRootfsUrl(distro);
        String extension = rootfsUrl.endsWith(".tar.xz") ? ".tar.xz" :
                          rootfsUrl.endsWith(".tar.gz") ? ".tar.gz" :
                          rootfsUrl.endsWith(".tgz") ? ".tgz" : ".tar";
        File cacheDir = new File(linuxDir, "cache");
        cacheDir.mkdirs();
        File rootfsArchive = new File(cacheDir, distro + "-rootfs" + extension);

        if (rootfsArchive.exists() && rootfsArchive.length() > 1000000) {
            Log.d(TAG, "Rootfs already cached: " + rootfsArchive.length() + " bytes");
            notifyProgress(progressCallback, 100, "Using cached rootfs");
            return rootfsArchive;
        }

        Log.d(TAG, "Downloading rootfs from: " + rootfsUrl);
        downloadFileWithProgress(rootfsUrl, rootfsArchive, progressCallback);

        return rootfsArchive;
    }

    private void extractRootfs(File archive, ProgressCallback progressCallback) throws IOException {
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
            Log.w(TAG, "Java extraction failed, trying system tar", e);
            try {
                extractWithSystemTar(archive, rootfsDir);
            } catch (Exception e2) {
                throw new IOException("Both Java and system extraction failed: " +
                        e.getMessage() + " / " + e2.getMessage());
            }
        }
    }

    private void extractTarGz(File archive, File destDir, ProgressCallback progressCallback) throws IOException {
        try (FileInputStream fis = new FileInputStream(archive);
             GZIPInputStream gis = new GZIPInputStream(fis);
             TarInputStream tis = new TarInputStream(gis)) {

            TarEntry entry;
            long totalSize = archive.length();
            long extractedSize = 0;
            int lastProgress = 0;

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

    private void extractTarXz(File archive, File destDir, ProgressCallback progressCallback) throws IOException {
        try {
            extractWithSystemTar(archive, destDir);
        } catch (Exception e) {
            Log.w(TAG, "System tar failed for xz, trying xzcat pipeline", e);
            try {
                ProcessBuilder pb = new ProcessBuilder("sh", "-c",
                        "cat '" + archive.getAbsolutePath() + "' | xz -d | tar -x -C '" +
                                destDir.getAbsolutePath() + "'");
                pb.redirectErrorStream(true);
                Process p = pb.start();
                int exit = p.waitFor();
                if (exit != 0) {
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

    private void extractTar(File archive, File destDir, ProgressCallback progressCallback) throws IOException {
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

        // Create profile.d script
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

        // Set up /etc/passwd
        File passwd = new File(rootfsDir, "etc/passwd");
        if (!passwd.exists()) {
            writeFile(passwd,
                    "root:x:0:0:root:/root:/bin/bash\n" +
                    "nobody:x:65534:65534:nobody:/nonexistent:/usr/sbin/nologin\n");
        }

        // Set up /etc/group
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

        // Create /dev/pts directory
        File devPts = new File(rootfsDir, "dev/pts");
        devPts.mkdirs();

        // Create /run directory
        File runDir = new File(rootfsDir, "run");
        runDir.mkdirs();
        runDir.setWritable(true, false);

        // Set up apt sources
        if (DISTRO_UBUNTU.equals(distro) || DISTRO_UDROID.equals(distro)) {
            String ubuntuCodename = DISTRO_UDROID.equals(distro) ? "jammy" : "noble";
            File aptSources = new File(rootfsDir, "etc/apt/sources.list");
            if (!aptSources.exists()) {
                writeFile(aptSources,
                        "deb http://ports.ubuntu.com/ubuntu-ports " + ubuntuCodename + " main restricted universe multiverse\n" +
                        "deb http://ports.ubuntu.com/ubuntu-ports " + ubuntuCodename + "-updates main restricted universe multiverse\n" +
                        "deb http://ports.ubuntu.com/ubuntu-ports " + ubuntuCodename + "-security main restricted universe multiverse\n");
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

    /**
     * Configure udroid-specific settings (Ubuntu Jammy with XFCE4).
     */
    private void configureUdroid() throws IOException {
        // Update .bashrc for udroid
        File rootHome = new File(rootfsDir, "root");
        rootHome.mkdirs();

        File bashrc = new File(rootHome, ".bashrc");
        writeFile(bashrc,
                "# ~/.bashrc - udroid CodeEditor Terminal\n" +
                "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\n" +
                "export TERM=xterm-256color\n" +
                "export LANG=en_US.UTF-8\n" +
                "export DISPLAY=:0\n" +
                "export PULSE_SERVER=tcp:127.0.0.1:4713\n" +
                "\n" +
                "# If not running interactively, don't do anything\n" +
                "case $- in\n" +
                "    *i*) ;;\n" +
                "      *) return;;\n" +
                "esac\n" +
                "\n" +
                "# udroid-style prompt\n" +
                "PS1='\\[\\e[1;32m\\]\\u@udroid\\[\\e[0m\\]:\\[\\e[1;34m\\]\\w\\[\\e[0m\\]\\$ '\n" +
                "\n" +
                "# Aliases\n" +
                "alias ll='ls -alF'\n" +
                "alias la='ls -A'\n" +
                "alias l='ls -CF'\n" +
                "alias cls='clear'\n" +
                "alias update='apt update && apt upgrade -y'\n" +
                "alias install='apt install -y'\n" +
                "\n" +
                "# udroid helper functions\n" +
                "udroid-setup-desktop() {\n" +
                "    echo 'Setting up XFCE4 desktop environment...'\n" +
                "    apt update\n" +
                "    apt install -y xfce4 xfce4-terminal dbus-x11\n" +
                "    echo 'Desktop environment installed!'\n" +
                "    echo 'Use a VNC viewer to connect to display :0'\n" +
                "}\n" +
                "\n" +
                "udroid-setup-vnc() {\n" +
                "    apt install -y tigervnc-standalone-server tigervnc-common\n" +
                "    mkdir -p ~/.vnc\n" +
                "    echo '#!/bin/sh' > ~/.vnc/xstartup\n" +
                "    echo 'dbus-launch --exit-with-session startxfce4' >> ~/.vnc/xstartup\n" +
                "    chmod +x ~/.vnc/xstartup\n" +
                "    vncserver :0 -geometry 1280x720 -depth 24\n" +
                "    echo 'VNC server started on display :0'\n" +
                "    echo 'Connect with VNC viewer to 127.0.0.1:5900'\n" +
                "}\n" +
                "\n" +
                "# Welcome message\n" +
                "echo ''\n" +
                "echo '  \\033[1;32mudroid CodeEditor Terminal\\033[0m'\n" +
                "echo '  Ubuntu Jammy (22.04) via proot'\n" +
                "echo '  Workspace: /workspace'\n" +
                "echo ''\n" +
                "echo '  Quick Commands:'\n" +
                "echo '    udroid-setup-desktop  - Install XFCE4 desktop'\n" +
                "echo '    udroid-setup-vnc      - Setup VNC server'\n" +
                "echo '    update                - apt update && upgrade'\n" +
                "echo '    install <pkg>         - apt install package'\n" +
                "echo ''\n");

        // Create udroid setup script
        File udroidSetup = new File(rootfsDir, "usr/local/bin/udroid-setup");
        udroidSetup.getParentFile().mkdirs();
        writeFile(udroidSetup,
                "#!/bin/bash\n" +
                "# udroid Setup Script for CodeEditor\n" +
                "set -e\n" +
                "\n" +
                "echo '=== udroid CodeEditor Setup ==='\n" +
                "echo ''\n" +
                "\n" +
                "# Update system\n" +
                "echo '[1/3] Updating system packages...'\n" +
                "apt update && apt upgrade -y\n" +
                "\n" +
                "# Install essential packages\n" +
                "echo '[2/3] Installing essential packages...'\n" +
                "apt install -y nano wget curl git sudo apt-utils dialog\n" +
                "\n" +
                "# Install XFCE4 desktop\n" +
                "echo '[3/3] Installing XFCE4 desktop (this may take a while)...'\n" +
                "apt install -y xfce4 xfce4-terminal dbus-x11\n" +
                "\n" +
                "echo ''\n" +
                "echo '=== Setup Complete! ==='\n" +
                "echo 'Use udroid-setup-vnc to start a VNC desktop session'\n");
        udroidSetup.setExecutable(true, false);

        Log.d(TAG, "udroid configuration completed");
    }

    // ===== Download Utilities =====

    private void downloadFile(String urlStr, File destFile) throws IOException {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(60000);
        conn.setRequestProperty("User-Agent", "CodeEditor/1.3.1");
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
                conn.setConnectTimeout(30000);
                conn.setReadTimeout(60000);
                conn.setRequestProperty("User-Agent", "CodeEditor/1.3.1");
                responseCode = conn.getResponseCode();
                redirects++;
            }

            if (responseCode != HttpURLConnection.HTTP_OK) {
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
                                           ProgressCallback progressCallback) throws IOException {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(60000);
        conn.setReadTimeout(120000);
        conn.setRequestProperty("User-Agent", "CodeEditor/1.3.1");
        conn.setFollowRedirects(true);
        conn.setInstanceFollowRedirects(true);

        try {
            int responseCode = conn.getResponseCode();
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
                conn.setRequestProperty("User-Agent", "CodeEditor/1.3.1");
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

    // ===== File Utilities =====

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

    // ===== Notification Helpers =====

    private void notifyProgress(InstallCallback callback, int progress, String message) {
        if (callback != null) {
            mainHandler.post(() -> callback.onProgress(progress, message));
        }
    }

    private void notifyProgress(ProgressCallback callback, int progress, String message) {
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
     * Clean up resources.
     */
    public void destroy() {
        executor.shutdownNow();
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
            if (prettyName != null) return prettyName;
            return name + (versionId != null ? " " + versionId : "");
        }
    }

    // ===== TarInputStream and TarEntry (minimal tar parser) =====
    // These inner classes provide a minimal tar archive parser for .tar.gz files.
    // For .tar.xz files, we fall back to the system tar command.

    private static class TarInputStream extends InputStream {
        private final InputStream is;
        private long entryRemaining = 0;
        private TarEntry currentEntry;

        TarInputStream(InputStream is) {
            this.is = is;
        }

        TarEntry getNextEntry() throws IOException {
            // Skip remaining data of current entry
            if (currentEntry != null && entryRemaining > 0) {
                long skip = entryRemaining;
                while (skip > 0) {
                    long s = is.skip(skip);
                    if (s <= 0) break;
                    skip -= s;
                }
                // Skip padding
                long padding = (512 - (entryRemaining % 512)) % 512;
                while (padding > 0) {
                    long s = is.skip(padding);
                    if (s <= 0) break;
                    padding -= s;
                }
            }

            // Read 512-byte header
            byte[] header = new byte[512];
            int totalRead = 0;
            while (totalRead < 512) {
                int r = is.read(header, totalRead, 512 - totalRead);
                if (r == -1) return null; // End of archive
                totalRead += r;
            }

            // Check for end-of-archive (two zero blocks)
            boolean allZero = true;
            for (byte b : header) {
                if (b != 0) { allZero = false; break; }
            }
            if (allZero) return null;

            currentEntry = new TarEntry(header);
            entryRemaining = currentEntry.getSize();
            return currentEntry;
        }

        @Override
        public int read() throws IOException {
            byte[] b = new byte[1];
            int r = read(b);
            return r == -1 ? -1 : b[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (entryRemaining <= 0) return -1;
            int toRead = (int) Math.min(len, entryRemaining);
            int r = is.read(b, off, toRead);
            if (r > 0) entryRemaining -= r;
            return r;
        }

        @Override
        public void close() throws IOException {
            is.close();
        }
    }

    private static class TarEntry {
        private final String name;
        private final long size;
        private final int mode;

        TarEntry(byte[] header) {
            // Parse POSIX tar header
            name = readString(header, 0, 100);
            size = parseOctal(header, 124, 12);
            mode = (int) parseOctal(header, 100, 8);
        }

        private String readString(byte[] header, int offset, int length) {
            int end = offset + length;
            while (end > offset && header[end - 1] == 0) end--;
            return new String(header, offset, end - offset).trim();
        }

        private long parseOctal(byte[] header, int offset, int length) {
            String s = readString(header, offset, length);
            if (s.isEmpty()) return 0;
            try {
                // Handle base-256 encoding (first byte has high bit set)
                if ((header[offset] & 0x80) != 0) {
                    long result = 0;
                    for (int i = offset; i < offset + length; i++) {
                        result = (result << 8) | (header[i] & 0xFF);
                    }
                    result &= ~(1L << (length * 8 - 1)); // Clear high bit
                    return result;
                }
                return Long.parseLong(s, 8);
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        String getName() { return name; }
        long getSize() { return size; }
        int getMode() { return mode; }
        boolean isDirectory() { return name.endsWith("/"); }
    }
}
