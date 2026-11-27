-keepattributes Signature,InnerClasses,EnclosingMethod

# Gson TypeToken & reflection safety
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# App data models serialized by Gson (tasks.db & rememberSaveable)
-keep class io.github.bilidownload.MediaItem { *; }
-keep class io.github.bilidownload.Variant { *; }
-keep class io.github.bilidownload.StreamPart { *; }
-keep class io.github.bilidownload.DownloadPlan { *; }
-keep class io.github.bilidownload.DownloadEntry { *; }
-keep class io.github.bilidownload.MusicTags { *; }
-keep class io.github.bilidownload.AudioTrack { *; }
-keep class io.github.bilidownload.AudioOutput { *; }
-keep class io.github.bilidownload.XDocument { *; }
-keep class io.github.bilidownload.XContent { *; }
-keep class io.github.bilidownload.XDestination { *; }
-keep class io.github.bilidownload.BiliErrorKind { *; }
-keep class io.github.bilidownload.SessionState { *; }
-keep class io.github.bilidownload.AccountInfo { *; }
-keep class io.github.bilidownload.LoginOutcome { *; }
-keep class io.github.bilidownload.LoginResult { *; }
-keep class io.github.bilidownload.PlaylistPage { *; }
-keep class io.github.bilidownload.YoutubeCatalog { *; }

# Keep TagLib JNI bindings
-keep class com.kyant.taglib.** { *; }
