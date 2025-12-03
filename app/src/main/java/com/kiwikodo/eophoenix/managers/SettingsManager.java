package com.kiwikodo.eophoenix.managers;

import android.content.Context;
import android.os.Environment;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.kiwikodo.eophoenix.R;
import com.kiwikodo.eophoenix.Settings;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Arrays;

public class SettingsManager {
    private final Context context;
    private final LogManager logManager;
    private Settings currentSettings;
    private String sdCardPath;
    private StorageManager storageManager;

    public SettingsManager(Context context, LogManager logManager, StorageManager storageManager) {
        this.context = context;
        this.logManager = logManager;
        this.storageManager = storageManager;
    }

    public void setStorageManager(StorageManager storageManager) {
        this.storageManager = storageManager;
    }

    public Settings loadSettings(String sdCardPath) {
        // Accept explicit sdCardPath but fall back to StorageManager if provided
        this.sdCardPath = sdCardPath;
        if ((sdCardPath == null || sdCardPath.isEmpty()) && storageManager != null) {
            String effective = storageManager.getEffectiveRoot();
            sdCardPath = effective;
            this.sdCardPath = sdCardPath;
        }
        File settingsFile;
        if (storageManager != null) {
            settingsFile = storageManager.getEoPhoenixSubdir("settings.json");
        } else if (sdCardPath != null) {
            settingsFile = new File(sdCardPath, "EoPhoenix/settings.json");
        } else {
            return null;
        }
    
        if (!settingsFile.exists()) {
            return null;
        }
    
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(settingsFile));

            // Use a lenient JsonReader to tolerate comments (JSONC) and trailing commas in settings
            JsonReader jr = new JsonReader(reader);
            jr.setLenient(true);

            Gson gson = new GsonBuilder()
                .setDateFormat("HH:mm")
                .create();
            // Lightweight JSON schema validation (surface clearer errors before deserializing)
            JsonElement tree = null;
            try {
                tree = JsonParser.parseReader(jr);
            } catch (Exception e) {
                logManager.addLog(context.getString(R.string.fatal_settings_parse));
                logManager.addPendingFileLog("[SETTINGS_PARSE_ERROR] " + e.getMessage());
                return null;
            }
            if (tree == null || !tree.isJsonObject()) {
                logManager.addLog(context.getString(R.string.fatal_settings_parse));
                return null;
            }

            JsonObject jobj = tree.getAsJsonObject();
            if (!validateJsonSchema(jobj)) {
                logManager.addLog(context.getString(R.string.fatal_settings_validation_failed));
                return null;
            }

            // Parse into Settings POJO. Do not modify settings.json on disk here;
            // missing logging keys are handled by Settings getters (defaults).
            // Deserialize from the parsed JsonElement to respect lenient parsing
            try {
                currentSettings = gson.fromJson(tree, Settings.class);
            } catch (Exception e) {
                logManager.addLog(context.getString(R.string.fatal_settings_parse));
                logManager.addPendingFileLog("[SETTINGS_DESERIALIZE_ERROR] " + e.getMessage());
                return null;
            }
            if (currentSettings == null) {
                logManager.addLog(context.getString(R.string.fatal_settings_parse));
                return null;
            }
            
            if (validateSettings(currentSettings)) {
                logManager.addLog(context.getString(R.string.settings_loaded_successfully));
                try {
                    // Redact sensitive fields (wifiPassword) before logging
                        JsonElement redactedTree = gson.toJsonTree(currentSettings);
                        if (redactedTree != null && redactedTree.isJsonObject()) {
                            JsonObject obj = redactedTree.getAsJsonObject();
                            if (obj.has("wifiPassword")) obj.addProperty("wifiPassword", "*****REDACTED*****");
                        }
                        logManager.addLog("Settings content: " + gson.toJson(redactedTree));
                } catch (Exception ignored) {}
                return currentSettings;
            } else {
                logManager.addLog(context.getString(R.string.fatal_settings_validation_failed));
                return null;
            }
            
        } catch (Exception e) {
            logManager.addLog("Fatal: Error loading settings: " + e.getMessage());
            return null;
        } finally {
            try {
                if (reader != null) reader.close();
            } catch (IOException e) {
                logManager.addLog("Warning: Error closing reader: " + e.getMessage());
            }
        }
    }
    
    private boolean isExternalStorageReadable() {
        String state = Environment.getExternalStorageState();
        return Environment.MEDIA_MOUNTED.equals(state) || 
               Environment.MEDIA_MOUNTED_READ_ONLY.equals(state);
    }

    private boolean validateSettings(Settings settings) {
        // Validate required fields
        if (settings.getWifiSSID() == null || settings.getWifiSSID().isEmpty()) {
            logManager.addLog("Error: WiFi SSID not set");
            return false;
        }
        
        if (settings.getWifiPassword() == null || settings.getWifiPassword().isEmpty()) {
            logManager.addLog("Error: WiFi password not set");
            return false;
        }
        
        if (settings.getTimeZone() == null || settings.getTimeZone().isEmpty()) {
            logManager.addLog("Error: Timezone not set");
            return false;
        }
        
        // Validate numeric ranges
        if (settings.getStartupDelay() < 0) {
            logManager.addLog("Error: Invalid startup delay");
            return false;
        }
        
        if (settings.getSlideshowDelay() <= 0) {
            logManager.addLog("Error: Invalid slideshow delay");
            return false;
        }
        
        // Media folder validation
        if (settings.getFolder() == null || settings.getFolder().isEmpty()) {
            logManager.addLog("Error: Media folder not specified in settings");
            return false;
        }
        
        // Check if media folder exists in EoPhoenix directory
        File mediaFolder;
        if (storageManager != null) {
            mediaFolder = storageManager.getEoPhoenixSubdir(settings.getFolder());
        } else if (sdCardPath != null) {
            mediaFolder = new File(sdCardPath, "EoPhoenix/" + settings.getFolder());
        } else {
            mediaFolder = new File("EoPhoenix/" + settings.getFolder());
        }
        if (!mediaFolder.exists() || !mediaFolder.isDirectory()) {
            logManager.addLog("Error: Media folder not found: " + settings.getFolder());
            return false;
        }
        
        logManager.addLog("Media folder validated: " + settings.getFolder());
        return true;
    }

    /**
     * Lightweight structural checks for the settings JSON to catch obvious schema problems
     * and provide clearer error messages. This is not a full JSON Schema implementation but
     * covers required keys and simple types used by the app.
     */
    private boolean validateJsonSchema(JsonObject obj) {
        try {
            if (!obj.has("wifiSSID") || !obj.get("wifiSSID").isJsonPrimitive()) {
                logManager.addLog("Settings schema error: wifiSSID missing or not a string");
                return false;
            }
            if (!obj.has("wifiPassword") || !obj.get("wifiPassword").isJsonPrimitive()) {
                logManager.addLog("Settings schema error: wifiPassword missing or not a string");
                return false;
            }
            if (!obj.has("folder") || !obj.get("folder").isJsonPrimitive()) {
                logManager.addLog("Settings schema error: folder missing or not a string");
                return false;
            }
            if (obj.has("slideshowDelay") && !obj.get("slideshowDelay").isJsonPrimitive()) {
                logManager.addLog("Settings schema warning: slideshowDelay not a primitive number");
                // continue but keep note
            }

            // Validate schedule structure if present
            if (obj.has("schedule") && obj.get("schedule").isJsonObject()) {
                JsonObject sched = obj.getAsJsonObject("schedule");
                for (String day : new String[]{"monday","tuesday","wednesday","thursday","friday","saturday","sunday"}) {
                    if (sched.has(day)) {
                        if (!sched.get(day).isJsonArray()) {
                            logManager.addLog("Settings schema error: schedule." + day + " is not an array");
                            return false;
                        }
                        // Check each timeslot object
                        for (JsonElement e : sched.getAsJsonArray(day)) {
                            if (!e.isJsonObject()) {
                                logManager.addLog("Settings schema error: schedule." + day + " contains non-object");
                                return false;
                            }
                            JsonObject ts = e.getAsJsonObject();
                            // If a timeslot has neither 'on' nor 'off', silently ignore it.
                            if (!ts.has("on") && !ts.has("off")) {
                                // User intentionally left an empty slot; skip it.
                                continue;
                            }
                        }
                    }
                }
            }
            return true;
        } catch (Exception e) {
            logManager.addLog("Settings schema validation failed: " + e.getMessage());
            return false;
        }
    }

    public Settings getCurrentSettings() {
        return currentSettings;
    }
}
