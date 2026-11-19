package com.winlator.cmod.runtime.display.environment.components;

import android.content.Context;
import android.os.Build;
import android.util.Log;
import com.winlator.cmod.runtime.display.connector.UnixSocketConfig;
import com.winlator.cmod.runtime.display.environment.EnvironmentComponent;
import com.winlator.cmod.runtime.system.ProcessHelper;
import com.winlator.cmod.runtime.wine.EnvVars;
import com.winlator.cmod.shared.io.FileUtils;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * PulseAudio server management.
 *
 * <p>This mirrors the GameNative PulseAudio stack, which is the coherent, maintained build that
 * reliably resumes audio after interruptions such as phone calls. The pieces that make resume work
 * live in the native binaries, not here:
 *
 * <ul>
 *   <li>{@code libpulseaudio.so} + the PulseAudio libs ship as jniLibs and run from
 *       {@code nativeLibraryDir}.
 *   <li>The {@code pulseaudio.tzst} asset provides a real {@code pactl} plus a minimal, matched
 *       module set ({@code module-native-protocol-unix}, {@code module-aaudio-sink},
 *       {@code libprotocol-native}). The {@code module-aaudio-sink} registers an AAudio error
 *       callback and reopens its output stream when Android disconnects it during a call — the
 *       actual recovery mechanism the previous build lacked.
 * </ul>
 *
 * <p>Pause/resume simply suspend and un-suspend the sink via {@code pactl suspend-sink}; the module
 * closes the AAudio device on suspend and reopens it on resume.
 */
public class PulseAudioComponent extends EnvironmentComponent {
  private static final String TAG = "PulseAudioComponent";
  private static final String SINK_NAME = "AAudioSink";

  private final UnixSocketConfig socketConfig;
  private final Options options;
  private static final Object lock = new Object();
  private boolean isPaused = false;

  public PulseAudioComponent(UnixSocketConfig socketConfig) {
    this(socketConfig, new Options());
  }

  public PulseAudioComponent(UnixSocketConfig socketConfig, Options options) {
    this.socketConfig = socketConfig;
    this.options = options != null ? options : new Options();
  }

  public static class Options {
    public static final int DEFAULT_LATENCY_MILLIS = 40;
    public static final int DEFAULT_FRAGMENT_MILLIS = 10;
    public static final int DEFAULT_SAMPLE_RATE = 48000;
    public static final int DEFAULT_ALTERNATE_SAMPLE_RATE = 44100;
    public static final int DEFAULT_CHANNELS = 2;
    public static final float DEFAULT_VOLUME = 1.0f;
    public static final float MAX_VOLUME = 2.0f;
    public static final String PERFORMANCE_MODE_NONE = "none";
    public static final String PERFORMANCE_MODE_POWER_SAVING = "power_saving";
    public static final String PERFORMANCE_MODE_LOW_LATENCY = "low_latency";

    public int latencyMillis = DEFAULT_LATENCY_MILLIS;
    public int fragmentMillis = DEFAULT_FRAGMENT_MILLIS;
    public int sampleRate = DEFAULT_SAMPLE_RATE;
    public int alternateSampleRate = DEFAULT_ALTERNATE_SAMPLE_RATE;
    public int channels = DEFAULT_CHANNELS;
    public float volume = DEFAULT_VOLUME;
    public String performanceMode = PERFORMANCE_MODE_NONE;
    public boolean sampleRateOverridden = false;
    public boolean alternateSampleRateOverridden = false;

    public static Options fromEnvVars(EnvVars envVars) {
      Options options = new Options();
      if (envVars == null) return options;

      options.latencyMillis =
          Math.max(
              0,
              parseInt(
                  firstNonEmpty(
                      envVars.get("WINNATIVE_PULSE_LATENCY_MS"),
                      envVars.get("PULSE_LATENCY_MSEC")),
                  DEFAULT_LATENCY_MILLIS));
      options.fragmentMillis =
          Math.max(
              1,
              parseInt(
                  firstNonEmpty(
                      envVars.get("WINNATIVE_PULSE_FRAGMENT_MS"),
                      envVars.get("ANDROID_PULSE_FRAGMENT_MS")),
                  DEFAULT_FRAGMENT_MILLIS));
      String sampleRate =
          firstNonEmpty(
              envVars.get("WINNATIVE_PULSE_SAMPLE_RATE"),
              envVars.get("ANDROID_PULSE_SAMPLE_RATE"));
      options.sampleRateOverridden = !sampleRate.isEmpty();
      options.sampleRate =
          Math.max(
              8000,
              parseInt(sampleRate, DEFAULT_SAMPLE_RATE));

      String alternateSampleRate =
          firstNonEmpty(
              envVars.get("WINNATIVE_PULSE_ALTERNATE_SAMPLE_RATE"),
              envVars.get("ANDROID_PULSE_ALTERNATE_SAMPLE_RATE"));
      options.alternateSampleRateOverridden = !alternateSampleRate.isEmpty();
      options.alternateSampleRate =
          Math.max(
              8000,
              parseInt(alternateSampleRate, DEFAULT_ALTERNATE_SAMPLE_RATE));
      options.channels =
          Math.max(
              1,
              Math.min(
                  2,
                  parseInt(
                      firstNonEmpty(
                          envVars.get("WINNATIVE_PULSE_CHANNELS"),
                          envVars.get("ANDROID_PULSE_CHANNELS")),
                      DEFAULT_CHANNELS)));
      options.volume =
          Math.max(
              0.0f,
              Math.min(
                  parseFloat(
                      firstNonEmpty(
                          envVars.get("WINNATIVE_PULSE_VOLUME"),
                          envVars.get("ANDROID_PULSE_VOLUME")),
                      DEFAULT_VOLUME),
                  MAX_VOLUME));

      String performanceMode =
          firstNonEmpty(
              envVars.get("WINNATIVE_PULSE_AAUDIO_PERFORMANCE_MODE"),
              envVars.get("ANDROID_PULSE_AAUDIO_PERFORMANCE_MODE"));
      if (performanceMode.equalsIgnoreCase(PERFORMANCE_MODE_LOW_LATENCY)
          || performanceMode.equals("12")) {
        options.performanceMode = PERFORMANCE_MODE_LOW_LATENCY;
      } else if (performanceMode.equalsIgnoreCase(PERFORMANCE_MODE_POWER_SAVING)
          || performanceMode.equals("11")) {
        options.performanceMode = PERFORMANCE_MODE_POWER_SAVING;
      } else {
        options.performanceMode = PERFORMANCE_MODE_NONE;
      }

      return options;
    }

    private static String firstNonEmpty(String first, String second) {
      return first != null && !first.isEmpty() ? first : (second != null ? second : "");
    }

    private static int parseInt(String value, int fallback) {
      try {
        if (value != null && !value.isEmpty()) return Integer.parseInt(value);
      } catch (NumberFormatException ignored) {
      }
      return fallback;
    }

    private static float parseFloat(String value, float fallback) {
      try {
        if (value != null && !value.isEmpty()) return Float.parseFloat(value);
      } catch (NumberFormatException ignored) {
      }
      return fallback;
    }
  }

  @Override
  public void start() {
    synchronized (lock) {
      if (!isServerRunning()) {
        killAllPulseAudioProcesses();
        startPulseAudio();
        isPaused = false;
      }
    }
  }

  @Override
  public void stop() {
    synchronized (lock) {
      updateSink(true);
      isPaused = false;
      killAllPulseAudioProcesses();
    }
  }

  public void suspend() {
    synchronized (lock) {
      if (!isPaused && isServerRunning()) {
        isPaused = true;
        updateSink(true);
      }
    }
  }

  public void resume() {
    synchronized (lock) {
      if (isPaused) {
        if (isServerRunning()) {
          isPaused = false;
          updateSink(false);
        } else {
          // Daemon died while backgrounded; relaunch it. default.pa re-creates the sink.
          start();
        }
      }
    }
  }

  public boolean isServerRunning() {
    String info = execPactlCommand("info").toLowerCase(java.util.Locale.ROOT);
    return info.contains("server name:") && !info.contains("connection failure");
  }

  private void updateSink(boolean suspend) {
    execPactlCommand("suspend-sink " + SINK_NAME + " " + (suspend ? "true" : "false"));
  }

  private void killAllPulseAudioProcesses() {
    File proc = new File("/proc");
    String[] allPids =
        proc.list((dir, name) -> new File(dir, name).isDirectory() && name.matches("[0-9]+"));
    if (allPids == null) return;
    boolean killed = false;
    for (String pidStr : allPids) {
      String cmdline = ProcessHelper.readProcCmdline(pidStr);
      if (cmdline.contains("libpulseaudio.so")) {
        try {
          ProcessHelper.killProcess(Integer.parseInt(pidStr));
          killed = true;
        } catch (NumberFormatException ignored) {
        }
      }
    }
    if (killed) {
      try {
        Thread.sleep(200);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private static final String[] PULSE_LIBRARIES = {
    "libpulse.so",
    "libpulseaudio.so",
    "libpulsecommon-13.0.so",
    "libpulsecore-13.0.so",
    "libsndfile.so",
    "libltdl.so",
    "libffi.so"
  };

  /**
   * Ensures the PulseAudio shared libraries exist as real files on disk and returns the directory
   * holding them.
   *
   * <p>The daemon ({@code libpulseaudio.so}) is exec'd as a separate process and dlopens its
   * dependencies by path, so it cannot load them straight out of the APK. With
   * {@code extractNativeLibs=false} they are no longer decompressed into {@code
   * nativeLibraryDir}; this copies them from {@code nativeLibraryDir} when still present, or
   * extracts the {@code lib/<abi>/} entries from the APK into {@code files/pulseaudio/libs}
   * otherwise.
   */
  private File ensurePulseLibraries() {
    Context context = environment.getContext();
    File workingDir = new File(context.getFilesDir(), "/pulseaudio");
    File libsDir = new File(workingDir, "libs");
    if (!libsDir.isDirectory() && !libsDir.mkdirs()) {
      return new File(context.getApplicationInfo().nativeLibraryDir);
    }

    String nativeLibDir = context.getApplicationInfo().nativeLibraryDir;
    long apkInstalledAt = new File(context.getApplicationInfo().sourceDir).lastModified();
    String abi = Build.SUPPORTED_ABIS[0];

    try (ZipFile apk = new ZipFile(context.getApplicationInfo().sourceDir)) {
      for (String libraryName : PULSE_LIBRARIES) {
        File destFile = new File(libsDir, libraryName);
        File sourceFile = new File(nativeLibDir, libraryName);

        if (sourceFile.exists()) {
          if (!destFile.exists() || destFile.length() != sourceFile.length()) {
            FileUtils.copy(sourceFile, destFile);
            destFile.setExecutable(true, false);
          }
          continue;
        }

        if (destFile.exists()
            && destFile.length() > 0
            && destFile.lastModified() >= apkInstalledAt) {
          continue;
        }

        String entryName = "lib/" + abi + "/" + libraryName;
        ZipEntry entry = apk.getEntry(entryName);
        if (entry == null) {
          continue;
        }

        try (InputStream is = apk.getInputStream(entry);
            FileOutputStream fos = new FileOutputStream(destFile)) {
          byte[] buf = new byte[8192];
          int len;
          while ((len = is.read(buf)) != -1) {
            fos.write(buf, 0, len);
          }
        }
        destFile.setExecutable(true, false);
      }
    } catch (IOException e) {
      return new File(context.getApplicationInfo().nativeLibraryDir);
    }

    return libsDir;
  }

  private void startPulseAudio() {
    Context context = environment.getContext();
    File pulseLibDir = ensurePulseLibraries();

    File workingDir = new File(context.getFilesDir(), "/pulseaudio");
    if (!workingDir.isDirectory()) {
      workingDir.mkdirs();
      FileUtils.chmod(workingDir, 0771);
    }

    // Clear any stale runtime state (e.g. cookie) from a previous run.
    File configDir = new File(workingDir, ".config");
    if (configDir.exists()) FileUtils.delete(configDir);

    boolean lowLatency = Options.PERFORMANCE_MODE_LOW_LATENCY.equals(options.performanceMode);
    String sinkParams = "volume=" + options.volume + " performance_mode=1";
    if (lowLatency) sinkParams += " low_latency=true";

    File configFile = new File(workingDir, "default.pa");
    FileUtils.writeString(
        configFile,
        String.join(
            "\n",
            "load-module module-native-protocol-unix auth-anonymous=1 auth-cookie-enabled=false socket=\""
                + socketConfig.path
                + "\"",
            "load-module module-aaudio-sink " + sinkParams,
            ""));

    File modulesDir = new File(workingDir, "modules");

    ArrayList<String> envVars = new ArrayList<>();
    envVars.add("LD_LIBRARY_PATH=/system/lib64:" + pulseLibDir.getAbsolutePath() + ":" + modulesDir);
    envVars.add("HOME=" + workingDir);
    envVars.add("TMPDIR=" + environment.getTmpDir());

    String command = pulseLibDir.getAbsolutePath() + "/libpulseaudio.so";
    command += " --system=false";
    command += " --disable-shm=true";
    command += " --fail=false";
    command += " -n --file=default.pa";
    command += " --daemonize=true";
    command += " --use-pid-file=false";
    command += " --exit-idle-time=-1";

    ProcessHelper.exec(command, envVars.toArray(new String[0]), workingDir);
  }

  private String execPactlCommand(String command) {
    Context context = environment.getContext();
    File pulseLibDir = ensurePulseLibraries();
    File workingDir = new File(context.getFilesDir(), "/pulseaudio");
    if (!workingDir.isDirectory()) return "";

    File pactl = new File(workingDir, "pactl");
    if (!pactl.isFile()) {
      Log.w(TAG, "pactl not found at " + pactl.getAbsolutePath());
      return "";
    }
    if (!pactl.canExecute()) FileUtils.chmod(pactl, 0755);

    File modulesDir = new File(workingDir, "modules");
    StringBuilder output = new StringBuilder();
    try {
      String[] envp =
          new String[] {
            "LD_LIBRARY_PATH=/system/lib64:" + pulseLibDir.getAbsolutePath() + ":" + modulesDir,
            "HOME=" + workingDir,
            "PULSE_SERVER=" + socketConfig.path,
            "TMPDIR=" + environment.getTmpDir()
          };
      Process process =
          Runtime.getRuntime().exec(pactl.getAbsolutePath() + " " + command, envp, workingDir);
      try (BufferedReader reader =
          new BufferedReader(new InputStreamReader(process.getInputStream()))) {
        String line;
        while ((line = reader.readLine()) != null) {
          output.append(line).append('\n');
        }
      }
      process.waitFor();
    } catch (IOException e) {
      return "";
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return "";
    }
    return output.toString();
  }
}