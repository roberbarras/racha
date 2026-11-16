# R8 rules for Racha's release build. Libraries ship their own consumer rules (kotlinx.serialization, Ktor, OkHttp,
# Media3, WorkManager, SQLDelight, Compose); only what reflection reaches in our own code is listed here.

# Keep line numbers in crash reports sent to the user's server (§8); file names are not needed.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
