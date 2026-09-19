# libarchive JNI entry points are reached from native code, never from Kotlin.
-keepclasseswithmembernames class * { native <methods>; }

# Generic signatures feed Room's schema validation and Hilt's generated factories. This keeps
# metadata only — it does not stop R8 shrinking the classes themselves.
-keepattributes Signature,InnerClasses,EnclosingMethod

# Deliberately NOT here: blanket keeps for dagger.hilt.**, ViewModel subclasses, RoomDatabase
# subclasses, @Entity and @Dao. Hilt and Room both ship consumer ProGuard rules that AGP applies
# automatically, so those keeps are redundant — and they cost 113 KiB of dex by disabling
# shrinking across both runtimes, which pushed the APK 49 KiB past the size gate.
# If a release build ever fails reflectively, add the narrowest rule that fixes it, with a note.

# smbj (via :feature:remote -> :remote:smb, first pulled into :app by RemoteBookOpener's Hilt
# binding) defensively references Kerberos/GSSAPI and Java EE Expression Language classes for
# code paths this app never exercises (server-side JNDI/EL, GSS auth) — real on a JVM, absent on
# Android. -dontwarn only silences R8's warning about the missing reference; it keeps nothing and
# costs no dex size, unlike a real keep rule.
-dontwarn javax.el.BeanELResolver
-dontwarn javax.el.ELContext
-dontwarn javax.el.ELResolver
-dontwarn javax.el.ExpressionFactory
-dontwarn javax.el.FunctionMapper
-dontwarn javax.el.ValueExpression
-dontwarn javax.el.VariableMapper
-dontwarn org.ietf.jgss.GSSContext
-dontwarn org.ietf.jgss.GSSCredential
-dontwarn org.ietf.jgss.GSSException
-dontwarn org.ietf.jgss.GSSManager
-dontwarn org.ietf.jgss.GSSName
-dontwarn org.ietf.jgss.Oid
