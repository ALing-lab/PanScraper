# PanScraper R8 规则。依赖库（OkHttp、Coil、kotlinx-serialization、Tink/security-crypto、Compose）自带 consumer rules，
# 这里补上反射 / JNI / JS 桥接用到、R8 看不出来的部分。

# 便于看崩溃堆栈（不影响混淆强度太多）
-keepattributes SourceFile,LineNumberTable,*Annotation*,Signature,InnerClasses,EnclosingMethod
-renamesourcefileattribute SourceFile

# ---- kotlinx-serialization：本 App 的 @Serializable 类和生成的 serializer ----
-keepclassmembers @kotlinx.serialization.Serializable class com.tamu.pan123.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class com.tamu.pan123.**$$serializer { *; }
-keepclassmembers class com.tamu.pan123.** {
    *** Companion;
}
-keepclasseswithmembers class com.tamu.pan123.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ---- WebView JS 桥（登录页取 token）----
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface

# ---- BouncyCastle：JCA Provider 通过反射按名字加载算法实现 ----
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**

# ---- LZ4（115 加解密）：LZ4Factory 用反射挑选 JNI / Unsafe / Java 实现 ----
-keep class net.jpountz.** { *; }
-dontwarn net.jpountz.**

# ---- OkHttp / DoH ----
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# ---- security-crypto / Tink ----
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**

# ---- protobuf-lite（Tink 内置的 shaded 版 / DataStore 内置的版本）：字段靠反射读写，R8 full mode 下要保留 ----
-keepclassmembers class * extends com.google.crypto.tink.shaded.protobuf.GeneratedMessageLite { <fields>; }
-keepclassmembers class * extends androidx.datastore.preferences.protobuf.GeneratedMessageLite { <fields>; }
