# R8 est désactivé en v1 (voir app/build.gradle.kts). Ces règles sont là pour le
# jour où il sera réactivé : PDFBox et ML Kit reposent sur la réflexion.
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
-keep class com.tom_roush.harmony.** { *; }
-dontwarn com.tom_roush.**
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**

-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_text_common.** { *; }
-dontwarn com.google.mlkit.**
