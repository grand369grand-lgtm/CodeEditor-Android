package com.codeeditor.app.runner;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.codeeditor.app.R;
import com.codeeditor.app.terminal.ProotSession;
import com.codeeditor.app.terminal.UbuntuManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

/**
 * TerminalActivity - Interactive terminal with proper PTY support and udroid auto-login.
 *
 * This implements the same PTY (Pseudo-Terminal) approach that Termux uses internally:
 * forkpty() creates a proper pseudo-terminal pair, giving the shell a real TTY.
 *
 * Key features:
 * - PTY-based terminal via JNI (like Termux) - fixes "can't find tty fd" error
 * - Android shell and Linux (proot) sessions
 * - udroid (Ubuntu Jammy + XFCE4) auto-install and auto-login
 * - Full ANSI/VT100 escape sequence support from the shell
 * - Special keys toolbar (ESC, TAB, CTRL, arrows, symbols)
 * - WebView-based terminal rendering with ANSI parsing
 *
 * When terminal opens:
 * 1. If udroid is installed and auto-login is enabled → auto-login to udroid
 * 2. If no Linux is installed → show install dialog
 * 3. User can switch between Android shell and Linux
 */
public class TerminalActivity extends AppCompatActivity {

    private static final String TAG = "TerminalActivity";

    // Session types
    private static final String SESSION_ANDROID = "android";
    private static final String SESSION_LINUX = "linux";
    private static final String SESSION_UDROID = "udroid";

    // Intent extras
    public static final String EXTRA_OUTPUT = "extra_output";
    public static final String EXTRA_LANGUAGE = "extra_language";
    public static final String EXTRA_FILE_NAME = "extra_file_name";
    public static final String EXTRA_WORK_DIR = "extra_work_dir";
    public static final String EXTRA_RUN_CODE = "extra_run_code";
    public static final String EXTRA_CODE = "extra_code";
    public static final String EXTRA_SESSION_TYPE = "extra_session_type";
    public static final String EXTRA_DISTRO = "extra_distro";

    // Terminal state
    private WebView webView;
    private TerminalSession androidSession;
    private ProotSession linuxSession;
    private UbuntuManager ubuntuManager;
    private String currentSession = SESSION_ANDROID;
    private boolean ctrlActive = false;

    // UI elements
    private TextView tvSessionInfo;
    private TextView tvTerminalTitle;
    private View linuxBar;
    private View installButtons;
    private View runButtons;
    private SharedPreferences prefs;

    // udroid setup state
    private boolean udroidSetupPending = false;
    private Handler autoLoginHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_terminal);

        prefs = getSharedPreferences("codeeditor_terminal", MODE_PRIVATE);
        ubuntuManager = new UbuntuManager(this);

        initViews();
        setupWebView();
        setupSpecialKeys();
        setupLinuxBar();
        parseIntentAndStartSession();
    }

    private void initViews() {
        tvSessionInfo = findViewById(R.id.tv_session_info);
        tvTerminalTitle = findViewById(R.id.tv_terminal_title);
        linuxBar = findViewById(R.id.linux_bar);
        installButtons = findViewById(R.id.install_buttons);
        runButtons = findViewById(R.id.run_buttons);
    }

    /**
     * Set up the WebView for terminal rendering.
     * The WebView loads an HTML page that renders terminal output with ANSI support.
     */
    private void setupWebView() {
        webView = findViewById(R.id.webview_terminal);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setAllowFileAccess(true);
        webView.getSettings().setMediaPlaybackRequiresUserGesture(false);
        webView.setWebViewClient(new TerminalWebViewClient());
        webView.setWebChromeClient(new WebChromeClient());
        webView.addJavascriptInterface(new TerminalJsInterface(), "AndroidTerminal");

        // Load terminal HTML
        webView.loadUrl("file:///android_asset/terminal/terminal.html");
    }

    /**
     * Set up the special keys toolbar for terminal input.
     */
    private void setupSpecialKeys() {
        // ESC
        findViewById(R.id.btn_esc).setOnClickListener(v -> sendSpecialKey("ESC"));

        // TAB
        findViewById(R.id.btn_tab).setOnClickListener(v -> sendSpecialKey("TAB"));

        // CTRL toggle
        TextView btnCtrl = findViewById(R.id.btn_ctrl);
        btnCtrl.setOnClickListener(v -> {
            ctrlActive = !ctrlActive;
            btnCtrl.setTextColor(ctrlActive ? 0xFF51cf66 : 0xFF8892b0);
        });

        // Arrow keys
        findViewById(R.id.btn_arrow_up).setOnClickListener(v -> sendArrowKey(0));
        findViewById(R.id.btn_arrow_down).setOnClickListener(v -> sendArrowKey(1));
        findViewById(R.id.btn_arrow_left).setOnClickListener(v -> sendArrowKey(2));
        findViewById(R.id.btn_arrow_right).setOnClickListener(v -> sendArrowKey(3));

        // HOME / END / DEL
        findViewById(R.id.btn_home).setOnClickListener(v -> sendSpecialKey("HOME"));
        findViewById(R.id.btn_end).setOnClickListener(v -> sendSpecialKey("END"));
        findViewById(R.id.btn_del).setOnClickListener(v -> sendSpecialKey("DEL"));

        // Symbol keys
        setupSymbolKey(R.id.btn_pipe, "|");
        setupSymbolKey(R.id.btn_slash, "/");
        setupSymbolKey(R.id.btn_tilde, "~");
        setupSymbolKey(R.id.btn_dollar, "$");
        setupSymbolKey(R.id.btn_ampersand, "&");
        setupSymbolKey(R.id.btn_semicolon, ";");
        setupSymbolKey(R.id.btn_exclamation, "!");
        setupSymbolKey(R.id.btn_less, "<");
        setupSymbolKey(R.id.btn_greater, ">");
        setupSymbolKey(R.id.btn_quote, "'");
        setupSymbolKey(R.id.btn_double_quote, "\"");
    }

    private void setupSymbolKey(int id, String symbol) {
        View btn = findViewById(id);
        if (btn != null) {
            btn.setOnClickListener(v -> sendText(symbol));
        }
    }

    /**
     * Set up the Linux distro control bar.
     */
    private void setupLinuxBar() {
        // Install buttons
        findViewById(R.id.btn_install_ubuntu).setOnClickListener(v ->
                installLinuxDistro(UbuntuManager.DISTRO_UBUNTU));
        findViewById(R.id.btn_install_debian).setOnClickListener(v ->
                installLinuxDistro(UbuntuManager.DISTRO_DEBIAN));
        findViewById(R.id.btn_install_alpine).setOnClickListener(v ->
                installLinuxDistro(UbuntuManager.DISTRO_ALPINE));
        findViewById(R.id.btn_install_udroid).setOnClickListener(v ->
                installLinuxDistro(UbuntuManager.DISTRO_UDROID));

        // Run buttons
        findViewById(R.id.btn_start_linux).setOnClickListener(v -> startLinuxSession());
        findViewById(R.id.btn_switch_android).setOnClickListener(v -> switchToAndroidSession());

        // Update bar visibility based on installation state
        updateLinuxBar();
    }

    /**
     * Update Linux bar to show install or run buttons based on state.
     */
    private void updateLinuxBar() {
        boolean installed = ubuntuManager.isDistroInstalled();
        installButtons.setVisibility(installed ? View.GONE : View.VISIBLE);
        runButtons.setVisibility(installed ? View.VISIBLE : View.GONE);
    }

    /**
     * Parse intent extras and start the appropriate session.
     */
    private void parseIntentAndStartSession() {
        Intent intent = getIntent();
        String sessionType = intent.getStringExtra(EXTRA_SESSION_TYPE);
        String distro = intent.getStringExtra(EXTRA_DISTRO);

        if (sessionType != null) {
            switch (sessionType) {
                case SESSION_LINUX:
                    startLinuxSession();
                    return;
                case SESSION_UDROID:
                    startUdroidSession();
                    return;
            }
        }

        // Check for code execution request
        boolean runCode = intent.getBooleanExtra(EXTRA_RUN_CODE, false);
        if (runCode) {
            String code = intent.getStringExtra(EXTRA_CODE);
            String language = intent.getStringExtra(EXTRA_LANGUAGE);
            String fileName = intent.getStringExtra(EXTRA_FILE_NAME);
            String workDir = intent.getStringExtra(EXTRA_WORK_DIR);
            executeCodeInTerminal(language, code, fileName, workDir);
            return;
        }

        // Auto-start: check udroid auto-login
        autoStartSession();
    }

    /**
     * Auto-start the appropriate session.
     * If udroid is installed with auto-login enabled, auto-login to udroid.
     * If Linux is installed, start Linux.
     * Otherwise, start Android shell.
     */
    private void autoStartSession() {
        // Priority 1: udroid auto-login
        if (ubuntuManager.isUdroidInstalled() && ubuntuManager.isAutoLoginEnabled()) {
            Log.d(TAG, "Auto-starting udroid session");
            startUdroidSession();
            return;
        }

        // Priority 2: Linux session
        if (ubuntuManager.isDistroInstalled() && ubuntuManager.isProotAvailable()) {
            Log.d(TAG, "Auto-starting Linux session");
            startLinuxSession();
            return;
        }

        // Priority 3: Android shell
        Log.d(TAG, "Auto-starting Android shell session");
        startAndroidShellSession();
    }

    // =====================================================================
    // Session Management
    // =====================================================================

    /**
     * Start an Android shell session with proper PTY support.
     */
    private void startAndroidShellSession() {
        // Destroy existing sessions
        destroySessions();

        currentSession = SESSION_ANDROID;
        updateSessionInfo();

        String workDir = getWorkDirectory();

        androidSession = new TerminalSession(workDir, null, new TerminalSession.SessionCallback() {
            @Override
            public void onOutput(byte[] data, int length) {
                // PTY output includes ANSI codes - pass directly to WebView
                String text = new String(data, 0, length, StandardCharsets.UTF_8);
                appendOutput(text);
            }

            @Override
            public void onSessionExit(int exitCode) {
                runOnUiThread(() -> {
                    updateSessionInfo();
                    if (exitCode != 0) {
                        appendOutput("\r\n[Process exited with code " + exitCode + "]\r\n");
                    }
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> appendOutput("\r\n[Error: " + error + "]\r\n"));
            }
        });

        androidSession.start();
    }

    /**
     * Start a Linux session using proot with proper PTY support.
     */
    private void startLinuxSession() {
        if (!ubuntuManager.isDistroInstalled()) {
            Toast.makeText(this, R.string.linux_not_installed, Toast.LENGTH_LONG).show();
            return;
        }

        if (!ubuntuManager.isProotAvailable()) {
            Toast.makeText(this, R.string.proot_not_available, Toast.LENGTH_LONG).show();
            return;
        }

        destroySessions();

        currentSession = SESSION_LINUX;
        updateSessionInfo();

        String distro = ubuntuManager.getInstalledDistro();
        if (distro == null) distro = UbuntuManager.DISTRO_UBUNTU;

        linuxSession = new ProotSession(ubuntuManager, distro, new ProotSession.SessionCallback() {
            @Override
            public void onOutput(byte[] data, int length) {
                String text = new String(data, 0, length, StandardCharsets.UTF_8);
                appendOutput(text);
            }

            @Override
            public void onSessionExit(int exitCode) {
                runOnUiThread(() -> {
                    currentSession = SESSION_ANDROID;
                    updateSessionInfo();
                    appendOutput("\r\n[Linux session exited with code " + exitCode + "]\r\n");
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> appendOutput("\r\n[Linux Error: " + error + "]\r\n"));
            }
        });

        linuxSession.start();
    }

    /**
     * Start a udroid session (Ubuntu Jammy + XFCE4) with proper PTY support.
     * This is the main entry point for udroid auto-login.
     */
    private void startUdroidSession() {
        if (!ubuntuManager.isUdroidInstalled()) {
            // udroid not installed - show install dialog
            showInstallUdroidDialog();
            return;
        }

        if (!ubuntuManager.isProotAvailable()) {
            Toast.makeText(this, R.string.proot_not_available, Toast.LENGTH_LONG).show();
            return;
        }

        destroySessions();

        currentSession = SESSION_UDROID;
        updateSessionInfo();

        linuxSession = new ProotSession(ubuntuManager, UbuntuManager.DISTRO_UDROID,
                new ProotSession.SessionCallback() {
            @Override
            public void onOutput(byte[] data, int length) {
                String text = new String(data, 0, length, StandardCharsets.UTF_8);
                appendOutput(text);
            }

            @Override
            public void onSessionExit(int exitCode) {
                runOnUiThread(() -> {
                    currentSession = SESSION_ANDROID;
                    updateSessionInfo();
                    appendOutput("\r\n[udroid session exited with code " + exitCode + "]\r\n");
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> appendOutput("\r\n[udroid Error: " + error + "]\r\n"));
            }
        });

        // Start udroid-specific session
        linuxSession.startUdroidSession();

        // If udroid setup is pending (first run after install), run setup commands
        if (udroidSetupPending) {
            udroidSetupPending = false;
            autoLoginHandler.postDelayed(() -> {
                if (linuxSession != null && linuxSession.isRunning()) {
                    runUdroidSetupCommands();
                }
            }, 3000); // Wait 3 seconds for shell to initialize
        }
    }

    /**
     * Run udroid setup commands after first installation.
     * These commands set up the Ubuntu Jammy environment:
     * 1. apt update && apt upgrade -y
     * 2. . <(curl -Ls https://bit.ly/udroid-installer)  (install udroid manager)
     * 3. udroid login jammy:xfce4  (start XFCE4 desktop)
     */
    private void runUdroidSetupCommands() {
        if (linuxSession == null || !linuxSession.isRunning()) return;

        appendOutput("\r\n\033[1;33m=== Running udroid setup ===\033[0m\r\n");

        // Step 1: Update system
        linuxSession.executeCommand("apt update && apt upgrade -y");

        // Step 2: Install udroid manager (delayed to allow step 1 to complete)
        autoLoginHandler.postDelayed(() -> {
            if (linuxSession != null && linuxSession.isRunning()) {
                linuxSession.executeCommand(". <(curl -Ls https://bit.ly/udroid-installer)");
            }
        }, 15000); // Wait 15 seconds for apt update/upgrade

        // Step 3: Login to udroid (delayed for installer to complete)
        autoLoginHandler.postDelayed(() -> {
            if (linuxSession != null && linuxSession.isRunning()) {
                linuxSession.executeCommand("udroid login jammy:xfce4");
            }
        }, 45000); // Wait 45 seconds for udroid installer
    }

    /**
     * Switch to Android shell session.
     */
    private void switchToAndroidSession() {
        if (linuxSession != null) {
            linuxSession.destroy();
            linuxSession = null;
        }
        currentSession = SESSION_ANDROID;
        startAndroidShellSession();
    }

    /**
     * Destroy all active sessions.
     */
    private void destroySessions() {
        if (androidSession != null) {
            androidSession.destroy();
            androidSession = null;
        }
        if (linuxSession != null) {
            linuxSession.destroy();
            linuxSession = null;
        }
        autoLoginHandler.removeCallbacksAndMessages(null);
    }

    // =====================================================================
    // Linux Distro Installation
    // =====================================================================

    /**
     * Install a Linux distribution.
     */
    private void installLinuxDistro(String distro) {
        if (ubuntuManager.isInstalling()) {
            Toast.makeText(this, "Installation already in progress", Toast.LENGTH_SHORT).show();
            return;
        }

        ProgressDialog progressDialog = new ProgressDialog(this);
        progressDialog.setTitle("Installing " + distro);
        progressDialog.setMessage("Preparing...");
        progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progressDialog.setMax(100);
        progressDialog.setCancelable(false);
        progressDialog.show();

        ubuntuManager.installDistroAsync(distro, new UbuntuManager.InstallCallback() {
            @Override
            public void onProgress(int progress, String message) {
                runOnUiThread(() -> {
                    progressDialog.setProgress(progress);
                    progressDialog.setMessage(message);
                });
            }

            @Override
            public void onComplete() {
                runOnUiThread(() -> {
                    progressDialog.dismiss();
                    updateLinuxBar();
                    Toast.makeText(TerminalActivity.this,
                            UbuntuManager.DISTRO_UDROID.equals(distro)
                                    ? R.string.udroid_installed
                                    : R.string.linux_installed,
                            Toast.LENGTH_LONG).show();

                    // Auto-start udroid session after installation
                    if (UbuntuManager.DISTRO_UDROID.equals(distro)) {
                        udroidSetupPending = true;
                        startUdroidSession();
                    } else {
                        startLinuxSession();
                    }
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    progressDialog.dismiss();
                    new AlertDialog.Builder(TerminalActivity.this)
                            .setTitle("Installation Failed")
                            .setMessage(error)
                            .setPositiveButton("OK", null)
                            .show();
                });
            }
        });
    }

    /**
     * Show dialog to install udroid.
     */
    private void showInstallUdroidDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.install_udroid)
                .setMessage("Install Ubuntu Jammy with XFCE4 desktop via udroid?\n\n" +
                        "This will:\n" +
                        "1. Download Ubuntu rootfs (~200MB)\n" +
                        "2. Set up proot environment\n" +
                        "3. Install udroid manager\n" +
                        "4. Auto-login to jammy:xfce4\n\n" +
                        "Requires internet connection.")
                .setPositiveButton("Install", (d, w) -> installLinuxDistro(UbuntuManager.DISTRO_UDROID))
                .setNegativeButton("Cancel", null)
                .show();
    }

    // =====================================================================
    // Terminal Input/Output
    // =====================================================================

    /**
     * Send text to the active session.
     */
    private void sendText(String text) {
        if (ctrlActive) {
            ctrlActive = false;
            findViewById(R.id.btn_ctrl).setTextColor(0xFF8892b0);
            sendCtrlChar(text);
            return;
        }

        switch (currentSession) {
            case SESSION_ANDROID:
                if (androidSession != null) androidSession.write(text);
                break;
            case SESSION_LINUX:
            case SESSION_UDROID:
                if (linuxSession != null) linuxSession.write(text);
                break;
        }
    }

    /**
     * Send a special key to the active session.
     */
    private void sendSpecialKey(String key) {
        switch (currentSession) {
            case SESSION_ANDROID:
                if (androidSession != null) androidSession.sendKey(key);
                break;
            case SESSION_LINUX:
            case SESSION_UDROID:
                if (linuxSession != null) linuxSession.sendEscapeSequence(getEscapeForSpecialKey(key));
                break;
        }
    }

    private String getEscapeForSpecialKey(String key) {
        switch (key) {
            case "ESC": return "";
            case "TAB": return "\t";
            case "HOME": return "[H";
            case "END": return "[F";
            case "DEL": return "[3~";
            default: return "";
        }
    }

    /**
     * Send arrow key input to the active session.
     */
    private void sendArrowKey(int direction) {
        switch (currentSession) {
            case SESSION_ANDROID:
                if (androidSession != null) androidSession.sendArrowKey(direction);
                break;
            case SESSION_LINUX:
            case SESSION_UDROID:
                if (linuxSession != null) {
                    char code = (char) ('A' + direction);
                    linuxSession.sendEscapeSequence("[" + code);
                }
                break;
        }
    }

    /**
     * Handle Ctrl+key combinations.
     */
    private void sendCtrlChar(String key) {
        switch (currentSession) {
            case SESSION_ANDROID:
                if (androidSession != null) {
                    switch (key.toLowerCase()) {
                        case "c": androidSession.sendCtrlC(); break;
                        case "d": androidSession.sendCtrlD(); break;
                        case "z": androidSession.sendCtrlZ(); break;
                        case "l":
                            androidSession.executeCommand("clear");
                            break;
                    }
                }
                break;
            case SESSION_LINUX:
            case SESSION_UDROID:
                if (linuxSession != null) {
                    switch (key.toLowerCase()) {
                        case "c": linuxSession.sendCtrlC(); break;
                        case "d": linuxSession.sendCtrlD(); break;
                        case "z": linuxSession.sendCtrlZ(); break;
                        case "l":
                            linuxSession.executeCommand("clear");
                            break;
                    }
                }
                break;
        }
    }

    /**
     * Append output to the terminal WebView.
     * With proper PTY, output includes ANSI escape sequences which the
     * WebView terminal renders with colors and formatting.
     */
    private void appendOutput(String text) {
        if (webView == null || text == null) return;

        // Base64 encode to safely pass through JavaScript
        String encoded = Base64.encodeToString(text.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        webView.post(() -> {
            if (webView != null) {
                webView.evaluateJavascript(
                        "appendTerminalOutput('" + encoded + "')", null);
            }
        });
    }

    /**
     * Execute code in the terminal.
     */
    private void executeCodeInTerminal(String language, String code, String fileName, String workDir) {
        // Start an Android shell session first
        startAndroidShellSession();

        // Write code to a temp file and execute
        try {
            File tempDir = new File(getCacheDir(), "code_temp");
            tempDir.mkdirs();

            String extension = getExtensionForLanguage(language);
            File tempFile = new File(tempDir, (fileName != null ? fileName : "temp") + extension);
            try (FileOutputStream fos = new FileOutputStream(tempFile);
                 OutputStreamWriter writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
                writer.write(code);
            }
            tempFile.setReadable(true, false);

            // Execute based on language
            String command;
            switch (language != null ? language.toLowerCase() : "") {
                case "python":
                case "python3":
                    command = "python3 " + tempFile.getAbsolutePath() + "\n";
                    break;
                case "cpp":
                case "c++":
                    // Compile first, then run
                    String outputPath = tempDir + "/code_output";
                    command = "g++ -o " + outputPath + " " + tempFile.getAbsolutePath() +
                            " && " + outputPath + "\n";
                    break;
                case "java":
                    command = "javac " + tempFile.getAbsolutePath() +
                            " && java -cp " + tempDir.getAbsolutePath() + " " +
                            fileName.replace(".java", "") + "\n";
                    break;
                case "shell":
                case "bash":
                default:
                    command = "sh " + tempFile.getAbsolutePath() + "\n";
                    break;
            }

            // Delay to allow shell to initialize
            autoLoginHandler.postDelayed(() -> {
                if (androidSession != null) {
                    androidSession.executeCommand(command);
                }
            }, 1000);

        } catch (IOException e) {
            appendOutput("\r\n[Error writing temp file: " + e.getMessage() + "]\r\n");
        }
    }

    private String getExtensionForLanguage(String language) {
        if (language == null) return ".txt";
        switch (language.toLowerCase()) {
            case "python": case "python3": return ".py";
            case "cpp": case "c++": return ".cpp";
            case "java": return ".java";
            case "javascript": return ".js";
            case "shell": case "bash": return ".sh";
            default: return ".txt";
        }
    }

    /**
     * Update the session info display.
     */
    private void updateSessionInfo() {
        if (tvSessionInfo == null) return;

        String info;
        int color;
        switch (currentSession) {
            case SESSION_UDROID:
                info = "\u25CF udroid Active";
                color = 0xFF51cf66;
                break;
            case SESSION_LINUX:
                info = "\u25CF Linux Active";
                color = 0xFF51cf66;
                break;
            case SESSION_ANDROID:
            default:
                info = "\u25CF Shell Active";
                color = getColor(R.color.ios_green);
                break;
        }

        tvSessionInfo.setText(info);
        tvSessionInfo.setTextColor(color);

        if (tvTerminalTitle != null) {
            tvTerminalTitle.setText("CodeEditor Terminal v1.4.0");
        }
    }

    private String getWorkDirectory() {
        String workDir = getIntent().getStringExtra(EXTRA_WORK_DIR);
        if (workDir != null && new File(workDir).exists()) {
            return workDir;
        }
        return getFilesDir().getAbsolutePath();
    }

    // =====================================================================
    // JavaScript Interface for WebView Terminal
    // =====================================================================

    private class TerminalJsInterface {
        @JavascriptInterface
        public void onCommand(String command) {
            runOnUiThread(() -> {
                switch (currentSession) {
                    case SESSION_ANDROID:
                        if (androidSession != null) androidSession.executeCommand(command);
                        break;
                    case SESSION_LINUX:
                    case SESSION_UDROID:
                        if (linuxSession != null) linuxSession.executeCommand(command);
                        break;
                }
            });
        }

        @JavascriptInterface
        public void onCtrlC() {
            runOnUiThread(() -> {
                switch (currentSession) {
                    case SESSION_ANDROID:
                        if (androidSession != null) androidSession.sendCtrlC();
                        break;
                    case SESSION_LINUX:
                    case SESSION_UDROID:
                        if (linuxSession != null) linuxSession.sendCtrlC();
                        break;
                }
            });
        }

        @JavascriptInterface
        public void onCtrlD() {
            runOnUiThread(() -> {
                switch (currentSession) {
                    case SESSION_ANDROID:
                        if (androidSession != null) androidSession.sendCtrlD();
                        break;
                    case SESSION_LINUX:
                    case SESSION_UDROID:
                        if (linuxSession != null) linuxSession.sendCtrlD();
                        break;
                }
            });
        }

        @JavascriptInterface
        public void onCtrlZ() {
            runOnUiThread(() -> {
                switch (currentSession) {
                    case SESSION_ANDROID:
                        if (androidSession != null) androidSession.sendCtrlZ();
                        break;
                    case SESSION_LINUX:
                    case SESSION_UDROID:
                        if (linuxSession != null) linuxSession.sendCtrlZ();
                        break;
                }
            });
        }

        @JavascriptInterface
        public void onEscape() {
            runOnUiThread(() -> sendSpecialKey("ESC"));
        }

        @JavascriptInterface
        public void onTab() {
            runOnUiThread(() -> sendSpecialKey("TAB"));
        }

        @JavascriptInterface
        public void onInput(String text) {
            runOnUiThread(() -> sendText(text));
        }
    }

    // =====================================================================
    // WebView Client
    // =====================================================================

    private class TerminalWebViewClient extends WebViewClient {
        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            // Set terminal prompt
            view.evaluateJavascript("setTerminalPrompt('$ ')", null);
        }
    }

    // =====================================================================
    // Menu
    // =====================================================================

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_terminal, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        if (id == R.id.action_new_session) {
            // New session - restart current session type
            switch (currentSession) {
                case SESSION_UDROID:
                    startUdroidSession();
                    break;
                case SESSION_LINUX:
                    startLinuxSession();
                    break;
                default:
                    startAndroidShellSession();
                    break;
            }
            return true;

        } else if (id == R.id.action_start_linux) {
            startLinuxSession();
            return true;

        } else if (id == R.id.action_android_shell) {
            switchToAndroidSession();
            return true;

        } else if (id == R.id.action_install_distro) {
            showInstallDialog();
            return true;

        } else if (id == R.id.action_uninstall_distro) {
            showUninstallDialog();
            return true;

        } else if (id == R.id.action_run_file) {
            showRunFileDialog();
            return true;

        } else if (id == R.id.action_paste) {
            pasteFromClipboard();
            return true;

        } else if (id == R.id.action_copy_output) {
            copyTerminalOutput();
            return true;

        } else if (id == R.id.action_clear) {
            if (webView != null) {
                webView.evaluateJavascript("clearTerminal()", null);
            }
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private void showInstallDialog() {
        String[] options = {"Ubuntu (Noble 24.04)", "Debian", "Alpine (Lightweight)",
                "udroid (Ubuntu Jammy + XFCE4)"};
        String[] distros = {UbuntuManager.DISTRO_UBUNTU, UbuntuManager.DISTRO_DEBIAN,
                UbuntuManager.DISTRO_ALPINE, UbuntuManager.DISTRO_UDROID};

        new AlertDialog.Builder(this)
                .setTitle(R.string.install_linux)
                .setItems(options, (d, which) -> installLinuxDistro(distros[which]))
                .show();
    }

    private void showUninstallDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.uninstall_linux)
                .setMessage("Remove the installed Linux distribution? This will delete all data.")
                .setPositiveButton("Uninstall", (d, w) -> {
                    destroySessions();
                    ubuntuManager.uninstallDistro();
                    updateLinuxBar();
                    startAndroidShellSession();
                    Toast.makeText(this, "Linux uninstalled", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showRunFileDialog() {
        // TODO: Open file picker to select a file to run
        Toast.makeText(this, "Run file: select a file from the file manager",
                Toast.LENGTH_SHORT).show();
    }

    private void copyTerminalOutput() {
        if (webView != null) {
            webView.evaluateJavascript("getTerminalOutput()", value -> {
                if (value != null && value.length() > 2) {
                    String text = value.substring(1, value.length() - 1); // Remove quotes
                    android.content.ClipboardManager clipboard =
                            (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    android.content.ClipData clip = android.content.ClipData.newPlainText(
                            "Terminal Output", text);
                    clipboard.setPrimaryClip(clip);
                    Toast.makeText(this, R.string.copied_to_clipboard,
                            Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    private void pasteFromClipboard() {
        android.content.ClipboardManager clipboard =
                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard.hasPrimaryClip()) {
            CharSequence text = clipboard.getPrimaryClip().getItemAt(0).getText();
            if (text != null) {
                sendText(text.toString());
            }
        }
    }

    // =====================================================================
    // Lifecycle
    // =====================================================================

    @Override
    protected void onResume() {
        super.onResume();
        // Re-focus terminal
        if (webView != null) {
            webView.requestFocus();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        destroySessions();
        if (ubuntuManager != null) {
            ubuntuManager.destroy();
        }
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
    }

    @Override
    public void onBackPressed() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.close_terminal)
                .setMessage(R.string.close_terminal_message)
                .setPositiveButton(R.string.close, (d, w) -> {
                    destroySessions();
                    finish();
                })
                .setNeutralButton(R.string.background, (d, w) -> {
                    // Keep running in background
                    moveTaskToBack(true);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /**
     * Handle window focus changes for soft keyboard.
     */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && webView != null) {
            // Auto-show keyboard when terminal is focused
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            imm.showSoftInput(webView, InputMethodManager.SHOW_IMPLICIT);
        }
    }
}
