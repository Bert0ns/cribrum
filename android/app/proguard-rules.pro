# ProGuard rules for release builds. Room and WorkManager ship their own.

# ONNX Runtime's native code finds its Java classes, constructors and fields by name, and its AAR
# only keeps the telemetry classes. Without this rule the first OrtSession.run aborts the app with
# "JNI DETECTED ERROR IN APPLICATION: java_class == null".
-keep class ai.onnxruntime.** { *; }
