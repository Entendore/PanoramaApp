# OpenCV: JNI calls into these classes by name — R8 must not rename/strip them
-keep class org.opencv.** { *; }

# Readable Java stack traces in local crash logs (line numbers)
-keepattributes SourceFile,LineNumberTable
