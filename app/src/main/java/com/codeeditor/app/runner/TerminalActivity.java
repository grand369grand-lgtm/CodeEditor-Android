package com.codeeditor.app.runner;

import android.annotation.SuppressLint;
import android.content.Intent;
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
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.codeeditor.app.R;
import com.codeeditor.app.utils.FileUtils;

import java.io.File;

/**
 * Terminal Activity - Interactive shell terminal emulator (Termux-like).
 * Features:
 * - Interactive /system/bin/sh session
 * - Real-time output streaming with ANSI color support
 * - Special keys toolbar (Tab, Ctrl, Esc, arrows, etc.)
 * - Command history navigation
 * - Run code from the editor in the terminal
 * - Working directory management
 * - iOS-style dark theme terminal UI
 */
public class TerminalActivity extends AppCompatActivity {

    private static final String TAG = "TerminalActivity";

    // Intent extras
    public static final String EXTRA_OUTPUT = "output";
    public static final String EXTRA_LANGUAGE = "language";
    public static final String EXTRA_FILE_NAME = "file_name";
    public static final String EXTRA_WORK_DIR = "work_dir";
    public static final String EXTRA_RUN_CODE = "run_code";
    public static final String EXTRA_CODE = "code";

    private WebView webView;
    private TerminalSession session;
    private HorizontalScrollView specialKeysBar;
    private TextView tvSessionInfo;

    private String currentWorkDir;
    private boolean ctrlActive = false;
    private TextView btnCtrl;

    private Handler mainHandler;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_terminal);

        mainHandler = new Handler(Looper.getMainLooper());

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.terminal);
        }

        initViews();
        setupWebView();
        setupSpecialKeys();
        parseIntentAndStartSession();
    }

    private void initViews() {
        webView = findViewById(R.id.webview_terminal);
        specialKeysBar = findViewById(R.id.special_keys_bar);
        tvSessionInfo = findViewById(R.id.tv_session_info);
        btnCtrl = findViewById(R.id.btn_ctrl);
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

        // Add JavaScript interface for bidirectional communication
        webView.addJavascriptInterface(new TerminalJsInterface(), "AndroidTerminal");

        // Load the terminal HTML
        webView.loadUrl("file:///android_asset/terminal/terminal.html");
    }

    /**
     * Set up the special keys toolbar (Tab, Ctrl, Esc, arrows, etc.)
     */
    private void setupSpecialKeys() {
        // ESC
        findViewById(R.id.btn_esc).setOnClickListener(v ->
                callJs("sendKey('ESC')"));

        // Tab
        findViewById(R.id.btn_tab).setOnClickListener(v ->
                callJs("sendKey('TAB')"));

        // Ctrl - toggle mode
        btnCtrl.setOnClickListener(v -> {
            ctrlActive = !ctrlActive;
            btnCtrl.setTextColor(ctrlActive ?
                    getColor(R.color.ios_blue) : getColor(R.color.terminal_text));
            if (ctrlActive) {
                Toast.makeText(this, "Ctrl mode ON - tap a key", Toast.LENGTH_SHORT).show();
            }
        });

        // Arrow keys
        findViewById(R.id.btn_arrow_up).setOnClickListener(v ->
                callJs("sendKey('UP')"));
        findViewById(R.id.btn_arrow_down).setOnClickListener(v ->
                callJs("sendKey('DOWN')"));
        findViewById(R.id.btn_arrow_left).setOnClickListener(v ->
                callJs("sendKey('LEFT')"));
        findViewById(R.id.btn_arrow_right).setOnClickListener(v ->
                callJs("sendKey('RIGHT')"));

        // Home / End
        findViewById(R.id.btn_home).setOnClickListener(v ->
                callJs("sendKey('HOME')"));
        findViewById(R.id.btn_end).setOnClickListener(v ->
                callJs("sendKey('END')"));

        // Pipe
        findViewById(R.id.btn_pipe).setOnClickListener(v -> {
            if (ctrlActive) {
                // Ctrl+\ = send SIGQUIT
                sendCtrlChar("\\");
                ctrlActive = false;
                btnCtrl.setTextColor(getColor(R.color.terminal_text));
            } else {
                callJs("insertText('|')");
            }
        });

        // Slash
        findViewById(R.id.btn_slash).setOnClickListener(v ->
                callJs("insertText('/')"));

        // Tilde
        findViewById(R.id.btn_tilde).setOnClickListener(v ->
                callJs("insertText('~')"));

        // Dollar
        findViewById(R.id.btn_dollar).setOnClickListener(v ->
                callJs("insertText('$')"));

        // Ampersand
        findViewById(R.id.btn_ampersand).setOnClickListener(v ->
                callJs("insertText('&')"));

        // Semicolon
        findViewById(R.id.btn_semicolon).setOnClickListener(v ->
                callJs("insertText(';')"));

        // Exclamation
        findViewById(R.id.btn_exclamation).setOnClickListener(v ->
                callJs("insertText('!')"));

        // Less than
        findViewById(R.id.btn_less).setOnClickListener(v ->
                callJs("insertText('<')"));

        // Greater than
        findViewById(R.id.btn_greater).setOnClickListener(v ->
                callJs("insertText('>')"));

        // Single quote
        findViewById(R.id.btn_quote).setOnClickListener(v ->
                callJs("insertText(\"'\")"));

        // Double quote
        findViewById(R.id.btn_double_quote).setOnClickListener(v ->
                callJs("insertText('\"')"));

        // Delete
        findViewById(R.id.btn_del).setOnClickListener(v ->
                callJs("sendKey('DEL')"));
    }

    /**
     * Send a Ctrl+key combination to the terminal session.
     */
    private void sendCtrlChar(String key) {
        if (session != null && session.isRunning()) {
            switch (key.toLowerCase()) {
                case "c":
                    session.sendCtrlC();
                    break;
                case "d":
                    session.sendCtrlD();
                    break;
                case "z":
                    session.sendCtrlZ();
                    break;
                case "l":
                    callJs("clearTerminal()");
                    session.executeCommand("");
                    break;
                default:
                    // For other Ctrl combos, send as raw byte
                    char ctrlChar = (char) (key.toLowerCase().charAt(0) - 'a' + 1);
                    session.write(String.valueOf(ctrlChar));
                    break;
            }
        }
    }

    /**
     * Parse intent extras and start the terminal session.
     * Supports both interactive mode and code execution mode.
     */
    private void parseIntentAndStartSession() {
        Intent intent = getIntent();
        if (intent == null) {
            startShellSession(null);
            return;
        }

        // Get working directory
        currentWorkDir = intent.getStringExtra(EXTRA_WORK_DIR);
        if (currentWorkDir == null) {
            currentWorkDir = com.codeeditor.app.CodeEditorApp.getWorkDirectory();
        }

        // Check if opened with static output (backward compatibility)
        String staticOutput = intent.getStringExtra(EXTRA_OUTPUT);
        String code = intent.getStringExtra(EXTRA_CODE);
        boolean runCode = intent.getBooleanExtra(EXTRA_RUN_CODE, false);

        // Start shell session first
        startShellSession(currentWorkDir);

        // If there's code to execute, run it after a delay
        if (code != null && !code.isEmpty() && runCode) {
            String language = intent.getStringExtra(EXTRA_LANGUAGE);
            mainHandler.postDelayed(() -> executeCodeInTerminal(code, language), 1500);
        } else if (staticOutput != null && !staticOutput.isEmpty()) {
            // Legacy: display static output
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
     * Start an interactive shell session.
     */
    private void startShellSession(String workDir) {
        String[] env = new String[]{
                "TERM=xterm-256color",
                "HOME=" + (workDir != null ? workDir : "/"),
                "LANG=en_US.UTF-8"
        };

        session = new TerminalSession(workDir, env, new TerminalSession.SessionCallback() {
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
                runOnUiThread(() -> {
                    appendOutput("\n[Error: " + error + "]\n");
                });
            }
        });

        session.start();
        updateSessionInfo(true);
    }

    /**
     * Execute code in the terminal by writing to the shell.
     */
    private void executeCodeInTerminal(String code, String language) {
        if (session == null || !session.isRunning()) return;

        String workDir = com.codeeditor.app.CodeEditorApp.getWorkDirectory();
        String ext = "txt";
        String interpreter = null;

        if (language != null) {
            switch (language.toLowerCase()) {
                case "python":
                case "py":
                    ext = "py";
                    interpreter = "python3";
                    break;
                case "cpp":
                case "c":
                    ext = "cpp";
                    break;
                case "java":
                    ext = "java";
                    break;
                case "javascript":
                case "js":
                    ext = "js";
                    interpreter = "node";
                    break;
                case "sh":
                case "bash":
                    ext = "sh";
                    interpreter = "sh";
                    break;
            }
        }

        // Write code to a temp file
        String tempFile = workDir + "/temp_terminal_run." + ext;
        FileUtils.writeFile(tempFile, code);

        if (interpreter != null) {
            // Run with interpreter
            session.executeCommand(interpreter + " " + tempFile);
        } else if ("cpp".equals(ext) || "c".equals(ext)) {
            // Compile and run C/C++
            String outFile = workDir + "/temp_terminal_run_out";
            session.executeCommand("g++ -o " + outFile + " " + tempFile + " && " + outFile + "; rm -f " + outFile);
        } else if ("java".equals(ext)) {
            // Compile and run Java
            String className = "temp_terminal_run";
            session.executeCommand("cd " + workDir + " && javac " + tempFile + " && java " + className);
        } else {
            // Try as shell script
            session.executeCommand("cat " + tempFile);
        }
    }

    /**
     * Append output text to the terminal WebView.
     */
    private void appendOutput(String text) {
        if (text == null) return;
        try {
            // Use URL encoding to safely pass text with ANSI codes to JavaScript
            String encoded = java.net.URLEncoder.encode(text, "UTF-8")
                    .replace("+", "%20")
                    .replace("'", "%27");
            callJs("appendTerminalOutput(decodeURIComponent('" + encoded + "'))");
        } catch (java.io.UnsupportedEncodingException e) {
            // Fallback: simple escape approach (no ANSI support)
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

    /**
     * Call a JavaScript function in the WebView.
     */
    private void callJs(String js) {
        if (webView != null) {
            webView.evaluateJavascript(js, null);
        }
    }

    /**
     * Update session info display.
     */
    private void updateSessionInfo(boolean running) {
        if (tvSessionInfo != null) {
            tvSessionInfo.setText(running ? "● Shell Active" : "○ Shell Ended");
            tvSessionInfo.setTextColor(getColor(running ? R.color.ios_green : R.color.ios_red));
        }
    }

    // ===== JavaScript Interface =====

    private class TerminalJsInterface {
        @JavascriptInterface
        public void onCommand(String command) {
            if (session != null && session.isRunning()) {
                session.write(command);
            }
        }

        @JavascriptInterface
        public void onCtrlC() {
            if (session != null && session.isRunning()) {
                session.sendCtrlC();
            }
        }

        @JavascriptInterface
        public void onCtrlD() {
            if (session != null && session.isRunning()) {
                session.sendCtrlD();
            }
        }

        @JavascriptInterface
        public void onCtrlZ() {
            if (session != null && session.isRunning()) {
                session.sendCtrlZ();
            }
        }

        @JavascriptInterface
        public void onEscape() {
            // ESC key pressed - could be used for vi/nano
            if (session != null && session.isRunning()) {
                session.write("\u001b");
            }
        }

        @JavascriptInterface
        public void onTab(String currentInput) {
            if (session != null && session.isRunning()) {
                session.write("\t");
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
            // Set prompt
            String prompt = "$ ";
            if (currentWorkDir != null) {
                String shortDir = new File(currentWorkDir).getName();
                prompt = shortDir + " $ ";
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
        }
        return super.onOptionsItemSelected(item);
    }

    /**
     * Restart the shell session.
     */
    private void restartSession() {
        if (session != null) {
            session.destroy();
        }
        callJs("clearTerminal()");
        appendOutput("\u001b[33m[Starting new shell session...]\u001b[0m\n\n");
        startShellSession(currentWorkDir);
    }

    /**
     * Show dialog to run a file in the terminal.
     */
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
                    String path = selected.getAbsolutePath();

                    String command;
                    if (name.endsWith(".py")) {
                        command = "python3 " + path;
                    } else if (name.endsWith(".cpp") || name.endsWith(".c")) {
                        String outPath = workDir + "/run_out";
                        command = "g++ -o " + outPath + " " + path + " && " + outPath + "; rm -f " + outPath;
                    } else if (name.endsWith(".java")) {
                        String className = name.substring(0, name.length() - 5);
                        command = "cd " + workDir + " && javac " + name + " && java " + className;
                    } else if (name.endsWith(".sh")) {
                        command = "sh " + path;
                    } else {
                        command = "cat " + path;
                    }

                    if (session != null && session.isRunning()) {
                        session.executeCommand(command);
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /**
     * Paste text from clipboard into the terminal.
     */
    private void pasteFromClipboard() {
        android.content.ClipboardManager clipboard =
                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard.hasPrimaryClip()) {
            android.content.ClipData.Item item = clipboard.getPrimaryClip().getItemAt(0);
            if (item != null && item.getText() != null) {
                String text = item.getText().toString();
                if (session != null && session.isRunning()) {
                    session.write(text);
                }
            }
        } else {
            Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Copy terminal output to clipboard.
     */
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
        if (session != null) {
            session.destroy();
            session = null;
        }
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Keep session running in background
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Re-focus terminal
        callJs("focusTerminal()");
    }
}
