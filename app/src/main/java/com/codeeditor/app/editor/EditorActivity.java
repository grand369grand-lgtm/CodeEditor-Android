package com.codeeditor.app.editor;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import android.net.Uri;

import com.codeeditor.app.R;
import com.codeeditor.app.filemanager.FileManagerActivity;
import com.codeeditor.app.filemanager.SafHelper;
import com.codeeditor.app.runner.NativeRunner;
import com.codeeditor.app.runner.TerminalActivity;
import com.codeeditor.app.utils.FileUtils;
import com.codeeditor.app.utils.LanguageDetector;
import com.codeeditor.app.utils.PreferenceManager;

import java.io.File;

/**
 * Editor Activity - Contains Monaco Editor loaded in WebView.
 * Handles file loading, saving, and code execution.
 * iOS-style toolbar and navigation design.
 */
public class EditorActivity extends AppCompatActivity {

    public static final String EXTRA_FILE_PATH = "file_path";
    public static final String EXTRA_NEW_FILE = "new_file";

    private WebView webView;
    private ProgressBar progressBar;
    private HorizontalScrollView toolbarExtension;
    private TextView tvFileName;
    private TextView tvLanguage;

    private String filePath;
    private String fileContent = "";
    private String currentLanguage = "plaintext";
    private boolean isNewFile = false;
    private boolean isModified = false;
    private PreferenceManager prefManager;
    private NativeRunner nativeRunner;

    // SAF support
    private Uri safUri;             // SAF content URI for the file
    private Uri safTreeUri;        // SAF tree URI for the parent directory
    private boolean isSafFile = false;  // true if file opened via SAF

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_editor);

        prefManager = new PreferenceManager(this);

        // Initialize native runner safely
        try {
            nativeRunner = new NativeRunner(this);
        } catch (UnsatisfiedLinkError e) {
            nativeRunner = null;
            android.util.Log.w("EditorActivity", "Native library not available", e);
        }

        initViews();
        setupToolbarButtons();
        parseIntent();
        setupWebView();
        setupToolbar();
    }

    private void initViews() {
        webView = findViewById(R.id.webview_editor);
        progressBar = findViewById(R.id.progress_bar);
        toolbarExtension = findViewById(R.id.toolbar_extension);
        tvFileName = findViewById(R.id.tv_file_name);
        tvLanguage = findViewById(R.id.tv_language);
    }

    /**
     * Set up click listeners for the toolbar extension buttons.
     */
    private void setupToolbarButtons() {
        ImageView btnUndo = findViewById(R.id.btn_undo);
        ImageView btnRedo = findViewById(R.id.btn_redo);
        ImageView btnFind = findViewById(R.id.btn_find);
        ImageView btnReplace = findViewById(R.id.btn_replace);
        ImageView btnGotoLine = findViewById(R.id.btn_goto_line);
        ImageView btnFormat = findViewById(R.id.btn_format);
        ImageView btnRun = findViewById(R.id.btn_run);

        if (btnUndo != null) btnUndo.setOnClickListener(v ->
                webView.evaluateJavascript("if(editor) editor.trigger('keyboard','undo');", null));
        if (btnRedo != null) btnRedo.setOnClickListener(v ->
                webView.evaluateJavascript("if(editor) editor.trigger('keyboard','redo');", null));
        if (btnFind != null) btnFind.setOnClickListener(v ->
                webView.evaluateJavascript("if(editor) editor.getAction('actions.find').run();", null));
        if (btnReplace != null) btnReplace.setOnClickListener(v ->
                webView.evaluateJavascript("if(editor) editor.getAction('editor.action.startFindReplaceAction').run();", null));
        if (btnGotoLine != null) btnGotoLine.setOnClickListener(v -> showGotoLineDialog());
        if (btnFormat != null) btnFormat.setOnClickListener(v ->
                webView.evaluateJavascript("if(editor) editor.getAction('editor.action.formatDocument').run();", null));
        if (btnRun != null) btnRun.setOnClickListener(v -> runCode());
    }

    private void parseIntent() {
        Intent intent = getIntent();
        if (intent != null) {
            filePath = intent.getStringExtra(EXTRA_FILE_PATH);
            isNewFile = intent.getBooleanExtra(EXTRA_NEW_FILE, false);

            // Check for SAF file
            isSafFile = intent.getBooleanExtra("is_saf", false);
            String safUriStr = intent.getStringExtra("saf_uri");
            String safTreeUriStr = intent.getStringExtra("saf_tree_uri");
            if (safUriStr != null) {
                safUri = Uri.parse(safUriStr);
            }
            if (safTreeUriStr != null) {
                safTreeUri = Uri.parse(safTreeUriStr);
            }
        }

        if (isSafFile && safUri != null) {
            // SAF file - read via content resolver
            String fileName = filePath != null ? new File(filePath).getName() : "SAF File";
            tvFileName.setText(fileName);
            currentLanguage = LanguageDetector.detectLanguage(fileName);
            tvLanguage.setText(currentLanguage.toUpperCase());
            fileContent = SafHelper.readSafFile(this, safUri);
        } else if (filePath != null) {
            File file = new File(filePath);
            tvFileName.setText(file.getName());
            currentLanguage = LanguageDetector.detectLanguage(file.getName());
            tvLanguage.setText(currentLanguage.toUpperCase());
            fileContent = FileUtils.readFile(filePath);
        } else if (isNewFile) {
            tvFileName.setText(R.string.untitled);
            currentLanguage = "plaintext";
            tvLanguage.setText("TEXT");
            fileContent = "";
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setSupportZoom(false);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setMediaPlaybackRequiresUserGesture(false);

        webView.setWebViewClient(new EditorWebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (newProgress == 100) {
                    progressBar.setVisibility(View.GONE);
                    // Editor loaded, inject code
                    loadCodeIntoEditor();
                } else {
                    progressBar.setVisibility(View.VISIBLE);
                }
            }
        });

        // Add JavaScript interface for communication from Monaco to Java
        webView.addJavascriptInterface(new EditorJsInterface(), "AndroidEditor");

        // Load the Monaco Editor HTML from assets
        webView.loadUrl("file:///android_asset/editor/monaco.html");
    }

    private void setupToolbar() {
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("");
        }
    }

    /**
     * Load file content into Monaco Editor via JavaScript.
     */
    private void loadCodeIntoEditor() {
        if (fileContent != null) {
            String escaped = escapeForJs(fileContent);
            String theme = prefManager.isDarkMode() ? "vs-dark" : "vs";
            int fontSize = prefManager.getFontSize();
            boolean wordWrap = prefManager.isWordWrap();
            boolean lineNumbers = prefManager.showLineNumbers();
            boolean minimap = prefManager.showMinimap();
            int tabSize = prefManager.getTabSize();

            String js = String.format(
                    "if(typeof setEditorContent === 'function'){" +
                            "setEditorContent('%s','%s','%s',%d,%b,%b,%b,%d);" +
                            "}",
                    escaped, currentLanguage, theme, fontSize, wordWrap, lineNumbers, minimap, tabSize
            );

            new Handler(Looper.getMainLooper()).postDelayed(() ->
                    webView.evaluateJavascript(js, null), 500);
        }
    }

    /**
     * Escape special characters for JavaScript string injection.
     */
    private String escapeForJs(String text) {
        if (text == null) return "";
        return text
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * Save the current file content.
     */
    private void saveFile() {
        webView.evaluateJavascript("getEditorContent();", value -> {
            if (value != null && !"null".equals(value)) {
                // Remove surrounding quotes from JSON string
                String content = value;
                if (content.startsWith("\"") && content.endsWith("\"")) {
                    content = content.substring(1, content.length() - 1);
                }
                // Unescape
                content = content
                        .replace("\\n", "\n")
                        .replace("\\r", "\r")
                        .replace("\\t", "\t")
                        .replace("\\\"", "\"")
                        .replace("\\'", "'")
                        .replace("\\\\", "\\");

                fileContent = content;

                if (isSafFile && safUri != null) {
                    // Save via SAF
                    boolean saved = SafHelper.writeSafFile(EditorActivity.this, safUri, content);
                    if (saved) {
                        isModified = false;
                        invalidateOptionsMenu();
                        Toast.makeText(EditorActivity.this, R.string.file_saved, Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(EditorActivity.this, R.string.file_save_failed, Toast.LENGTH_SHORT).show();
                    }
                } else if (filePath != null) {
                    boolean saved = FileUtils.writeFile(filePath, content);
                    if (saved) {
                        isModified = false;
                        invalidateOptionsMenu();
                        Toast.makeText(EditorActivity.this, R.string.file_saved, Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(EditorActivity.this, R.string.file_save_failed, Toast.LENGTH_SHORT).show();
                    }
                } else {
                    // New file - prompt for save location
                    showSaveAsDialog();
                }
            }
        });
    }

    /**
     * Show save-as dialog for new files.
     */
    private void showSaveAsDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.save_file);

        View dialogView = getLayoutInflater().inflate(R.layout.dialog_save_file, null);
        builder.setView(dialogView);

        android.widget.EditText etFileName = dialogView.findViewById(R.id.et_file_name);

        builder.setPositiveButton(R.string.save, (dialog, which) -> {
            String fileName = etFileName.getText().toString().trim();
            if (!fileName.isEmpty()) {
                String dir = com.codeeditor.app.CodeEditorApp.getWorkDirectory();
                filePath = dir + "/" + fileName;
                tvFileName.setText(fileName);
                currentLanguage = LanguageDetector.detectLanguage(fileName);
                tvLanguage.setText(currentLanguage.toUpperCase());
                FileUtils.writeFile(filePath, fileContent);
                isNewFile = false;
                isModified = false;
                invalidateOptionsMenu();

                // Update editor language
                String js = String.format("setEditorLanguage('%s');", currentLanguage);
                webView.evaluateJavascript(js, null);

                Toast.makeText(this, R.string.file_saved, Toast.LENGTH_SHORT).show();
            }
        });

        builder.setNegativeButton(R.string.cancel, null);
        builder.show();
    }

    /**
     * Run the current code using C++ native runner.
     */
    private void runCode() {
        webView.evaluateJavascript("getEditorContent();", value -> {
            if (value != null && !"null".equals(value)) {
                String content = value;
                if (content.startsWith("\"") && content.endsWith("\"")) {
                    content = content.substring(1, content.length() - 1);
                }
                content = content
                        .replace("\\n", "\n")
                        .replace("\\r", "\r")
                        .replace("\\t", "\t")
                        .replace("\\\"", "\"")
                        .replace("\\'", "'")
                        .replace("\\\\", "\\");

                fileContent = content;

                // Execute code using native C++ runner
                String result;
                if (nativeRunner != null) {
                    result = nativeRunner.executeCode(currentLanguage, fileContent, filePath);
                } else {
                    result = "Native runner not available.\n" +
                            "The native library could not be loaded.\n" +
                            "Code execution requires the C++ native library.";
                }

                // Open terminal to show results
                Intent intent = new Intent(EditorActivity.this, TerminalActivity.class);
                intent.putExtra(TerminalActivity.EXTRA_OUTPUT, result);
                intent.putExtra(TerminalActivity.EXTRA_LANGUAGE, currentLanguage);
                intent.putExtra(TerminalActivity.EXTRA_FILE_NAME,
                        filePath != null ? new File(filePath).getName() : "untitled");
                startActivity(intent);
            }
        });
    }

    // ===== JavaScript Interface for Monaco → Java communication =====

    private class EditorJsInterface {
        @android.webkit.JavascriptInterface
        public void onEditorReady() {
            runOnUiThread(() -> {
                // Editor is ready, content will be loaded via loadCodeIntoEditor
            });
        }

        @android.webkit.JavascriptInterface
        public void onContentChanged() {
            runOnUiThread(() -> {
                isModified = true;
                invalidateOptionsMenu();
            });
        }

        @android.webkit.JavascriptInterface
        public void onSaveRequested() {
            runOnUiThread(() -> saveFile());
        }

        @android.webkit.JavascriptInterface
        public void onRunRequested() {
            runOnUiThread(() -> runCode());
        }

        @android.webkit.JavascriptInterface
        public String getContent() {
            return fileContent;
        }
    }

    // ===== WebView Client =====

    private class EditorWebViewClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return false;
        }
    }

    // ===== Menu =====

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_editor, menu);
        MenuItem saveItem = menu.findItem(R.id.action_save);
        if (saveItem != null) {
            saveItem.setVisible(isModified);
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) {
            handleBackPress();
            return true;
        } else if (id == R.id.action_save) {
            saveFile();
            return true;
        } else if (id == R.id.action_run) {
            runCode();
            return true;
        } else if (id == R.id.action_undo) {
            webView.evaluateJavascript("if(editor) editor.trigger('keyboard','undo');", null);
            return true;
        } else if (id == R.id.action_redo) {
            webView.evaluateJavascript("if(editor) editor.trigger('keyboard','redo');", null);
            return true;
        } else if (id == R.id.action_find) {
            webView.evaluateJavascript("if(editor) editor.getAction('actions.find').run();", null);
            return true;
        } else if (id == R.id.action_replace) {
            webView.evaluateJavascript("if(editor) editor.getAction('editor.action.startFindReplaceAction').run();", null);
            return true;
        } else if (id == R.id.action_format) {
            webView.evaluateJavascript("if(editor) editor.getAction('editor.action.formatDocument').run();", null);
            return true;
        } else if (id == R.id.action_goto_line) {
            showGotoLineDialog();
            return true;
        } else if (id == R.id.action_save_as) {
            showSaveAsDialog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void showGotoLineDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.goto_line);
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setHint(R.string.enter_line_number);
        builder.setView(input);
        builder.setPositiveButton(R.string.go, (dialog, which) -> {
            String lineStr = input.getText().toString().trim();
            if (!lineStr.isEmpty()) {
                int line = Integer.parseInt(lineStr);
                String js = String.format(
                        "editor.revealLineInCenter(%d);editor.setPosition({lineNumber:%d,column:1});",
                        line, line);
                webView.evaluateJavascript(js, null);
            }
        });
        builder.setNegativeButton(R.string.cancel, null);
        builder.show();
    }

    private void handleBackPress() {
        if (isModified) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.unsaved_changes)
                    .setMessage(R.string.unsaved_changes_message)
                    .setPositiveButton(R.string.save, (d, w) -> {
                        saveFile();
                        finish();
                    })
                    .setNegativeButton(R.string.discard, (d, w) -> finish())
                    .setNeutralButton(R.string.cancel, null)
                    .show();
        } else {
            finish();
        }
    }

    @Override
    public void onBackPressed() {
        handleBackPress();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }
}
