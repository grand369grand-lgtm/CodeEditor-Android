package com.codeeditor.app;

import android.app.Application;
import android.os.Environment;

import java.io.File;

/**
 * Application class for CodeEditor.
 * Initializes global state and ensures storage directories exist.
 */
public class CodeEditorApp extends Application {

    private static CodeEditorApp instance;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        ensureWorkDirectory();
    }

    public static CodeEditorApp getInstance() {
        return instance;
    }

    /**
     * Create the working directory for code files if it doesn't exist.
     * This is where user code files will be stored by default.
     */
    private void ensureWorkDirectory() {
        File workDir = new File(Environment.getExternalStorageDirectory(), "CodeEditor");
        if (!workDir.exists()) {
            workDir.mkdirs();
        }
        // Create subdirectories for each language
        new File(workDir, "Python").mkdirs();
        new File(workDir, "Cpp").mkdirs();
        new File(workDir, "Java").mkdirs();
    }

    /**
     * Get the default working directory path.
     */
    public static String getWorkDirectory() {
        return Environment.getExternalStorageDirectory() + "/CodeEditor";
    }
}
