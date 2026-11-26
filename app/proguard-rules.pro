# Keep information required for useful, retraceable crash reports and reflection.
-keepattributes SourceFile,LineNumberTable,Signature,InnerClasses,EnclosingMethod,Exceptions,*Annotation*

# Remove low-value logging from release builds without suppressing errors.
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}

# Preserve native entry points. Media3 and ML Kit otherwise provide their own
# consumer rules; blanket keep/dontwarn directives would hide real release bugs.
-keep class androidx.media3.common.util.LibraryLoader { *; }
-keepclassmembers class androidx.media3.** {
    native <methods>;
}

# Compose stability annotations may be inspected by generated code.
-keepclassmembers class com.oxigenplayer.app.** {
    @androidx.compose.runtime.Immutable *;
    @androidx.compose.runtime.Stable *;
}

-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# NextLib and ML Kit package their own consumer ProGuard rules. Do not add blanket
# `-dontwarn` directives here: unresolved classes in an optional flavor must remain
# visible during the minified release build instead of being silently hidden.

# ML Kit discovers its components by instantiating the registrars named in the manifest
# through reflection. The libraries' consumer rules keep the class names only; in R8 full
# mode that no longer keeps the no-argument constructor, so every registrar failed with
# "Could not instantiate" and the first ML Kit client (language identification) crashed the
# release build at launch with a NullPointerException.
-keep class * implements com.google.firebase.components.ComponentRegistrar {
    <init>();
}
