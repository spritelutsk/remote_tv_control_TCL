# Сообщения protobuf-javalite обращаются к полям через рефлексию.
-keep class com.sprit.tvremote.proto.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.protobuf.**

# BouncyCastle тянет за собой опциональные ссылки на классы JDK, которых нет на Android.
-dontwarn org.bouncycastle.**
