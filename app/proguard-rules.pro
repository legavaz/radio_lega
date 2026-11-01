# Правила ProGuard/R8 для релизной сборки.
# Пока minify выключен (isMinifyEnabled = false), файл нужен только
# чтобы не менять конфигурацию при включении R8.

# media3 работает через рефлексию в нескольких местах
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# kotlinx.serialization: сериализаторы генерируются как вложенные классы
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
