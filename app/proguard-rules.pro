# Termux terminal emulator uses JNI (libtermux.so) that calls back into these classes.
-keep class com.termux.terminal.** { *; }
-keep class com.termux.view.** { *; }
# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers @kotlinx.serialization.Serializable class ** { *** Companion; *; }
# Optional commons-compress codecs we never use.
-dontwarn org.tukaani.xz.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.brotli.dec.**
-dontwarn org.objectweb.asm.**
