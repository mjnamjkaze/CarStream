# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Enable optimization and minification (done via build.gradle's isMinifyEnabled)

# Keep line number and source file info for crash reporting, but obfuscate their names.
-keepattributes SourceFile,LineNumberTable,*Annotation*,Signature,EnclosingMethod,InnerClasses

# Renamesourcefileattribute hides the actual Java file name in stack traces
-renamesourcefileattribute SourceFile

# Keep JavascriptInterfaces because WebView relies on reflection/names to invoke them from JS
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep the specific classes/members registered as Javascript Interfaces
-keep class com.gsvn.aabrowser.web.ImeKeyboardBridge { *; }
-keepclassmembers class com.gsvn.aabrowser.web.ImeKeyboardBridge {
    public <methods>;
}

# Also keep SpeechRecognitionBridge just in case it is ever referenced/used dynamically
-keep class com.gsvn.aabrowser.web.SpeechRecognitionBridge { *; }
-keepclassmembers class com.gsvn.aabrowser.web.SpeechRecognitionBridge {
    public <methods>;
}

# Android Auto and general androidx.car.app rules
-keep class androidx.car.app.** { *; }
-dontwarn androidx.car.app.**

# Keep standard Android classes
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends android.app.backup.BackupAgentHelper
-keep public class * extends android.preference.Preference

# Keep custom binding classes if reflection is used
-keep class com.gsvn.aabrowser.databinding.** { *; }

# Keep model/serialization classes if we use kotlinx.serialization
-keepclassmembers class * {
    @kotlinx.serialization.Serializable *;
}
-keepclassmembers class * {
    *** Companion;
}
-keep class kotlinx.serialization.json.** { *; }
-dontwarn kotlinx.serialization.**