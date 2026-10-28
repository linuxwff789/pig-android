# Keep default rules for now.

# OkHttp 4 reaches into its own internals reflectively on some platforms; the
# AAR ships the needed keep rules, only the optional platform hooks need this.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
