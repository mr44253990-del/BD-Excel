# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

-dontnote **
-dontwarn androidx.room.**
-dontwarn org.apache.poi.**
-dontwarn com.opencsv.**
-keep class com.bdexcel.app.** { *; }
-keepnames class * implements android.os.Parcelable { *; }
