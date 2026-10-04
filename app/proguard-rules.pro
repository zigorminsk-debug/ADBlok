-keep class com.adblok.app.vpn.** { *; }
-dontwarn kotlinx.coroutines.**
# WorkManager создаёт воркеры рефлексией
-keep class com.adblok.app.update.UpdateWorker { <init>(...); }
-keep class * extends androidx.work.Worker { <init>(...); }
