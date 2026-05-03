package com.codeeditor.app.filemanager;

import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.UriPermission;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.text.InputType;
import android.util.Log;
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
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.codeeditor.app.R;
import com.codeeditor.app.editor.EditorActivity;
import com.codeeditor.app.utils.FileUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Stack;

/**
 * File Manager Activity - Browse, create, rename, delete files/folders.
 * iOS-style file browser with SAF support for Android/data access.
 * Long-press files to edit in editor, open folders to browse and edit.
 */
public class FileManagerActivity extends AppCompatActivity {

    private static final String TAG = "FileManager";
    public static final String EXTRA_PATH = "path";
    public static final String EXTRA_MODE = "mode";
    public static final String MODE_OPEN = "open";
    public static final String MODE_BROWSE = "browse";
    public static final String EXTRA_FOLDER_URI = "folder_uri";

    private static final int REQUEST_OPEN_TREE = 2001;
    private static final int REQUEST_OPEN_TREE_ANDROID_DATA = 2002;

    private RecyclerView rvFiles;
    private TextView tvCurrentPath;
    private TextView tvEmptyState;
    private LinearLayout breadcrumbBar;
    private SwipeRefreshLayout swipeRefresh;

    private FileListAdapter adapter;
    private String currentPath;
    private String mode;
    private Stack<String> pathHistory;
    private Stack<Uri> treeUriHistory;     // For SAF navigation history
    private Uri currentTreeUri;            // Current SAF tree URI (for Android/data etc.)
    private boolean isSafMode = false;     // true if browsing via SAF
    private Uri pendingTreeUri;            // Temp storage during SAF permission request

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_file_manager);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.file_manager);
        }

        pathHistory = new Stack<>();
        treeUriHistory = new Stack<>();
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

        swipeRefresh = findViewById(R.id.swipe_refresh);
        if (swipeRefresh != null) {
            swipeRefresh.setColorSchemeResources(R.color.ios_blue);
            swipeRefresh.setOnRefreshListener(() -> {
                refreshDirectory();
                swipeRefresh.setRefreshing(false);
            });
        }

        rvFiles.setLayoutManager(new LinearLayoutManager(this));
        adapter = new FileListAdapter(new ArrayList<>(), this::onFileClick, this::onFileLongClick);
        rvFiles.setAdapter(adapter);
    }

    private void parseIntent() {
        Intent intent = getIntent();
        if (intent != null) {
            mode = intent.getStringExtra(EXTRA_MODE);
            if (mode == null) mode = MODE_BROWSE;

            currentPath = intent.getStringExtra(EXTRA_PATH);

            // Check if opened with a SAF tree URI
            String uriString = intent.getStringExtra(EXTRA_FOLDER_URI);
            if (uriString != null) {
                currentTreeUri = Uri.parse(uriString);
                isSafMode = true;
            }
        }

        if (currentPath == null && currentTreeUri == null) {
            currentPath = com.codeeditor.app.CodeEditorApp.getWorkDirectory();
        }

        if (currentPath != null) {
            File dir = new File(currentPath);
            if (!dir.exists() || !dir.isDirectory()) {
                currentPath = Environment.getExternalStorageDirectory().getAbsolutePath();
            }
        }
    }

    // =====================================================================
    // Directory Loading - Dual Mode (File API + SAF)
    // =====================================================================

    /**
     * Load directory - automatically selects File API or SAF based on the path.
     */
    private void loadDirectory(String path) {
        if (SafHelper.isRestrictedPath(path) || isSafMode) {
            // Need SAF for Android/data etc.
            if (currentTreeUri != null) {
                loadSafDirectory(currentTreeUri, path);
            } else {
                // Request SAF access for this path
                requestSafAccess(path);
            }
        } else {
            loadFileDirectory(path);
        }
    }

    /**
     * Load directory using java.io.File (for normal storage paths).
     */
    private void loadFileDirectory(String path) {
        File directory = new File(path);
        if (!directory.exists() || !directory.isDirectory()) {
            Toast.makeText(this, R.string.directory_not_found, Toast.LENGTH_SHORT).show();
            return;
        }

        isSafMode = false;
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
                item.setSaf(false);
                item.setCanWrite(file.canWrite());
                item.setCanDelete(file.canWrite());
                item.setCanRename(file.canWrite());

                if (file.isDirectory()) {
                    folders.add(item);
                } else {
                    fileItems.add(item);
                }
            }

            Collections.sort(folders, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            Collections.sort(fileItems, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));

            items.addAll(folders);
            items.addAll(fileItems);
        }

        updateFileList(items);
    }

    /**
     * Load directory using SAF (for Android/data and other restricted paths).
     */
    private void loadSafDirectory(Uri treeUri, String displayPath) {
        isSafMode = true;
        currentTreeUri = treeUri;

        if (displayPath != null) {
            currentPath = displayPath;
            tvCurrentPath.setText(displayPath);
        } else {
            tvCurrentPath.setText("SAF: " + treeUri.getLastPathSegment());
        }

        List<FileItem> items = SafHelper.listSafDirectory(this, treeUri);
        updateFileList(items);
    }

    /**
     * Update the file list UI.
     */
    private void updateFileList(List<FileItem> items) {
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
     * Refresh the current directory.
     */
    private void refreshDirectory() {
        if (isSafMode && currentTreeUri != null) {
            loadSafDirectory(currentTreeUri, currentPath);
        } else if (currentPath != null) {
            loadFileDirectory(currentPath);
        }
    }

    // =====================================================================
    // File Click Handlers
    // =====================================================================

    /**
     * Handle single click - open folder or file.
     */
    private void onFileClick(FileItem item) {
        if (item.isDirectory()) {
            openFolder(item);
        } else {
            openFileInEditor(item);
        }
    }

    /**
     * Handle long click - show context menu with edit/rename/delete/share.
     */
    private void onFileLongClick(FileItem item) {
        showItemContextMenu(item);
    }

    /**
     * Open a folder - navigate into it.
     */
    private void openFolder(FileItem item) {
        if (item.isSaf() && item.getUri() != null) {
            // SAF-based folder - use the document URI as new tree URI
            treeUriHistory.push(currentTreeUri);
            pathHistory.push(currentPath);
            String newPath = item.getPath() != null ? item.getPath() :
                    currentPath + "/" + item.getName();
            loadSafDirectory(item.getUri(), newPath);
        } else if (item.getPath() != null) {
            // Regular file-based folder
            if (SafHelper.isRestrictedPath(item.getPath())) {
                // Trying to enter Android/data - request SAF access
                pathHistory.push(currentPath);
                requestSafAccess(item.getPath());
            } else {
                pathHistory.push(currentPath);
                loadFileDirectory(item.getPath());
            }
        }
    }

    /**
     * Open a file in the editor.
     */
    private void openFileInEditor(FileItem item) {
        Intent intent = new Intent(this, EditorActivity.class);

        if (item.isSaf() && item.getUri() != null) {
            // SAF file - pass URI information
            intent.putExtra(EditorActivity.EXTRA_FILE_PATH, item.getPath());
            intent.putExtra("saf_uri", item.getUri().toString());
            intent.putExtra("saf_tree_uri", item.getTreeUri() != null ?
                    item.getTreeUri().toString() : "");
            intent.putExtra("is_saf", true);
        } else {
            intent.putExtra(EditorActivity.EXTRA_FILE_PATH, item.getPath());
        }

        startActivity(intent);
    }

    /**
     * Show context menu for a file/folder item.
     * Files: Edit, Rename, Delete, Properties
     * Folders: Open, Select Folder, Rename, Delete
     */
    private void showItemContextMenu(FileItem item) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(item.getName());

        if (item.isDirectory()) {
            String[] options = {
                    getString(R.string.open_folder),
                    getString(R.string.select_this_folder),
                    getString(R.string.rename),
                    getString(R.string.delete)
            };
            builder.setItems(options, (dialog, which) -> {
                switch (which) {
                    case 0: // Open
                        openFolder(item);
                        break;
                    case 1: // Select this folder (return it as result)
                        selectFolderResult(item);
                        break;
                    case 2: // Rename
                        showRenameDialog(item);
                        break;
                    case 3: // Delete
                        showDeleteDialog(item);
                        break;
                }
            });
        } else {
            String[] options = {
                    getString(R.string.edit_in_editor),
                    getString(R.string.rename),
                    getString(R.string.delete),
                    getString(R.string.properties)
            };
            builder.setItems(options, (dialog, which) -> {
                switch (which) {
                    case 0: // Edit in Editor
                        openFileInEditor(item);
                        break;
                    case 1: // Rename
                        showRenameDialog(item);
                        break;
                    case 2: // Delete
                        showDeleteDialog(item);
                        break;
                    case 3: // Properties
                        showPropertiesDialog(item);
                        break;
                }
            });
        }
        builder.show();
    }

    // =====================================================================
    // SAF Access - Android/data and Restricted Folders
    // =====================================================================

    /**
     * Request SAF access for a restricted directory (e.g., Android/data).
     */
    private void requestSafAccess(String path) {
        // Check if we already have persisted permission for this path
        Uri existingUri = findExistingTreeUri(path);
        if (existingUri != null) {
            loadSafDirectory(existingUri, path);
            return;
        }

        // Launch SAF tree picker
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        pendingTreeUri = null;

        // Try to set the initial URI to the Android directory
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                Uri initialUri = Uri.parse("content://com.android.externalstorage.documents/document/primary:Android");
                intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri);
            } catch (Exception e) {
                Log.w(TAG, "Could not set initial URI for SAF picker", e);
            }
        }

        Toast.makeText(this, R.string.select_android_data_hint, Toast.LENGTH_LONG).show();
        startActivityForResult(intent, REQUEST_OPEN_TREE);
    }

    /**
     * Request access specifically for Android/data folder.
     */
    private void requestAndroidDataAccess() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                Uri initialUri = Uri.parse("content://com.android.externalstorage.documents/document/primary:Android%2Fdata");
                intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri);
            } catch (Exception e) {
                Log.w(TAG, "Could not set initial URI", e);
            }
        }

        Toast.makeText(this, R.string.select_android_data_hint, Toast.LENGTH_LONG).show();
        startActivityForResult(intent, REQUEST_OPEN_TREE_ANDROID_DATA);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (resultCode != RESULT_OK || data == null) return;

        Uri treeUri = data.getData();
        if (treeUri == null) return;

        switch (requestCode) {
            case REQUEST_OPEN_TREE:
            case REQUEST_OPEN_TREE_ANDROID_DATA:
                handleSafTreeResult(treeUri);
                break;
        }
    }

    /**
     * Handle the result of SAF tree selection.
     * Takes persistable permission and loads the directory.
     */
    private void handleSafTreeResult(Uri treeUri) {
        try {
            // Take persistable URI permission
            getContentResolver().takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            );
        } catch (Exception e) {
            Log.w(TAG, "Could not take persistable permission", e);
        }

        // Determine the display path from the tree URI
        String displayPath = resolveSafPath(treeUri);
        currentTreeUri = treeUri;
        loadSafDirectory(treeUri, displayPath);
    }

    /**
     * Find an existing persisted tree URI for a given file path.
     */
    private Uri findExistingTreeUri(String path) {
        if (path == null) return null;

        List<UriPermission> permissions = getContentResolver().getPersistedUriPermissions();
        for (UriPermission perm : permissions) {
            String uriPath = perm.getUri().getLastPathSegment();
            if (uriPath != null) {
                // Convert URI path segment to file system path
                // e.g., "primary:Android/data" → "/storage/emulated/0/Android/data"
                String filePath = SafHelper.tryResolveFilePath(
                        uriPath.substring(uriPath.lastIndexOf(':') + 1), "");
                if (filePath != null && path.startsWith(filePath)) {
                    return perm.getUri();
                }
            }
        }
        return null;
    }

    /**
     * Try to resolve a file system path from a SAF tree URI.
     */
    private String resolveSafPath(Uri treeUri) {
        String docId = DocumentsContract.getTreeDocumentId(treeUri);
        return SafHelper.tryResolveFilePath(docId, "");
    }

    /**
     * Select a folder and return the result to the caller.
     */
    private void selectFolderResult(FileItem item) {
        Intent resultIntent = new Intent();
        if (item.isSaf() && item.getUri() != null) {
            resultIntent.putExtra(EXTRA_FOLDER_URI, item.getUri().toString());
        }
        resultIntent.putExtra(EXTRA_PATH, item.getPath() != null ?
                item.getPath() : item.getName());
        setResult(RESULT_OK, resultIntent);
        finish();
    }

    // =====================================================================
    // File Operations (Create, Rename, Delete)
    // =====================================================================

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
                boolean created;
                if (isSafMode && currentTreeUri != null) {
                    Uri newUri = SafHelper.createSafFile(this, currentTreeUri,
                            "text/plain", name);
                    created = newUri != null;
                } else {
                    String newPath = currentPath + "/" + name;
                    created = FileUtils.createNewFile(newPath);
                }
                if (created) {
                    refreshDirectory();
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
                boolean created;
                if (isSafMode && currentTreeUri != null) {
                    Uri newUri = SafHelper.createSafDirectory(this, currentTreeUri, name);
                    created = newUri != null;
                } else {
                    String newPath = currentPath + "/" + name;
                    created = new File(newPath).mkdirs();
                }
                if (created) {
                    refreshDirectory();
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
            if (!newName.isEmpty() && !newName.equals(item.getName())) {
                boolean renamed;
                if (item.isSaf() && item.getUri() != null) {
                    Uri newUri = SafHelper.renameSafDocument(this, item.getUri(), newName);
                    renamed = newUri != null;
                } else {
                    File oldFile = new File(item.getPath());
                    File newFile = new File(oldFile.getParent(), newName);
                    renamed = oldFile.renameTo(newFile);
                }
                if (renamed) {
                    refreshDirectory();
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
                    boolean deleted;
                    if (item.isSaf() && item.getUri() != null) {
                        deleted = SafHelper.deleteSafDocument(this, item.getUri());
                    } else {
                        deleted = FileUtils.deleteRecursively(new File(item.getPath()));
                    }
                    if (deleted) {
                        refreshDirectory();
                        Toast.makeText(this, R.string.deleted_successfully, Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, R.string.delete_failed, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /**
     * Show properties dialog for a file.
     */
    private void showPropertiesDialog(FileItem item) {
        StringBuilder info = new StringBuilder();
        info.append(getString(R.string.file_name_prop)).append(" ").append(item.getName()).append("\n");
        if (item.getPath() != null) {
            info.append(getString(R.string.file_path_prop)).append(" ").append(item.getPath()).append("\n");
        }
        if (!item.isDirectory()) {
            info.append(getString(R.string.file_size_prop)).append(" ").append(item.getFormattedSize()).append("\n");
        }
        info.append(getString(R.string.file_type_prop)).append(" ");
        if (item.isDirectory()) {
            info.append(getString(R.string.folder_type));
        } else {
            String ext = item.getExtension();
            info.append(ext.isEmpty() ? getString(R.string.unknown_type) : ext.toUpperCase());
        }
        info.append("\n");
        info.append(getString(R.string.access_method)).append(" ");
        info.append(item.isSaf() ? "SAF (Storage Access Framework)" : getString(R.string.direct_access));
        info.append("\n");
        if (item.isSaf() && item.getUri() != null) {
            info.append(getString(R.string.uri_prop)).append(" ").append(item.getUri()).append("\n");
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.properties)
                .setMessage(info.toString())
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    // =====================================================================
    // Navigation
    // =====================================================================

    /**
     * Navigate back in directory history.
     */
    private void navigateUp() {
        if (isSafMode && !treeUriHistory.isEmpty()) {
            Uri prevUri = treeUriHistory.pop();
            String prevPath = pathHistory.isEmpty() ? null : pathHistory.pop();
            loadSafDirectory(prevUri, prevPath);
        } else if (!pathHistory.isEmpty()) {
            String previousPath = pathHistory.pop();
            // Also pop any SAF history if switching back from SAF
            if (!treeUriHistory.isEmpty()) {
                treeUriHistory.pop();
            }
            loadFileDirectory(previousPath);
        } else {
            File current = new File(currentPath != null ? currentPath : "/");
            File parent = current.getParentFile();
            if (parent != null && parent.canRead()) {
                if (SafHelper.isRestrictedPath(parent.getAbsolutePath())) {
                    // Can't navigate up to restricted path without SAF
                    finish();
                } else {
                    loadFileDirectory(parent.getAbsolutePath());
                }
            } else {
                finish();
            }
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_file_manager, menu);

        // Add Android/data access option
        menu.add(Menu.NONE, R.id.action_android_data, Menu.NONE, R.string.android_data_access)
                .setIcon(R.drawable.ic_folder)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);

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
            refreshDirectory();
            return true;
        } else if (id == R.id.action_home) {
            pathHistory.clear();
            treeUriHistory.clear();
            isSafMode = false;
            currentTreeUri = null;
            loadFileDirectory(com.codeeditor.app.CodeEditorApp.getWorkDirectory());
            return true;
        } else if (id == R.id.action_android_data) {
            requestAndroidDataAccess();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onBackPressed() {
        if (isRootDirectory()) {
            finish();
        } else {
            navigateUp();
        }
    }

    private boolean isRootDirectory() {
        if (isSafMode) return treeUriHistory.isEmpty();
        if (pathHistory.isEmpty()) {
            String externalRoot = Environment.getExternalStorageDirectory().getAbsolutePath();
            return externalRoot.equals(currentPath);
        }
        return false;
    }
}
