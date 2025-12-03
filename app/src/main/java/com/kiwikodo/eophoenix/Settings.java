package com.kiwikodo.eophoenix;

import java.util.List;

public class Settings {
    public static class Schedule {
        public static class TimeSlot {
            public String on;
            public String off;
            
            public TimeSlot(String on, String off) {
                this.on = on;
                this.off = off;
            }
        }
        
        public List<TimeSlot> monday;
        public List<TimeSlot> tuesday;
        public List<TimeSlot> wednesday;
        public List<TimeSlot> thursday;
        public List<TimeSlot> friday;
        public List<TimeSlot> saturday;
        public List<TimeSlot> sunday;
    }

    private String wifiSSID;
    private String wifiPassword;
    private int wifiMaxAttempts;
    private int wifiAttemptDelay;
    private int wifiEnableDelay;
    private int timeSyncDelay;
    // Network/timing configuration (new configurable tuning knobs)
    private int wifiEnableBaseWaitMs; // base wait before polling for wifi enabled
    private int wifiEnableMaxMultiplier; // multiplier used to compute max wait
    private int wifiEnableMinMaxWaitMs; // minimum upper bound for enable wait
    private int wifiStatePollIntervalMs; // poll interval while waiting for wifi enabled
    private int wifiEnablingExtraGraceMs; // extra grace when wifi state is ENABLING
    private int wifiEnablingExtraPollMs; // poll interval during extra grace
    private int addNetworkPostDelayMs; // delay before first addNetwork attempt
    private int addNetworkMaxAttempts; // max number of addNetwork attempts
    private int addNetworkBaseRetryMs; // base retry delay for addNetwork
    private int addNetworkMaxRetryMs; // capped retry delay for addNetwork
    // New configurable fields
    private int logRotationSizeBytes; // max log file size in bytes
    private int logBufferLines; // buffer size for pending logs
    private int logRotationRetention; // number of rotated backups to keep
    private Boolean logRotationCompress; // whether to gzip rotated backups (nullable so we can default true)
    private int sdWaitMaxAttempts;
    private int sdWaitDelayMs;
    private String timeZone;
    private int startupDelay;
    // Crash policy defaults
    private long crashWindowMs;
    private int crashMaxCount;
    private long crashBackoffBaseMs;

    private String folder;
    private int slideshowDelay;
    private boolean shuffle;
    private boolean loopVideos;
    private boolean allowFullLengthVideos;
    private int maxVideoSizeKB;
    private int maxVideoPixels;
    private String brightness;
    private Schedule schedule;
    // Video prepare timeout (ms) to allow slow SD reads; default 15s
    private int videoPrepareTimeoutMs;

    // Getters
    public String getWifiSSID() { return wifiSSID; }
    public String getWifiPassword() { return wifiPassword; }
    public int getWifiMaxAttempts() { return wifiMaxAttempts; }
    public int getWifiEnableDelay() { return wifiEnableDelay; }
    public int getWifiAttemptDelay() { return wifiAttemptDelay; }
    public int getTimeSyncDelay() { return timeSyncDelay; }
    // Network tuning getters with safe defaults and simple validation
    public int getWifiEnableBaseWaitMs() {
        return wifiEnableBaseWaitMs > 0 ? wifiEnableBaseWaitMs : 1500;
    }

    public int getWifiEnableMaxMultiplier() {
        // sensible range 1..10
        if (wifiEnableMaxMultiplier < 1 || wifiEnableMaxMultiplier > 10) return 4;
        return wifiEnableMaxMultiplier;
    }

    public int getWifiEnableMinMaxWaitMs() {
        return wifiEnableMinMaxWaitMs > 0 ? wifiEnableMinMaxWaitMs : 3000;
    }

    public int getWifiStatePollIntervalMs() {
        return wifiStatePollIntervalMs > 0 ? wifiStatePollIntervalMs : 500;
    }

    public int getWifiEnablingExtraGraceMs() {
        return wifiEnablingExtraGraceMs > 0 ? wifiEnablingExtraGraceMs : 10000;
    }

    public int getWifiEnablingExtraPollMs() {
        return wifiEnablingExtraPollMs > 0 ? wifiEnablingExtraPollMs : 1000;
    }

    public int getAddNetworkPostDelayMs() {
        return addNetworkPostDelayMs > 0 ? addNetworkPostDelayMs : 400;
    }

    public int getAddNetworkMaxAttempts() {
        int v = addNetworkMaxAttempts > 0 ? addNetworkMaxAttempts : 6;
        if (v < 1) return 1;
        if (v > 20) return 20;
        return v;
    }

    public int getAddNetworkBaseRetryMs() {
        return addNetworkBaseRetryMs > 0 ? addNetworkBaseRetryMs : 400;
    }

    public int getAddNetworkMaxRetryMs() {
        return addNetworkMaxRetryMs > 0 ? addNetworkMaxRetryMs : 3000;
    }
    public int getLogRotationSizeBytes() { 
        // default 10MB if not set
        return logRotationSizeBytes > 0 ? logRotationSizeBytes : 10 * 1024 * 1024; 
    }
    public int getLogBufferLines() { 
        // default 500 lines
        return logBufferLines > 0 ? logBufferLines : 500;
    }
    public int getLogRotationRetention() { 
        // default 3 backups
        return logRotationRetention > 0 ? logRotationRetention : 3; 
    }
    public boolean isLogRotationCompress() { 
        // default true when not provided in settings.json
        return logRotationCompress != null ? logRotationCompress : true; 
    }
    public int getSdWaitMaxAttempts() { return sdWaitMaxAttempts; }
    public int getSdWaitDelayMs() { return sdWaitDelayMs; }
    public String getTimeZone() { return timeZone; }
    public int getStartupDelay() { return startupDelay; }

    // Crash policy getters with sensible defaults
    public long getCrashWindowMs() {
        return crashWindowMs > 0 ? crashWindowMs : 30 * 60 * 1000L; // 30 minutes
    }

    public int getCrashMaxCount() {
        return crashMaxCount > 0 ? crashMaxCount : 3;
    }

    public long getCrashBackoffBaseMs() {
        return crashBackoffBaseMs > 0 ? crashBackoffBaseMs : 2000L; // 2 seconds
    }

    public String getFolder() { return folder; }
    public int getSlideshowDelay() { return slideshowDelay; }
    public boolean isShuffle() { return shuffle; }
    public boolean isLoopVideos() { return loopVideos; }
    public boolean isAllowFullLengthVideos() { return allowFullLengthVideos; }
    public int getMaxVideoSizeKB() { return maxVideoSizeKB; }
    public int getMaxVideoPixels() { return maxVideoPixels; }    
    public String getBrightness() { return brightness; }
    public Schedule getSchedule() { return schedule; }

    // Allow configuring video prepare timeout; default 15000ms
    public int getVideoPrepareTimeoutMs() { return videoPrepareTimeoutMs > 0 ? videoPrepareTimeoutMs : 15000; }
}
