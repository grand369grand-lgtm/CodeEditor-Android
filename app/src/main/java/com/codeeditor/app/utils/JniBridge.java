package com.codeeditor.app.utils;

/**
 * JNI Bridge - Provides Java methods that can be called from C++ native code.
 * This class acts as a bridge between the native C++ layer and Java layer.
 * Used for file I/O operations from native code, logging, and callbacks.
 */
public class JniBridge {

    private static final String TAG = "JniBridge";

    /**
     * Called from C++ to read a file.
     * This allows native code to read files through Java's file API.
     *
     * @param filePath Absolute path to the file
     * @return File content as string
     */
    public static String readFileFromNative(String filePath) {
        return FileUtils.readFile(filePath);
    }

    /**
     * Called from C++ to write a file.
     *
     * @param filePath Absolute path to the file
     * @param content  Content to write
     * @return true if write succeeded
     */
    public static boolean writeFileFromNative(String filePath, String content) {
        return FileUtils.writeFile(filePath, content);
    }

    /**
     * Called from C++ to check if a file exists.
     *
     * @param filePath Absolute path to check
     * @return true if file exists
     */
    public static boolean fileExists(String filePath) {
        return new java.io.File(filePath).exists();
    }

    /**
     * Called from C++ to create a directory.
     *
     * @param dirPath Directory path to create
     * @return true if directory was created or already exists
     */
    public static boolean createDirectory(String dirPath) {
        java.io.File dir = new java.io.File(dirPath);
        return dir.exists() || dir.mkdirs();
    }

    /**
     * Called from C++ to delete a file.
     *
     * @param filePath File to delete
     * @return true if deletion succeeded
     */
    public static boolean deleteFile(String filePath) {
        return new java.io.File(filePath).delete();
    }

    /**
     * Called from C++ to log a message.
     *
     * @param level   Log level (0=verbose, 1=debug, 2=info, 3=warn, 4=error)
     * @param message Log message
     */
    public static void logFromNative(int level, String message) {
        switch (level) {
            case 0:
                android.util.Log.v(TAG, message);
                break;
            case 1:
                android.util.Log.d(TAG, message);
                break;
            case 2:
                android.util.Log.i(TAG, message);
                break;
            case 3:
                android.util.Log.w(TAG, message);
                break;
            case 4:
                android.util.Log.e(TAG, message);
                break;
        }
    }
}
