-dontwarn io.github.libxposed.annotation.**
-adaptresourcefilecontents META-INF/xposed/java_init.list

# Keep the app's own package names stable. The manifest, LSPosed entry list,
# explicit broadcasts, and reflection-style bridge paths all refer to these
# concrete class names. Without this, release minification can rename/remove
# entry classes such as dev.chaoxingdeadline.App and crash on startup.
-keep class dev.chaoxingdeadline.** { *; }
-keepnames class dev.chaoxingdeadline.**

-keep public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}
-keep public class * extends android.app.Application { *; }
-keep public class * extends android.app.Activity { *; }
-keep public class * extends android.content.BroadcastReceiver { *; }

-dontwarn androidx.annotation.NonNull
-dontwarn androidx.annotation.Nullable
