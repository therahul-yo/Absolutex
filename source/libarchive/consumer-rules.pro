# Constructed by JNI FindClass/ThrowNew, including in minified consuming apps.
-keep class com.absolutex.source.libarchive.PasswordRequiredException { public <init>(java.lang.String); }
-keep class com.absolutex.source.libarchive.WrongPasswordException { public <init>(java.lang.String); }
-keep class com.absolutex.source.libarchive.UnsupportedEncryptionException { public <init>(java.lang.String); }

# Called back from native code (nativeStreamEntries looks onEntry up by name), so nothing in Kotlin
# references it and R8 would otherwise remove or rename it in a minified consuming app.
-keep interface com.absolutex.source.libarchive.EntrySink { boolean onEntry(int, byte[]); }
-keepclassmembers class * implements com.absolutex.source.libarchive.EntrySink { boolean onEntry(int, byte[]); }
