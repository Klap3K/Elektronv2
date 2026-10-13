-keepattributes *Annotation*, InnerClasses, Signature, SourceFile, LineNumberTable
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keepclasseswithmembers class * { @dagger.hilt.android.AndroidEntryPoint <methods>; }
-keepclasseswithmembers class * { @dagger.hilt.android.lifecycle.HiltViewModel <init>(...); }
-keepclasseswithmembers class * { @androidx.hilt.work.HiltWorker <init>(...); }
-keep class androidx.room.** { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-keep class pl.zse.bydgoszcz.elektron.data.local.** { *; }
-keep class pl.zse.bydgoszcz.elektron.data.remote.dto.** { *; }
-keep class pl.zse.bydgoszcz.elektron.data.remote.vulcan.** { *; }
-keep class pl.zse.bydgoszcz.elektron.data.update.** { *; }
-keep class pl.zse.bydgoszcz.elektron.domain.model.** { *; }
-keep class * extends androidx.work.ListenableWorker { <init>(...); }
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-keep class okhttp3.internal.platform.** { *; }
-keep class org.jsoup.** { *; }
-dontwarn org.jsoup.**
-dontwarn coil.**
-dontwarn kotlinx.coroutines.**
-keep class androidx.datastore.** { *; }
-keep class androidx.datastore.preferences.** { *; }
-keep class kotlin.Metadata { *; }

# --- Dodane w 0.4.0 (pierwszy build release z R8) ---
# AGP 8 traktuje brakujące klasy jako błąd. Jsoup (1.15+) i OkHttp mają opcjonalne
# zależności, których nie dołączamy — bez tych reguł assembleRelease się wykłada.
-dontwarn com.google.re2j.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**

# Widżety Glance: receivery są w manifeście (R8 je zachowuje), EntryPoint Hilt też.
# Zostawiamy nazwy klas widżetów — launcher odwołuje się do nich po nazwie.
-keep class pl.zse.bydgoszcz.elektron.widget.** { *; }

# --- 1.0.0-rc1: czytelne raporty błędów ---
# Bez zaciemniania nazw (kod jest otwarty, więc niczego nie chroni): ślad stosu w raporcie
# pokazuje prawdziwe klasy i pliki, np. DeveloperTools.crash(DeveloperTools.kt:120), a nie
# K2.a.run(SourceFile:25) - bez pliku mapowania. Zmniejszanie i optymalizacja kodu zostają.
-dontobfuscate
