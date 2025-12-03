package com.kiwikodo.eophoenix.managers;

import android.app.Activity;
import android.widget.TextView;
import com.kiwikodo.eophoenix.R;
import com.kiwikodo.eophoenix.Settings;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

public class UIManager implements NetworkStatusListener {
    private final Activity activity;
    private final LogManager logManager;
    private final NetworkManager networkManager;
    private Settings currentSettings;

    public UIManager(Activity activity, LogManager logManager, NetworkManager networkManager) {
        this.activity = activity;
        this.logManager = logManager;
        this.networkManager = networkManager;
        networkManager.setNetworkStatusListener(this);
    }

    public void setCurrentSettings(Settings settings) {
        this.currentSettings = settings;
    }

    @Override
    public void onWifiStatusUpdate(String ssid, String statusMessage) {
        String displaySsid = ssid == null || ssid.isEmpty() ? "None" : ssid;
        updateTextView(R.id.wifiStatus,
            activity.getString(R.string.wifi_status_fmt, displaySsid, statusMessage));
    }    

    @Override
    public void onTimeStatusUpdate(boolean isAutoSynced, String timezone) {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
        String timeString = sdf.format(new Date());
        String syncStatus = isAutoSynced ? "(Auto Synced)" : "(MANUAL SET REQUIRED)";

        updateTextView(R.id.timeInfo,
            activity.getString(R.string.time_info_fmt, timeString, syncStatus));
        updateTextView(R.id.timezoneInfo,
            activity.getString(R.string.timezone_fmt, timezone));
    }       

    public void updateSettingsDisplay(Settings settings) {
        try {
            logManager.addLog("Starting UI updates...");
                       
            // Startup Info
            updateTextView(R.id.startupInfo,
                activity.getString(R.string.startup_delay_fmt, settings.getStartupDelay()));
           
            // Folder Info
            updateTextView(R.id.folderInfo,
                activity.getString(R.string.folder_info_fmt, settings.getFolder()));
           
            updateTextView(R.id.slideshowInfo,
                activity.getString(R.string.slideshow_interval_fmt,
                settings.getSlideshowDelay()));
           
            updateTextView(R.id.shuffleInfo,
                activity.getString(R.string.shuffle_fmt,
                settings.isShuffle() ? "On" : "Off"));
           
            String brightness = settings.getBrightness() == null || settings.getBrightness().isEmpty() ?
                "Auto" : settings.getBrightness();
            updateTextView(R.id.brightnessInfo,
                activity.getString(R.string.brightness_fmt, brightness));
                
            // Video Settings
            String loopSetting = settings.isLoopVideos() ? "Loop: On" : "Loop: Off";
            String lengthSetting = settings.isAllowFullLengthVideos() ? "Full Length" : "Respect Delay";
            int maxSizeKB = settings.getMaxVideoSizeKB();
            int maxPixels = settings.getMaxVideoPixels();

            // Convert pixels to resolution for readability
            String resolution;
            if (maxPixels <= 307200) {
                resolution = "480p";
            } else if (maxPixels <= 921600) {
                resolution = "720p";
            } else if (maxPixels <= 2073600) {
                resolution = "1080p";
            } else {
                resolution = maxPixels + "px";
            }

            updateTextView(R.id.videoSettingsInfo,
                activity.getString(R.string.video_settings_fmt,
                loopSetting, lengthSetting, maxSizeKB, resolution));
           
            logManager.addLog("UI updates completed successfully");
           
        } catch (Exception e) {
            logManager.addLog("Error updating UI: " + e.getMessage());
            if (e.getStackTrace().length > 0) {
                logManager.addLog("Location: " + e.getStackTrace()[0].toString());
            }
        }
    }    

    public void updateMediaInfo(String text) {
        try {
            TextView view = activity.findViewById(R.id.mediaInfo);
            if (view != null) {
                activity.runOnUiThread(() -> {
                    try {
                        view.setText(text);
                    } catch (Exception e) {
                        logManager.addLog("Error setting text: " + e.getMessage());
                    }
                });
            } else {
                logManager.addLog("MediaInfo TextView not found");
            }
        } catch (Exception e) {
            logManager.addLog("Error in updateMediaInfo: " + e.getMessage() + 
                             "\nStack trace: " + Arrays.toString(e.getStackTrace()));
        }
    }
        
    private void updateTextView(int viewId, String text) {
        try {
            TextView view = activity.findViewById(viewId);
            if (view != null) {
                activity.runOnUiThread(() -> view.setText(text));
            } else {
                logManager.addLog("View not found - ID: " + viewId + ", Text: " + text);
            }
        } catch (Exception e) {
            logManager.addLog("TextView update failed - ID: " + viewId + 
                             ", Text: " + text + 
                             ", Error: " + e.getMessage());
        }
    }    
}
