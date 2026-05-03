# Proguard rules for CodeEditor
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Keep JNI classes
-keep class com.codeeditor.app.runner.NativeRunner { *; }
-keep class com.codeeditor.app.utils.JniBridge { *; }

# Keep Parcelable classes
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}
