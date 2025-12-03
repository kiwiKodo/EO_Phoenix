package com.kiwikodo.eophoenix;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Environment;

import com.kiwikodo.eophoenix.managers.LogManager;

import java.io.File;
import java.io.FileWriter;

public class AdminReceiver extends DeviceAdminReceiver {
    @Override
    public void onEnabled(Context context, Intent intent) {
        super.onEnabled(context, intent);
        // Buffer an admin-enabled event for the background flusher. Avoid direct file I/O here.
        try {
            LogManager lm = LogManager.getInstance();
            if (lm != null) {
                lm.addLog("Admin enabled");
                lm.addPendingFileLog("[ADMIN] enabled | timestamp=" + System.currentTimeMillis());
            }
        } catch (Exception ignored) {}
    }

    @Override
    public void onDisabled(Context context, Intent intent) {
        super.onDisabled(context, intent);
        // Buffer an admin-disabled event for the background flusher. Avoid direct file I/O here.
        try {
            LogManager lm = LogManager.getInstance();
            if (lm != null) {
                lm.addLog("Admin disabled");
                lm.addPendingFileLog("[ADMIN] disabled | timestamp=" + System.currentTimeMillis());
            }
        } catch (Exception ignored) {}
    }
}
