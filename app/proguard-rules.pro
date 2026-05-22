# JNI entry point / class used via FindClass from native code (native_content_io.cpp)
-keep class com.winlator.cmod.shared.io.NativeContentIO {
    *;
}

# JNI FindClass + GetMethodID from native_content_io.cpp
-keep class com.winlator.cmod.shared.util.OnExtractFileListener {
    *;
}

# JNI FindClass + GetStaticMethodID from vulkan.c (Vulkan init → crash on launch)
-keep class com.winlator.cmod.shared.android.AppUtils {
    *;
}

# JNI FindClass + GetMethodID from vulkan.c (Vulkan init → crash on launch)
-keep class com.winlator.cmod.runtime.content.AdrenotoolsManager {
    *;
}

# JNI GetMethodID from xconnector_epoll.c (XServer display connector)
-keep class com.winlator.cmod.runtime.display.connector.XConnectorEpoll {
    *;
}
-keep class com.winlator.cmod.runtime.display.connector.ClientSocket {
    *;
}

# JNI FindClass + GetMethodID from wn_steam_jni.cpp
-keep class com.winlator.cmod.feature.stores.steam.wnsteam.WnConnectionObserver {
    *;
}

# JNI FindClass + GetMethodID from wn_session_jni.cpp
-keep class com.winlator.cmod.feature.stores.steam.wnsteam.WnAuthResult {
    *;
}
-keep class com.winlator.cmod.feature.stores.steam.wnsteam.WnAuthCallback {
    *;
}
-keep class com.winlator.cmod.feature.stores.steam.wnsteam.WnQrCallback {
    *;
}
-keep class com.winlator.cmod.feature.stores.steam.wnsteam.WnSteamStateObserver {
    *;
}
-keep class com.winlator.cmod.feature.stores.steam.wnsteam.WnAuthenticator {
    *;
}
-keep class com.winlator.cmod.feature.stores.steam.wnsteam.WnPrepareAppCallback {
    *;
}
-keep class com.winlator.cmod.feature.stores.steam.wnsteam.WnLibraryObserver {
    *;
}
-keep class com.winlator.cmod.feature.stores.steam.wnsteam.WnDownloadListener {
    *;
}

# Legacy rule (DownloadListener is not in Wn* but directly referenced)
-keep class com.winlator.cmod.runtime.content.Downloader$DownloadListener {
    public void onProgress(long, long);
}

# BouncyCastle JCA provider — keep BKS KeyStore (OkHttp TLS dependency)
# R8 strips META-INF/services and unused JCA provider SPI classes, breaking BKS
-keep class org.bouncycastle.jce.provider.** { *; }
-keep class org.bouncycastle.jcajce.provider.** { *; }
-dontwarn org.bouncycastle.jce.**
-dontwarn org.bouncycastle.jcajce.**

# Keep shortcut-related classes — accessed from Java-to-Kotlin interop and container I/O
-keep class com.winlator.cmod.runtime.wine.PeIconExtractor { *; }
-keep class com.winlator.cmod.runtime.container.Shortcut { *; }
-keep class com.winlator.cmod.runtime.container.Container { *; }
-keep class com.winlator.cmod.runtime.container.ContainerManager { *; }
-keep class com.winlator.cmod.shared.io.FileUtils { *; }
-keep class com.winlator.cmod.runtime.display.environment.ImageFs { *; }

# IO helper classes used pervasively in shortcut/container persistence
-keep class com.winlator.cmod.shared.io.StreamUtils { *; }

# Container dependencies — transitively used by Container/ContainerManager constructors
-keep class com.winlator.cmod.runtime.wine.WineInfo { *; }
-keep class com.winlator.cmod.runtime.wine.MSLink { *; }
-keep class com.winlator.cmod.runtime.wine.MSLink$Options { *; }
-keep class com.winlator.cmod.shared.util.KeyValueSet { *; }
-keep class com.winlator.cmod.shared.util.StringUtils { *; }
-keep class com.winlator.cmod.runtime.compat.box64.Box64Preset { *; }
-keep class com.winlator.cmod.runtime.compat.fexcore.FEXCorePreset { *; }
-keep class com.winlator.cmod.runtime.wine.EnvVars { *; }
-keep class com.winlator.cmod.runtime.wine.WineUtils { *; }
-keep class com.winlator.cmod.runtime.wine.WineThemeManager { *; }
-keep class com.winlator.cmod.runtime.display.winhandler.WinHandler { *; }
-keep class com.winlator.cmod.shared.io.TarCompressorUtils { *; }
-keep class com.winlator.cmod.shared.util.Callback { *; }
-keep class com.winlator.cmod.runtime.content.ContentProfile { *; }
-keep class com.winlator.cmod.runtime.content.ContentsManager { *; }

# Keep enum values() and valueOf() — EnumSet, EnumMap, and Kotlin libs use reflection
-keepclassmembers,allowoptimization enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Suppress warnings for missing optional Window Extensions classes
-dontwarn androidx.window.extensions.area.ExtensionWindowAreaPresentation
-dontwarn androidx.window.extensions.core.util.function.Consumer
-dontwarn androidx.window.extensions.core.util.function.Function
-dontwarn androidx.window.extensions.core.util.function.Predicate
