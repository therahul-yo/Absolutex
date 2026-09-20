# libarchive JNI entry points are reached from native code, never from Kotlin.
-keepclasseswithmembernames class * { native <methods>; }

# Generic signatures feed Room's schema validation and Hilt's generated factories. This keeps
# metadata only — it does not stop R8 shrinking the classes themselves.
-keepattributes Signature,InnerClasses,EnclosingMethod

# Deliberately NOT here: blanket keeps for dagger.hilt.**, ViewModel subclasses, RoomDatabase
# subclasses, @Entity and @Dao. Hilt and Room both ship consumer ProGuard rules that AGP applies
# automatically, so those keeps are redundant — and they cost 113 KiB of dex by disabling
# shrinking across both runtimes, which pushed the APK 49 KiB past the size gate.
# smbj transitives that never execute on Android, only fail R8's closed-world check:
# - javax.el: mbassador's EL listener filter; smbj never registers EL listeners.
# - org.ietf.jgss: Java GSS-API for the Kerberos branch of SpnegoAuthenticator; we only
#   offer NTLM (NtlmAuthenticator), and Android verifies classes lazily, so the GSS types
#   are never loaded. If Kerberos is ever offered, this must be revisited, not widened.
-dontwarn javax.el.**
-dontwarn org.ietf.jgss.**

# Policy reminder: if a release build ever fails reflectively, add the narrowest rule
# that fixes it, with a note — never a blanket keep.
