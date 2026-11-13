# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.atlanyuey.aenht.**$$serializer { *; }
-keepclassmembers class com.atlanyuey.aenht.** { *** Companion; }
-keepclasseswithmembers class com.atlanyuey.aenht.** { kotlinx.serialization.KSerializer serializer(...); }
-dontwarn org.slf4j.**
