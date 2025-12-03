package com.kiwikodo.eophoenix.managers;

import android.content.Context;
import android.os.Environment;
import android.util.Log;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class LogManager {
    private static final int MAX_LOG_ENTRIES = 100;
    private static final String TAG = "EoPhoenix";
    private long maxLogFileSize = 5 * 1024 * 1024; // default 5MB
    private static final String LOG_FILENAME = "eophoenix.log";
    private static final String LOG_BACKUP = "eophoenix.log.old";
    private int rotationRetention = 1; // number of rotated backups to keep
    private boolean rotationCompress = false; // gzip rotated backups

    private final Context context;
    private LinearLayout logContainer;
    private final List<String> logs;
    private final List<String> pendingFileLogs = new ArrayList<>();
    // Pending arbitrary file writes to be flushed to the EoPhoenix dir when available.
    private final List<PendingWrite> pendingWrites = new ArrayList<>();
    private String sdCardPath;
    private File logFile;
    private int pendingBufferLimit = MAX_LOG_ENTRIES * 5; // default
    
    // ... existing fields
    private StorageManager storageManager;
    
    public LogManager(Context context) {
        this.context = context;
        this.logs = new ArrayList<>();
    }

    // Global singleton accessor so background services can flush pending in-memory logs
    private static volatile LogManager INSTANCE = null;

    public static void setInstance(LogManager lm) {
        INSTANCE = lm;
    }

    public static LogManager getInstance() {
        return INSTANCE;
    }

    public void setStorageManager(StorageManager storageManager) {
        this.storageManager = storageManager;
        // If a removable root is already available via StorageManager, configure file logging
        try {
            if (this.storageManager != null) {
                String rem = this.storageManager.getRemovableRoot();
                if (rem != null) setRemovableSdRoot(rem);
            }
        } catch (Exception ignored) {}
    }

    /**
     * Returns true when logs should be produced — either UI is present or the EoPhoenix
     * directory (for eophoenix.log) can be resolved.
     */
    private boolean shouldWriteOrDisplay() {
        try {
            if (this.logContainer != null) return true;
            if (this.storageManager != null) {
                java.io.File d = this.storageManager.getEoPhoenixDir();
                if (d != null) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    /**
     * Expose the configured StorageManager for callers that need to query storage locations.
     * Returns null if not configured.
     */
    public StorageManager getStorageManager() {
        return this.storageManager;
    }

    /**
     * Add a message directly to the pending file log buffer. Thread-safe and size-capped.
     * Made public so other managers can push device-visible messages that should be
     * persisted to removable storage when available.
     */
    public void addPendingFileLog(String message) {
        // Only buffer pending-file logs if we can actually resolve an EoPhoenix dir to flush to later.
        try {
            if (this.storageManager == null || this.storageManager.getEoPhoenixDir() == null) {
                // No place to flush later; skip buffering to avoid wasted work
                return;
            }
        } catch (Exception ignored) {
            return;
        }

        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            .format(new Date());
        String logMessage = timestamp + ": " + message;
        synchronized (this) {
            pendingFileLogs.add(logMessage);
            if (pendingFileLogs.size() > pendingBufferLimit) {
                // drop oldest
                pendingFileLogs.remove(0);
            }
        }
    }

    // Small envelope representing a deferred write request to a file under the EoPhoenix dir.
    private static class PendingWrite {
        final String filename;
        final String contents;
        final boolean append;
        PendingWrite(String filename, String contents, boolean append) {
            this.filename = filename;
            this.contents = contents;
            this.append = append;
        }
    }

    /**
     * Request a deferred write to a file under the EoPhoenix dir. If the removable SD root
     * is already configured, this will attempt to write immediately; otherwise the request
     * is queued and flushed later when `setRemovableSdRoot(...)` is called.
     * @param filename relative filename (e.g., "last_crash.txt")
     * @param contents file contents
     * @param append true to append, false to overwrite
     */
    public void writeDeferredFile(String filename, String contents, boolean append) {
        synchronized (this) {
            // If logFile is available, write directly into the same parent directory
            if (this.logFile != null) {
                File dir = this.logFile.getParentFile();
                try {
                    if (dir != null && !dir.exists()) dir.mkdirs();
                    File target = new File(dir, filename);
                    try (java.io.FileWriter fw = new java.io.FileWriter(target, append)) {
                        fw.write(contents);
                    }
                    return;
                } catch (Exception e) {
                    // Fall through to buffering
                    addPendingFileLog("[DEFERRED_WRITE_ERROR] Failed immediate write " + filename + ": " + e.getMessage());
                }
            }
            // Buffer request for later flush
            pendingWrites.add(new PendingWrite(filename, contents, append));
            // Cap pending writes to avoid unbounded memory use
            if (pendingWrites.size() > 200) pendingWrites.remove(0);
        }
    }

    /**
     * Configure runtime values from Settings. Call after SettingsManager has parsed settings.
     */
    public void configureFromSettings(com.kiwikodo.eophoenix.Settings settings) {
        try {
            if (settings == null) return;
            if (settings.getLogRotationSizeBytes() > 0) maxLogFileSize = settings.getLogRotationSizeBytes();
            if (settings.getLogBufferLines() > 0) {
                // Use settings to cap pending buffer size (line count)
                pendingBufferLimit = settings.getLogBufferLines();
            }
            if (settings.getLogRotationRetention() > 0) rotationRetention = settings.getLogRotationRetention();
            rotationCompress = settings.isLogRotationCompress();
        } catch (Exception e) {
            addPendingFileLog("[CONFIG_ERROR] Failed to configure LogManager from settings: " + e.getMessage() + "\n" + Log.getStackTraceString(e));
        }
    }
    
    public void setSdCardPath(String sdCardPath) {
        this.sdCardPath = sdCardPath;
        try {
            // Avoid writing to the primary/emulated external storage root.
            String primary = Environment.getExternalStorageDirectory().getPath();
            if (sdCardPath != null && sdCardPath.equals(primary)) {
                // primary external == internal/emulated storage; do not configure file logging here
                addLog("Provided sdCardPath points to primary/emulated storage; skipping file log setup.");
                this.logFile = null;
                return;
            }

            File logDir = new File(sdCardPath, "EoPhoenix");
            if (!logDir.exists()) {
                logDir.mkdirs();
            }
            this.logFile = new File(logDir, LOG_FILENAME);
        } catch (Exception e) {
            addPendingFileLog("[STORAGE_ERROR] Failed to set sdCardPath for LogManager: " + e.getMessage() + "\n" + Log.getStackTraceString(e));
            this.logFile = null;
        }
    }

    /**
     * Configure LogManager to write logs to the provided removable storage root.
     * This is the recommended entry point to ensure logs land on a removable memory card.
     */
    public void setRemovableSdRoot(String removableRoot) {
        if (removableRoot == null) return;
        try {
            File logDir = new File(removableRoot, "EoPhoenix");
            if (!logDir.exists()) logDir.mkdirs();
            this.logFile = new File(logDir, LOG_FILENAME);
            this.sdCardPath = removableRoot;
            addLog("Using removable SD for logs: " + this.logFile.getAbsolutePath());
            // Flush any buffered logs to file
            flushPendingLogs();
        } catch (Exception e) {
            addPendingFileLog("[STORAGE_ERROR] Failed to configure removable SD log root: " + e.getMessage() + "\n" + Log.getStackTraceString(e));
            this.logFile = null;
        }
    }

    private synchronized void flushPendingLogs() {
        if (logFile == null && pendingFileLogs.isEmpty() && pendingWrites.isEmpty()) return;
        try {
            // First, process any pending write envelopes (append/overwrite files)
            if (logFile != null && !pendingWrites.isEmpty()) {
                File dir = logFile.getParentFile();
                if (dir != null && !dir.exists()) dir.mkdirs();
                for (int i = 0; i < pendingWrites.size(); i++) {
                    PendingWrite pw = pendingWrites.get(i);
                    try {
                        File tf = new File(dir, pw.filename);
                        try (java.io.FileWriter fw = new java.io.FileWriter(tf, pw.append)) {
                            fw.write(pw.contents);
                        }
                    } catch (Exception e) {
                        addPendingFileLog("[DEFERRED_WRITE_FAIL] " + pw.filename + ": " + e.getMessage());
                    }
                }
                pendingWrites.clear();
            }
            // Ensure rotation logic runs before we append
            if (logFile.exists() && logFile.length() > maxLogFileSize) {
                rotateLogs(logFile);
                logFile = new File(logFile.getParent(), LOG_FILENAME);
            }
            try (PrintWriter writer = new PrintWriter(new FileWriter(logFile, true))) {
                // Write oldest -> newest so file is chronological
                for (int i = 0; i < pendingFileLogs.size(); i++) {
                    writer.println(pendingFileLogs.get(i));
                }
            }
            pendingFileLogs.clear();
        } catch (Exception e) {
            addPendingFileLog("[LOG_FLUSH_ERROR] Failed to flush pending logs: " + e.getMessage() + "\n" + Log.getStackTraceString(e));
        }
    }

    /**
     * Test helper to trigger pending log flush from unit tests.
     */
    public void flushPendingLogsForTest() {
    flushPendingLogs();
    }

    /**
     * Stop file logging (used when SD card is removed) but keep in-memory logs.
     */
    public void clearFileLogging() {
        synchronized (this) {
            this.logFile = null;
            this.sdCardPath = null;
            // keep pendingFileLogs as-is so logs generated while removed are buffered
        }
        addLog("File logging disabled (SD removed)");
    }
    
    public void setLogContainer(LinearLayout container) {
        this.logContainer = container;
        updateLogContainer();
    }
    
    public void addLog(String message) {
        // Short-circuit if there's nowhere to display or persist logs
        if (!shouldWriteOrDisplay()) return;

        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            .format(new Date());
        String logMessage = timestamp + ": " + message;

        synchronized (this) {
            // Add to memory logs (newest at index 0 for UI)
            logs.add(0, logMessage);
            if (logs.size() > MAX_LOG_ENTRIES) {
                logs.remove(logs.size() - 1);
            }

            // Write to Android log for debug visibility
            Log.d(TAG, logMessage);

            // Write to SD card if available, otherwise buffer pending file logs
            if (logFile != null) {
                writeToFile(logMessage);
            } else {
                // Buffer newest at end, drop oldest when above cap
                pendingFileLogs.add(logMessage);
                if (pendingFileLogs.size() > pendingBufferLimit) {
                    pendingFileLogs.remove(0);
                }
            }
        }

        // Update UI if available (do this outside synchronized block)
        if (logContainer != null) {
            logContainer.post(() -> updateLogContainer());
        }
    }
    
    private void writeToFile(String logMessage) {
        synchronized (this) {
            if (logFile != null) {
                try {
                    // Check file size
                    if (logFile.exists() && logFile.length() > maxLogFileSize) {
                        rotateLogs(logFile);
                        logFile = new File(logFile.getParent(), LOG_FILENAME);
                    }

                    // Write new log entry
                    try (PrintWriter writer = new PrintWriter(new FileWriter(logFile, true))) {
                        writer.println(logMessage);
                    }
                } catch (Exception e) {
                    addPendingFileLog("[WRITE_ERROR] Failed to write to log file: " + e.getMessage() + "\n" + Log.getStackTraceString(e));
                }
            }
        }
    }

    private void rotateLogs(File currentLog) {
        try {
            File dir = currentLog.getParentFile();
            // Delete oldest if retention reached
            if (rotationRetention > 0) {
                File oldest = new File(dir, LOG_FILENAME + ".old." + rotationRetention);
                if (oldest.exists()) oldest.delete();
            }

            // Shift existing backups up by one
            for (int i = rotationRetention - 1; i >= 1; i--) {
                File src = new File(dir, LOG_FILENAME + ".old." + i);
                File dst = new File(dir, LOG_FILENAME + ".old." + (i + 1));
                if (src.exists()) src.renameTo(dst);
            }

            // Move current log to .old.1 (optionally compress)
            File first = new File(dir, LOG_FILENAME + ".old.1");
            if (rotationCompress) {
                // gzip the current log into first.gz
                File gz = new File(dir, LOG_FILENAME + ".old.1.gz");
                try (java.io.FileInputStream fis = new java.io.FileInputStream(currentLog);
                     java.io.FileOutputStream fos = new java.io.FileOutputStream(gz);
                     java.util.zip.GZIPOutputStream gos = new java.util.zip.GZIPOutputStream(fos)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = fis.read(buf)) > 0) {
                        gos.write(buf, 0, len);
                    }
                }
                // delete original currentLog
                currentLog.delete();
                // Remove existing uncompressed first if exists
                if (first.exists()) first.delete();
            } else {
                // Rename current to .old.1
                if (first.exists()) first.delete();
                currentLog.renameTo(first);
            }
        } catch (Exception e) {
            addPendingFileLog("[ROTATE_ERROR] rotateLogs failed: " + e.getMessage() + "\n" + Log.getStackTraceString(e));
        }
    }
    
    private void updateLogContainer() {
        if (logContainer == null) return;
        
        logContainer.removeAllViews();
        for (String log : logs) {
            TextView logView = new TextView(context);
            logView.setText(log);
            logView.setPadding(0, 4, 0, 4);
            logContainer.addView(logView);
        }
    }
    
    public List<String> getLogs() {
        return new ArrayList<>(logs);
    }
}
