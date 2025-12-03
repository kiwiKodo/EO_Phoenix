package com.kiwikodo.eophoenix.managers;

public interface NetworkStatusListener {
    void onWifiStatusUpdate(String ssid, String statusMessage);
    void onTimeStatusUpdate(boolean isAutoSynced, String timezone);
}
