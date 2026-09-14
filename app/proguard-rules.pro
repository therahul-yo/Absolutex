# libarchive JNI entry points are reached from native code.
-keepclasseswithmembernames class * { native <methods>; }
