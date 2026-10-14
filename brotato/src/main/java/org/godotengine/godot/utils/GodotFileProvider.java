package org.godotengine.godot.utils;

import androidx.core.content.FileProvider;

/**
 * Thin subclass of {@link FileProvider} so the brotato library can declare its own
 * content provider under a distinct class name. This avoids a manifest merger conflict
 * with WinNative's own androidx.core.content.FileProvider (tileprovider / WinlatorFilesProvider),
 * while remaining a fully functional FileProvider for Godot's share/export features.
 */
public class GodotFileProvider extends FileProvider {
}
