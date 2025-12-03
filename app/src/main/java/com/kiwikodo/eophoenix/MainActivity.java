package com.kiwikodo.eophoenix;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;

import com.kiwikodo.eophoenix.managers.BrightnessManager;
import com.kiwikodo.eophoenix.managers.LogManager;
import com.kiwikodo.eophoenix.managers.MediaManager;
import com.kiwikodo.eophoenix.managers.NetworkManager;
import com.kiwikodo.eophoenix.managers.SDCardManager;
import com.kiwikodo.eophoenix.managers.SettingsManager;
import com.kiwikodo.eophoenix.managers.StorageManager;
import com.kiwikodo.eophoenix.managers.SlideshowManager;
import com.kiwikodo.eophoenix.managers.UIManager;


// WALKTHROUTHS
// KEYBOARD
// - ctrl alt del - reset
// - windows + L
// = if no option to use existing account - factory reset device
// - esc, tab, arrows
// - storage > apps > Bluetooth share > clear data


//  show permissions and why we need them
// mbox - won't uninstall
// allow unknown sources

// APP CAN TURN ON THE WIFI - confirmed (but suggest manual turn on if required)









// "orientation": "land",
// digital clock

// https://www.freeconvert.com/video-compressor/download
// Recommended conversion settings:
// Resolution: 720×1280 (or even 480×854 for guaranteed compatibility)
// Codec: H.264 Baseline Profile
// Container: MP4
// Bitrate: 1-1.5 Mbps

// Video Compatibility Settings:

// 1. maxVideoSizeKB: Maximum allowed video file size in kilobytes
//    - Default: 2048 (2MB)
//    - Recommended range: 1024-4096
//    - Higher values may cause out-of-memory errors

// 2. maxVideoPixels: Maximum allowed video resolution in total pixels
//    - Default: 921600 (equivalent to 1280×720 or 720p)
//    - Common values:
//      - 307200 (640×480 or 480p) - Most compatible, best for low-end devices
//      - 921600 (1280×720 or 720p) - Default, good balance
//      - 2073600 (1920×1080 or 1080p) - Only for higher-end devices
//    - Higher values require more processing power and memory


// should add this??
// app can read init status text
            // // Set screen timeout to maximum
            // android.provider.Settings.System.putInt(
            //     contentResolver,
            //     android.provider.Settings.System.SCREEN_OFF_TIMEOUT,
            //     Integer.MAX_VALUE
            // )

// -make completely black on sleep

// remove sd card when turned off - exit app!!

public class MainActivity extends Activity implements SDCardManager.SDCardListener {
    private BrightnessManager brightnessManager;
    private LogManager logManager;
    private MediaManager mediaManager;
    private NetworkManager networkManager;
    private SDCardManager sdCardManager; 
    private SettingsManager settingsManager;
    private StorageManager storageManager;
    private SlideshowManager slideshowManager;
    private com.kiwikodo.eophoenix.managers.ScheduleManager scheduleManager;
    private android.content.BroadcastReceiver scheduleBroadcastReceiver = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(android.content.Context context, android.content.Intent intent) {
            String action = intent.getAction();
            try {
                if (com.kiwikodo.eophoenix.managers.ScheduleReceiver.ACTION_RESCHEDULE.equals(action)) {
                    if (scheduleManager != null) {
                        try {
                            if (sdCardManager != null && sdCardManager.isSDCardMounted()) {
                                logManager.addLog("Received ACTION_RESCHEDULE -> calling scheduleManager.startFromSettings()");
                                scheduleManager.startFromSettings();
                            } else {
                                logManager.addLog("Received ACTION_RESCHEDULE but SD not mounted - skipping startFromSettings");
                            }
                        } catch (Exception ignored) {}
                    }
                } else if (com.kiwikodo.eophoenix.managers.ScheduleReceiver.ACTION_TRANSITION_FIRED.equals(action)) {
                    boolean turnOn = intent.getBooleanExtra("turnOn", true);
                    if (!turnOn) {
                        // Turn off: stop slideshow and dim/turn off display
                        try { if (slideshowManager != null) slideshowManager.cleanup(); } catch (Exception ignored) {}
                        try { if (brightnessManager != null) brightnessManager.turnOffDisplay(); } catch (Exception ignored) {}
                        // Optionally perform a device lock to force the screen off if admin available
                        try {
                            if (devicePolicyManager != null && devicePolicyManager.isAdminActive(adminComponent)) {
                                // devicePolicyManager.lockNow(); // Uncomment if you want to lock the device when sleeping
                            }
                        } catch (Exception ignored) {}
                    } else {
                        // Turn on: ensure media scanned and slideshow started
                        try {
                            String sd = getEffectiveSdPath();
                            com.kiwikodo.eophoenix.Settings s = (settingsManager != null) ? settingsManager.getCurrentSettings() : null;
                            if (mediaManager != null && s != null && !mediaManager.hasMedia()) mediaManager.scanMediaDirectory(sd, s.getFolder());
                            if (slideshowManager != null) slideshowManager.startSlideshow();
                            if (brightnessManager != null && s != null) brightnessManager.initializeBrightness(s.getBrightness());
                        } catch (Exception ignored) {}
                    }
                }
            } catch (Exception ignored) {}
        }
    };
    private UIManager uiManager;   
    private DevicePolicyManager devicePolicyManager;
    private ComponentName adminComponent;
    private static final int RESULT_ENABLE = 1;
    private boolean isHandlingRemoval = false;
    private boolean slideshowWasRunning = false;
    private com.kiwikodo.eophoenix.managers.CrashManager crashManager;
    // Handler and reference for pending scheduled dim tasks (so ON can cancel OFF's delayed dim)
    private Handler scheduleHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingDimRunnable = null;
    // When true, onResume should not attempt to auto-restart slideshow (used during scheduled OFF)
    private boolean suppressResumeRestart = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_startup);
        try {
            if (brightnessManager != null) {
                // Ensure startup view is readable: set to ~70% brightness
                brightnessManager.setExactNormalizedBrightness(0.7f);
                logManager.addLog("Startup view brightness set to 70%");
            }
        } catch (Exception ignored) {}
        initializeManagers();
        requestAdminPrivileges();
        
    // Initialize crash manager (prefer StorageManager's EoPhoenix dir when available)
    try {
        if (settingsManager != null) {
            com.kiwikodo.eophoenix.Settings s = null;
            try { s = settingsManager.loadSettings((storageManager != null) ? storageManager.getEffectiveRoot() : null); } catch (Exception ignored) {}
            crashManager = new com.kiwikodo.eophoenix.managers.CrashManager(getApplicationContext(), storageManager, s);
        } else {
            crashManager = new com.kiwikodo.eophoenix.managers.CrashManager(getApplicationContext(), storageManager, 30 * 60 * 1000L, 3);
        }
    } catch (Exception e) {
        crashManager = new com.kiwikodo.eophoenix.managers.CrashManager(getApplicationContext(), storageManager, 30 * 60 * 1000L, 3);
    }

        // Add the global exception handler after managers are initialized
        Thread.setDefaultUncaughtExceptionHandler((thread, ex) -> {
            long now = System.currentTimeMillis();
            // Minimal handler: record a short message and schedule background processing
            try {
                if (logManager != null) {
                    logManager.addLog("FATAL ERROR: " + ex.getMessage());
                    logManager.addPendingFileLog("[FATAL] " + ex.getMessage());
                }
            } catch (Exception ignored) {}

            // Print to system log for immediate visibility
            ex.printStackTrace();

            // Record crash to persistent history quickly
            try {
                if (crashManager != null) crashManager.recordAndPrune(now);
            } catch (Exception ignored) {}

            // Enqueue background work to write full diagnostics and flush logs.
            try {
                String stack = android.util.Log.getStackTraceString(ex);
                com.kiwikodo.eophoenix.services.CrashProcessingService.enqueueWork(getApplicationContext(), stack);
            } catch (Exception ignored) {}

            // Decide whether to schedule a restart (off the handler). If allowed, schedule via AlarmManager
            boolean allowRestart = false;
            try {
                allowRestart = crashManager == null || crashManager.shouldRestart();
            } catch (Exception ignored) {}

            if (allowRestart) {
                try {
                    long delay = -1L;
                    try {
                        if (crashManager != null) delay = crashManager.getNextRestartDelayMs();
                    } catch (Exception ignored) {}
                    if (delay < 0) {
                        if (logManager != null) logManager.addLog("CrashManager advised no restart; not scheduling restart.");
                    } else {
                        android.app.AlarmManager am = (android.app.AlarmManager) getSystemService(Context.ALARM_SERVICE);
                        Intent i = new Intent(getApplicationContext(), MainActivity.class);
                        i.setAction("com.kiwikodo.eophoenix.ACTION_RECOVER");
                        int flags = android.os.Build.VERSION.SDK_INT >= 23 ? android.app.PendingIntent.FLAG_IMMUTABLE : 0;
                        android.app.PendingIntent pi = android.app.PendingIntent.getActivity(getApplicationContext(), 12345, i, flags);
                        long triggerAt = System.currentTimeMillis() + delay;
                        if (am != null) am.set(android.app.AlarmManager.RTC_WAKEUP, triggerAt, pi);
                        if (logManager != null) logManager.addLog("Scheduled restart in ms=" + delay);
                    }
                } catch (Exception ignored) {}
            } else {
                if (logManager != null) logManager.addLog("Crash rate too high; not scheduling restart.");
            }

            // Let the process terminate so the AlarmManager can restart the Activity cleanly
        });
    }

    @Override
    public void onSdCardMounted(String path) {
        isHandlingRemoval = false;
        // Determine effective SD root (prefer removable root when available)
        String effectiveSdPath = path;
        String removableRoot = null;
        try {
            removableRoot = (sdCardManager != null) ? sdCardManager.getRemovableStorageRoot() : null;
            if (removableRoot != null) effectiveSdPath = removableRoot;
        } catch (Exception ignored) {}

        // Attempt to load settings first. If settings.json is missing or invalid, treat this as a negative mount
        Settings settings = null;
        try {
            settings = settingsManager.loadSettings(effectiveSdPath);
        } catch (Exception ignored) {}

        if (settings == null) {
            // No valid settings.json found — show the compact no-SD UI so operator can insert/configure card
            runOnUiThread(this::showNoSdView);
            return;
        }

        // Settings loaded successfully — now configure LogManager to use the removable SD (or fallback path)
        try {
            if (removableRoot != null) {
                logManager.setRemovableSdRoot(removableRoot);
            } else {
                // Fallback for older devices where removable root isn't detectable
                logManager.setSdCardPath(path);
            }
        } catch (Exception e) {
            logManager.setSdCardPath(path);
        }

        // Log and update UI to show variables when SD is present
        logManager.addLog("SD CARD MOUNTED: " + effectiveSdPath);
    // Ambient light probing removed - we only apply brightness from settings now
        // Ensure any fallback crash history is migrated into the removable EoPhoenix dir
        try {
            if (crashManager != null && storageManager != null) {
                crashManager.migrateTo(storageManager);
            }
        } catch (Exception me) {
            // Record migration failures to the pending file buffer so they aren't lost
            if (logManager != null) {
                logManager.addPendingFileLog("[MIGRATE_ERROR] " + me.getMessage());
            }
        }
        runOnUiThread(this::showVariablesView);
        // Start networking and proceed with startup. Also ensure schedule handling is enabled now that SD is present.
        networkManager.setupWiFiAndTimeSync(settings, () -> {
            try {
                // Start schedule handling once settings/network are ready
                try { if (scheduleManager != null && sdCardManager != null && sdCardManager.isSDCardMounted()) scheduleManager.startFromSettings(); } catch (Exception ignored) {}
            } catch (Exception ignored) {}
            try { proceedWithStartup(); } catch (Exception ignored) {}
        });
    }    

    @Override
    public void onSdCardRemoved() {
        if (isHandlingRemoval) {
            try { if (logManager != null) logManager.addLog("SD Card removal detected while another removal/schedule handling was in progress - proceeding to handle removal"); } catch (Exception ignored) {}
        }

    isHandlingRemoval = true;
    // Prevent resume logic from restarting slideshow during removal
    try { suppressResumeRestart = true; slideshowWasRunning = false; } catch (Exception ignored) {}
       
        try {            
            // Disable file logging immediately while handling removal
            if (logManager != null) {
                logManager.clearFileLogging();
            }
            // Stop schedule manager so no scheduled transitions fire while SD is absent
            try {
                if (scheduleManager != null) {
                    scheduleManager.stop();
                    logManager.addLog("ScheduleManager stopped due to SD removal");
                }
            } catch (Exception ignored) {}
            // First, prevent any ongoing operations that might access the SD card
            if (slideshowManager != null) {
                slideshowManager.cleanup();
            }

            // // Signal that we're leaving presentation mode 
            // if (brightnessManager != null) {
            //     brightnessManager.setPresentationMode(false);
            // }

            // Reset brightness to default startup level
            if (brightnessManager != null) {
                brightnessManager.resetToStartupBrightness();
            }
           
            // Cancel any pending operations
            if (networkManager != null) {
                networkManager.cancelOperations();
            }
           
            // Clear the media manager's files
            if (mediaManager != null) {
                try {
                    mediaManager.clearMedia();
                    logManager.addLog("Media manager cleared successfully");
                } catch (Exception e) {
                    logManager.addLog("Error clearing media manager: " + e.getMessage());
                }
            }
           
            // Use runOnUiThread to avoid potential threading issues
            runOnUiThread(() -> {
                try {
                    // Cancel any pending scheduled dim so we don't remain dark after SD removal
                    try {
                        if (pendingDimRunnable != null) {
                            scheduleHandler.removeCallbacks(pendingDimRunnable);
                            pendingDimRunnable = null;
                        }
                    } catch (Exception ignored) {}

                    // Hide any sleep overlay that's present
                    try { View overlay = findViewById(R.id.sleepOverlay); if (overlay != null) overlay.setVisibility(View.GONE); } catch (Exception ignored) {}

                    // Always return to the startup/no-SD UI so an operator can interact — do not respect schedule while SD is absent
                    try {
                        setContentView(R.layout.activity_startup);
                        // Ensure startup view is readable: set to ~70% brightness
                        if (brightnessManager != null) {
                            brightnessManager.setExactNormalizedBrightness(0.7f);
                            logManager.addLog("Startup view brightness set to 70% on SD removal");
                        }
                        // Reattach log container and show compact no-SD UI
                        LinearLayout logContainer = findViewById(R.id.logContainer);
                        if (logManager != null && logContainer != null) logManager.setLogContainer(logContainer);
                        showNoSdView();
                    } catch (Exception e) {
                        if (logManager != null) logManager.addLog("Error switching to startup on SD removal: " + e.getMessage());
                    }

                    // Briefly acquire a wake lock to ensure display is turned back on (useful if display was powered off)
                    try {
                        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                        if (pm != null) {
                            PowerManager.WakeLock wl = pm.newWakeLock(
                                PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE,
                                "EoPhoenix:SDRemovalWake");
                            try {
                                wl.acquire(2000);
                            } catch (Exception ignored) {}
                            try { wl.release(); } catch (Exception ignored) {}
                        }
                    } catch (Exception ignored) {}

                    // Keep the device awake to prevent system from taking over
                    try { getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); } catch (Exception ignored) {}

                } catch (Exception e) {
                    if (logManager != null) {
                        logManager.addLog("Error resetting UI: " + e.getMessage());
                    }
                }
            });
        } catch (Exception e) {
            if (logManager != null) {
                logManager.addLog("Critical error during SD card removal handling: " + e.getMessage());
            }
        } finally {
            // Reset the flag after a longer delay to prevent multiple handling attempts
            new Handler().postDelayed(() -> isHandlingRemoval = false, 5000);
        }
    }

    private void showNoSdView() {
        try {
            LinearLayout variables = findViewById(R.id.variablesContainer);
            if (variables != null) {
                // Hide everything except title and noSdMessage
                for (int i = 0; i < variables.getChildCount(); i++) {
                    if (variables.getChildAt(i).getId() == R.id.appTitle ||
                        variables.getChildAt(i).getId() == R.id.noSdMessage) {
                        variables.getChildAt(i).setVisibility(View.VISIBLE);
                    } else {
                        variables.getChildAt(i).setVisibility(View.GONE);
                    }
                }
                View noSd = findViewById(R.id.noSdMessage);
                if (noSd != null) noSd.setVisibility(View.VISIBLE);
            }
        } catch (Exception ignored) {}
    }

    private void showVariablesView() {
        try {
            LinearLayout variables = findViewById(R.id.variablesContainer);
            if (variables != null) {
                for (int i = 0; i < variables.getChildCount(); i++) {
                    variables.getChildAt(i).setVisibility(View.VISIBLE);
                }
                View noSd = findViewById(R.id.noSdMessage);
                if (noSd != null) noSd.setVisibility(View.GONE);
            }
        } catch (Exception ignored) {}
    }
            
    private void initializeManagers() {
        logManager = new LogManager(this);
        LinearLayout logContainer = findViewById(R.id.logContainer);
        if (logContainer != null) {
            logManager.setLogContainer(logContainer);
        }
    // Make the LogManager globally accessible to background services
    com.kiwikodo.eophoenix.managers.LogManager.setInstance(logManager);
        
        // Track initialization results for a compact summary
        List<String> initResults = new ArrayList<>();
        long initStart = System.currentTimeMillis();

        // SettingsManager will be created after we have a StorageManager; create a placeholder for now
        try {
            settingsManager = new SettingsManager(getApplicationContext(), logManager, null);
            initResults.add("SettingsManager:OK");
        } catch (Throwable t) {
            initResults.add("SettingsManager:FAIL: " + t.getMessage());
            logManager.addLog("SettingsManager init failed: " + t.getMessage());
        }

        // SDCardManager
        try {
            sdCardManager = new SDCardManager(getApplicationContext(), logManager, settingsManager);
            sdCardManager.initialize(this);
            initResults.add("SDCardManager:OK");
        } catch (Throwable t) {
            initResults.add("SDCardManager:FAIL: " + t.getMessage());
            logManager.addLog("SDCardManager init failed: " + t.getMessage());
        }

        // StorageManager (centralized SD path resolution)
        storageManager = null;
        try {
            storageManager = new StorageManager(getApplicationContext(), sdCardManager, logManager);
            // Recreate SettingsManager with storage support
            settingsManager = new SettingsManager(getApplicationContext(), logManager, storageManager);
            // Let LogManager know about the StorageManager so it can configure removable logs
            if (logManager != null) logManager.setStorageManager(storageManager);
            initResults.add("StorageManager:OK");
        } catch (Throwable t) {
            initResults.add("StorageManager:FAIL: " + t.getMessage());
            logManager.addLog("StorageManager init failed: " + t.getMessage());
        }

        // If settings.json is not present under the effective EoPhoenix directory at cold start,
        // show the compact no-SD UI immediately. This avoids treating unrelated removable
        // devices (like USB keyboards) as valid SD cards.
        try {
            boolean settingsPresent = false;
            java.io.File settingsFile = null;
            if (storageManager != null) {
                settingsFile = storageManager.getEoPhoenixSubdir("settings.json");
            } else {
                String effective = (storageManager != null) ? storageManager.getEffectiveRoot() : null;
                if (effective != null) settingsFile = new java.io.File(effective, "EoPhoenix/settings.json");
            }
            if (settingsFile != null && settingsFile.exists()) settingsPresent = true;

            if (!settingsPresent) {
                runOnUiThread(this::showNoSdView);
            }
        } catch (Exception ignored) {}

        // BrightnessManager (needs Activity for Window control)
        try {
            brightnessManager = new BrightnessManager(this, logManager);
            initResults.add("BrightnessManager:OK");
        } catch (Throwable t) {
            initResults.add("BrightnessManager:FAIL: " + t.getMessage());
            logManager.addLog("BrightnessManager init failed: " + t.getMessage());
        }

    // ...existing code...

        // NetworkManager (application context preferred)
        try {
            networkManager = new NetworkManager(getApplicationContext(), logManager);
            initResults.add("NetworkManager:OK");
        } catch (Throwable t) {
            initResults.add("NetworkManager:FAIL: " + t.getMessage());
            logManager.addLog("NetworkManager init failed: " + t.getMessage());
        }

        // UIManager (requires Activity)
        try {
            uiManager = new UIManager(this, logManager, networkManager);
            initResults.add("UIManager:OK");
        } catch (Throwable t) {
            initResults.add("UIManager:FAIL: " + t.getMessage());
            logManager.addLog("UIManager init failed: " + t.getMessage());
        }

        // MediaManager (application context ok)
        try {
            mediaManager = new MediaManager(getApplicationContext(), logManager, uiManager, settingsManager, storageManager);
            initResults.add("MediaManager:OK");
        } catch (Throwable t) {
            initResults.add("MediaManager:FAIL: " + t.getMessage());
            logManager.addLog("MediaManager init failed: " + t.getMessage());
        }

        // SlideshowManager (requires Activity and several managers)
        try {
            slideshowManager = new SlideshowManager(this, logManager, mediaManager, brightnessManager, settingsManager);
            initResults.add("SlideshowManager:OK");
        } catch (Throwable t) {
            initResults.add("SlideshowManager:FAIL: " + t.getMessage());
            logManager.addLog("SlideshowManager init failed: " + t.getMessage());
        }

        // ScheduleManager - used to schedule quiet hours transitions
        try {
            scheduleManager = new com.kiwikodo.eophoenix.managers.ScheduleManager(this, logManager, settingsManager);
            initResults.add("ScheduleManager:OK");
        } catch (Throwable t) {
            initResults.add("ScheduleManager:FAIL: " + t.getMessage());
            logManager.addLog("ScheduleManager init failed: " + t.getMessage());
        }

        long initEnd = System.currentTimeMillis();
        String initSummary = "Manager init summary: " + String.join(" | ", initResults) + " | duration_ms=" + (initEnd - initStart);
        logManager.addLog(initSummary);

        // Buffer init summary to pending-file logs. We intentionally avoid writing an "init_status.txt"
        // directly during startup to prevent racey filesystem access and to prefer a single background
        // flusher responsible for placing diagnostics onto removable storage when available.
        try {
            if (logManager != null) {
                logManager.addPendingFileLog("[INIT] " + initSummary);
                logManager.addLog("Init summary buffered for background flush");
            }
        } catch (Exception e) {
            if (logManager != null) logManager.addLog("Failed to buffer init summary: " + e.getMessage());
        }

        // slideshowManager.toggleBrightnessDebugging(true);

    logManager.addLog(getString(R.string.application_started));
    }    

    private void requestAdminPrivileges() {
        devicePolicyManager = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        adminComponent = new ComponentName(this, AdminReceiver.class);
        
        if (!devicePolicyManager.isAdminActive(adminComponent)) {
            Intent adminIntent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
            adminIntent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent);
            adminIntent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Admin access needed to control screen state");
            startActivityForResult(adminIntent, RESULT_ENABLE);
        } else {
            startApplication();
        }
    }

    // Centralized SD path resolution to prefer StorageManager when available
    private String getEffectiveSdPath() {
        try {
            if (storageManager != null) {
                String r = storageManager.getEffectiveRoot();
                if (r != null) return r;
            }
            if (sdCardManager != null) return sdCardManager.getMountedPath();
        } catch (Exception ignored) {}
        return null;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        logManager.addLog("Activity result - Request: " + requestCode + ", Result: " + resultCode);
        
        if (requestCode == RESULT_ENABLE) {
            if (resultCode == Activity.RESULT_OK) {
                startApplication();
            } else {
                logManager.addLog("Admin privileges denied");
                // Buffer a short admin warning to the pending-file queue so the background flusher
                // can persist it to removable storage if/when available. Avoid direct file I/O here.
                try {
                    if (logManager != null) {
                        logManager.addPendingFileLog("[ADMIN_WARN] Device admin not active | timestamp=" + System.currentTimeMillis());
                        logManager.addLog("Admin warning buffered for background flush");
                    }
                } catch (Exception ignored) {}

                // Continue startup in a degraded/headless mode so the device remains operational
                try { startApplication(); } catch (Exception ignored) {}
            }
        }
    }

    private void startApplication() {
        try {
            if (!sdCardManager.isSDCardMounted()) {
                // Do not block UI: wait for SD card in background and retry startup when available
                // Show compact no-SD UI immediately
                runOnUiThread(this::showNoSdView);
                final int maxAttempts = (settingsManager != null && settingsManager.getCurrentSettings() != null)
                    ? settingsManager.getCurrentSettings().getSdWaitMaxAttempts() : 3;
                final int delayMs = (settingsManager != null && settingsManager.getCurrentSettings() != null)
                    ? settingsManager.getCurrentSettings().getSdWaitDelayMs() : 1000;

                new Thread(() -> {
                    String path = sdCardManager.waitForSdCardMounted(maxAttempts, delayMs);
                    runOnUiThread(() -> {
                        if (path == null) {
                            logManager.addLog(getString(R.string.no_sd_card));
                        } else {
                            // SD appeared - try startup again
                            startApplication();
                        }
                    });
                }).start();
                return;
            }

            String sdCardPath = getEffectiveSdPath();
        logManager.addLog(getString(R.string.loading_settings));
        Settings settings = settingsManager.loadSettings(sdCardPath);
    
            if (settings != null) {
                // Show variables now that settings are available
                runOnUiThread(this::showVariablesView);
                logManager.addLog(getString(R.string.starting_wifi_setup));
                networkManager.setupWiFiAndTimeSync(settings, this::proceedWithStartup);
            } else {
                logManager.addLog(getString(R.string.fatal_settings_not_loaded));
            }
        } catch (Exception e) {
            logManager.addLog(getString(R.string.fatal_error_startup_fmt, e.getMessage()));
            logManager.addLog(getString(R.string.stack_trace_fmt, Arrays.toString(e.getStackTrace())));
        }
    }    
    
    private void proceedWithStartup() {
        logManager.addLog("Updating settings...");
        Settings currentSettings = settingsManager.getCurrentSettings();
        // Configure runtime managers from settings
        try {
            logManager.configureFromSettings(currentSettings);
        } catch (Exception ignored) {}
        uiManager.updateSettingsDisplay(currentSettings);  
    
    String sdCardPath = getEffectiveSdPath();
    if (mediaManager.scanMediaDirectory(sdCardPath, currentSettings.getFolder())) {
            // Initialize brightness setting first (it won't apply until presentation mode is enabled)
            logManager.addLog("Preparing brightness settings...");
            brightnessManager.initializeBrightness(currentSettings.getBrightness());
            
            // Then start the slideshow initialization
            // Before initializing slideshow, check persisted schedule state (in case we should be OFF)
            try {
                boolean shouldBeOn = true;
                // Prefer authoritative schedule computation if we have a ScheduleManager
                try {
                    com.kiwikodo.eophoenix.managers.ScheduleManager sm = scheduleManager;
                    com.kiwikodo.eophoenix.Settings s = (settingsManager != null) ? settingsManager.getCurrentSettings() : null;
                    if (sm != null && s != null && s.getSchedule() != null) {
                        shouldBeOn = sm.isNowScheduledOn(s.getSchedule(), s.getTimeZone());
                        logManager.addLog("Computed schedule state at startup: " + (shouldBeOn ? "ON" : "OFF"));
                    } else {
                        android.content.SharedPreferences prefs = getSharedPreferences("eo_schedule", Context.MODE_PRIVATE);
                        shouldBeOn = prefs.getBoolean("schedule_currently_on", true);
                        logManager.addLog("Persisted schedule state used at startup: " + (shouldBeOn ? "ON" : "OFF"));
                    }
                } catch (Exception e) {
                    android.content.SharedPreferences prefs = getSharedPreferences("eo_schedule", Context.MODE_PRIVATE);
                    shouldBeOn = prefs.getBoolean("schedule_currently_on", true);
                }

                if (!shouldBeOn) {
                    logManager.addLog("Applying schedule OFF on startup");
                    applyScheduleTransition(false);
                } else {
                    slideshowManager.initializeSlideshow();
                }
            } catch (Exception e) {
                logManager.addLog("Error deciding schedule state on startup: " + e.getMessage());
                try { slideshowManager.initializeSlideshow(); } catch (Exception ignored) {}
            }
            // Start schedule handling after slideshow initialized
            try {
                try { if (scheduleManager != null && sdCardManager != null && sdCardManager.isSDCardMounted()) scheduleManager.startFromSettings(); } catch (Exception ignored) {}
            } catch (Exception ignored) {}
        }
    }
    
    


    

    


    @Override
    protected void onStart() {
        super.onStart();
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        // logManager.addLog("Activity restarting");
    }
    
    @Override
    protected void onResume() {
        super.onResume();

        // Set log container
        try { if (logManager != null) logManager.setLogContainer(findViewById(R.id.logContainer)); } catch (Exception ignored) {}

        // Register schedule broadcast listeners (idempotent if unregistered in onPause)
        try {
            android.content.IntentFilter f1 = new android.content.IntentFilter(com.kiwikodo.eophoenix.managers.ScheduleReceiver.ACTION_RESCHEDULE);
            android.content.IntentFilter f2 = new android.content.IntentFilter(com.kiwikodo.eophoenix.managers.ScheduleReceiver.ACTION_TRANSITION_FIRED);
            registerReceiver(scheduleBroadcastReceiver, f1);
            registerReceiver(scheduleBroadcastReceiver, f2);
        } catch (Exception ignored) {}

        // If slideshow was running before pause, ensure it restarts when possible
        try {
            if (!suppressResumeRestart && slideshowWasRunning && slideshowManager != null && !slideshowManager.isRunning() && sdCardManager != null && sdCardManager.isSDCardMounted()) {
                logManager.addLog("Screen turned back on - checking system state");
                String sdCardPath = getEffectiveSdPath();
                Settings currentSettings = settingsManager.getCurrentSettings();
                if (currentSettings != null) {
                    if (networkManager != null) {
                        String wifiStateVerbose = networkManager.getWifiStateVerbose();
                        logManager.addLog("Current WiFi state: " + wifiStateVerbose);
                        if (!wifiStateVerbose.startsWith("3 ")) {
                            logManager.addLog("\n");
                            logManager.addLog("!!! WIFI ISSUE DETECTED: " + wifiStateVerbose + " !!!");
                            logManager.addLog("Please LONG PRESS the POWER BUTTON to restart the device");
                            logManager.addLog("This may resolve the WiFi system settings");
                        }
                        restartSlideshow(sdCardPath, currentSettings);
                    } else {
                        restartSlideshow(sdCardPath, currentSettings);
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        try {
            logManager.addLog("MainActivity.onNewIntent: " + intent);
        } catch (Exception ignored) {}

        // If this intent carries a schedule transition, handle it centrally
        try {
            if (intent != null && intent.hasExtra("turnOn")) {
                boolean turnOn = intent.getBooleanExtra("turnOn", true);
                try { logManager.addLog("Schedule transition received via onNewIntent: turnOn=" + turnOn); } catch (Exception ignored) {}
                applyScheduleTransition(turnOn);
            }
        } catch (Exception e) {
            try { logManager.addLog("Error handling onNewIntent: " + e.getMessage()); } catch (Exception ignored) {}
        }
    }

    /**
     * Centralized handling for schedule ON/OFF transitions. This ensures the slideshow
     * stops reliably and display is dimmed when turning off, and restarts when turning on.
     */
    private void applyScheduleTransition(boolean turnOn) {
        // If SD card is not mounted, ignore schedule transitions — we manage UI manually on removal
        try {
            if (sdCardManager == null || !sdCardManager.isSDCardMounted()) {
                try { logManager.addLog("Ignoring schedule transition because SD is not mounted: turnOn=" + turnOn); } catch (Exception ignored) {}
                return;
            }
        } catch (Exception ignored) {}
        if (!turnOn) {
            try { logManager.addLog("Applying schedule OFF: stopping slideshow and dimming"); } catch (Exception ignored) {}

            // Pause slideshow for sleep (preserve caches/players for fast resume)
            try {
                if (slideshowManager != null) {
                    try { slideshowWasRunning = slideshowManager.isRunning(); } catch (Exception ignored) {}
                    logManager.addLog("Pausing slideshow for scheduled OFF");
                    try { slideshowManager.pauseForSleep(); } catch (Exception ex) { logManager.addLog("pauseForSleep error: " + ex.getMessage()); }
                }
            } catch (Exception e) {
                try { logManager.addLog("slideshowManager.pauseForSleep() error: " + e.getMessage()); } catch (Exception ignored) {}
            }

            // Suppress onResume auto-restart while we are in scheduled OFF
            try {
                suppressResumeRestart = true;
                // mark handling removal so other code knows we're intentionally paused
                isHandlingRemoval = true;
            } catch (Exception ignored) {}

            // Show sleep overlay on top of activity_view: display 'Sleeping...' for 10s then clear text and dim
            try {
                // Compute next wake text (best-effort) before updating UI
                String nextWake = "";
                try {
                    com.kiwikodo.eophoenix.Settings s = (settingsManager != null) ? settingsManager.getCurrentSettings() : null;
                    if (s != null && s.getSchedule() != null && scheduleManager != null) {
                        long nextOn = scheduleManager.getNextOnEpoch(s.getSchedule(), s.getTimeZone());
                        if (nextOn > 0) {
                            java.util.Date d = new java.util.Date(nextOn);
                            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("EEE HH:mm");
                            sdf.setTimeZone(java.util.TimeZone.getTimeZone(s.getTimeZone() != null ? s.getTimeZone() : java.util.TimeZone.getDefault().getID()));
                            nextWake = " See you at " + sdf.format(d);
                        }
                    }
                } catch (Exception ignored) {}

                final String overlayText = "Sleeping..." + nextWake;
                // Ensure we present a blank presentation view when SD is mounted during OFF
                runOnUiThread(() -> {
                    try {
                        // If SD card is present, switch to the presentation layout so the overlay sits on a
                        // blank background instead of the startup/log UI.
                        try {
                            if (sdCardManager != null && sdCardManager.isSDCardMounted()) {
                                setContentView(R.layout.activity_view);

                                // Hide media views (no media while sleeping) and show a neutral dim overlay
                                try {
                                    View iv = findViewById(R.id.imageView);
                                    View pv = findViewById(R.id.playerView);
                                    View dim = findViewById(R.id.dimOverlay);
                                    if (iv != null) iv.setVisibility(View.GONE);
                                    if (pv != null) pv.setVisibility(View.GONE);
                                    if (dim != null) {
                                        dim.setVisibility(View.VISIBLE);
                                        try { dim.setAlpha(0.0f); } catch (Exception ignored) {}
                                    }
                                } catch (Exception ignored) {}

                                // Rewire log container if present so logs don't remain visible on-screen
                                try {
                                    LinearLayout logContainer = findViewById(R.id.logContainer);
                                    if (logManager != null && logContainer != null) {
                                        logManager.setLogContainer(logContainer);
                                    }
                                } catch (Exception ignored) {}
                            }
                        } catch (Exception ignored) {}

                        View overlay = findViewById(R.id.sleepOverlay);
                        TextView tv = findViewById(R.id.sleepOverlayText);
                        if (overlay != null) overlay.setVisibility(View.VISIBLE);
                        if (tv != null) tv.setText(overlayText);
                    } catch (Exception ignored) {}
                });

                // Cancel any existing pending dim (defensive)
                try {
                    if (pendingDimRunnable != null) {
                        scheduleHandler.removeCallbacks(pendingDimRunnable);
                        pendingDimRunnable = null;
                    }
                } catch (Exception ignored) {}

                // Post the dim as a cancellable runnable on the shared handler
                pendingDimRunnable = () -> {
                    try {
                        runOnUiThread(() -> {
                            try {
                                TextView tv = findViewById(R.id.sleepOverlayText);
                                if (tv != null) tv.setText("");
                            } catch (Exception ignored) {}
                        });

                        if (brightnessManager != null) {
                            logManager.addLog("Setting exact brightness to 0 for sleep (post-message)");
                            brightnessManager.setExactNormalizedBrightness(0.0f);
                        }
                    } catch (Exception e) {
                        try { logManager.addLog("Error during sleep post-message: " + e.getMessage()); } catch (Exception ignored) {}
                    } finally {
                        // clear reference
                        pendingDimRunnable = null;
                    }
                };

                scheduleHandler.postDelayed(pendingDimRunnable, 10000);
            } catch (Exception e) {
                try { logManager.addLog("Error showing sleep overlay: " + e.getMessage()); } catch (Exception ignored) {}
            }

            // As a final measure, stop any running player via the SlideshowManager's cleanup already called
            try {
                logManager.addLog("Schedule OFF handling complete");
            } catch (Exception ignored) {}

            // Defensive: ensure the ScheduleManager has scheduled the next transition (ON)
            try {
                try { if (scheduleManager != null && sdCardManager != null && sdCardManager.isSDCardMounted()) {
                    logManager.addLog("Re-running scheduleManager.startFromSettings() after OFF to ensure next ON alarm is scheduled");
                    scheduleManager.startFromSettings();
                } } catch (Exception ignored) {}
            } catch (Exception ignored) {}
        } else {
            try { logManager.addLog("Applying schedule ON: restoring slideshow and brightness"); } catch (Exception ignored) {}

            // Restore brightness and start slideshow
            try {
                // Cancel any pending dim from a prior OFF transition so ON isn't overridden
                try {
                    if (pendingDimRunnable != null) {
                        logManager.addLog("Cancelling pending sleep dim due to schedule ON");
                        scheduleHandler.removeCallbacks(pendingDimRunnable);
                        pendingDimRunnable = null;
                    }
                } catch (Exception ignored) {}

                // Clear suppression so resume logic can restart normally
                try {
                    suppressResumeRestart = false;
                    isHandlingRemoval = false;
                } catch (Exception ignored) {}

                Settings s = (settingsManager != null) ? settingsManager.getCurrentSettings() : null;
                if (brightnessManager != null && s != null) {
                    // Restore brightness immediately
                    brightnessManager.initializeBrightness(s.getBrightness());
                    logManager.addLog("Brightness re-initialized from settings");
                }
            } catch (Exception e) {
                try { logManager.addLog("brightness re-init error: " + e.getMessage()); } catch (Exception ignored) {}
            }

            try {
                String sd = getEffectiveSdPath();
                Settings s = (settingsManager != null) ? settingsManager.getCurrentSettings() : null;
                if (mediaManager != null && s != null && !mediaManager.hasMedia()) {
                    mediaManager.scanMediaDirectory(sd, s.getFolder());
                    logManager.addLog("Media directory scanned on schedule ON");
                }
            } catch (Exception e) {
                try { logManager.addLog("media scan on ON error: " + e.getMessage()); } catch (Exception ignored) {}
            }

                // Reset cleanup guard and run slideshow restart helper to bring presentation back.
                // Note: this is *not* performing full device init (WiFi/time/etc), only restarts slideshow resources.
            try {
                if (slideshowManager != null) {
                    try { slideshowManager.resetCleanupGuard(); } catch (Exception ignored) {}
                }
                // Clear handling removal state
                isHandlingRemoval = false;
                // Restart slideshow via restartSlideshow which performs a delayed restart and brightness init
                try {
                    Settings s2 = (settingsManager != null) ? settingsManager.getCurrentSettings() : null;
                    String sdPath = getEffectiveSdPath();
                    if (s2 != null) {
                        restartSlideshow(sdPath, s2);
                        logManager.addLog("Simulated SD mount on schedule ON: restartSlideshow called");
                    }
                } catch (Exception ignored) {}
            } catch (Exception ignored) {}

            try {
                if (slideshowManager != null) {
                    // Show Waking... on overlay for 5 seconds, then hide overlay and initialize slideshow
                    runOnUiThread(() -> {
                        try {
                            View overlay = findViewById(R.id.sleepOverlay);
                            TextView tv = findViewById(R.id.sleepOverlayText);
                            if (overlay != null) overlay.setVisibility(View.VISIBLE);
                            if (tv != null) tv.setText("Waking...");
                        } catch (Exception ignored) {}
                    });

                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        try {
                            runOnUiThread(() -> {
                                try {
                                    View overlay = findViewById(R.id.sleepOverlay);
                                    if (overlay != null) overlay.setVisibility(View.GONE);
                                    // Reinitialize slideshow which will reuse activity_view
                                    slideshowManager.initializeSlideshow();
                                    logManager.addLog("slideshowManager.initializeSlideshow() called due to schedule ON");
                                } catch (Exception e) {
                                    try { logManager.addLog("Error initializing slideshow on ON: " + e.getMessage()); } catch (Exception ignored) {}
                                }
                            });
                        } catch (Exception e) {
                            try { logManager.addLog("Error during wake post-message: " + e.getMessage()); } catch (Exception ignored) {}
                        }
                    }, 5000);
                }
            } catch (Exception e) {
                try { logManager.addLog("slideshow start error: " + e.getMessage()); } catch (Exception ignored) {}
            }

            // Defensive: ensure the next transition is scheduled after handling ON
            try {
                try { if (scheduleManager != null && sdCardManager != null && sdCardManager.isSDCardMounted()) {
                    logManager.addLog("Re-running scheduleManager.startFromSettings() after ON to ensure next transition is scheduled");
                    scheduleManager.startFromSettings();
                } } catch (Exception ignored) {}
            } catch (Exception ignored) {}
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Save slideshow state
        try { if (slideshowManager != null) slideshowWasRunning = slideshowManager.isRunning(); } catch (Exception ignored) {}

        // Unregister schedule receiver to avoid leaks
        try { unregisterReceiver(scheduleBroadcastReceiver); } catch (Exception ignored) {}

        // If we're handling SD card removal, log it
        try { if (isHandlingRemoval) logManager.addLog("Activity paused while handling SD card removal"); } catch (Exception ignored) {}
    }
    
    private void restartSlideshow(String sdCardPath, Settings currentSettings) {
        // Compute effective SD path at time of restart
    String effectiveSd = getEffectiveSdPath();
        // Give the system a moment to stabilize before restarting
        new Handler().postDelayed(() -> {
            try {
                // Verify SD card is still mounted
                if (sdCardManager.isSDCardMounted()) {
                    // Reinitialize if needed
                    if (mediaManager != null && !mediaManager.hasMedia()) {
                        mediaManager.scanMediaDirectory(effectiveSd, currentSettings.getFolder());
                    }
                    
                    // Restart slideshow
                    if (slideshowManager != null) {
                        logManager.addLog("Restarting slideshow");
                        slideshowManager.startSlideshow();
                        
                        // Add this line to reinitialize brightness after slideshow restart
                        brightnessManager.initializeBrightness(currentSettings.getBrightness());
                    }
                }
            } catch (Exception e) {
                logManager.addLog("Error restarting slideshow: " + e.getMessage());
            }
        }, 1000);
    }

    // Note: duplicate onPause logic earlier in file; ensure slideshow state is saved
    

    @Override
    protected void onStop() {
        super.onStop();
        // If we're handling SD card removal, log it
        if (isHandlingRemoval) {
            logManager.addLog("Activity stopped while handling SD card removal");
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        
        if (slideshowManager != null) {
            slideshowManager.cleanup();
        }
        
        if (sdCardManager != null) {
            sdCardManager.cleanup();
        }
        
        if (networkManager != null) {
            networkManager.cancelOperations();
        }
        
        if (brightnessManager != null) {
            brightnessManager.cleanup();
        }
    }
}




//  works to turn off
                            // // Step 3: Turn display off
                            // new Handler().postDelayed(() -> {
                            //     addLog("Turning display off");
                            //     devicePolicyManager.lockNow();
                                
                            //     // Step 4: Turn display back on and return to startup
                            //     new Handler().postDelayed(() -> {
                            //         addLog("Turning display on");
                            //         PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                            //         PowerManager.WakeLock wakeLock = pm.newWakeLock(
                            //             PowerManager.FULL_WAKE_LOCK | 
                            //             PowerManager.ACQUIRE_CAUSES_WAKEUP | 
                            //             PowerManager.ON_AFTER_RELEASE, 
                            //             "EoPhoenix:WakeLock");
                                        
                            //         try {
                            //             wakeLock.acquire();
                            //             Thread.sleep(2000);
                            //             wakeLock.release();
                            //             addLog("Wake lock released");
                                        
                            //             // Now return to startup layout
                            //             setContentView(R.layout.activity_startup);
                            //             logContainer = findViewById(R.id.logContainer);
                            //             updateLogContainer();
                            //             addLog("Returned to startup layout");
                            //         } catch (Exception e) {
                            //             addLog("Wake lock error: " + e.getMessage());
                            //         }
                            //     }, 5000);
                            // }, 5000);


