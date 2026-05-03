package com.codeeditor.app.filemanager;

import android.net.Uri;

/**
 * Model class representing a file or folder item.
 * Supports both java.io.File paths and SAF content:// URIs.
 */
public class FileItem {

    private String name;
    private String path;          // java.io.File path (for direct file access)
    private Uri uri;              // SAF content:// URI (for Android/data etc.)
    private Uri treeUri;          // Tree URI for SAF-based directories
    private String documentId;    // DocumentsContract document ID for SAF items
    private boolean isDirectory;
    private boolean isSaf;        // true if accessed via Storage Access Framework
    private long size;
    private long lastModified;
    private boolean canWrite;
    private boolean canDelete;
    private boolean canRename;

    public FileItem() {}

    public FileItem(String name, String path, boolean isDirectory, long size, long lastModified) {
        this.name = name;
        this.path = path;
        this.isDirectory = isDirectory;
        this.size = size;
        this.lastModified = lastModified;
        this.isSaf = false;
        this.canWrite = true;
        this.canDelete = true;
        this.canRename = true;
    }

    // Getters and Setters
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }

    public Uri getUri() { return uri; }
    public void setUri(Uri uri) { this.uri = uri; }

    public Uri getTreeUri() { return treeUri; }
    public void setTreeUri(Uri treeUri) { this.treeUri = treeUri; }

    public String getDocumentId() { return documentId; }
    public void setDocumentId(String documentId) { this.documentId = documentId; }

    public boolean isDirectory() { return isDirectory; }
    public void setDirectory(boolean directory) { isDirectory = directory; }

    public boolean isSaf() { return isSaf; }
    public void setSaf(boolean saf) { isSaf = saf; }

    public long getSize() { return size; }
    public void setSize(long size) { this.size = size; }

    public long getLastModified() { return lastModified; }
    public void setLastModified(long lastModified) { this.lastModified = lastModified; }

    public boolean canWrite() { return canWrite; }
    public void setCanWrite(boolean canWrite) { this.canWrite = canWrite; }

    public boolean canDelete() { return canDelete; }
    public void setCanDelete(boolean canDelete) { this.canDelete = canDelete; }

    public boolean canRename() { return canRename; }
    public void setCanRename(boolean canRename) { this.canRename = canRename; }

    /**
     * Get the file extension from the name.
     */
    public String getExtension() {
        if (isDirectory) return "";
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1).toLowerCase() : "";
    }

    /**
     * Get human-readable file size.
     */
    public String getFormattedSize() {
        if (isDirectory) return "";
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format("%.1f KB", size / 1024.0);
        if (size < 1024 * 1024 * 1024) return String.format("%.1f MB", size / (1024.0 * 1024));
        return String.format("%.1f GB", size / (1024.0 * 1024 * 1024));
    }

    /**
     * Get the file type icon resource based on extension.
     */
    public int getIconResource() {
        if (isDirectory) return com.codeeditor.app.R.drawable.ic_folder;

        String ext = getExtension();
        switch (ext) {
            case "py":
                return com.codeeditor.app.R.drawable.ic_python;
            case "cpp":
            case "c":
            case "h":
            case "hpp":
                return com.codeeditor.app.R.drawable.ic_cpp;
            case "java":
                return com.codeeditor.app.R.drawable.ic_java;
            case "js":
            case "ts":
                return com.codeeditor.app.R.drawable.ic_javascript;
            case "html":
            case "htm":
                return com.codeeditor.app.R.drawable.ic_html;
            case "css":
                return com.codeeditor.app.R.drawable.ic_css;
            case "json":
                return com.codeeditor.app.R.drawable.ic_json;
            case "xml":
                return com.codeeditor.app.R.drawable.ic_xml;
            case "md":
                return com.codeeditor.app.R.drawable.ic_markdown;
            case "txt":
                return com.codeeditor.app.R.drawable.ic_text;
            default:
                return com.codeeditor.app.R.drawable.ic_file;
        }
    }
}
