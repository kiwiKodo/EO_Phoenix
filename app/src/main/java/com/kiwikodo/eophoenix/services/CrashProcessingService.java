package com.kiwikodo.eophoenix.services;

import android.app.IntentService;
import android.content.Intent;
import android.content.Context;
import android.util.Log;

import com.kiwikodo.eophoenix.managers.LogManager;
import com.kiwikodo.eophoenix.managers.StorageManager;
import com.kiwikodo.eophoenix.managers.CrashManager;
import com.kiwikodo.eophoenix.Settings;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;

/**
 * Background service to perform crash diagnostics persistence and log flush.
 * Keeps heavy I/O out of the uncaught exception handler. Designed for API19.
 */
public class CrashProcessingService extends IntentService {
    private static final String TAG = "CrashProcessingSvc";
    public static final String EXTRA_STACKTRACE = "extra_stacktrace";

    public CrashProcessingService() {
        super("CrashProcessingService");
    }

    public static void enqueueWork(Context ctx, String stacktrace) {
        Intent i = new Intent(ctx, CrashProcessingService.class);
        i.putExtra(EXTRA_STACKTRACE, stacktrace);
        ctx.startService(i);
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        if (intent == null) return;
        String stack = intent.getStringExtra(EXTRA_STACKTRACE);
        try {
            LogManager lm = LogManager.getInstance();
            if (lm != null) {
                lm.addPendingFileLog("[FATAL_FULL] " + (stack != null ? stack : "<no-stack>"));
            }

            // Try to persist a compact diagnostics bundle into EoPhoenix/last_crash.txt
            try {
                // Build compact diagnostics content
                StringBuilder diag = new StringBuilder();
                diag.append("timestamp=").append(System.currentTimeMillis()).append("\n");
                diag.append("stack=\n");
                if (stack != null) diag.append(stack).append("\n");

                if (lm != null) {
                    // Request LogManager to write the diagnostics file (deferred if SD unavailable)
                    lm.writeDeferredFile("last_crash.txt", diag.toString(), false);
                }

                // Try to persist a redacted settings snapshot for diagnostics (deferred)
                try {
                    StorageManager sm = null;
                    try {
                        if (lm != null) sm = lm.getStorageManager();
                    } catch (Exception ignored) {}

                    com.kiwikodo.eophoenix.Settings s = null;
                    if (sm != null) {
                        File settingsFile = new File(sm.getEoPhoenixDir(), "settings.json");
                        if (settingsFile.exists()) {
                            com.google.gson.Gson g = new com.google.gson.Gson();
                            try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(settingsFile))) {
                                StringBuilder sb = new StringBuilder();
                                String ln;
                                while ((ln = r.readLine()) != null) sb.append(ln);
                                s = g.fromJson(sb.toString(), com.kiwikodo.eophoenix.Settings.class);
                            } catch (Exception ignored) {}
                        }
                    }
                    if (s != null && lm != null) {
                        com.google.gson.Gson g = new com.google.gson.GsonBuilder().setPrettyPrinting().create();
                        com.google.gson.JsonElement jt = g.toJsonTree(s);
                        if (jt != null && jt.isJsonObject()) {
                            com.google.gson.JsonObject jo = jt.getAsJsonObject();
                            if (jo.has("wifiPassword")) jo.addProperty("wifiPassword", "*****REDACTED*****");
                        }
                        String redacted = g.toJson(jt);
                        lm.writeDeferredFile("last_settings.json.redacted", redacted, false);
                    }
                } catch (Exception ignored) {}

                // Flush pending logs to disk now if possible
                if (lm != null) {
                    lm.flushPendingLogsForTest();
                }

                // Ensure CrashManager records the crash
                try {
                    CrashManager cm = new CrashManager(getApplicationContext());
                    cm.recordAndPrune(System.currentTimeMillis());
                } catch (Exception ignored) {}

            } catch (Exception e) {
                if (lm != null) lm.addPendingFileLog("[CRASH_SVC_ERROR] " + e.getMessage());
            }
        } catch (Exception e) {
            Log.e(TAG, "CrashProcessingService failed: " + e.getMessage());
        }
    }
}
