package com.codeeditor.app.utils;

import java.util.HashMap;
import java.util.Map;

/**
 * Language Detector - Detects the programming language from file extension.
 * Maps file extensions to Monaco Editor language identifiers.
 */
public class LanguageDetector {

    private static final Map<String, String> EXTENSION_MAP = new HashMap<>();

    static {
        // Python
        EXTENSION_MAP.put("py", "python");
        EXTENSION_MAP.put("pyw", "python");
        EXTENSION_MAP.put("pyi", "python");

        // C/C++
        EXTENSION_MAP.put("c", "c");
        EXTENSION_MAP.put("h", "c");
        EXTENSION_MAP.put("cpp", "cpp");
        EXTENSION_MAP.put("hpp", "cpp");
        EXTENSION_MAP.put("cc", "cpp");
        EXTENSION_MAP.put("cxx", "cpp");

        // Java
        EXTENSION_MAP.put("java", "java");

        // JavaScript/TypeScript
        EXTENSION_MAP.put("js", "javascript");
        EXTENSION_MAP.put("jsx", "javascript");
        EXTENSION_MAP.put("ts", "typescript");
        EXTENSION_MAP.put("tsx", "typescript");
        EXTENSION_MAP.put("mjs", "javascript");

        // Web
        EXTENSION_MAP.put("html", "html");
        EXTENSION_MAP.put("htm", "html");
        EXTENSION_MAP.put("css", "css");
        EXTENSION_MAP.put("scss", "scss");
        EXTENSION_MAP.put("less", "less");

        // Data/Config
        EXTENSION_MAP.put("json", "json");
        EXTENSION_MAP.put("xml", "xml");
        EXTENSION_MAP.put("yaml", "yaml");
        EXTENSION_MAP.put("yml", "yaml");
        EXTENSION_MAP.put("toml", "ini");
        EXTENSION_MAP.put("ini", "ini");
        EXTENSION_MAP.put("conf", "ini");
        EXTENSION_MAP.put("properties", "ini");

        // Shell
        EXTENSION_MAP.put("sh", "shell");
        EXTENSION_MAP.put("bash", "shell");
        EXTENSION_MAP.put("bat", "bat");
        EXTENSION_MAP.put("cmd", "bat");

        // Other languages
        EXTENSION_MAP.put("go", "go");
        EXTENSION_MAP.put("rs", "rust");
        EXTENSION_MAP.put("rb", "ruby");
        EXTENSION_MAP.put("php", "php");
        EXTENSION_MAP.put("swift", "swift");
        EXTENSION_MAP.put("kt", "kotlin");
        EXTENSION_MAP.put("sql", "sql");
        EXTENSION_MAP.put("lua", "lua");
        EXTENSION_MAP.put("r", "r");
        EXTENSION_MAP.put("pl", "perl");
        EXTENSION_MAP.put("dart", "dart");
        EXTENSION_MAP.put("md", "markdown");
        EXTENSION_MAP.put("txt", "plaintext");
    }

    /**
     * Detect the programming language from a file name.
     *
     * @param fileName The file name (with or without path)
     * @return Monaco Editor language identifier
     */
    public static String detectLanguage(String fileName) {
        if (fileName == null) return "plaintext";

        int dot = fileName.lastIndexOf('.');
        if (dot < 0) return "plaintext";

        String extension = fileName.substring(dot + 1).toLowerCase();
        String language = EXTENSION_MAP.get(extension);
        return language != null ? language : "plaintext";
    }

    /**
     * Get the default file extension for a language.
     *
     * @param language Monaco Editor language identifier
     * @return Default file extension (without dot)
     */
    public static String getDefaultExtension(String language) {
        switch (language) {
            case "python": return "py";
            case "cpp": return "cpp";
            case "c": return "c";
            case "java": return "java";
            case "javascript": return "js";
            case "typescript": return "ts";
            case "html": return "html";
            case "css": return "css";
            case "json": return "json";
            case "xml": return "xml";
            case "markdown": return "md";
            case "shell": return "sh";
            case "go": return "go";
            case "rust": return "rs";
            case "ruby": return "rb";
            case "php": return "php";
            case "swift": return "swift";
            case "kotlin": return "kt";
            case "sql": return "sql";
            default: return "txt";
        }
    }

    /**
     * Check if a language supports code execution.
     *
     * @param language Monaco Editor language identifier
     * @return true if the language can be executed
     */
    public static boolean isExecutable(String language) {
        switch (language) {
            case "python":
            case "cpp":
            case "c":
            case "java":
            case "javascript":
            case "shell":
                return true;
            default:
                return false;
        }
    }
}
