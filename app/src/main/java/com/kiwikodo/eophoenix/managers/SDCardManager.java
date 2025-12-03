package com.kiwikodo.eophoenix.managers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Environment;
import java.io.File;

public class SDCardManager {
    private final Context context;
    private final LogManager logManager;
    private final SettingsManager settingsManager;
    private SDCardListener sdCardListener;
    private boolean isInitialCheck = true;
    private String mountedPath;

    public interface SDCardListener {
        void onSdCardMounted(String path);
        void onSdCardRemoved();
    }

    public String getMountedPath() {
        return mountedPath;
    }

    private final BroadcastReceiver sdCardReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_MEDIA_MOUNTED.equals(action)) {
                mountedPath = intent.getData().getPath();
                logManager.addLog("SD Card mounted - checking...");
                if (sdCardListener != null) {
                    sdCardListener.onSdCardMounted(mountedPath);
                }
            } else if (Intent.ACTION_MEDIA_REMOVED.equals(action)) {
                mountedPath = null;
                if (sdCardListener != null) {
                    sdCardListener.onSdCardRemoved();
                }
            }
        }
    };

    public SDCardManager(Context context, LogManager logManager, SettingsManager settingsManager) {
        this.context = context;
        this.logManager = logManager;
        this.settingsManager = settingsManager;
    }

    /**
     * Returns the best guess for a removable (secondary) external storage root path if one exists.
     * On API19+ we can use Context.getExternalFilesDirs(null) which returns app-specific dirs for
     * each external storage volume. If a secondary volume exists (index 1) we derive the mount root
     * by walking up from the app-specific path.
     * Returns null when no removable secondary external storage is detected.
     */
    public String getRemovableStorageRoot() {
        try {
            File[] dirs = context.getExternalFilesDirs(null);
            if (dirs != null && dirs.length > 1) {
                File sec = dirs[1];
                if (sec != null) {
                    // app-specific path looks like /storage/XXXX/Android/data/<pkg>/files
                    File root = sec;
                    for (int i = 0; i < 4; i++) {
                        if (root == null) break;
                        root = root.getParentFile();
                    }
                    if (root != null && root.exists()) return root.getAbsolutePath();
                    if (sec.exists()) return sec.getAbsolutePath();
                }
            }
        } catch (Exception e) {
            logManager.addLog("Error detecting removable storage: " + e.getMessage());
        }
        return null;
    }

    /**
     * Simple availability check for removable secondary external storage.
     */
    public boolean isRemovableStorageAvailable() {
        String p = getRemovableStorageRoot();
        return p != null;
    }

    public void initialize(SDCardListener listener) {
        this.sdCardListener = listener;
        registerSdCardReceiver();
        checkInitialSdCardState();
    }

    /**
     * Wait for SD card to become mounted with retries.
     * Returns the mounted path if available, otherwise null.
     */
    public String waitForSdCardMounted(int maxAttempts, long delayMs) {
        for (int i = 0; i < maxAttempts; i++) {
            if (isSDCardMounted()) {
                mountedPath = Environment.getExternalStorageDirectory().getPath();
                if (sdCardListener != null) sdCardListener.onSdCardMounted(mountedPath);
                return mountedPath;
            }
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return null;
    }

    private void registerSdCardReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_MEDIA_MOUNTED);
        filter.addAction(Intent.ACTION_MEDIA_REMOVED);
        filter.addDataScheme("file");
        filter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        context.registerReceiver(sdCardReceiver, filter);
    }

    private void checkInitialSdCardState() {
        if (isSDCardMounted()) {
            mountedPath = Environment.getExternalStorageDirectory().getPath();
            isInitialCheck = false;
            if (sdCardListener != null) {
                sdCardListener.onSdCardMounted(mountedPath);
            }
        }
    }

    public boolean isSDCardMounted() {
        return Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED);
    }

    public void cleanup() {
        try {
            context.unregisterReceiver(sdCardReceiver);
        } catch (IllegalArgumentException e) {
            logManager.addLog("Warning: SDCard receiver already unregistered");
        }
    }
}
