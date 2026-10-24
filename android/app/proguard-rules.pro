# ProGuard rules for release builds.

# ONNX Runtime JNI entry points and model reflection
-keep class ai.onnxruntime.** { *; }
