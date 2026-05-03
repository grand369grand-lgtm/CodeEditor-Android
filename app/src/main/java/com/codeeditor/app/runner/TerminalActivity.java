package com.codeeditor.app.runner;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.codeeditor.app.R;
import com.codeeditor.app.terminal.ProotSession;
import com.codeeditor.app.terminal.UbuntuManager;
import com.codeeditor.app.utils.FileUtils;

import java.io.File;

/**
 * Terminal Activity - Interactive terminal emulator with full Linux support.
 *
 * Features:
 * - Interactive shell sessions (Android shell /system/bin/sh)
 * - Full Linux environment via proot (Ubuntu, Debian, Alpine, udroid)
 * - Real-time output streaming with ANSI color support
 * - Special keys toolbar (Tab, Ctrl, Esc, arrows, etc.)
 * - Command history navigation
 * - Run code from the editor in the terminal
 * - Linux distribution installation and management
 * - udroid auto-login support (Ubuntu Jammy with XFCE4)
 * - iOS-style dark theme terminal UI
 */
public class TerminalActivity extends AppCompatActivity {

    private static final String TAG = "TerminalActivity";

    public static final String SESSION_ANDROID = "android";
    public static final String SESSION_LINUX = "linux";

    public static final String EXTRA_OUTPUT = "output";
    public static final String EXTRA_LANGUAGE = "language";
    public static final String EXTRA_FILE_NAME = "file_name";
    public static final String EXTRA_WORK_DIR = "work_dir";
    public static final String EXTRA_RUN_CODE = "run_code";
    public static final String EXTRA_CODE = "code";
    public static final String EXTRA_SESSION_TYPE = "session_type";
    public static final String EXTRA_DISTRO = "distro";

    private WebView webView;
    private TerminalSession androidSession;
    private ProotSession linuxSession;
    private UbuntuManager ubuntuManager;
    private HorizontalScrollView specialKeysBar;
    private TextView tvSessionInfo;
    private LinearLayout linuxBar;

    private String currentWorkDir;
    private String currentSessionType = SESSION_ANDROID;
    private String currentDistro = UbuntuManager.DISTRO_UBUNTU;
    private boolean ctrlActive = false;
    private TextView btnCtrl;

    private Handler mainHandler;
    private ProgressDialog installDialog;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_terminal);

        mainHandler = new Handler(Looper.getMainLooper());
        ubuntuManager = new UbuntuManager(this);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.terminal);
        }

        initViews();
        setupWebView();
        setupSpecialKeys();
        setupLinuxBar();
        parseIntentAndStartSession();
    }

    private void initViews() {
        webView = findViewById(R.id.webview_terminal);
        specialKeysBar = findViewById(R.id.special_keys_bar);
        tvSessionInfo = findViewById(R.id.tv_session_info);
        btnCtrl = findViewById(R.id.btn_ctrl);
        linuxBar = findViewById(R.id.linux_bar);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setSupportZoom(false);
        settings.setUseWideViewPort(true);
        settings.setMediaPlaybackRequiresUserGesture(false);

        webView.setWebViewClient(new TerminalWebViewClient());
        webView.setWebChromeClient(new WebChromeClient());
        webView.addJavascriptInterface(new TerminalJsInterface(), "AndroidTerminal");
        webView.loadUrl("file:///android_asset/terminal/terminal.html");
    }

    private void setupLinuxBar() {
        findViewById(R.id.btn_install_ubuntu).setOnClickListener(v ->
                installLinuxDistro(UbuntuManager.DISTRO_UBUNTU));

        findViewById(R.id.btn_install_debian).setOnClickListener(v ->
                installLinuxDistro(UbuntuManager.DISTRO_DEBIAN));

        findViewById(R.id.btn_install_alpine).setOnClickListener(v ->
                installLinuxDistro(UbuntuManager.DISTRO_ALPINE));

        // udroid install button
        View btnUdroid = findViewById(R.id.btn_install_udroid);
        if (btnUdroid != null) {
            btnUdroid.setOnClickListener(v ->
                    installLinuxDistro(UbuntuManager.DISTRO_UDROID));
        }

        findViewById(R.id.btn_start_linux).setOnClickListener(v ->
                startLinuxSession());

        findViewById(R.id.btn_switch_android).setOnClickListener(v ->
                switchToAndroidSession());

        updateLinuxBarVisibility();
    }

    private void updateLinuxBarVisibility() {
        boolean installed = ubuntuManager.isDistroInstalled();
        View installButtons = findViewById(R.id.install_buttons);
        View runButtons = findViewById(R.id.run_buttons);

        if (installButtons != null) {
            installButtons.setVisibility(installed ? View.GONE : View.VISIBLE);
        }
        if (runButtons != null) {
            runButtons.setVisibility(installed ? View.VISIBLE : View.GONE);
        }
    }

    private void installLinuxDistro(String distro) {
        currentDistro = distro;

        String distroName;
        switch (distro) {
            case UbuntuManager.DISTRO_DEBIAN:
                distroName = "Debian";
                break;
            case UbuntuManager.DISTRO_ALPINE:
                distroName = "Alpine";
                break;
            case UbuntuManager.DISTRO_UDROID:
                distroName = "udroid (Ubuntu Jammy + XFCE4)";
                break;
            default:
                distroName = "Ubuntu";
                break;
        }

        installDialog = new ProgressDialog(this);
        installDialog.setTitle("Installing " + distroName);
        installDialog.setMessage("Preparing installation...");
        installDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        installDialog.setMax(100);
        installDialog.setProgress(0);
        installDialog.setCancelable(false);
        installDialog.setButton(ProgressDialog.BUTTON_NEGATIVE, "Cancel", (d, w) -> d.dismiss());
        installDialog.show();

        ubuntuManager.installDistroAsync(distro, new UbuntuManager.InstallCallback() {
            @Override
            public void onProgress(int progress, String message) {
                runOnUiThread(() -> {
                    if (installDialog != null && installDialog.isShowing()) {
                        installDialog.setProgress(progress);
                        installDialog.setMessage(message);
                    }
                });
            }

            @Override
            public void onComplete() {
                runOnUiThread(() -> {
                    if (installDialog != null && installDialog.isShowing()) {
                        installDialog.dismiss();
                    }
                    updateLinuxBarVisibility();
                    Toast.makeText(TerminalActivity.this,
                            distroName + " installed successfully!", Toast.LENGTH_LONG).show();

                    UbuntuManager.DistroInfo info = ubuntuManager.getInstalledDistroInfo();
                    if (info != null) {
                        appendOutput("\n\u001b[32m═══════════════════════════════════════\u001b[0m\n");
                        appendOutput("\u001b[32m  " + info.toString() + " installed successfully!\u001b[0m\n");
                        appendOutput("\u001b[32m═══════════════════════════════════════\u001b[0m\n\n");

                        if (UbuntuManager.DISTRO_UDROID.equals(distro)) {
                            appendOutput("udroid is ready! Use these commands:\n");
                            appendOutput("  udroid-setup-desktop  - Install XFCE4 desktop\n");
                            appendOutput("  udroid-setup-vnc      - Setup VNC server\n");
                            appendOutput("  update                - apt update && upgrade\n\n");
                        } else {
                            appendOutput("Tap \"Start Linux\" to begin your Linux session.\n");
                            appendOutput("You can use apt/apt-get to install packages.\n\n");
                        }
                    }

                    startLinuxSession();
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    if (installDialog != null && installDialog.isShowing()) {
                        installDialog.dismiss();
                    }
                    appendOutput("\n\u001b[31m[Installation Error]\u001b[0m\n");
                    appendOutput(error + "\n\n");

                    if (error.contains("404") || error.contains("proot")) {
                        appendOutput("\u001b[33mProot Download Troubleshooting:\u001b[0m\n");
                        appendOutput("1. Make sure you have internet connection\n");
                        appendOutput("2. Install Termux from F-Droid for proot support\n");
                        appendOutput("3. Try Alpine (smallest download, ~3MB)\n");
                        appendOutput("4. Restart the app and try again\n\n");
                    } else {
                        appendOutput("Tips:\n");
                        appendOutput("1. Make sure you have internet connection\n");
                        appendOutput("2. Try installing Termux for proot support\n");
                        appendOutput("3. Use Alpine for a smaller download\n\n");
                    }

                    new AlertDialog.Builder(TerminalActivity.this)
                            .setTitle("Installation Failed")
                            .setMessage(error)
                            .setPositiveButton("OK", null)
                            .show();
                });
            }
        });
    }

    private void startLinuxSession() {
        if (!ubuntuManager.isDistroInstalled()) {
            Toast.makeText(this, "Please install a Linux distribution first", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!ubuntuManager.isProotAvailable()) {
            Toast.makeText(this, "proot binary not available. Try reinstalling.", Toast.LENGTH_SHORT).show();
            return;
        }

        // Destroy existing sessions
        if (linuxSession != null) {
            linuxSession.destroy();
            linuxSession = null;
        }
        if (androidSession != null) {
            androidSession.destroy();
            androidSession = null;
        }

        currentSessionType = SESSION_LINUX;
        callJs("clearTerminal()");

        // Show Linux welcome banner
        UbuntuManager.DistroInfo info = ubuntuManager.getInstalledDistroInfo();
        String distroName = info != null ? info.toString() : "Linux";
        boolean isUdroid = ubuntuManager.isUdroidInstalled();

        appendOutput("\u001b[32m╔══════════════════════════════════════════════════╗\u001b[0m\n");
        if (isUdroid) {
            appendOutput("\u001b[32m║     udroid CodeEditor Terminal v1.3.1            ║\u001b[0m\n");
            appendOutput("\u001b[32m║     Ubuntu Jammy (22.04) + XFCE4 Desktop        ║\u001b[0m\n");
        } else {
            appendOutput("\u001b[32m║     CodeEditor Linux Terminal v1.3.1             ║\u001b[0m\n");
            appendOutput("\u001b[32m║     " + String.format("%-46s", distroName) + "║\u001b[0m\n");
        }
        appendOutput("\u001b[32m╚══════════════════════════════════════════════════╝\u001b[0m\n\n");

        if (isUdroid) {
            appendOutput("  Shell: /bin/bash (via proot)\n");
            appendOutput("  Distro: Ubuntu 22.04 Jammy (udroid)\n");
            appendOutput("  Desktop: XFCE4 (install with udroid-setup-desktop)\n");
            appendOutput("  VNC: Available (setup with udroid-setup-vnc)\n");
            appendOutput("  Workspace: /workspace (synced with app)\n\n");
            appendOutput("\u001b[33m  Starting udroid session...\u001b[0m\n\n");
        } else {
            appendOutput("  Shell: /bin/sh (via proot)\n");
            appendOutput("  Workspace: /workspace (synced with app)\n");
            appendOutput("  Network: Available (DNS: 8.8.8.8)\n");
            appendOutput("  Package Manager: apt / apt-get\n\n");
            appendOutput("\u001b[33m  Starting proot session...\u001b[0m\n\n");
        }

        // Use udroid login command if udroid is installed
        String[] command = isUdroid ? ubuntuManager.buildUdroidLoginCommand() : ubuntuManager.buildProotCommand(null);
        String[] environment = ubuntuManager.buildProotEnvironment();

        linuxSession = new ProotSession(ubuntuManager, currentDistro, new ProotSession.SessionCallback() {
            @Override
            public void onOutput(String text) {
                appendOutput(text);
            }

            @Override
            public void onSessionExit(int exitCode) {
                runOnUiThread(() -> {
                    appendOutput("\n\n\u001b[33m[Linux session ended with exit code: " + exitCode + "]\u001b[0m\n");
                    appendOutput("[Tap \"Start Linux\" to restart the session]\n");
                    updateSessionInfo(false);
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    appendOutput("\n\u001b[31m[Proot Error: " + error + "]\u001b[0m\n");
                    appendOutput("\nFalling back to Android shell...\n\n");
                    startAndroidShellSession(currentWorkDir);
                });
            }
        });

        linuxSession.start();
        updateSessionInfo(true);

        String prompt = isUdroid ? "root@udroid:~# " : "root@codeeditor:~# ";
        callJs("setPrompt('" + prompt.replace("'", "\\'") + "')");
    }

    private void switchToAndroidSession() {
        if (linuxSession != null) {
            linuxSession.destroy();
            linuxSession = null;
        }

        currentSessionType = SESSION_ANDROID;
        callJs("clearTerminal()");

        appendOutput("\u001b[36m╔══════════════════════════════════════════╗\u001b[0m\n");
        appendOutput("\u001b[36m║     CodeEditor Android Shell v1.3.1     ║\u001b[0m\n");
        appendOutput("\u001b[36m╚══════════════════════════════════════════╝\u001b[0m\n\n");

        startAndroidShellSession(currentWorkDir);

        String prompt = "$ ";
        if (currentWorkDir != null) {
            String shortDir = new File(currentWorkDir).getName();
            prompt = shortDir + " $ ";
        }
        callJs("setPrompt('" + prompt.replace("'", "\\'") + "')");
    }

    private void setupSpecialKeys() {
        findViewById(R.id.btn_esc).setOnClickListener(v -> callJs("sendKey('ESC')"));
        findViewById(R.id.btn_tab).setOnClickListener(v -> callJs("sendKey('TAB')"));

        btnCtrl.setOnClickListener(v -> {
            ctrlActive = !ctrlActive;
            btnCtrl.setTextColor(ctrlActive ? getColor(R.color.ios_blue) : getColor(R.color.terminal_text));
            if (ctrlActive) {
                Toast.makeText(this, "Ctrl mode ON - tap a key", Toast.LENGTH_SHORT).show();
            }
        });

        findViewById(R.id.btn_arrow_up).setOnClickListener(v -> callJs("sendKey('UP')"));
        findViewById(R.id.btn_arrow_down).setOnClickListener(v -> callJs("sendKey('DOWN')"));
        findViewById(R.id.btn_arrow_left).setOnClickListener(v -> callJs("sendKey('LEFT')"));
        findViewById(R.id.btn_arrow_right).setOnClickListener(v -> callJs("sendKey('RIGHT')"));
        findViewById(R.id.btn_home).setOnClickListener(v -> callJs("sendKey('HOME')"));
        findViewById(R.id.btn_end).setOnClickListener(v -> callJs("sendKey('END')"));

        findViewById(R.id.btn_pipe).setOnClickListener(v -> {
            if (ctrlActive) {
                sendCtrlChar("\\");
                ctrlActive = false;
                btnCtrl.setTextColor(getColor(R.color.terminal_text));
            } else {
                callJs("insertText('|')");
            }
        });

        findViewById(R.id.btn_slash).setOnClickListener(v -> callJs("insertText('/')"));
        findViewById(R.id.btn_tilde).setOnClickListener(v -> callJs("insertText('~')"));
        findViewById(R.id.btn_dollar).setOnClickListener(v -> callJs("insertText('$')"));
        findViewById(R.id.btn_ampersand).setOnClickListener(v -> callJs("insertText('&')"));
        findViewById(R.id.btn_semicolon).setOnClickListener(v -> callJs("insertText(';')"));
        findViewById(R.id.btn_exclamation).setOnClickListener(v -> callJs("insertText('!')"));
        findViewById(R.id.btn_less).setOnClickListener(v -> callJs("insertText('<')"));
        findViewById(R.id.btn_greater).setOnClickListener(v -> callJs("insertText('>')"));
        findViewById(R.id.btn_quote).setOnClickListener(v -> callJs("insertText(\"'\")"));
        findViewById(R.id.btn_double_quote).setOnClickListener(v -> callJs("insertText('\"')"));
        findViewById(R.id.btn_del).setOnClickListener(v -> callJs("sendKey('DEL')"));
    }

    private void sendCtrlChar(String key) {
        if (SESSION_LINUX.equals(currentSessionType) && linuxSession != null && linuxSession.isRunning()) {
            switch (key.toLowerCase()) {
                case "c": linuxSession.sendCtrlC(); break;
                case "d": linuxSession.sendCtrlD(); break;
                case "z": linuxSession.sendCtrlZ(); break;
                case "l":
                    callJs("clearTerminal()");
                    linuxSession.executeCommand("");
                    break;
                default:
                    char ctrlChar = (char) (key.toLowerCase().charAt(0) - 'a' + 1);
                    linuxSession.write(String.valueOf(ctrlChar));
                    break;
            }
        } else if (androidSession != null && androidSession.isRunning()) {
            switch (key.toLowerCase()) {
                case "c": androidSession.sendCtrlC(); break;
                case "d": androidSession.sendCtrlD(); break;
                case "z": androidSession.sendCtrlZ(); break;
                case "l":
                    callJs("clearTerminal()");
                    androidSession.executeCommand("");
                    break;
                default:
                    char ctrlChar = (char) (key.toLowerCase().charAt(0) - 'a' + 1);
                    androidSession.write(String.valueOf(ctrlChar));
                    break;
            }
        }
    }

    private void parseIntentAndStartSession() {
        Intent intent = getIntent();
        if (intent == null) {
            autoStartSession();
            return;
        }

        currentSessionType = intent.getStringExtra(EXTRA_SESSION_TYPE);
        if (currentSessionType == null) currentSessionType = SESSION_ANDROID;

        currentDistro = intent.getStringExtra(EXTRA_DISTRO);
        if (currentDistro == null) currentDistro = UbuntuManager.DISTRO_UBUNTU;

        currentWorkDir = intent.getStringExtra(EXTRA_WORK_DIR);
        if (currentWorkDir == null) {
            currentWorkDir = com.codeeditor.app.CodeEditorApp.getWorkDirectory();
        }

        String staticOutput = intent.getStringExtra(EXTRA_OUTPUT);
        String code = intent.getStringExtra(EXTRA_CODE);
        boolean runCode = intent.getBooleanExtra(EXTRA_RUN_CODE, false);

        if (SESSION_LINUX.equals(currentSessionType) && ubuntuManager.isDistroInstalled()) {
            startLinuxSession();
        } else {
            autoStartSession();
        }

        if (code != null && !code.isEmpty() && runCode) {
            String language = intent.getStringExtra(EXTRA_LANGUAGE);
            mainHandler.postDelayed(() -> executeCodeInTerminal(code, language), 1500);
        } else if (staticOutput != null && !staticOutput.isEmpty()) {
            String fileName = intent.getStringExtra(EXTRA_FILE_NAME);
            String language = intent.getStringExtra(EXTRA_LANGUAGE);
            mainHandler.postDelayed(() -> {
                appendOutput("\n═══ Code Execution Output ═══\n");
                appendOutput("File: " + (fileName != null ? fileName : "untitled") + "\n");
                appendOutput("Language: " + (language != null ? language.toUpperCase() : "TEXT") + "\n");
                appendOutput("═════════════════════════════\n\n");
                appendOutput(staticOutput);
                appendOutput("\n\n═══ Process finished ═══\n\n");
            }, 1000);
        }
    }

    /**
     * Auto-start the appropriate session based on preferences.
     * If udroid is installed with auto-login, start Linux session automatically.
     */
    private void autoStartSession() {
        if (ubuntuManager.isDistroInstalled() && ubuntuManager.isAutoLoginEnabled()) {
            // Auto-start Linux session
            mainHandler.postDelayed(() -> {
                if (ubuntuManager.isDistroInstalled() && ubuntuManager.isProotAvailable()) {
                    startLinuxSession();
                } else {
                    startAndroidShellSession(currentWorkDir);
                }
            }, 500);
        } else {
            startAndroidShellSession(currentWorkDir);
        }
    }

    private void startAndroidShellSession(String workDir) {
        currentSessionType = SESSION_ANDROID;

        String[] env = new String[]{
                "TERM=xterm-256color",
                "HOME=" + (workDir != null ? workDir : "/"),
                "LANG=en_US.UTF-8"
        };

        androidSession = new TerminalSession(workDir, env, new TerminalSession.SessionCallback() {
            @Override
            public void onOutput(String text) {
                appendOutput(text);
            }

            @Override
            public void onSessionExit(int exitCode) {
                runOnUiThread(() -> {
                    appendOutput("\n\n[Session ended with exit code: " + exitCode + "]\n");
                    appendOutput("[Tap to start a new session]\n");
                    updateSessionInfo(false);
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> appendOutput("\n[Error: " + error + "]\n"));
            }
        });

        androidSession.start();
        updateSessionInfo(true);
    }

    private void executeCodeInTerminal(String code, String language) {
        String workDir = com.codeeditor.app.CodeEditorApp.getWorkDirectory();
        String ext = "txt";
        String interpreter = null;

        if (language != null) {
            switch (language.toLowerCase()) {
                case "python": case "py": ext = "py"; interpreter = "python3"; break;
                case "cpp": case "c": ext = "cpp"; break;
                case "java": ext = "java"; break;
                case "javascript": case "js": ext = "js"; interpreter = "node"; break;
                case "sh": case "bash": ext = "sh"; interpreter = "sh"; break;
            }
        }

        String tempFile = workDir + "/temp_terminal_run." + ext;
        FileUtils.writeFile(tempFile, code);

        String command;
        if (interpreter != null) {
            command = interpreter + " /workspace/temp_terminal_run." + ext;
        } else if ("cpp".equals(ext) || "c".equals(ext)) {
            command = "g++ -o /tmp/run_out /workspace/temp_terminal_run." + ext +
                    " && /tmp/run_out; rm -f /tmp/run_out";
        } else if ("java".equals(ext)) {
            command = "cd /workspace && javac temp_terminal_run.java && java temp_terminal_run";
        } else {
            command = "cat /workspace/temp_terminal_run." + ext;
        }

        if (SESSION_LINUX.equals(currentSessionType) && linuxSession != null && linuxSession.isRunning()) {
            linuxSession.executeCommand(command);
        } else if (androidSession != null && androidSession.isRunning()) {
            if (interpreter != null) {
                androidSession.executeCommand(interpreter + " " + tempFile);
            } else if ("cpp".equals(ext) || "c".equals(ext)) {
                String outFile = workDir + "/run_out";
                androidSession.executeCommand("g++ -o " + outFile + " " + tempFile +
                        " && " + outFile + "; rm -f " + outFile);
            } else if ("java".equals(ext)) {
                String className = "temp_terminal_run";
                androidSession.executeCommand("cd " + workDir + " && javac " + tempFile +
                        " && java " + className);
            } else {
                androidSession.executeCommand("cat " + tempFile);
            }
        }
    }

    private void appendOutput(String text) {
        if (text == null) return;
        try {
            String encoded = java.net.URLEncoder.encode(text, "UTF-8")
                    .replace("+", "%20")
                    .replace("'", "%27");
            callJs("appendTerminalOutput(decodeURIComponent('" + encoded + "'))");
        } catch (java.io.UnsupportedEncodingException e) {
            String escaped = text
                    .replace("\\", "\\\\")
                    .replace("'", "\\'")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t");
            callJs("appendTerminalOutput('" + escaped + "')");
        }
    }

    private void callJs(String js) {
        if (webView != null) {
            webView.evaluateJavascript(js, null);
        }
    }

    private void updateSessionInfo(boolean running) {
        if (tvSessionInfo != null) {
            String sessionLabel = SESSION_LINUX.equals(currentSessionType) ? "Linux" : "Shell";
            tvSessionInfo.setText(running ? "● " + sessionLabel + " Active" : "○ " + sessionLabel + " Ended");
            tvSessionInfo.setTextColor(getColor(running ? R.color.ios_green : R.color.ios_red));
        }
    }

    // ===== JavaScript Interface =====

    private class TerminalJsInterface {
        @JavascriptInterface
        public void onCommand(String command) {
            if (SESSION_LINUX.equals(currentSessionType) && linuxSession != null && linuxSession.isRunning()) {
                linuxSession.write(command);
            } else if (androidSession != null && androidSession.isRunning()) {
                androidSession.write(command);
            }
        }

        @JavascriptInterface
        public void onCtrlC() {
            if (SESSION_LINUX.equals(currentSessionType) && linuxSession != null && linuxSession.isRunning()) {
                linuxSession.sendCtrlC();
            } else if (androidSession != null && androidSession.isRunning()) {
                androidSession.sendCtrlC();
            }
        }

        @JavascriptInterface
        public void onCtrlD() {
            if (SESSION_LINUX.equals(currentSessionType) && linuxSession != null && linuxSession.isRunning()) {
                linuxSession.sendCtrlD();
            } else if (androidSession != null && androidSession.isRunning()) {
                androidSession.sendCtrlD();
            }
        }

        @JavascriptInterface
        public void onCtrlZ() {
            if (SESSION_LINUX.equals(currentSessionType) && linuxSession != null && linuxSession.isRunning()) {
                linuxSession.sendCtrlZ();
            } else if (androidSession != null && androidSession.isRunning()) {
                androidSession.sendCtrlZ();
            }
        }

        @JavascriptInterface
        public void onEscape() {
            if (SESSION_LINUX.equals(currentSessionType) && linuxSession != null && linuxSession.isRunning()) {
                linuxSession.write("\u001b");
            } else if (androidSession != null && androidSession.isRunning()) {
                androidSession.write("\u001b");
            }
        }

        @JavascriptInterface
        public void onTab(String currentInput) {
            if (SESSION_LINUX.equals(currentSessionType) && linuxSession != null && linuxSession.isRunning()) {
                linuxSession.write("\t");
            } else if (androidSession != null && androidSession.isRunning()) {
                androidSession.write("\t");
            }
        }
    }

    // ===== WebView Client =====

    private class TerminalWebViewClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return false;
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            String prompt;
            if (SESSION_LINUX.equals(currentSessionType)) {
                prompt = ubuntuManager.isUdroidInstalled() ? "root@udroid:~# " : "root@codeeditor:~# ";
            } else {
                prompt = "$ ";
                if (currentWorkDir != null) {
                    String shortDir = new File(currentWorkDir).getName();
                    prompt = shortDir + " $ ";
                }
            }
            callJs("setPrompt('" + prompt.replace("'", "\\'") + "')");
        }
    }

    // ===== Menu =====

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_terminal, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) {
            handleBackPress();
            return true;
        } else if (id == R.id.action_copy_output) {
            copyTerminalOutput();
            return true;
        } else if (id == R.id.action_clear) {
            callJs("clearTerminal()");
            return true;
        } else if (id == R.id.action_new_session) {
            restartSession();
            return true;
        } else if (id == R.id.action_run_file) {
            showRunFileDialog();
            return true;
        } else if (id == R.id.action_paste) {
            pasteFromClipboard();
            return true;
        } else if (id == R.id.action_start_linux) {
            if (ubuntuManager.isDistroInstalled()) {
                startLinuxSession();
            } else {
                showInstallDialog();
            }
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
        }
        return super.onOptionsItemSelected(item);
    }

    private void showInstallDialog() {
        String[] distros = {
                "udroid (Ubuntu Jammy + XFCE4) - Recommended",
                "Ubuntu 24.04 LTS",
                "Debian Bookworm",
                "Alpine 3.20 (Lightweight)"
        };
        String[] distroIds = {
                UbuntuManager.DISTRO_UDROID,
                UbuntuManager.DISTRO_UBUNTU,
                UbuntuManager.DISTRO_DEBIAN,
                UbuntuManager.DISTRO_ALPINE
        };

        new AlertDialog.Builder(this)
                .setTitle("Install Linux Distribution")
                .setMessage("Select a Linux distribution to install.\n\n" +
                        "udroid includes Ubuntu 22.04 with XFCE4 desktop support and VNC.\n" +
                        "Alpine is recommended for low-storage devices (~3MB download).")
                .setItems(distros, (dialog, which) -> {
                    if (ubuntuManager.isDistroInstalled()) {
                        new AlertDialog.Builder(this)
                                .setTitle("Replace Existing Installation?")
                                .setMessage("A Linux distribution is already installed. Installing a new one will replace it.")
                                .setPositiveButton("Replace", (d, w) -> {
                                    ubuntuManager.uninstallDistro();
                                    installLinuxDistro(distroIds[which]);
                                })
                                .setNegativeButton("Cancel", null)
                                .show();
                    } else {
                        installLinuxDistro(distroIds[which]);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showUninstallDialog() {
        UbuntuManager.DistroInfo info = ubuntuManager.getInstalledDistroInfo();
        String name = info != null ? info.toString() : "Linux";

        new AlertDialog.Builder(this)
                .setTitle("Uninstall " + name)
                .setMessage("This will remove the installed Linux distribution and free up storage space. " +
                        "Your workspace files in /sdcard/CodeEditor will not be affected.")
                .setPositiveButton("Uninstall", (d, w) -> {
                    if (linuxSession != null) {
                        linuxSession.destroy();
                        linuxSession = null;
                    }
                    ubuntuManager.uninstallDistro();
                    updateLinuxBarVisibility();
                    switchToAndroidSession();
                    Toast.makeText(this, name + " uninstalled", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void restartSession() {
        if (SESSION_LINUX.equals(currentSessionType)) {
            if (linuxSession != null) {
                linuxSession.destroy();
            }
            if (ubuntuManager.isDistroInstalled()) {
                startLinuxSession();
            } else {
                switchToAndroidSession();
            }
        } else {
            if (androidSession != null) {
                androidSession.destroy();
            }
            callJs("clearTerminal()");
            appendOutput("\u001b[33m[Starting new shell session...]\u001b[0m\n\n");
            startAndroidShellSession(currentWorkDir);
        }
    }

    private void showRunFileDialog() {
        String workDir = com.codeeditor.app.CodeEditorApp.getWorkDirectory();
        File dir = new File(workDir);
        File[] files = dir.listFiles();

        if (files == null || files.length == 0) {
            Toast.makeText(this, "No files in working directory", Toast.LENGTH_SHORT).show();
            return;
        }

        String[] fileNames = new String[files.length];
        for (int i = 0; i < files.length; i++) {
            fileNames[i] = files[i].getName();
        }

        new AlertDialog.Builder(this)
                .setTitle("Run File")
                .setItems(fileNames, (dialog, which) -> {
                    File selected = files[which];
                    String name = selected.getName();
                    String path = SESSION_LINUX.equals(currentSessionType) ?
                            "/workspace/" + name : selected.getAbsolutePath();

                    String command;
                    if (name.endsWith(".py")) {
                        command = "python3 " + path;
                    } else if (name.endsWith(".cpp") || name.endsWith(".c")) {
                        String outPath = SESSION_LINUX.equals(currentSessionType) ?
                                "/tmp/run_out" : workDir + "/run_out";
                        command = "g++ -o " + outPath + " " + path + " && " + outPath +
                                "; rm -f " + outPath;
                    } else if (name.endsWith(".java")) {
                        String className = name.substring(0, name.length() - 5);
                        command = "cd " + (SESSION_LINUX.equals(currentSessionType) ?
                                "/workspace" : workDir) + " && javac " + name + " && java " + className;
                    } else if (name.endsWith(".sh")) {
                        command = "sh " + path;
                    } else {
                        command = "cat " + path;
                    }

                    if (SESSION_LINUX.equals(currentSessionType) && linuxSession != null && linuxSession.isRunning()) {
                        linuxSession.executeCommand(command);
                    } else if (androidSession != null && androidSession.isRunning()) {
                        androidSession.executeCommand(command);
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void pasteFromClipboard() {
        android.content.ClipboardManager clipboard =
                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard.hasPrimaryClip()) {
            android.content.ClipData.Item item = clipboard.getPrimaryClip().getItemAt(0);
            if (item != null && item.getText() != null) {
                String text = item.getText().toString();
                if (SESSION_LINUX.equals(currentSessionType) && linuxSession != null && linuxSession.isRunning()) {
                    linuxSession.write(text);
                } else if (androidSession != null && androidSession.isRunning()) {
                    androidSession.write(text);
                }
            }
        } else {
            Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show();
        }
    }

    private void copyTerminalOutput() {
        webView.evaluateJavascript("document.getElementById('terminal-output').innerText", value -> {
            if (value != null && !"null".equals(value)) {
                String content = value;
                if (content.startsWith("\"") && content.endsWith("\"")) {
                    content = content.substring(1, content.length() - 1);
                }
                content = content.replace("\\n", "\n").replace("\\t", "\t").replace("\\\"", "\"");

                android.content.ClipboardManager clipboard =
                        (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                android.content.ClipData clip = android.content.ClipData.newPlainText(
                        "Terminal Output", content);
                clipboard.setPrimaryClip(clip);
                Toast.makeText(TerminalActivity.this, R.string.copied_to_clipboard,
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void handleBackPress() {
        new AlertDialog.Builder(this)
                .setTitle("Close Terminal")
                .setMessage("Close the terminal session?")
                .setPositiveButton("Close", (d, w) -> finish())
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Background", (d, w) -> finish())
                .show();
    }

    @Override
    public void onBackPressed() {
        handleBackPress();
    }

    @Override
    protected void onDestroy() {
        if (linuxSession != null) {
            linuxSession.destroy();
            linuxSession = null;
        }
        if (androidSession != null) {
            androidSession.destroy();
            androidSession = null;
        }
        if (ubuntuManager != null) {
            ubuntuManager.destroy();
        }
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Re-focus terminal
        if (webView != null) {
            callJs("focusTerminal()");
        }
    }
}
