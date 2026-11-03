# ProGuard rules for release builds. Room and WorkManager ship their own.

# ONNX Runtime's native code finds its Java classes, constructors and fields by name, and its AAR
# only keeps the telemetry classes. Without this rule the first OrtSession.run aborts the app with
# "JNI DETECTED ERROR IN APPLICATION: java_class == null".
-keep class ai.onnxruntime.** { *; }

# The compressor copies every EXIF tag by reading ExifInterface's TAG_ constants by reflection
# (docs/design.md §9). They are compile-time constants, so R8 would otherwise drop the fields.
-keepclassmembers class androidx.exifinterface.media.ExifInterface {
    public static final java.lang.String TAG_*;
}
