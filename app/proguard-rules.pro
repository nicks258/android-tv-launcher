-repackageclasses ''
-allowaccessmodification

# Keep FileProvider for OTA updates
-keep class androidx.core.content.FileProvider { *; }
-dontwarn androidx.core.content.FileProvider

