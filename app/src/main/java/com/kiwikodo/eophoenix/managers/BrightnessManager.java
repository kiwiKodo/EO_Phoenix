package com.kiwikodo.eophoenix.managers;

import android.app.Activity;
import android.provider.Settings;
// Sensor-based auto-brightness removed - app will use settings-only brightness
import android.view.View;
import android.os.Handler;
import android.os.Looper;
import android.view.Window;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;
import android.widget.ImageView;
import com.google.android.exoplayer2.ui.PlayerView;
import java.io.File;
import java.io.FileWriter;

public class BrightnessManager {
    private final Activity activity;
    private final LogManager logManager;
    private final Window window;
    private ImageView imageView;
    private PlayerView playerView;
    private android.view.View dimOverlay;
    private float lastLightLevel = 0f;
    
    // Store the brightness setting for delayed application
    private String currentBrightnessSetting = null;
    private boolean isAutoMode = false;
    private float currentBrightness = 1.0f;
    
    // Constants for this specific device
    private static final int MAX_DEVICE_BRIGHTNESS = 224; // Based on ADB findings
    private static final String BRIGHTNESS_SYSFS_PATH = "/sys/devices/virtual/lcd_lt8668/lcd_lt8668/brightness";
    
    // Default brightness to use for startup screen and fallbacks (map to level 7)
    private static final float DEFAULT_BRIGHTNESS = 0.7f; // level 7 = 70%
    // Minimum non-zero epsilon used for window/system brightness to avoid raw-zero special cases
    private static final float MIN_BRIGHTNESS_EPSILON = 0.0001f;

    public BrightnessManager(Activity activity, LogManager logManager) {
        this.activity = activity;
        this.logManager = logManager;
        this.window = activity.getWindow();
    // No SensorManager usage anymore
        
        // Make sure we have full brightness control flags
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        
        // Set default brightness for startup screen
        setDefaultBrightness();
    }
    
    // Set a good default brightness for the startup screen
    private void setDefaultBrightness() {
        try {
            // Set window brightness
            LayoutParams params = window.getAttributes();
            params.screenBrightness = DEFAULT_BRIGHTNESS;
            window.setAttributes(params);
            
            // Set system brightness
            try {
                Settings.System.putInt(activity.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS,
                    (int)(DEFAULT_BRIGHTNESS * 255));
            } catch (Exception e) {
                // Ignore if not available
            }
            
            // Set device-specific brightness
            try {
                int deviceBrightness = (int)(DEFAULT_BRIGHTNESS * MAX_DEVICE_BRIGHTNESS);
                writeSysfsBrightness(deviceBrightness);
            } catch (Exception e) {
                // Ignore if not available
            }
            
        } catch (Exception e) {
            logManager.addLog("Error setting default brightness: " + e.getMessage());
        }
    }

    public void setViews(ImageView imageView, PlayerView playerView) {
        this.imageView = imageView;
        this.playerView = playerView;
        logManager.addLog("Media views set successfully");
        // Apply any pending brightness setting (manual only)
        if (currentBrightnessSetting != null) {
            setManualBrightness(currentBrightnessSetting);
        }
    }

    // Overload to accept dim overlay view for video dimming
    public void setViews(ImageView imageView, PlayerView playerView, android.view.View dimOverlay) {
        this.imageView = imageView;
        this.playerView = playerView;
        this.dimOverlay = dimOverlay;
        logManager.addLog("Media views + dim overlay set successfully");
        if (currentBrightnessSetting != null) {
            setManualBrightness(currentBrightnessSetting);
        }
    }
    
    // For backward compatibility
    public void setMediaView(ImageView mediaView) {
        this.imageView = mediaView;
        logManager.addLog("MediaView set (legacy method)");
        // Apply any pending brightness setting (manual only)
        if (currentBrightnessSetting != null) {
            setManualBrightness(currentBrightnessSetting);
        }
    }

    private void setBrightnessNormalized(final float normalizedBrightness, final boolean isAuto) {
        // Store current brightness
        currentBrightness = normalizedBrightness;

        activity.runOnUiThread(() -> {
            try {
                // 1. Apply to window parameters
                LayoutParams params = window.getAttributes();
                params.screenBrightness = normalizedBrightness;
                window.setAttributes(params);
                
                // 2. Apply to system settings
                try {
                    Settings.System.putInt(activity.getContentResolver(),
                        Settings.System.SCREEN_BRIGHTNESS,
                        (int)(normalizedBrightness * 255));
                } catch (Exception e) {
                    // Ignore if not available
                }
                
                // 3. Apply directly to device-specific sysfs path - MOST RELIABLE METHOD
                try {
                    int deviceBrightness = (int)(normalizedBrightness * MAX_DEVICE_BRIGHTNESS);
                    writeSysfsBrightness(deviceBrightness);
                } catch (Exception e) {
                    logManager.addLog("Error writing to sysfs: " + e.getMessage());
                }
                
                // 4. Apply to views - IMPROVED APPROACH FOR THIS DEVICE
                // For this specific device, we need to boost the alpha values
                if (imageView != null) {
                    // Boost image brightness - never go below 0.4 for visibility
                    float imageAlpha = 0.4f + (normalizedBrightness * 0.6f);
                    imageView.setAlpha(imageAlpha);
                }
                
                // For videos we use an overlay to control perceived brightness because
                // SurfaceView used by PlayerView doesn't respect view alpha on many devices.
                if (playerView != null && dimOverlay != null) {
                    // dimOverlay alpha = 1 - normalizedBrightness
                    float overlayAlpha = 1.0f - normalizedBrightness;
                    dimOverlay.setVisibility(View.VISIBLE);
                    dimOverlay.setAlpha(overlayAlpha);
                } else if (playerView != null && dimOverlay == null) {
                    // fallback: attempt to set playerView alpha, may not be effective
                    try { playerView.setAlpha(normalizedBrightness); } catch (Exception ignored) {}
                }

                // Log detailed brightness information
                String brightnessInfo = getActualBrightnessInfo();
                logManager.addLog(String.format("%s brightness set to: %d%% | Actual: %s",
                    isAuto ? "Auto" : "Manual", 
                    (int)(normalizedBrightness * 100),
                    brightnessInfo));
                    
            } catch (Exception e) {
                logManager.addLog("Error setting brightness: " + e.getMessage());
            }
        });
    }

    /**
     * Public API to set an exact normalized brightness value (0.0 - 1.0).
     * Use this for sleep/wake transitions where we need deterministic control.
     */
    public void setExactNormalizedBrightness(final float normalizedBrightness) {
        // For sleep transitions we want the panel to be truly off if possible. Some devices
        // treat a raw 0.0 window/sys setting specially, so we write a raw 0 to the
        // device sysfs path while still applying a tiny epsilon to the Window/System
        // settings to avoid undefined behavior there. This produces Device: 0/MAX while
        // Window/System show a tiny non-zero value.
        float normalized = Math.max(0f, Math.min(1f, normalizedBrightness));
        if (normalized == 0f) {
            // Attempt a raw sysfs 0 (best-effort)
            try {
                writeSysfsBrightness(0);
            } catch (Exception ignored) {}
            // Use a tiny epsilon for window/system so they don't trigger special device behavior
            setBrightnessNormalized(MIN_BRIGHTNESS_EPSILON, false);
            return;
        }

        // Normal path: clamp and apply
        float clamped = normalized;
        setBrightnessNormalized(clamped, false);
    }

    // Helper to directly set dim overlay alpha when present
    private void setDimOverlayAlpha(float normalizedBrightness) {
        if (playerView != null && dimOverlay != null) {
            float overlayAlpha = 1.0f - normalizedBrightness;
            dimOverlay.setVisibility(View.VISIBLE);
            dimOverlay.setAlpha(overlayAlpha);
        }
    }
        
    // Helper method to write directly to sysfs
    private void writeSysfsBrightness(int brightness) {
        try {
            File brightnessFile = new File(BRIGHTNESS_SYSFS_PATH);
            if (brightnessFile.exists() && brightnessFile.canWrite()) {
                FileWriter writer = new FileWriter(brightnessFile);
                writer.write(String.valueOf(brightness));
                writer.close();
            }
        } catch (Exception e) {
            // Silently fail - this is just an additional method
        }
    }

    public void initializeBrightness(String brightnessSetting) {
        this.currentBrightnessSetting = brightnessSetting;
        logManager.addLog("Manual brightness mode selected: " + brightnessSetting);
        isAutoMode = false;
        if (imageView != null || playerView != null) {
            setManualBrightness(brightnessSetting);
        }
    }

    /**
     * Temporary helper to probe the ambient light sensor for a short duration and
     * log readings to LogManager for diagnostics. Safe to call at startup; this
     * registers a transient listener and unregisters after `seconds` seconds.
     */
    // probeAmbientLight removed - app will not attempt sensor or sysfs probing for ambient light
       
    private void setManualBrightness(String brightnessSetting) {
        try {
            int brightnessLevel = Integer.parseInt(brightnessSetting);
            if (brightnessLevel < 1 || brightnessLevel > 10) {
                logManager.addLog("Invalid brightness level: " + brightnessLevel + ", using default (5)");
                brightnessLevel = 5;
            }
            
            // FIXED: Exact conversion from 1-10 scale to 0.0-1.0 scale
            float normalizedBrightness = 0.1f + ((brightnessLevel - 1) / 9.0f * 0.9f);
            // float normalizedBrightness = (brightnessLevel - 1) / 9.0f;
            setBrightnessNormalized(normalizedBrightness, false);
            
            stopAutoBrightness();
        } catch (NumberFormatException e) {
            logManager.addLog("Invalid brightness format: " + brightnessSetting);
            setBrightnessNormalized(0.5f, false);
        }
    }
   
    private void startAutoBrightness() {
    // Auto-brightness removed - method retained as a no-op for compatibility
    }
    
    private void stopAutoBrightness() {
    // No-op - auto-brightness is not used
    }

    public String getActualBrightnessInfo() {
        StringBuilder info = new StringBuilder();
        
        try {
            // Window brightness
            float windowBrightness = window.getAttributes().screenBrightness;
            info.append(String.format("Window: %.2f", windowBrightness));
            
            // View alphas
            if (imageView != null) {
                info.append(String.format(" | ImageView alpha: %.2f", imageView.getAlpha()));
            }
            
            if (playerView != null) {
                info.append(String.format(" | PlayerView alpha: %.2f", playerView.getAlpha()));
            }
            
            // System brightness
            try {
                int systemBrightness = Settings.System.getInt(
                    activity.getContentResolver(), 
                    Settings.System.SCREEN_BRIGHTNESS);
                float normalizedSystemBrightness = systemBrightness / 255.0f;
                info.append(String.format(" | System: %.2f", normalizedSystemBrightness));
            } catch (Exception e) {
                info.append(" | System: unavailable");
            }
            
            // Device-specific brightness
            try {
                File brightnessFile = new File(BRIGHTNESS_SYSFS_PATH);
                if (brightnessFile.exists() && brightnessFile.canRead()) {
                    java.util.Scanner scanner = new java.util.Scanner(brightnessFile);
                    int deviceBrightness = scanner.nextInt();
                    float normalizedDeviceBrightness = deviceBrightness / (float)MAX_DEVICE_BRIGHTNESS;
                    info.append(String.format(" | Device: %.2f (%d/%d)", 
                        normalizedDeviceBrightness, deviceBrightness, MAX_DEVICE_BRIGHTNESS));
                    scanner.close();
                }
            } catch (Exception e) {
                info.append(" | Device: unavailable");
            }
            
            // Which view is visible
            if (imageView != null && playerView != null) {
                boolean imageViewVisible = imageView.getVisibility() == View.VISIBLE;
                boolean playerViewVisible = playerView.getVisibility() == View.VISIBLE;
                info.append(String.format(" | Visible: %s", 
                    imageViewVisible ? "Image" : (playerViewVisible ? "Video" : "None")));
            }
            
        } catch (Exception e) {
            return "Error getting brightness info: " + e.getMessage();
        }
        
        return info.toString();
    }

    public void turnOffDisplay() {
        setBrightnessNormalized(0.05f, false);
        logManager.addLog("Display dimmed");
    }
     
    public void restoreDisplay() {
    setManualBrightness(currentBrightnessSetting);
    logManager.addLog("Display brightness restored (manual)");
    }

    public void cleanup() {
    // Reset to default brightness when cleaning up
    setDefaultBrightness();
    }
    
    // Call this when returning to startup screen
    public void resetToStartupBrightness() {
        setDefaultBrightness();
    }

    // Recursive sysfs scanner helper
    private void scanSysfsRecursive(java.io.File dir, int depth, int maxDepth) {
        if (dir == null || !dir.exists() || depth > maxDepth) return;
        try {
            java.io.File[] entries = dir.listFiles();
            if (entries == null) return;
            // Log directory summary
            try {
                String dirLine = String.format("PROBE_SYSFS_LS: %s entries=%d\n", dir.getAbsolutePath(), entries.length);
                try { logManager.writeDeferredFile("ambient_probe_sysfs.log", dirLine, true); } catch (Exception ex) { logManager.addLog(dirLine); }
            } catch (Exception ignored) {}

            for (java.io.File e : entries) {
                try {
                    // Log basic entry metadata
                    try {
                        String entryMeta = String.format("PROBE_SYSFS_ENTRY: path=%s type=%s size=%d canRead=%b canWrite=%b\n",
                            e.getAbsolutePath(), e.isFile() ? "file" : (e.isDirectory() ? "dir" : "other"), e.isFile() ? e.length() : 0,
                            e.canRead(), e.canWrite());
                        try { logManager.writeDeferredFile("ambient_probe_sysfs.log", entryMeta, true); } catch (Exception ex) { logManager.addLog(entryMeta); }
                    } catch (Exception ignored) {}

                    if (e.isFile()) {
                        try {
                            if (e.length() > 4096) continue;
                            if (!e.canRead()) continue;
                            byte[] data = new byte[(int) e.length()];
                            java.io.FileInputStream fis = new java.io.FileInputStream(e);
                            try { fis.read(data); } finally { try { fis.close(); } catch (Exception ignored) {} }
                            String val = new String(data).trim().replaceAll("\r?\n", " ");
                            String rel = e.getAbsolutePath();
                            String line = String.format("PROBE_SYSFS: %s = %s\n", rel, val);
                            try { logManager.writeDeferredFile("ambient_probe_sysfs.log", line, true); } catch (Exception ex) { logManager.addLog(line); }
                        } catch (Exception ignored) {}
                    } else if (e.isDirectory()) {
                        // Recurse into directories
                        scanSysfsRecursive(e, depth + 1, maxDepth);
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }
}
