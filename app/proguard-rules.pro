# libarchive JNI entry points are reached from native code.
-keepclasseswithmembernames class * { native <methods>; }

# Phase 4: minimal safe keeps for the release/benchmark variants (R8 full mode).
# Generic signatures feed Hilt/Room/Compose codegen and runtime reflection; without them
# @HiltViewModel injection and Room's schema validation silently break in minified builds.
-keepattributes Signature,InnerClasses,EnclosingMethod

# Hilt: entry points and generated components are reached reflectively.
-keep class * extends androidx.lifecycle.ViewModel { *; }
-keep class dagger.hilt.** { *; }
-keep class **_HiltModules { *; }

# Room: entities/DAOs/database are read reflectively; the impl is generated at compile time.
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
