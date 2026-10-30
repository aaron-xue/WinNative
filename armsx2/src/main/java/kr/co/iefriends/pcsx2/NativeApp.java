package kr.co.iefriends.pcsx2;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.ParcelFileDescriptor;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.system.Os;
import android.system.OsConstants;
import android.view.InputDevice;
import android.view.Surface;

import com.armsx2.BiosInfo;
import com.armsx2.EmuState;
import com.armsx2.runtime.MainActivityRuntime;
import com.armsx2.events.TestResult;

import java.io.File;
import java.lang.ref.WeakReference;

public class NativeApp {
	private static boolean loadAttempted;

	/**
	 * Delegates to Armsx2Bootstrap which loads all native libs from the
	 * Retro-Consoles bundle path data/armsx2/jniLibs instead of bundling
	 * .so files in the APK.
	 */
	public static synchronized void ensureLoaded(Context context) {
		if (loadAttempted) return;
		loadAttempted = true;
		if (com.armsx2.wn.Armsx2Bootstrap.INSTANCE.ensure(context)) {
			hasNoNativeBinary = false;
			android.util.Log.i("ARMSX2", "PCSX2_LOAD " + selectNativeLibraryName()
					+ " pageSize=" + getRuntimePageSize());
		} else {
			hasNoNativeBinary = true;
			android.util.Log.e("ARMSX2", "PCSX2_LOAD_FAILED pageSize=" + getRuntimePageSize());
		}
	}

	public static boolean hasNoNativeBinary = true;

	/**
	 * Called by Armsx2Bootstrap when the emucore .so loads successfully.
	 */
	public static void markNativeBinaryAvailable() {
		hasNoNativeBinary = false;
	}

	/**
	 * Called by Armsx2Bootstrap when the emucore .so fails to load.
	 */
	public static void markNativeBinaryAbsent() {
		hasNoNativeBinary = true;
	}

	private static long getRuntimePageSize() {
		try {
			long pageSize = Os.sysconf(OsConstants._SC_PAGESIZE);
			return pageSize > 0 ? pageSize : 4096;
		} catch (Throwable ignored) {
			return 4096;
		}
	}

	private static String selectNativeLibraryName() {
		return getRuntimePageSize() >= 16384 ? "emucore_16k" : "emucore_4k";
	}

	/**
	 * Public variant for Armsx2Bootstrap so it can determine which emucore
	 * variant to load without duplicating the page-size logic.
	 */
	public static String selectNativeLibraryNameStatic(Context context) {
		return selectNativeLibraryName();
	}

	protected static WeakReference<Context> mContext;
	public static Context getContext() {
		return mContext != null ? mContext.get() : null;
	}

	public static void initializeOnce(Context context) {
		mContext = new WeakReference<>(context);

		File externalFilesDir = context.getExternalFilesDir(null);
		if (externalFilesDir == null) {
			externalFilesDir = context.getDataDir();
		}

		String chosen = MainActivityRuntime.Companion.systemDirPosix();
		if (chosen != null && !MainActivityRuntime.Companion.validateSystemDirWritable(chosen)) {
			chosen = null;
		}
		String dataPath = (chosen != null) ? chosen : externalFilesDir.getAbsolutePath();

		String biosFolder = MainActivityRuntime.Companion.biosFolderPosix();
		if (biosFolder == null || biosFolder.isEmpty()) {
			biosFolder = externalFilesDir.getAbsolutePath() + java.io.File.separator + "bios";
		}

		initialize(dataPath, biosFolder, android.os.Build.VERSION.SDK_INT);

		com.armsx2.RetroAchievementsHostOverrideReceiver.applyPending(context);
	}

	public static native void initialize(String path, String biosFolder, int apiVer);

	public static native void dumpPgoProfile();

	public static native String shaderPresetParams(String presetPath);

	public static native void setShaderChainParams(String presetPath, String[] names, float[] values);

	public static native void captureGsDump(int frames);

	public static native void setEeDiffVerify(boolean enabled);

	public static native void setSetting(String section, String key, String type, String value);

	public static native void commitSettings();

	public static native boolean applyGSSettingsLive();
	public static native int reloadPatches();
	public static native boolean reloadTextureReplacements();

	public static native void setEnabledPatches(boolean cheats, String[] allNames, String[] enabledNames, String serial);
	public static native String getGameSerial();
	public static native String getGameCRC();

	public static native String getBuildVersion();

	public static native String getPauseGameSerial();

	public static native String getAchievementsJSON();

	public static native String getRichPresence();

	public static native String loginAchievements(String username, String password);

	public static native void logoutAchievements();

	public static native void setHardcoreMode(boolean enabled);

	public static native boolean isHardcoreMode();
	public static native boolean isHardcorePersisted();

	public static native void setAchievementsOption(String key, boolean enabled);

	public static native void setAchievementsUnlockSound(String path);

	public static native void setAchievementsHostOverride(String host);

	public static native void clearAchievementsHostOverride();

	public static native void osdApplyFlags(boolean fps, boolean vps, boolean speed, boolean cpu,
		boolean gpu, boolean res, boolean gsStats, boolean frameTimes, boolean hwInfo,
		boolean version, boolean settings, boolean inputs);

	public static native void osdShowFPS(boolean enabled);
	public static native void osdShowVPS(boolean enabled);
	public static native void osdShowSpeed(boolean enabled);
	public static native void osdShowCPU(boolean enabled);
	public static native void osdShowGPU(boolean enabled);
	public static native void osdShowResolution(boolean enabled);
	public static native void osdShowGSStats(boolean enabled);
	public static native void osdShowFrameTimes(boolean enabled);
	public static native void osdShowHardwareInfo(boolean enabled);
	public static native void osdShowMessages(boolean enabled);
	public static native void osdShowGpuStats(boolean enabled);
	public static native void osdShowVersion(boolean enabled);
	public static native void osdShowSettings(boolean enabled);
	public static native void osdShowInputs(boolean enabled);
	public static native void osdSetScale(float scale);

	public static native boolean gameIniBeginWrite();
	public static native void gameIniPut(String section, String key, String value);
	public static native boolean gameIniCommitWrite();

	public static native void setCustomVulkanDriver(
		String driverDir, String driverName,
		String redirectDir, String hookLibDir);

	public static native void setPadButton(int index, int range, boolean iskeypressed);
	public static native void setPadButtonForPort(int port, int index, int range, boolean iskeypressed);
	public static native void setMultitap(int port, boolean enabled);

	public static native boolean usbKeyboardKey(int port, int androidKeyCode, boolean pressed);
	public static native void usbApplyPorts();

	public static volatile int sRumbleDeviceId = -1;
	public static volatile boolean sRumbleEnabled = true;
	private static final int RUMBLE_MS = 3000;

	public static void onPadRumble(int pad, int largeMotor, int smallMotor) {
		if (!sRumbleEnabled) return;
		int devId = com.armsx2.input.PadRouter.INSTANCE.deviceIdForPort(pad);
		if (devId < 0) devId = sRumbleDeviceId;
		if (devId < 0 && pad != 0) return;
		float low = Math.max(0f, Math.min(1f, largeMotor / 255f));
		float high = Math.max(0f, Math.min(1f, smallMotor / 255f));
		vibrateDevice(devId, low, high, RUMBLE_MS, pad == 0);
	}

	private static final java.util.Set<android.media.MediaPlayer> sActiveSounds =
			java.util.Collections.synchronizedSet(new java.util.HashSet<>());

	public static void playSound(String path) {
		if (path == null || path.isEmpty()) return;
		if (sActiveSounds.size() >= 4) return;
		new Thread(() -> {
			android.media.MediaPlayer mp = null;
			try {
				mp = new android.media.MediaPlayer();
				mp.setAudioAttributes(new android.media.AudioAttributes.Builder()
						.setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
						.setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
						.build());
				mp.setDataSource(path);
				mp.setOnCompletionListener(m -> { sActiveSounds.remove(m); try { m.release(); } catch (Throwable ignore) {} });
				mp.setOnErrorListener((m, what, extra) -> { sActiveSounds.remove(m); try { m.release(); } catch (Throwable ignore) {} return true; });
				sActiveSounds.add(mp);
				mp.prepare();
				mp.start();
			} catch (Throwable t) {
				if (mp != null) { sActiveSounds.remove(mp); try { mp.release(); } catch (Throwable ignore) {} }
				android.util.Log.e("ARMSX2", "playSound failed: " + path, t);
			}
		}, "armsx2-ra-sound").start();
	}

	private static void vibrateDevice(int devId, float low, float high, int ms, boolean allowSystemFallback) {
		try {
			float combined = Math.min(1f, low * 0.6f + high * 0.4f);
			boolean drove = false;
			InputDevice dev = (devId >= 0) ? InputDevice.getDevice(devId) : null;
			if (dev != null) {
				if (Build.VERSION.SDK_INT >= 31) {
					VibratorManager vm = dev.getVibratorManager();
					int[] ids = vm.getVibratorIds();
					if (ids.length >= 2) {
						drove = rumbleOne(vm.getVibrator(ids[0]), low, ms);
						drove |= rumbleOne(vm.getVibrator(ids[1]), high, ms);
					} else if (ids.length == 1) {
						drove = rumbleOne(vm.getVibrator(ids[0]), combined, ms);
					} else {
						drove = rumbleOne(dev.getVibrator(), combined, ms);
					}
				} else {
					drove = rumbleOne(dev.getVibrator(), combined, ms);
				}
			}
			if (!drove && allowSystemFallback) {
				rumbleOne(systemVibrator(), combined, ms);
			}
		} catch (Throwable ignored) {
		}
	}

	private static boolean rumbleOne(Vibrator v, float intensity, int ms) {
		if (v == null || !v.hasVibrator()) return false;
		if (intensity <= 0f) {
			try { v.cancel(); } catch (Throwable ignored) {}
			return true;
		}
		int amp = Math.round(intensity * 255f);
		if (amp < 1) amp = 1;
		if (amp > 255) amp = 255;
		try {
			v.vibrate(VibrationEffect.createOneShot(ms, amp));
		} catch (Throwable t) {
			try { v.vibrate(ms); } catch (Throwable ignored) {}
		}
		return true;
	}

	private static volatile Vibrator sSystemVibrator;
	private static Vibrator systemVibrator() {
		Vibrator v = sSystemVibrator;
		if (v != null) return v;
		try {
			Context ctx = getContext();
			if (ctx != null) {
				if (Build.VERSION.SDK_INT >= 31) {
					VibratorManager vm = (VibratorManager) ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
					v = (vm != null) ? vm.getDefaultVibrator() : null;
				} else {
					v = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
				}
				if (v != null) sSystemVibrator = v;
			}
		} catch (Throwable ignored) {
		}
		return v;
	}

	private static volatile long sLastTouchHapticMs = 0L;
	public static void touchHaptic() {
		long now = android.os.SystemClock.uptimeMillis();
		if (now - sLastTouchHapticMs < 24L) return;
		sLastTouchHapticMs = now;
		try { rumbleOne(systemVibrator(), 0.6f, 12); } catch (Throwable ignored) {}
	}

	private static int nthGamepadDeviceId(int index) {
		int n = 0;
		for (int id : InputDevice.getDeviceIds()) {
			InputDevice d = InputDevice.getDevice(id);
			if (d == null) continue;
			int src = d.getSources();
			boolean pad = (src & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
					|| (src & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
			if (!pad) continue;
			if (n == index) return id;
			n++;
		}
		return -1;
	}

	public static void testRumble(int port) {
		int devId = com.armsx2.input.PadRouter.INSTANCE.deviceIdForPort(port);
		if (devId < 0) devId = nthGamepadDeviceId(port);
		vibrateDevice(devId, 0.9f, 0.9f, 500, true);
	}

	public static String rumbleStatusForPort(int port) {
		int devId = com.armsx2.input.PadRouter.INSTANCE.deviceIdForPort(port);
		boolean mapped = devId >= 0;
		if (devId < 0) devId = nthGamepadDeviceId(port);
		if (devId < 0) return "Player " + (port + 1) + ": no controller found";
		InputDevice d = InputDevice.getDevice(devId);
		String name = (d != null && d.getName() != null) ? d.getName() : ("device " + devId);
		int vmCount = 0;
		boolean legacy = false;
		try {
			Vibrator lv = (d != null) ? d.getVibrator() : null;
			legacy = lv != null && lv.hasVibrator();
		} catch (Throwable ignored) {}
		if (Build.VERSION.SDK_INT >= 31 && d != null) {
			try { vmCount = d.getVibratorManager().getVibratorIds().length; } catch (Throwable ignored) {}
		}
		boolean hasRumble = vmCount > 0 || legacy;
		int motors = Math.max(vmCount, legacy ? 1 : 0);
		return "Player " + (port + 1) + ": " + name
				+ (mapped ? "" : " (not active in-game yet)")
				+ (hasRumble ? " — rumble OK (" + motors + " motor" + (motors == 1 ? "" : "s") + ")"
						: " — NO rumble exposed by Android");
	}

	public static native void setAspectRatio(int type);
	public static native void setFmvAspectRatio(int type);
	public static native void speedhackLimitermode(int value);
	public static native void setNominalSpeed(int percent);
	public static native void setFpsCap(int fps);
	public static native void applyFramerateLive(float ntsc, float pal);
	public static native void setFrameSkip(int skip);
	public static native void setAudioVolume(int volume);
	public static native void setAudioMuted(boolean muted);
	public static native void setAudioSwapChannels(boolean swap);
	public static native void speedhackEecyclerate(int value);
	public static native void speedhackEecycleskip(int value);
	public static native void setInstantVU1(boolean enabled);

	public static native void renderUpscalemultiplier(float value);
	public static native void renderTvShader(int value);
	public static native void renderShadeBoost(boolean enabled, int brightness, int contrast, int saturation, int gamma);
	public static native void renderSoftware();
	public static native void renderOpenGL();
	public static native void renderVulkan();
	public static native void renderAuto();

	public static native boolean toggleTextureDumping();

	public static native boolean createMemoryCard(String name, int type, int fileType);
	public static native boolean isMemoryCard(String name);

	public static native void onNativeSurfaceCreated();
	public static native void onNativeSurfaceChanged(Surface surface, int w, int h);
	public static native void onNativeSurfaceDestroyed();
	public static native void setDisplayRefreshRate(float hz);

	public static native boolean runVMThread(String path);
	public static native void pause();
	public static native void resume();
	public static native void shutdown();
	public static native boolean hasActiveVM();

	public static native void flushShaderCache();

	/* JIT self-tests removed from the bundled libemucore — keep the callback for
	 * any potential future use and remove the native declarations. */

	public static void onTestResults(String label, int passed, int total) {
		MainActivityRuntime.Companion.onTestResults(new TestResult(label, passed, total));
	}

	public static native BiosInfo getBiosInfoFromFd(int fd);

	public static native String getGameSerialFromFd(int fd);

	// ---- auto-tune callbacks (called from native via GetStaticMethodID) ----

	/** Called from native after auto-tune committed a setting. No-op in WinNative
	 *  (no AutoTuneStore yet), but the method MUST exist or JNI aborts. */
	public static void onAutoTuneCommitted(String serial, String section, String key, String value) {
		android.util.Log.v("ARMSX2", "onAutoTuneCommitted " + section + "/" + key + "=" + value);
	}

	// ---- native methods present in the bundled libemucore but absent from this class ----

	public static native String[] autoTuneDescribe(String serial);
	public static native String[] autoTuneReset(String serial);
	public static native String getGameDbEntries(String serial);
	public static native String gameDbClaimingKeys();

	public static native String getGameTitle(String path);
	public static native float getFPS();
	public static native float getNominalFrameRate();
	public static native float getVPS();
	public static native float getEmuSpeedPercent();
	public static native float getCpuThreadUsage();
	public static native float getGsThreadUsage();
	public static native float getGpuUsage();
	public static native float getAverageFrameTime();

	public static native String getPauseGameTitle();
	public static native String getAchievementsHashForPath(String imagePath);
	public static native boolean isHardwareRenderer();

	public static native void setAchievementsOptionInt(String key, int value);

	public static native void setAdpfEnabled(boolean enabled);
	public static native void emulog(String msg);

	public static native void setThermals(float cpu, float gpu, float battery, boolean show);

	public static native void osdShowAll(boolean enabled);
	public static native void osdSetColor(int rgb);

	public static native long zstdDecoderCreate(long maxOutputBytes);
	public static native int zstdDecoderDecode(long handle, byte[] in, int inOff, int inLen, byte[] out, int outOff, int outLen);
	public static native void zstdDecoderDestroy(long handle);

	public static native void usbSetDeviceType(int port, String type);
	public static native String usbDeviceTypes();
	public static native void usbSetDeviceSubtype(int port, int subtype);
	public static native void usbLightgunAim(float x, float y);
	public static native void usbLightgunButton(int port, int bind, boolean pressed);

	public static native void setArcadeLaunchFiles(String elf, String media, String sram);
	public static native String getLastBootError();
	public static native int arcadeModeForGameId(String gameId);
	public static native boolean isArcadeSession();
	public static native void arcadeInsertCoin(int player);
	public static native void arcadeService(boolean pressed);
	public static native void arcadeToggleTest();
	public static native boolean arcadeTestModeOn();
	public static native boolean isArcadeBios(String path);
	public static native String getArcadeBiosNeed(String gameId);
	public static native String getArcadeControls(String gameId);
	public static native void setArcadeRemap(int[] pairs);
	public static native String getArcadeGames();

	public static native String getHostfsDir();
	public static native int extractIsoToHostfs(String isoPath, String subdir);
	public static native boolean setElfDiscOverride(String elfPath, String discPath);
	public static native String getElfDiscOverride(String elfPath);
	public static native void gameIniClaim(String section, String key);
	public static native boolean gameIniBeginStage(String serial);
	public static native void gameIniClearStage();
	public static native void reloadGameSettingsLayer();
	public static native boolean gameIniBeginWriteForSerial(String serial);

	public static native int getCompatibilityForSerial(String serial);

	public static native String getRegionForSerial(String serial);

	public static native boolean saveStateToSlot(int slot);
	public static native boolean loadStateFromSlot(int slot);
	public static native String getGamePathSlot(int slot);
	public static native byte[] getImageSlot(int slot);
	public static native byte[] getSaveStateImage(String path);

	public static native boolean changeDisc(String path);

	public static native boolean saveAutosaveState();
	public static native boolean loadAutosaveState();
	public static native boolean hasAutosaveState();
	public static native byte[] getAutosaveImage();
	public static native String getAutosaveGamePath();
	public static native int getPresentedFrameCount();

	public static void vmSetPaused(boolean paused) {
		new Handler(Looper.getMainLooper()).post(() -> {
			if (MainActivityRuntime.isVmStopInProgress())
				return;
			if (!paused && MainActivityRuntime.eState.getValue() == EmuState.STOPPED)
				return;
			if (paused) {
				MainActivityRuntime.eState.setValue(EmuState.PAUSED);
			} else {
				MainActivityRuntime.eState.setValue(EmuState.RUNNING);
				MainActivityRuntime.onVmRunning();
			}
		});
	}

	public static int openContentUri(String uriString) {
		Context _context = getContext();
		if(_context != null) {
			ContentResolver _contentResolver = _context.getContentResolver();
			try {
				ParcelFileDescriptor filePfd = _contentResolver.openFileDescriptor(Uri.parse(uriString), "r");
				if (filePfd != null) {
					return filePfd.detachFd();
				}
			} catch (Exception ignored) {}
		}
		return -1;
	}

	public static boolean createDirectoryPath(String path) {
		if (path == null || path.isEmpty()) return false;
		try {
			java.io.File dir = new java.io.File(path);
			if (dir.isDirectory()) return true;
			dir.mkdirs();
			return dir.isDirectory();
		} catch (Throwable t) {
			return false;
		}
	}
}