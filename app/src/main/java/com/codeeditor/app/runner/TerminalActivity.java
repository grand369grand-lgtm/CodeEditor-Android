package com.codeeditor.app.runner;

import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.codeeditor.app.R;

/**
 * Terminal Activity - Displays code execution output.
 * iOS-style terminal/console with monospace font and dark theme.
 */
public class TerminalActivity extends AppCompatActivity {

    public static final String EXTRA_OUTPUT = "output";
    public static final String EXTRA_LANGUAGE = "language";
    public static final String EXTRA_FILE_NAME = "file_name";

    private ScrollView scrollView;
    private TextView tvOutput;
    private TextView tvTitle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_terminal);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.terminal);
        }

        initViews();
        displayOutput();
    }

    private void initViews() {
        scrollView = findViewById(R.id.scroll_output);
        tvOutput = findViewById(R.id.tv_output);
        tvTitle = findViewById(R.id.tv_terminal_title);
    }

    private void displayOutput() {
        Bundle extras = getIntent().getExtras();
        if (extras != null) {
            String output = extras.getString(EXTRA_OUTPUT, "");
            String language = extras.getString(EXTRA_LANGUAGE, "");
            String fileName = extras.getString(EXTRA_FILE_NAME, "untitled");

            // Build header
            StringBuilder display = new StringBuilder();
            display.append("═══════════════════════════════════\n");
            display.append("  CodeEditor Terminal\n");
            display.append("  File: ").append(fileName).append("\n");
            display.append("  Language: ").append(language.toUpperCase()).append("\n");
            display.append("═══════════════════════════════════\n\n");

            if (output.isEmpty()) {
                display.append("(No output)\n");
            } else {
                display.append(output);
            }

            display.append("\n═══════════════════════════════════\n");
            display.append("  Process finished.\n");
            display.append("═══════════════════════════════════\n");

            tvOutput.setText(display.toString());
            tvTitle.setText(fileName + " - " + language.toUpperCase());

            // Auto-scroll to bottom
            scrollView.post(() -> scrollView.fullScroll(ScrollView.FOCUS_DOWN));
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_terminal, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) {
            finish();
            return true;
        } else if (id == R.id.action_copy_output) {
            copyOutputToClipboard();
            return true;
        } else if (id == R.id.action_clear) {
            tvOutput.setText("");
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void copyOutputToClipboard() {
        String output = tvOutput.getText().toString();
        android.content.ClipboardManager clipboard =
                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        android.content.ClipData clip = android.content.ClipData.newPlainText(
                "Code Output", output);
        clipboard.setPrimaryClip(clip);
        android.widget.Toast.makeText(this, R.string.copied_to_clipboard,
                android.widget.Toast.LENGTH_SHORT).show();
    }
}
