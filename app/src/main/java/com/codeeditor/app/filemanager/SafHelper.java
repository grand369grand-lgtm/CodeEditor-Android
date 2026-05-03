package com.codeeditor.app.filemanager;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.util.Log;

import androidx.documentfile.provider.DocumentFile;

import com.codeeditor.app.utils.FileUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * SAF (Storage Access Framework) Helper for accessing restricted directories
 * like Android/data on Android 11+.
 * Provides methods for listing, reading, writing, and managing files via content URIs.
 */
public class SafHelper {

    private static final String TAG = "SafHelper";

    /**
     * Check if a file path is within a restricted directory (Android/data, Android/obb).
     * On Android 11+, these directories require SAF access.
     */
    public static boolean isRestrictedPath(String path) {
        if (path == null) return false;
        return path.contains("/Android/data/") ||
                path.contains("/Android/obb/") ||
                path.contains("/Android/media/");
    }

    /**
     * Check if we can access a directory directly via java.io.File.
     * Returns false for Android/data on API 30+ even with MANAGE_EXTERNAL_STORAGE.
     */
    public static boolean canAccessDirectly(String path) {
        if (path == null) return false;
        if (isRestrictedPath(path)) {
            return false;
        }
        File dir = new File(path);
        return dir.exists() && dir.canRead();
    }

    /**
     * List files in a SAF-accessible directory using a tree URI.
     * Returns a list of FileItem objects representing children.
     */
    public static List<FileItem> listSafDirectory(Context context, Uri treeUri) {
        List<FileItem> items = new ArrayList<>();
        if (treeUri == null) return items;

        ContentResolver resolver = context.getContentResolver();

        // Get the document ID from the tree URI
        String documentId = DocumentsContract.getTreeDocumentId(treeUri);

        // Build the children URI
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri, documentId);

        // Query children
        String[] projection = new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                DocumentsContract.Document.COLUMN_FLAGS
        };

        try (Cursor cursor = resolver.query(childrenUri, projection, null, null,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME + " ASC")) {
            if (cursor == null) return items;

            List<FileItem> folders = new ArrayList<>();
            List<FileItem> files = new ArrayList<>();

            while (cursor.moveToNext()) {
                try {
                    String docId = cursor.getString(0);
                    String displayName = cursor.getString(1);
                    String mimeType = cursor.getString(2);
                    long size = cursor.getLong(3);
                    long lastModified = cursor.getLong(4);
                    int flags = cursor.getInt(5);

                    boolean isDir = DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType);
                    boolean canWrite = (flags & DocumentsContract.Document.FLAG_SUPPORTS_WRITE) != 0;
                    boolean canDelete = (flags & DocumentsContract.Document.FLAG_SUPPORTS_DELETE) != 0;
                    boolean canRename = (flags & DocumentsContract.Document.FLAG_SUPPORTS_RENAME) != 0;

                    // Build the document URI for this child
                    Uri docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);

                    FileItem item = new FileItem();
                    item.setName(displayName);
                    item.setUri(docUri);
                    item.setTreeUri(treeUri);
                    item.setDocumentId(docId);
                    item.setDirectory(isDir);
                    item.setSaf(true);
                    item.setSize(size);
                    item.setLastModified(lastModified);
                    item.setCanWrite(canWrite);
                    item.setCanDelete(canDelete);
                    item.setCanRename(canRename);

                    // Try to build a file path for compatibility
                    String filePath = tryResolveFilePath(docId, displayName);
                    item.setPath(filePath);

                    if (isDir) {
                        folders.add(item);
                    } else {
                        files.add(item);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Error reading document from cursor", e);
                }
            }

            // Folders first, then files
            Collections.sort(folders, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            Collections.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            items.addAll(folders);
            items.addAll(files);
        } catch (Exception e) {
            Log.e(TAG, "Error listing SAF directory: " + treeUri, e);
        }

        return items;
    }

    /**
     * Try to resolve a file path from a document ID and display name.
     * This is a best-effort mapping for compatibility with code that uses file paths.
     */
    private static String tryResolveFilePath(String documentId, String displayName) {
        if (documentId == null) return null;
        // Document IDs from ExternalStorageProvider typically look like:
        // "primary:Android/data/com.example/files/something.txt"
        // We can convert "primary:" to the external storage path
        if (documentId.startsWith("primary:")) {
            String relativePath = documentId.substring("primary:".length());
            return "/storage/emulated/0/" + relativePath;
        }
        // For other storage volumes (like SD cards), the ID format is "XXXX-XXXX:path"
        if (documentId.length() > 5 && documentId.charAt(4) == '-') {
            int colonIndex = documentId.indexOf(':');
            if (colonIndex > 0) {
                String volumeId = documentId.substring(0, colonIndex);
                String relativePath = documentId.substring(colonIndex + 1);
                return "/storage/" + volumeId + "/" + relativePath;
            }
        }
        return null;
    }

    /**
     * Read file content from a SAF URI.
     */
    public static String readSafFile(Context context, Uri uri) {
        if (uri == null) return "";
        StringBuilder content = new StringBuilder();
        try (InputStream is = context.getContentResolver().openInputStream(uri);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                if (!first) content.append("\n");
                content.append(line);
                first = false;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error reading SAF file: " + uri, e);
        }
        return content.toString();
    }

    /**
     * Write content to a SAF URI.
     */
    public static boolean writeSafFile(Context context, Uri uri, String content) {
        if (uri == null) return false;
        try (OutputStream os = context.getContentResolver().openOutputStream(uri, "wt")) {
            if (os == null) return false;
            os.write(content.getBytes("UTF-8"));
            os.flush();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error writing SAF file: " + uri, e);
            return false;
        }
    }

    /**
     * Create a new file in a SAF directory.
     * Returns the URI of the created file, or null on failure.
     */
    public static Uri createSafFile(Context context, Uri treeUri, String mimeType, String displayName) {
        try {
            String parentId = DocumentsContract.getTreeDocumentId(treeUri);
            Uri parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentId);
            return DocumentsContract.createDocument(context.getContentResolver(), parentUri, mimeType, displayName);
        } catch (Exception e) {
            Log.e(TAG, "Error creating SAF file: " + displayName, e);
            return null;
        }
    }

    /**
     * Create a new directory in a SAF directory.
     * Returns the URI of the created directory, or null on failure.
     */
    public static Uri createSafDirectory(Context context, Uri treeUri, String displayName) {
        return createSafFile(context, treeUri, DocumentsContract.Document.MIME_TYPE_DIR, displayName);
    }

    /**
     * Delete a document via SAF.
     */
    public static boolean deleteSafDocument(Context context, Uri docUri) {
        try {
            return DocumentsContract.deleteDocument(context.getContentResolver(), docUri);
        } catch (Exception e) {
            Log.e(TAG, "Error deleting SAF document: " + docUri, e);
            return false;
        }
    }

    /**
     * Rename a document via SAF.
     * Returns the new URI, or null on failure.
     */
    public static Uri renameSafDocument(Context context, Uri docUri, String newName) {
        try {
            return DocumentsContract.renameDocument(context.getContentResolver(), docUri, newName);
        } catch (Exception e) {
            Log.e(TAG, "Error renaming SAF document: " + docUri, e);
            return null;
        }
    }

    /**
     * Read a file that can be accessed either via file path or SAF URI.
     * Automatically detects the access method.
     */
    public static String readFile(Context context, FileItem item) {
        if (item == null) return "";
        if (item.isSaf() && item.getUri() != null) {
            return readSafFile(context, item.getUri());
        } else if (item.getPath() != null) {
            return FileUtils.readFile(item.getPath());
        }
        return "";
    }

    /**
     * Write a file that can be accessed either via file path or SAF URI.
     */
    public static boolean writeFile(Context context, FileItem item, String content) {
        if (item == null) return false;
        if (item.isSaf() && item.getUri() != null) {
            return writeSafFile(context, item.getUri(), content);
        } else if (item.getPath() != null) {
            return FileUtils.writeFile(item.getPath(), content);
        }
        return false;
    }
}
