# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

-keepattributes *Annotation*
# The app's own classes, whole: Gson fills the API models and the download queue by
# reflection, Room and Hilt generate code against them. Only the libraries are shrunk.
-keep class com.mickstarify.zooforzotero.** { *; }

# Retrofit and Gson read generic types and annotations at runtime
-keepattributes Signature, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-keep,allowobfuscation,allowshrinking class com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class * extends com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class io.reactivex.rxjava3.core.Observable
-keep,allowobfuscation,allowshrinking class io.reactivex.rxjava3.core.Single
-keep,allowobfuscation,allowshrinking class io.reactivex.rxjava3.core.Completable

# optional or platform classes that libraries refer to but the app never loads
-dontwarn org.apache.http.**
-dontwarn android.net.http.**
-dontwarn oauth.signpost.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
