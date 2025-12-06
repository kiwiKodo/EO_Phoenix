package com.kiwikodo.eophoenix;

import java.util.List;

public class Settings {
    // Nested configuration classes
    public static class WifiConfig {
        public String wifiSSID;
        public String wifiPassword;
        public int wifiMaxAttempts;
        public int wifiAttemptDelay;
        public int wifiEnableDelay;
        public int timeSyncDelay;
        public int wifiEnableBaseWaitMs;
        public int wifiEnableMaxMultiplier;
        public int wifiEnableMinMaxWaitMs;
        public int wifiStatePollIntervalMs;
        public int wifiEnablingExtraGraceMs;
        public int wifiEnablingExtraPollMs;
        public int addNetworkPostDelayMs;
        public int addNetworkMaxAttempts;
        public int addNetworkBaseRetryMs;
        public int addNetworkMaxRetryMs;
        public String timeZone;
        public int startupDelay;
    }

    public static class SlideshowConfig {
        public String folder;
        public int slideshowDelay;
        public boolean shuffle;
        public boolean loopVideos;
        public boolean allowFullLengthVideos;
        public int maxVideoSizeKB;
        public int maxVideoPixels;
        public String brightness;
        public int videoPrepareTimeoutMs;
    }

    public static class LoggingConfig {
        public int logRotationSizeBytes;
        public int logBufferLines;
        public int logRotationRetention;
        public Boolean logRotationCompress;
    }

    public static class SystemConfig {
        public int sdWaitMaxAttempts;
        public int sdWaitDelayMs;
    }

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

    // Nested objects (new structure)
    public WifiConfig wifi;
    public SlideshowConfig slideshow;
    public LoggingConfig logging;
    public SystemConfig system;
    public Schedule schedule;

    // Flat fields (legacy structure - kept for backwards compatibility)
    private String wifiSSID;
    private String wifiPassword;
    private int wifiMaxAttempts;
    private int wifiAttemptDelay;
    private int wifiEnableDelay;
    private int timeSyncDelay;
    private int wifiEnableBaseWaitMs;
    private int wifiEnableMaxMultiplier;
    private int wifiEnableMinMaxWaitMs;
    private int wifiStatePollIntervalMs;
    private int wifiEnablingExtraGraceMs;
    private int wifiEnablingExtraPollMs;
    private int addNetworkPostDelayMs;
    private int addNetworkMaxAttempts;
    private int addNetworkBaseRetryMs;
    private int addNetworkMaxRetryMs;
    private int logRotationSizeBytes;
    private int logBufferLines;
    private int logRotationRetention;
    private Boolean logRotationCompress;
    private int sdWaitMaxAttempts;
    private int sdWaitDelayMs;
    private String timeZone;
    private int startupDelay;
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
    private int videoPrepareTimeoutMs;

    // Getters (support both nested and flat structure)
    public String getWifiSSID() { 
        return wifi != null && wifi.wifiSSID != null ? wifi.wifiSSID : wifiSSID; 
    }
    public String getWifiPassword() { 
        return wifi != null && wifi.wifiPassword != null ? wifi.wifiPassword : wifiPassword; 
    }
    public int getWifiMaxAttempts() { 
        return wifi != null && wifi.wifiMaxAttempts > 0 ? wifi.wifiMaxAttempts : wifiMaxAttempts; 
    }
    public int getWifiEnableDelay() { 
        return wifi != null && wifi.wifiEnableDelay > 0 ? wifi.wifiEnableDelay : wifiEnableDelay; 
    }
    public int getWifiAttemptDelay() { 
        return wifi != null && wifi.wifiAttemptDelay > 0 ? wifi.wifiAttemptDelay : wifiAttemptDelay; 
    }
    public int getTimeSyncDelay() { 
        return wifi != null && wifi.timeSyncDelay > 0 ? wifi.timeSyncDelay : timeSyncDelay; 
    }
    
    public int getWifiEnableBaseWaitMs() {
        int val = wifi != null && wifi.wifiEnableBaseWaitMs > 0 ? wifi.wifiEnableBaseWaitMs : wifiEnableBaseWaitMs;
        return val > 0 ? val : 1500;
    }

    public int getWifiEnableMaxMultiplier() {
        int val = wifi != null && wifi.wifiEnableMaxMultiplier > 0 ? wifi.wifiEnableMaxMultiplier : wifiEnableMaxMultiplier;
        if (val < 1 || val > 10) return 4;
        return val;
    }

    public int getWifiEnableMinMaxWaitMs() {
        int val = wifi != null && wifi.wifiEnableMinMaxWaitMs > 0 ? wifi.wifiEnableMinMaxWaitMs : wifiEnableMinMaxWaitMs;
        return val > 0 ? val : 3000;
    }

    public int getWifiStatePollIntervalMs() {
        int val = wifi != null && wifi.wifiStatePollIntervalMs > 0 ? wifi.wifiStatePollIntervalMs : wifiStatePollIntervalMs;
        return val > 0 ? val : 500;
    }

    public int getWifiEnablingExtraGraceMs() {
        int val = wifi != null && wifi.wifiEnablingExtraGraceMs > 0 ? wifi.wifiEnablingExtraGraceMs : wifiEnablingExtraGraceMs;
        return val > 0 ? val : 10000;
    }

    public int getWifiEnablingExtraPollMs() {
        int val = wifi != null && wifi.wifiEnablingExtraPollMs > 0 ? wifi.wifiEnablingExtraPollMs : wifiEnablingExtraPollMs;
        return val > 0 ? val : 1000;
    }

    public int getAddNetworkPostDelayMs() {
        int val = wifi != null && wifi.addNetworkPostDelayMs > 0 ? wifi.addNetworkPostDelayMs : addNetworkPostDelayMs;
        return val > 0 ? val : 400;
    }

    public int getAddNetworkMaxAttempts() {
        int val = wifi != null && wifi.addNetworkMaxAttempts > 0 ? wifi.addNetworkMaxAttempts : addNetworkMaxAttempts;
        val = val > 0 ? val : 6;
        if (val < 1) return 1;
        if (val > 20) return 20;
        return val;
    }

    public int getAddNetworkBaseRetryMs() {
        int val = wifi != null && wifi.addNetworkBaseRetryMs > 0 ? wifi.addNetworkBaseRetryMs : addNetworkBaseRetryMs;
        return val > 0 ? val : 400;
    }

    public int getAddNetworkMaxRetryMs() {
        int val = wifi != null && wifi.addNetworkMaxRetryMs > 0 ? wifi.addNetworkMaxRetryMs : addNetworkMaxRetryMs;
        return val > 0 ? val : 3000;
    }
    
    public int getLogRotationSizeBytes() { 
        int val = logging != null && logging.logRotationSizeBytes > 0 ? logging.logRotationSizeBytes : logRotationSizeBytes;
        return val > 0 ? val : 10 * 1024 * 1024; 
    }
    public int getLogBufferLines() { 
        int val = logging != null && logging.logBufferLines > 0 ? logging.logBufferLines : logBufferLines;
        return val > 0 ? val : 500;
    }
    public int getLogRotationRetention() { 
        int val = logging != null && logging.logRotationRetention > 0 ? logging.logRotationRetention : logRotationRetention;
        return val > 0 ? val : 3; 
    }
    public boolean isLogRotationCompress() { 
        Boolean val = logging != null && logging.logRotationCompress != null ? logging.logRotationCompress : logRotationCompress;
        return val != null ? val : true; 
    }
    
    public int getSdWaitMaxAttempts() { 
        return system != null && system.sdWaitMaxAttempts > 0 ? system.sdWaitMaxAttempts : sdWaitMaxAttempts; 
    }
    public int getSdWaitDelayMs() { 
        return system != null && system.sdWaitDelayMs > 0 ? system.sdWaitDelayMs : sdWaitDelayMs; 
    }
    
    public String getTimeZone() { 
        return wifi != null && wifi.timeZone != null ? wifi.timeZone : timeZone; 
    }
    public int getStartupDelay() { 
        return wifi != null && wifi.startupDelay > 0 ? wifi.startupDelay : startupDelay; 
    }

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

    public String getFolder() { 
        return slideshow != null && slideshow.folder != null ? slideshow.folder : folder; 
    }
    public int getSlideshowDelay() { 
        return slideshow != null && slideshow.slideshowDelay > 0 ? slideshow.slideshowDelay : slideshowDelay; 
    }
    public boolean isShuffle() { 
        return slideshow != null ? slideshow.shuffle : shuffle; 
    }
    public boolean isLoopVideos() { 
        return slideshow != null ? slideshow.loopVideos : loopVideos; 
    }
    public boolean isAllowFullLengthVideos() { 
        return slideshow != null ? slideshow.allowFullLengthVideos : allowFullLengthVideos; 
    }
    public int getMaxVideoSizeKB() { 
        return slideshow != null && slideshow.maxVideoSizeKB > 0 ? slideshow.maxVideoSizeKB : maxVideoSizeKB; 
    }
    public int getMaxVideoPixels() { 
        return slideshow != null && slideshow.maxVideoPixels > 0 ? slideshow.maxVideoPixels : maxVideoPixels; 
    }    
    public String getBrightness() { 
        return slideshow != null && slideshow.brightness != null ? slideshow.brightness : brightness; 
    }
    public Schedule getSchedule() { return schedule; }

    // Allow configuring video prepare timeout; default 15000ms
    public int getVideoPrepareTimeoutMs() { 
        int val = slideshow != null && slideshow.videoPrepareTimeoutMs > 0 ? slideshow.videoPrepareTimeoutMs : videoPrepareTimeoutMs;
        return val > 0 ? val : 15000; 
    }
}
