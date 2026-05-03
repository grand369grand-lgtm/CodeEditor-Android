# Proguard rules for CodeEditor
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Keep JNI classes
-keep class com.codeeditor.app.runner.NativeRunner { *; }
-keep class com.codeeditor.app.utils.JniBridge { *; }

# Keep terminal classes (used by reflection / callbacks)
-keep class com.codeeditor.app.terminal.UbuntuManager { *; }
-keep class com.codeeditor.app.terminal.UbuntuManager$* { *; }
-keep class com.codeeditor.app.terminal.ProotSession { *; }
-keep class com.codeeditor.app.terminal.ProotSession$* { *; }

# Keep Parcelable classes
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}
