# LekasPOS R8 rules. Keep this file short: every rule must say why it exists.

# Strip any direct debug/verbose platform logging from release builds. (Our own
# com.lekaspos.util.Log.d is already compiled out through BuildConfig.DEBUG.)
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}

# Smaller dex: move obfuscated classes into one package, allow access widening.
-repackageclasses 'l'
-allowaccessmodification

# Readable stack traces from testers (upload mapping.txt with every Play release).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
