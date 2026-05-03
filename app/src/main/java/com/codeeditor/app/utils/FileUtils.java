package com.codeeditor.app.utils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

/**
 * File utility class for reading, writing, and managing files.
 * Provides common file operations used throughout the app.
 */
public class FileUtils {

    /**
     * Read the entire content of a file as a string.
     *
     * @param filePath Absolute path to the file
     * @return File content or empty string on error
     */
    public static String readFile(String filePath) {
        if (filePath == null) return "";
        File file = new File(filePath);
        if (!file.exists() || !file.canRead()) return "";

        StringBuilder content = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                if (!first) content.append("\n");
                content.append(line);
                first = false;
            }
        } catch (IOException e) {
            e.printStackTrace();
            return "";
        }
        return content.toString();
    }

    /**
     * Write string content to a file.
     * Creates parent directories if they don't exist.
     *
     * @param filePath Absolute path to the file
     * @param content  Content to write
     * @return true if write succeeded
     */
    public static boolean writeFile(String filePath, String content) {
        if (filePath == null) return false;
        File file = new File(filePath);

        // Create parent directories
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        try (FileWriter writer = new FileWriter(file)) {
            writer.write(content);
            writer.flush();
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Create a new empty file.
     *
     * @param filePath Absolute path for the new file
     * @return true if file was created
     */
    public static boolean createNewFile(String filePath) {
        if (filePath == null) return false;
        File file = new File(filePath);
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            return file.createNewFile();
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Delete a file or directory recursively.
     *
     * @param file The file or directory to delete
     * @return true if deletion succeeded
     */
    public static boolean deleteRecursively(File file) {
        if (file == null || !file.exists()) return false;

        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        return file.delete();
    }

    /**
     * Get the file name from a path.
     */
    public static String getFileName(String path) {
        if (path == null) return "";
        int sep = path.lastIndexOf('/');
        return sep >= 0 ? path.substring(sep + 1) : path;
    }

    /**
     * Get the file extension from a path.
     */
    public static String getExtension(String path) {
        String name = getFileName(path);
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1).toLowerCase() : "";
    }

    /**
     * Check if a path is a code file that can be edited.
     */
    public static boolean isEditableFile(String path) {
        String ext = getExtension(path);
        switch (ext) {
            case "py":
            case "cpp":
            case "c":
            case "h":
            case "hpp":
            case "java":
            case "js":
            case "ts":
            case "html":
            case "htm":
            case "css":
            case "scss":
            case "json":
            case "xml":
            case "yaml":
            case "yml":
            case "md":
            case "txt":
            case "sh":
            case "bat":
            case "sql":
            case "rb":
            case "go":
            case "rs":
            case "swift":
            case "kt":
            case "php":
            case "lua":
            case "r":
            case "pl":
            case "dart":
                return true;
            default:
                return false;
        }
    }

    /**
     * Count the number of lines in a file.
     */
    public static int countLines(String filePath) {
        String content = readFile(filePath);
        if (content.isEmpty()) return 0;
        return content.split("\n").length;
    }
}
