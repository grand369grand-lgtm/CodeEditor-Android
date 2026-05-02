package com.codeeditor.app.utils;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Preference Manager - Handles all app preferences using SharedPreferences.
 * Stores editor settings, recent files, and user preferences.
 */
public class PreferenceManager {

    private static final String PREF_NAME = "code_editor_prefs";
    private static final String KEY_AUTO_SAVE = "auto_save";
    private static final String KEY_WORD_WRAP = "word_wrap";
    private static final String KEY_SHOW_LINE_NUMBERS = "show_line_numbers";
    private static final String KEY_SHOW_MINIMAP = "show_minimap";
    private static final String KEY_DARK_MODE = "dark_mode";
    private static final String KEY_FONT_SIZE = "font_size";
    private static final String KEY_TAB_SIZE = "tab_size";
    private static final String KEY_VIBRATE_ON_KEY = "vibrate_on_key";
    private static final String KEY_RECENT_FILES = "recent_files";
    private static final String KEY_LAST_OPENED_PATH = "last_opened_path";

    private final SharedPreferences prefs;

    public PreferenceManager(Context context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    // Auto Save
    public boolean isAutoSave() {
        return prefs.getBoolean(KEY_AUTO_SAVE, true);
    }

    public void setAutoSave(boolean value) {
        prefs.edit().putBoolean(KEY_AUTO_SAVE, value).apply();
    }

    // Word Wrap
    public boolean isWordWrap() {
        return prefs.getBoolean(KEY_WORD_WRAP, true);
    }

    public void setWordWrap(boolean value) {
        prefs.edit().putBoolean(KEY_WORD_WRAP, value).apply();
    }

    // Show Line Numbers
    public boolean showLineNumbers() {
        return prefs.getBoolean(KEY_SHOW_LINE_NUMBERS, true);
    }

    public void setShowLineNumbers(boolean value) {
        prefs.edit().putBoolean(KEY_SHOW_LINE_NUMBERS, value).apply();
    }

    // Show Minimap
    public boolean showMinimap() {
        return prefs.getBoolean(KEY_SHOW_MINIMAP, false);
    }

    public void setShowMinimap(boolean value) {
        prefs.edit().putBoolean(KEY_SHOW_MINIMAP, value).apply();
    }

    // Dark Mode
    public boolean isDarkMode() {
        return prefs.getBoolean(KEY_DARK_MODE, true);
    }

    public void setDarkMode(boolean value) {
        prefs.edit().putBoolean(KEY_DARK_MODE, value).apply();
    }

    // Font Size
    public int getFontSize() {
        return prefs.getInt(KEY_FONT_SIZE, 14);
    }

    public void setFontSize(int size) {
        prefs.edit().putInt(KEY_FONT_SIZE, size).apply();
    }

    // Tab Size
    public int getTabSize() {
        return prefs.getInt(KEY_TAB_SIZE, 4);
    }

    public void setTabSize(int size) {
        prefs.edit().putInt(KEY_TAB_SIZE, size).apply();
    }

    // Vibrate on Key
    public boolean vibrateOnKey() {
        return prefs.getBoolean(KEY_VIBRATE_ON_KEY, false);
    }

    public void setVibrateOnKey(boolean value) {
        prefs.edit().putBoolean(KEY_VIBRATE_ON_KEY, value).apply();
    }

    // Recent Files
    public List<String> getRecentFiles() {
        String files = prefs.getString(KEY_RECENT_FILES, "");
        if (files.isEmpty()) {
            return Collections.emptyList();
        }
        String[] parts = files.split("\\|");
        List<String> result = new ArrayList<>();
        for (String part : parts) {
            if (!part.trim().isEmpty()) {
                result.add(part.trim());
            }
        }
        return result;
    }

    public void addRecentFile(String filePath) {
        List<String> recent = new ArrayList<>(getRecentFiles());
        // Remove if already exists
        recent.remove(filePath);
        // Add to front
        recent.add(0, filePath);
        // Keep only last 20 files
        if (recent.size() > 20) {
            recent = recent.subList(0, 20);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < recent.size(); i++) {
            if (i > 0) sb.append("|");
            sb.append(recent.get(i));
        }
        prefs.edit().putString(KEY_RECENT_FILES, sb.toString()).apply();
    }

    // Last Opened Path
    public String getLastOpenedPath() {
        return prefs.getString(KEY_LAST_OPENED_PATH,
                com.codeeditor.app.CodeEditorApp.getWorkDirectory());
    }

    public void setLastOpenedPath(String path) {
        prefs.edit().putString(KEY_LAST_OPENED_PATH, path).apply();
    }
}
