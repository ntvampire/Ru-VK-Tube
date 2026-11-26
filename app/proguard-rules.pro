# Proguard rules for Ru-VK-Tube / RuVK Hub
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# Gson & API DTOs
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep class com.ruvk.tube.data.api.tmdb.** { *; }
-keep class com.ruvk.tube.data.api.rutube.** { *; }
-keep class com.ruvk.tube.data.api.vk.** { *; }
-keep class com.ruvk.tube.data.update.UpdateInfo { *; }

# Serializable domain models passed via Intent extras
-keep class com.ruvk.tube.domain.models.** { *; }

# Room entities & database
-keep class com.ruvk.tube.data.db.** { *; }
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# Retrofit / OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-dontwarn org.bouncycastle.**
-keep interface com.ruvk.tube.data.api.tmdb.TmdbApi { *; }

# Media3 / ExoPlayer
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# AndroidX Leanback / TvProvider
-keep class androidx.leanback.** { *; }
-keep class androidx.tvprovider.** { *; }
