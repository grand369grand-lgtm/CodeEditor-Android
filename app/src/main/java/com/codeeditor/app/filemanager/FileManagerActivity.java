package com.codeeditor.app.filemanager;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.codeeditor.app.R;
import com.codeeditor.app.editor.EditorActivity;
import com.codeeditor.app.utils.FileUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Stack;

/**
 * File Manager Activity - Browse, create, rename, and delete files/folders.
 * iOS-style file browser with grouped sections and smooth animations.
 * Provides real device filesystem access.
 */
public class FileManagerActivity extends AppCompatActivity {

    public static final String EXTRA_PATH = "path";
    public static final String EXTRA_MODE = "mode";
    public static final String MODE_OPEN = "open";
    public static final String MODE_BROWSE = "browse";

    private RecyclerView rvFiles;
    private TextView tvCurrentPath;
    private TextView tvEmptyState;
    private LinearLayout breadcrumbBar;

    private FileListAdapter adapter;
    private String currentPath;
    private String mode;
    private Stack<String> pathHistory;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_file_manager);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.file_manager);
        }

        pathHistory = new Stack<>();
        mode = MODE_BROWSE;

        initViews();
        parseIntent();
        loadDirectory(currentPath);
    }

    private void initViews() {
        rvFiles = findViewById(R.id.rv_files);
        tvCurrentPath = findViewById(R.id.tv_current_path);
        tvEmptyState = findViewById(R.id.tv_empty_state);
        breadcrumbBar = findViewById(R.id.breadcrumb_bar);

        rvFiles.setLayoutManager(new LinearLayoutManager(this));
        adapter = new FileListAdapter(new ArrayList<>(), this::onFileClicked);
        rvFiles.setAdapter(adapter);
    }

    private void parseIntent() {
        Intent intent = getIntent();
        if (intent != null) {
            mode = intent.getStringExtra(EXTRA_MODE);
            if (mode == null) mode = MODE_BROWSE;

            currentPath = intent.getStringExtra(EXTRA_PATH);
        }

        if (currentPath == null) {
            currentPath = com.codeeditor.app.CodeEditorApp.getWorkDirectory();
        }

        File dir = new File(currentPath);
        if (!dir.exists() || !dir.isDirectory()) {
            currentPath = android.os.Environment.getExternalStorageDirectory().getAbsolutePath();
        }
    }

    /**
     * Load and display files in the given directory.
     */
    private void loadDirectory(String path) {
        File directory = new File(path);
        if (!directory.exists() || !directory.isDirectory()) {
            Toast.makeText(this, R.string.directory_not_found, Toast.LENGTH_SHORT).show();
            return;
        }

        currentPath = path;
        tvCurrentPath.setText(path);

        File[] files = directory.listFiles();
        List<FileItem> items = new ArrayList<>();

        if (files != null) {
            List<FileItem> folders = new ArrayList<>();
            List<FileItem> fileItems = new ArrayList<>();

            for (File file : files) {
                // Skip hidden files
                if (file.isHidden()) continue;

                FileItem item = new FileItem();
                item.setName(file.getName());
                item.setPath(file.getAbsolutePath());
                item.setDirectory(file.isDirectory());
                item.setSize(file.length());
                item.setLastModified(file.lastModified());

                if (file.isDirectory()) {
                    folders.add(item);
                } else {
                    fileItems.add(item);
                }
            }

            // Sort folders and files alphabetically
            Collections.sort(folders, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            Collections.sort(fileItems, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));

            items.addAll(folders);
            items.addAll(fileItems);
        }

        if (items.isEmpty()) {
            tvEmptyState.setVisibility(View.VISIBLE);
            rvFiles.setVisibility(View.GONE);
        } else {
            tvEmptyState.setVisibility(View.GONE);
            rvFiles.setVisibility(View.VISIBLE);
        }

        adapter.updateItems(items);
    }

    /**
     * Handle file/folder click events.
     */
    private void onFileClicked(FileItem item) {
        if (item.isDirectory()) {
            pathHistory.push(currentPath);
            loadDirectory(item.getPath());
        } else {
            // Open file in editor
            if (MODE_OPEN.equals(mode)) {
                Intent resultIntent = new Intent();
                resultIntent.putExtra(EXTRA_PATH, item.getPath());
                setResult(RESULT_OK, resultIntent);
                finish();
            } else {
                Intent intent = new Intent(this, EditorActivity.class);
                intent.putExtra(EditorActivity.EXTRA_FILE_PATH, item.getPath());
                startActivity(intent);
            }
        }
    }

    /**
     * Navigate back in directory history.
     */
    private void navigateUp() {
        if (!pathHistory.isEmpty()) {
            String previousPath = pathHistory.pop();
            loadDirectory(previousPath);
        } else {
            File current = new File(currentPath);
            File parent = current.getParentFile();
            if (parent != null && parent.canRead()) {
                loadDirectory(parent.getAbsolutePath());
            } else {
                finish();
            }
        }
    }

    /**
     * Show dialog to create a new file or folder.
     */
    private void showNewDialog() {
        String[] options = {getString(R.string.new_file), getString(R.string.new_folder)};
        new AlertDialog.Builder(this)
                .setTitle(R.string.create_new)
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        showNewFileDialog();
                    } else {
                        showNewFolderDialog();
                    }
                })
                .show();
    }

    private void showNewFileDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.new_file);

        EditText input = new EditText(this);
        input.setHint(R.string.enter_file_name);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        builder.setView(input);

        builder.setPositiveButton(R.string.create, (dialog, which) -> {
            String name = input.getText().toString().trim();
            if (!name.isEmpty()) {
                String newPath = currentPath + "/" + name;
                boolean created = FileUtils.createNewFile(newPath);
                if (created) {
                    loadDirectory(currentPath);
                    Toast.makeText(this, R.string.file_created, Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, R.string.file_create_failed, Toast.LENGTH_SHORT).show();
                }
            }
        });
        builder.setNegativeButton(R.string.cancel, null);
        builder.show();
    }

    private void showNewFolderDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.new_folder);

        EditText input = new EditText(this);
        input.setHint(R.string.enter_folder_name);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        builder.setView(input);

        builder.setPositiveButton(R.string.create, (dialog, which) -> {
            String name = input.getText().toString().trim();
            if (!name.isEmpty()) {
                String newPath = currentPath + "/" + name;
                boolean created = new File(newPath).mkdirs();
                if (created) {
                    loadDirectory(currentPath);
                    Toast.makeText(this, R.string.folder_created, Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, R.string.folder_create_failed, Toast.LENGTH_SHORT).show();
                }
            }
        });
        builder.setNegativeButton(R.string.cancel, null);
        builder.show();
    }

    /**
     * Show rename dialog for a file or folder.
     */
    public void showRenameDialog(FileItem item) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.rename);

        EditText input = new EditText(this);
        input.setText(item.getName());
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        builder.setView(input);

        builder.setPositiveButton(R.string.rename, (dialog, which) -> {
            String newName = input.getText().toString().trim();
            if (!newName.isEmpty()) {
                File oldFile = new File(item.getPath());
                File newFile = new File(oldFile.getParent(), newName);
                boolean renamed = oldFile.renameTo(newFile);
                if (renamed) {
                    loadDirectory(currentPath);
                    Toast.makeText(this, R.string.renamed_successfully, Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, R.string.rename_failed, Toast.LENGTH_SHORT).show();
                }
            }
        });
        builder.setNegativeButton(R.string.cancel, null);
        builder.show();
    }

    /**
     * Show delete confirmation for a file or folder.
     */
    public void showDeleteDialog(FileItem item) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.confirm_delete)
                .setMessage(getString(R.string.delete_message, item.getName()))
                .setPositiveButton(R.string.delete, (dialog, which) -> {
                    File file = new File(item.getPath());
                    boolean deleted = FileUtils.deleteRecursively(file);
                    if (deleted) {
                        loadDirectory(currentPath);
                        Toast.makeText(this, R.string.deleted_successfully, Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, R.string.delete_failed, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_file_manager, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) {
            navigateUp();
            return true;
        } else if (id == R.id.action_new) {
            showNewDialog();
            return true;
        } else if (id == R.id.action_refresh) {
            loadDirectory(currentPath);
            return true;
        } else if (id == R.id.action_home) {
            pathHistory.clear();
            loadDirectory(com.codeeditor.app.CodeEditorApp.getWorkDirectory());
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onBackPressed() {
        File current = new File(currentPath);
        File parent = current.getParentFile();
        boolean isRoot = currentPath.equals(
                android.os.Environment.getExternalStorageDirectory().getAbsolutePath());

        if (isRoot || !pathHistory.isEmpty()) {
            navigateUp();
        } else {
            super.onBackPressed();
        }
    }
}
