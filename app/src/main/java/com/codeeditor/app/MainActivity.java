package com.codeeditor.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.codeeditor.app.editor.EditorActivity;
import com.codeeditor.app.filemanager.FileManagerActivity;
import com.codeeditor.app.filemanager.FileListAdapter;
import com.codeeditor.app.filemanager.FileItem;
import com.codeeditor.app.runner.TerminalActivity;
import com.codeeditor.app.utils.PreferenceManager;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Main Activity - Displays the home screen with quick actions and recent files.
 * iOS-style design with clean cards and smooth navigation.
 */
public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CODE = 1001;
    private RecyclerView rvRecentFiles;
    private TextView tvWelcome;
    private CardView cardNewFile;
    private CardView cardOpenFile;
    private CardView cardFileManager;
    private CardView cardSettings;
    private CardView cardTerminal;
    private PreferenceManager prefManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefManager = new PreferenceManager(this);

        // Request storage permissions
        requestStoragePermissions();

        initViews();
        setupClickListeners();
        loadRecentFiles();
    }

    private void initViews() {
        rvRecentFiles = findViewById(R.id.rv_recent_files);
        tvWelcome = findViewById(R.id.tv_welcome);
        cardNewFile = findViewById(R.id.card_new_file);
        cardOpenFile = findViewById(R.id.card_open_file);
        cardFileManager = findViewById(R.id.card_file_manager);
        cardSettings = findViewById(R.id.card_settings);
        cardTerminal = findViewById(R.id.card_terminal);

        rvRecentFiles.setLayoutManager(new GridLayoutManager(this, 2));
    }

    private void setupClickListeners() {
        // New File - Open editor with empty file
        cardNewFile.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, EditorActivity.class);
            intent.putExtra(EditorActivity.EXTRA_NEW_FILE, true);
            startActivity(intent);
        });

        // Open File - Open file manager
        cardOpenFile.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, FileManagerActivity.class);
            intent.putExtra(FileManagerActivity.EXTRA_MODE, FileManagerActivity.MODE_OPEN);
            startActivity(intent);
        });

        // File Manager - Full file browser
        cardFileManager.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, FileManagerActivity.class);
            intent.putExtra(FileManagerActivity.EXTRA_MODE, FileManagerActivity.MODE_BROWSE);
            startActivity(intent);
        });

        // Settings
        cardSettings.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, SettingsActivity.class);
            startActivity(intent);
        });

        // Terminal - Open interactive terminal
        cardTerminal.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, TerminalActivity.class);
            startActivity(intent);
        });
    }

    /**
     * Load recent files from preferences and display them.
     */
    private void loadRecentFiles() {
        List<String> recentPaths = prefManager.getRecentFiles();
        List<FileItem> recentFiles = new ArrayList<>();

        for (String path : recentPaths) {
            File file = new File(path);
            if (file.exists()) {
                FileItem item = new FileItem();
                item.setName(file.getName());
                item.setPath(file.getAbsolutePath());
                item.setDirectory(file.isDirectory());
                item.setSize(file.length());
                item.setLastModified(file.lastModified());
                recentFiles.add(item);
            }
        }

        if (recentFiles.isEmpty()) {
            rvRecentFiles.setVisibility(View.GONE);
            tvWelcome.setVisibility(View.VISIBLE);
        } else {
            rvRecentFiles.setVisibility(View.VISIBLE);
            tvWelcome.setVisibility(View.GONE);
            FileListAdapter adapter = new FileListAdapter(recentFiles, item -> {
                if (item.isDirectory()) {
                    openFileManager(item.getPath());
                } else {
                    openEditor(item.getPath());
                }
            }, item -> {
                // Long press on recent file - open in editor
                if (!item.isDirectory()) {
                    openEditor(item.getPath());
                }
            });
            rvRecentFiles.setAdapter(adapter);
        }
    }

    private void openEditor(String filePath) {
        Intent intent = new Intent(this, EditorActivity.class);
        intent.putExtra(EditorActivity.EXTRA_FILE_PATH, filePath);
        startActivity(intent);
    }

    private void openFileManager(String path) {
        Intent intent = new Intent(this, FileManagerActivity.class);
        intent.putExtra(FileManagerActivity.EXTRA_PATH, path);
        startActivity(intent);
    }

    /**
     * Request necessary storage permissions based on Android version.
     */
    private void requestStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(android.net.Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{
                                Manifest.permission.READ_EXTERNAL_STORAGE,
                                Manifest.permission.WRITE_EXTERNAL_STORAGE
                        }, PERMISSION_REQUEST_CODE);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                loadRecentFiles();
            } else {
                Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        } else if (id == R.id.action_file_manager) {
            startActivity(new Intent(this, FileManagerActivity.class));
            return true;
        } else if (id == R.id.action_terminal) {
            startActivity(new Intent(this, TerminalActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadRecentFiles();
    }
}
