package com.kiwikodo.eophoenix.managers;

import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.content.Context;
import android.content.pm.PackageManager;
// Note: Avoid importing android.provider.Settings to prevent ambiguity with com.kiwikodo.eophoenix.Settings
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import com.kiwikodo.eophoenix.Settings;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.TimeZone;

public class NetworkManager {
    // Timeout and retry constants (centralized for easy tuning)
    private static final long WIFI_ENABLE_BASE_WAIT_MS = 1500L; // minimum wait before polling wifi state
    private static final long WIFI_ENABLE_MAX_MULTIPLIER = 4L; // multiplier for max wait
    private static final long WIFI_ENABLE_MIN_MAX_WAIT_MS = 3000L; // minimum upper bound for enable wait
    private static final long WIFI_STATE_POLL_INTERVAL_MS = 500L; // poll interval while waiting for wifi enabled
    private static final long WIFI_ENABLING_EXTRA_GRACE_MS = 10000L; // extra grace when state is ENABLING
    private static final long WIFI_ENABLING_EXTRA_POLL_MS = 1000L; // poll during extra grace
    private static final long ADD_NETWORK_POST_DELAY_MS = 400L; // base post delay before first addNetwork attempt
    private static final int ADD_NETWORK_MAX_ATTEMPTS = 6; // max addNetwork attempts
    private static final long ADD_NETWORK_MAX_RETRY_MS = 3000L; // cap for addNetwork retry delay
    private static final long ADD_NETWORK_BASE_RETRY_MS = 400L; // base for exponential backoff
    private NetworkStatusListener statusListener;
    private final WeakReference<Context> contextRef;
    private final LogManager logManager;
    private final Handler handler;
    private WifiManager wifiManager;
    private Settings currentSettings;
    private Runnable onComplete;
    private int attempts = 0;
    private boolean isOperationCancelled = false;

    public void setNetworkStatusListener(NetworkStatusListener listener) {
        this.statusListener = listener;
    }
    
    public NetworkManager(Context context, LogManager logManager) {
        this.contextRef = new WeakReference<>(context);
        this.logManager = logManager;
        this.handler = new Handler(Looper.getMainLooper());
        initWifiManager();
    }

    private void initWifiManager() {
        Context context = contextRef.get();
        if (context != null) {
            wifiManager = (WifiManager) context.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        }
    }

    public void setupWiFiAndTimeSync(Settings settings, Runnable onComplete) {
        cancelOperations();
        this.currentSettings = settings;
        this.onComplete = onComplete;
        this.attempts = 0;
        this.isOperationCancelled = false;
    
        if (wifiManager == null) {
            logManager.addLog("WiFi service not available");
            if (onComplete != null) onComplete.run();
            return;
        }
    
        if (statusListener != null) {
            statusListener.onWifiStatusUpdate(settings.getWifiSSID(), "(Waiting for connection...)");
        }
        
    // Start the connection process with retries (wrap configure network with retries)
    startWifiConnection();
    }
    
    // private void startWifiConnection() {
    //     // Add diagnostic logs
    //     logManager.addLog("WiFi Enabled: " + wifiManager.isWifiEnabled());
    //     logManager.addLog("WiFi State: " + wifiManager.getWifiState());

    //     if (!wifiManager.isWifiEnabled()) {
    //         wifiManager.setWifiEnabled(true);
    //         logManager.addLog("Attempting to enable WiFi");
    //         handler.postDelayed(() -> {
    //             logManager.addLog("WiFi State after enable: " + wifiManager.getWifiState());
    //             configureWifiNetwork();
    //         }, currentSettings.getWifiEnableDelay());
    //     } else {
    //         configureWifiNetwork();
    //     }
    // }
    private void startWifiConnection() {
        // Add diagnostic logs
        logManager.addLog("WiFi Enabled: " + wifiManager.isWifiEnabled());
        int wifiState = wifiManager.getWifiState();
        logManager.addLog("WiFi State: " + wifiState + " (" + wifiStateName(wifiState) + ")");

        // If WiFi appears disabled, we'll attempt to enable it. Reserve helper suggestions until after the enable attempt.
        if (wifiState != WifiManager.WIFI_STATE_ENABLED && wifiState != WifiManager.WIFI_STATE_ENABLING) {
            logManager.addLog("WiFi appears disabled; attempting to enable...");
        }
    
        // If CHANGE_WIFI_STATE is missing, we still attempt the flow because this app targets API19
        // (permissions are install-time). Log a warning so operators know we may not be able to change WiFi state.
        Context ctx = contextRef.get();
        if (ctx != null && ctx.checkCallingOrSelfPermission(android.Manifest.permission.CHANGE_WIFI_STATE)
                != PackageManager.PERMISSION_GRANTED) {
            logManager.addLog("Warning: CHANGE_WIFI_STATE permission not granted; attempts to enable/configure WiFi may fail on this device.");
        }

        if (!wifiManager.isWifiEnabled()) {
            wifiManager.setWifiEnabled(true);
            logManager.addLog("Attempting to enable WiFi");

            // Wait for WIFI to reach ENABLED state (3) before attempting to add networks.
            // We'll poll the state up to a maximum timeout (based on wifiEnableDelay * 4 or at least 3000ms)
            long baseWait = Math.max(currentSettings != null ? currentSettings.getWifiEnableDelay() : WIFI_ENABLE_BASE_WAIT_MS, WIFI_ENABLE_BASE_WAIT_MS);
            final long maxWait = Math.max(baseWait * WIFI_ENABLE_MAX_MULTIPLIER, WIFI_ENABLE_MIN_MAX_WAIT_MS);
            final long pollInterval = WIFI_STATE_POLL_INTERVAL_MS;
            final long start = System.currentTimeMillis();

            Runnable poll = new Runnable() {
                @Override
                public void run() {
                    int updatedWifiState = wifiManager.getWifiState();
                    logManager.addLog("WiFi State after enable: " + updatedWifiState + " (" + wifiStateName(updatedWifiState) + ")");

                    if (updatedWifiState == WifiManager.WIFI_STATE_ENABLED) {
                        // Enabled, proceed to configure
                        tryConfigureNetwork(currentSettings != null ? currentSettings.getWifiMaxAttempts() : 1,
                                currentSettings != null ? currentSettings.getWifiAttemptDelay() : 1000L);
                        return;
                    }

                    long elapsed = System.currentTimeMillis() - start;
                    if (elapsed >= maxWait) {
                        // Timed out waiting for ENABLED.
                        logManager.addLog("Timed out waiting for WiFi to become ENABLED (state=" + updatedWifiState + " - " + wifiStateName(updatedWifiState) + ")");

                        // If the radio is still transitioning (ENABLING) we will give it an extra grace period
                        // before falling back to best-effort configuration. This reduces spurious addNetwork failures
                        // when the firmware/driver is slow to complete initialization.
                        if (updatedWifiState == WifiManager.WIFI_STATE_ENABLING) { // ENABLING
                            final long extraGrace = WIFI_ENABLING_EXTRA_GRACE_MS; // extra grace
                            final long extraPollInterval = WIFI_ENABLING_EXTRA_POLL_MS;
                            final long graceStart = System.currentTimeMillis();
                            logManager.addLog("WiFi still ENABLING; extending wait by " + extraGrace + "ms before best-effort configure");

                            Runnable extraPoll = new Runnable() {
                                @Override
                                public void run() {
                                    int s = wifiManager.getWifiState();
                                    logManager.addLog("WiFi State after enable: " + s + " (" + wifiStateName(s) + ")");
                    if (s == WifiManager.WIFI_STATE_ENABLED) {
                    tryConfigureNetwork(currentSettings != null ? currentSettings.getWifiMaxAttempts() : 1,
                        currentSettings != null ? currentSettings.getWifiAttemptDelay() : 1000L);
                                        return;
                                    }
                                    if (System.currentTimeMillis() - graceStart >= extraGrace) {
                                        logManager.addLog("Extended wait expired; proceeding with best-effort configuration (state=" + s + ")");
                    tryConfigureNetwork(currentSettings != null ? currentSettings.getWifiMaxAttempts() : 1,
                        currentSettings != null ? currentSettings.getWifiAttemptDelay() : 1000L);
                                        return;
                                    }
                                    handler.postDelayed(this, extraPollInterval);
                                }
                            };
                            handler.postDelayed(extraPoll, extraPollInterval);
                            return;
                        }

            if (updatedWifiState == WifiManager.WIFI_STATE_DISABLED) { // DISABLED
                            logManager.addLog("Turn WiFi on manually in Device Settings/Wi-Fi");
                        } else {
                            logManager.addLog("Proceeding with network configuration as a best-effort; system is in state: " + updatedWifiState);
                        }
                tryConfigureNetwork(currentSettings != null ? currentSettings.getWifiMaxAttempts() : 1,
                            currentSettings != null ? currentSettings.getWifiAttemptDelay() : 1000L);
                        return;
                    }

                    // Not yet enabled and not timed out; poll again shortly
                    handler.postDelayed(this, pollInterval);
                }
            };

            // Start polling after a short initial delay to allow the state machine to progress
            handler.postDelayed(poll, baseWait);
        } else {
            tryConfigureNetwork(currentSettings.getWifiMaxAttempts(), currentSettings.getWifiAttemptDelay());
        }
    }

    // Attempt to configure the WiFi network up to maxAttempts times with delay between attempts
    private void tryConfigureNetwork(int maxAttempts, long delayMs) {
        handler.post(() -> {
            if (isOperationCancelled) return;
            boolean success = configureWifiNetwork();
            if (!success && maxAttempts > 1 && !isOperationCancelled) {
                logManager.addLog("configureWifiNetwork failed; retrying in " + delayMs + "ms (" + (maxAttempts-1) + " attempts left)");
                handler.postDelayed(() -> tryConfigureNetwork(maxAttempts - 1, delayMs), delayMs);
            } else if (!success) {
                logManager.addLog("configureWifiNetwork failed after retries");
                if (onComplete != null) onComplete.run();
            }
        });
    }
        
    @SuppressLint("MissingPermission")
    private boolean configureWifiNetwork() {
    if (isOperationCancelled) return false;
        Context ctx = contextRef.get();
        if (ctx != null && ctx.checkCallingOrSelfPermission(android.Manifest.permission.CHANGE_WIFI_STATE)
                != PackageManager.PERMISSION_GRANTED) {
            logManager.addLog("configureWifiNetwork: Missing CHANGE_WIFI_STATE permission; aborting network configuration");
            return false;
        }

        // First remove any existing configurations for this SSID
        List<WifiConfiguration> existingNetworks = wifiManager.getConfiguredNetworks();
        if (existingNetworks != null) {
            for (WifiConfiguration network : existingNetworks) {
                if (network.SSID.equals("\"" + currentSettings.getWifiSSID() + "\"")) {
                    wifiManager.removeNetwork(network.networkId);
                }
            }
        }
        wifiManager.saveConfiguration();

        // Create new configuration
        WifiConfiguration conf = new WifiConfiguration();
        conf.SSID = "\"" + currentSettings.getWifiSSID() + "\"";
        conf.preSharedKey = "\"" + currentSettings.getWifiPassword() + "\"";

        // Full security configuration
        conf.allowedProtocols.clear();
        conf.allowedKeyManagement.clear();
        conf.allowedPairwiseCiphers.clear();
        conf.allowedGroupCiphers.clear();
        
        conf.allowedProtocols.set(WifiConfiguration.Protocol.RSN);
        conf.allowedProtocols.set(WifiConfiguration.Protocol.WPA);
        conf.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
        conf.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.CCMP);
        conf.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.TKIP);
        conf.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.WEP40);
        conf.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.WEP104);
        conf.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.CCMP);
        conf.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.TKIP);

    // Schedule the actual addNetwork operation after a short non-blocking delay.
    // Devices sometimes return transient failures immediately after enabling WiFi,
    // so we'll attempt addNetwork up to a few times with a short backoff.
    long postDelay = Math.max(ADD_NETWORK_POST_DELAY_MS, currentSettings != null ? currentSettings.getWifiEnableDelay() / 2 : ADD_NETWORK_POST_DELAY_MS);
    final int maxAddAttempts = ADD_NETWORK_MAX_ATTEMPTS; // attempts to handle slow radios

    logManager.addLog("Scheduling network add (post-delay " + postDelay + "ms): " + conf.SSID);

    handler.postDelayed(new Runnable() {
        int addAttempt = 0;

        @Override
    public void run() {
            if (isOperationCancelled) return;
            addAttempt++;
            String ssidPlain = currentSettings != null ? currentSettings.getWifiSSID() : conf.SSID;
            logManager.addLog(String.format("Network add attempt %d/%d for \"%s\"", addAttempt, maxAddAttempts, ssidPlain));
            int netId = wifiManager.addNetwork(conf);
            String resultDesc;
            if (netId >= 0) {
                resultDesc = "Configured (" + netId + ")";
            } else if (netId == -1) {
                resultDesc = "Failed (-1)";
            } else {
                resultDesc = "Unknown (" + netId + ")";
            }
            logManager.addLog("Network add result: " + resultDesc);

            if (netId != -1) {
                try {
                    wifiManager.disconnect();
                    wifiManager.enableNetwork(netId, true);
                    wifiManager.reconnect();
                } catch (Exception e) {
                    logManager.addLog("Error enabling network id " + netId + ": " + e.getMessage());
                }
                // Start checking connection status after initiating the network
                checkConnectionStatus();
                return;
            }

            if (addAttempt < maxAddAttempts && !isOperationCancelled) {
                // Exponential backoff: base, base*1.5, ... capped
                double factor = Math.pow(1.5, addAttempt - 1);
                long retryDelay = Math.min(ADD_NETWORK_MAX_RETRY_MS, (long)(ADD_NETWORK_BASE_RETRY_MS * factor));
                logManager.addLog("addNetwork failed; retrying in " + retryDelay + "ms");
                handler.postDelayed(this, retryDelay);
            } else {
                logManager.addLog("addNetwork failed after " + addAttempt + " attempts");
                // Let the existing connection attempt loop continue and report failures
            }
        }
    }, postDelay);

    // Return true to indicate configuration has been initiated (async add in progress)
    return true;
    }

    private void checkConnectionStatus() {
        if (isOperationCancelled) return;

        attempts++;
        WifiInfo wifiInfo = wifiManager.getConnectionInfo();
        String currentSSID = wifiInfo.getSSID();
        boolean isConnected = currentSSID != null && 
            currentSSID.equals("\"" + currentSettings.getWifiSSID() + "\"") &&
            wifiInfo.getIpAddress() != 0;

        if (statusListener != null) {
            if (isConnected) {
                statusListener.onWifiStatusUpdate(currentSettings.getWifiSSID(), "(Connected Successfully)");
            } else if (attempts >= currentSettings.getWifiMaxAttempts()) {
                statusListener.onWifiStatusUpdate(currentSettings.getWifiSSID(), "(CONNECTION FAILED - CHECK PASSWORD)");
            } else {
                statusListener.onWifiStatusUpdate(currentSettings.getWifiSSID(), "(Connecting...)");
            }
        }

        logManager.addLog(String.format("Attempt %d/%d - Status: %s",
            attempts, currentSettings.getWifiMaxAttempts(),
            isConnected ? "Connected" : "Connecting"));

            if (isConnected) {
                logManager.addLog("WiFi connected successfully");
                boolean isAutoTime = syncDeviceTime();
                if (isAutoTime) {
                    logManager.addLog("Setting system time...");
                } else {
                    logManager.addLog("Skipping system time set because AUTO_TIME is disabled");
                }

                // Apply timezone explicitly after we communicate system time behavior so logs read sensibly
                applyTimezoneIfAllowed();

                handler.postDelayed(() -> {
                    // Update time status after delay
                    if (statusListener != null) {
                        Context context = contextRef.get();
                        if (context != null) {
                            boolean isAutoSynced = android.provider.Settings.System.getInt(
                                context.getContentResolver(),
                                android.provider.Settings.System.AUTO_TIME, 0) == 1;
                            TimeZone tz = TimeZone.getTimeZone(currentSettings.getTimeZone());
                            statusListener.onTimeStatusUpdate(isAutoSynced, tz.getDisplayName());
                        }
                    }
                    
                    disconnectWiFi(currentSettings);
                    if (onComplete != null) onComplete.run();
                }, currentSettings.getTimeSyncDelay());
            } else if (attempts < currentSettings.getWifiMaxAttempts()) {
            handler.postDelayed(this::checkConnectionStatus, 
                currentSettings.getWifiAttemptDelay());
        } else {
            logManager.addLog("Connection failed after " + attempts + " attempts");
            if (onComplete != null) onComplete.run();
        }
    }

    private boolean syncDeviceTime() {
        try {
            Context context = contextRef.get();
            if (context == null) return false;
            // Do NOT attempt to toggle AUTO_TIME/AUTO_TIME_ZONE programmatically.
            // On many devices this is restricted; the operator must enable "Automatic date & time" before inserting the SD card.
            boolean isAuto = false;
            try {
                int currentAuto = android.provider.Settings.System.getInt(context.getContentResolver(), android.provider.Settings.System.AUTO_TIME, 0);
                if (currentAuto == 1) {
                    logManager.addLog("AUTO_TIME already enabled");
                    isAuto = true;
                } else {
                    logManager.addLog("AUTO_TIME is DISABLED: please enable 'Automatic date & time' in Device Settings");
                    isAuto = false;
                }
            } catch (Exception e) {
                logManager.addLog("Unable to read AUTO_TIME state: " + e.getMessage());
                logManager.addLog("Ensure 'Automatic date & time' is enabled in Device Settings");
                isAuto = false;
            }

            try {
                int currentAutoTz = android.provider.Settings.System.getInt(context.getContentResolver(), android.provider.Settings.System.AUTO_TIME_ZONE, 0);
                if (currentAutoTz != 1) {
                    logManager.addLog("AUTO_TIME_ZONE is DISABLED: enable automatic timezone in Device Settings");
                }
            } catch (Exception ignored) {}

            
            return isAuto;
        } catch (Exception e) {
            logManager.addLog("Time sync error: " + e.getMessage());
            return false;
        }
    }

    // Apply timezone if the app has permission; kept separate so logs about system-time vs timezone appear in preferred order
    private void applyTimezoneIfAllowed() {
        try {
            Context context = contextRef.get();
            if (context == null) return;
            boolean canSetTz = context.checkCallingOrSelfPermission(android.Manifest.permission.SET_TIME_ZONE)
                    == PackageManager.PERMISSION_GRANTED;
            if (canSetTz) {
                AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
                alarmManager.setTimeZone(currentSettings.getTimeZone());
                TimeZone tz = TimeZone.getTimeZone(currentSettings.getTimeZone());
                logManager.addLog("Timezone set to: " + tz.getDisplayName());
            } else {
                logManager.addLog("No SET_TIME_ZONE permission: skipping explicit timezone set. Timezone request: " + currentSettings.getTimeZone());
            }
        } catch (Exception e) {
            logManager.addLog("Timezone set error: " + e.getMessage());
        }
    }
            
    public void cancelOperations() {
        isOperationCancelled = true;
        handler.removeCallbacksAndMessages(null);
    }

    @SuppressLint("MissingPermission")
    public void disconnectWiFi(Settings settings) {
        if (wifiManager == null) return;
    
        try {
            wifiManager.disconnect();
            Context ctx = contextRef.get();
            boolean canChange = ctx != null && ctx.checkCallingOrSelfPermission(android.Manifest.permission.CHANGE_WIFI_STATE)
                    == PackageManager.PERMISSION_GRANTED;

            if (canChange) {
                List<WifiConfiguration> networks = wifiManager.getConfiguredNetworks();
                if (networks != null) {
                    for (WifiConfiguration config : networks) {
                        if (config.SSID.equals("\"" + settings.getWifiSSID() + "\"")) {
                            wifiManager.removeNetwork(config.networkId);
                        }
                    }
                    wifiManager.saveConfiguration();
                }
            } else {
                logManager.addLog("Missing CHANGE_WIFI_STATE permission: skipping removal of saved network configurations");
            }
            
            if (statusListener != null) {
                statusListener.onWifiStatusUpdate("", "(Disconnected Successfully)");
            }
            
            logManager.addLog("WiFi disconnected");
        } catch (Exception e) {
            logManager.addLog("Disconnect error: " + e.getMessage());
        }
    }

    public int getWifiState() {
        if (wifiManager != null) {
            return wifiManager.getWifiState();
        }
        return -1; // Error code
    }
    
    /**
     * Return a compact human-friendly representation of the wifi state: "<code> (<name>)"
     */
    public String getWifiStateVerbose() {
        int s = getWifiState();
        return s + " (" + wifiStateName(s) + ")";
    }
    
    public boolean isWifiConnected() {
        if (wifiManager == null) return false;
        WifiInfo wifiInfo = wifiManager.getConnectionInfo();
        return wifiInfo != null && wifiInfo.getIpAddress() != 0;
    }

    // Helper to translate WifiManager state codes to readable names for logging
    private String wifiStateName(int state) {
        switch (state) {
            case 0: return "DISABLING";
            case 1: return "DISABLED";
            case 2: return "ENABLING";
            case 3: return "ENABLED";
            case 4: return "UNKNOWN";
            default: return "STATE(" + state + ")";
        }
    }
}
