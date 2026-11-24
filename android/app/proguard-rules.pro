# Gson reads and writes the DTOs in pojo by reflection: keep the classes and their fields.
-keep class com.arnavjot.glimpse.pojo.** { *; }

# Retrofit interfaces and generic signatures (needed for Call<T> / response types).
-keepattributes Signature, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations, AnnotationDefault
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation interface com.arnavjot.glimpse.network.ApiInterface
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Remote config is cached on disk as JSON (Gson, by field name): keep the names stable
# across releases, or an update couldn't read the saved config.
-keep class com.arnavjot.glimpse.config.RemoteConfig$Snapshot { *; }

# The signed-in session is stored as JSON (Gson, by field name), encrypted.
-keep class com.arnavjot.glimpse.auth.Session { *; }
