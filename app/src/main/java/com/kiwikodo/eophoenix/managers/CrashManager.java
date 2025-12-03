package com.kiwikodo.eophoenix.managers;

import android.content.Context;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Lightweight persistent crash tracker. Stores UTC epoch millis of recent crashes to a file
 * and provides a shouldRestart() predicate to avoid restart loops.
 */
public class CrashManager {
    private final Context context;
    private File historyFile;
    private final long windowMs; // time window to consider (e.g., 30 minutes)
    private final int maxCrashes; // allowed crashes within window
    private long backoffBaseMs = 2000L; // default base backoff (2s)
    // Buffer writes that occur before LogManager is available. These are kept in-memory
    // and flushed via LogManager.writeDeferredFile(...) once the logger is ready.
    private final List<String> pendingAppends = new ArrayList<>();
    private String pendingOverwrite = null;

    public CrashManager(Context context) {
    this(context, null, 30 * 60 * 1000L, 3);
    }
    public CrashManager(Context context, StorageManager storageManager, long windowMs, int maxCrashes) {
        this.context = context;
        this.windowMs = windowMs;
        this.maxCrashes = maxCrashes;
    this.backoffBaseMs = 2000L;
        File dir = null;
        try {
            if (storageManager != null) {
                File eo = storageManager.getEoPhoenixDir();
                if (eo != null) dir = eo;
            }
        } catch (Exception ignored) {}
        if (dir == null) dir = new File(android.os.Environment.getExternalStorageDirectory(), "EoPhoenix");
        if (!dir.exists()) dir.mkdirs();
        this.historyFile = new File(dir, "crash_history.txt");
    }

    /** Construct using Settings values when available. */
    public CrashManager(Context context, StorageManager storageManager, com.kiwikodo.eophoenix.Settings settings) {
        this.context = context;
        if (settings != null) {
            this.windowMs = settings.getCrashWindowMs();
            this.maxCrashes = settings.getCrashMaxCount();
            this.backoffBaseMs = settings.getCrashBackoffBaseMs();
        } else {
            this.windowMs = 30 * 60 * 1000L;
            this.maxCrashes = 3;
            this.backoffBaseMs = 2000L;
        }

        File dir = null;
        try {
            if (storageManager != null) {
                File eo = storageManager.getEoPhoenixDir();
                if (eo != null) dir = eo;
            }
        } catch (Exception ignored) {}
        if (dir == null) dir = new File(android.os.Environment.getExternalStorageDirectory(), "EoPhoenix");
        if (!dir.exists()) dir.mkdirs();
        this.historyFile = new File(dir, "crash_history.txt");
    }

    /**
     * Migrate existing crash history into the EoPhoenix dir provided by StorageManager.
     * If a different history file already exists at the target, we append the old entries
     * and delete the fallback file.
     */
    public synchronized void migrateTo(StorageManager storageManager) {
        if (storageManager == null) return;
        try {
            File targetDir = storageManager.getEoPhoenixDir();
            if (targetDir == null) return;
            if (!targetDir.exists()) targetDir.mkdirs();
            File targetFile = new File(targetDir, "crash_history.txt");
            // If the current historyFile is already under the target, nothing to do
            File currentParent = historyFile != null ? historyFile.getParentFile() : null;
            if (currentParent != null && currentParent.getAbsolutePath().equals(targetDir.getAbsolutePath())) {
                return;
            }

            // Read old contents and either append into the target via LogManager or
            // buffer the contents locally until LogManager is available.
            if (historyFile != null && historyFile.exists()) {
                StringBuilder old = new StringBuilder();
                try (BufferedReader br = new BufferedReader(new FileReader(historyFile))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        old.append(line).append("\n");
                    }
                } catch (Exception ignored) {}

                com.kiwikodo.eophoenix.managers.LogManager lm = com.kiwikodo.eophoenix.managers.LogManager.getInstance();
                if (lm != null) {
                    lm.writeDeferredFile("crash_history.txt", old.toString(), true);
                    try { historyFile.delete(); } catch (Exception ignored) {}
                } else {
                    // Buffer old contents to be flushed later. We keep the original file
                    // on disk as a fallback for manual inspection until the deferred flush runs.
                    synchronized (this) {
                        pendingAppends.add(old.toString());
                    }
                }
            }
            historyFile = targetFile;
        } catch (Exception ignored) {}
    }

    /** Record a crash timestamp (UTC millis). */
    public synchronized void recordCrash(long epochMillis) {
        String line = Long.toString(epochMillis) + "\n";
        try {
            com.kiwikodo.eophoenix.managers.LogManager lm = com.kiwikodo.eophoenix.managers.LogManager.getInstance();
            if (lm != null) {
                // Flush any buffered writes first to preserve chronology
                flushPendingIfPossible(lm);
                lm.writeDeferredFile("crash_history.txt", line, true);
                return;
            }
        } catch (Exception ignored) {}

        // Buffer in-memory until LogManager is available. This avoids disk I/O here.
        synchronized (this) {
            pendingAppends.add(line);
            // Cap pending memory to a modest size to avoid OOM
            if (pendingAppends.size() > 1000) pendingAppends.remove(0);
        }
    }

    /** Return true if a restart is allowed according to the recent crash history. */
    public synchronized boolean shouldRestart() {
        long now = System.currentTimeMillis();
        List<Long> timestamps = new ArrayList<>();
        if (historyFile.exists()) {
            try (BufferedReader br = new BufferedReader(new FileReader(historyFile))) {
                String line;
                while ((line = br.readLine()) != null) {
                    try {
                        long t = Long.parseLong(line.trim());
                        if (t > 0) timestamps.add(t);
                    } catch (NumberFormatException ignored) {}
                }
            } catch (Exception ignored) {}
        }

        // prune old timestamps and count recent ones
        long cutoff = now - windowMs;
        Iterator<Long> it = timestamps.iterator();
        int recentCount = 0;
        while (it.hasNext()) {
            long t = it.next();
            if (t >= cutoff) recentCount++;
        }

        return recentCount < maxCrashes;
    }

    /**
     * Compute a recommended delay (ms) before attempting a restart using simple
     * exponential backoff based on the number of recent crashes. If the crash
     * count has already reached the `maxCrashes` threshold this returns -1 to
     * indicate that a restart should not be scheduled.
     *
     * Example: base=2000ms, recentCount=0 -> 2000ms, recentCount=1 -> 4000ms, etc.
     */
    public synchronized long getNextRestartDelayMs() {
        long now = System.currentTimeMillis();
        List<Long> timestamps = new ArrayList<>();
        if (historyFile.exists()) {
            try (BufferedReader br = new BufferedReader(new FileReader(historyFile))) {
                String line;
                while ((line = br.readLine()) != null) {
                    try {
                        long t = Long.parseLong(line.trim());
                        if (t > 0) timestamps.add(t);
                    } catch (NumberFormatException ignored) {}
                }
            } catch (Exception ignored) {}
        }

        long cutoff = now - windowMs;
        int recentCount = 0;
        for (Long t : timestamps) {
            if (t >= cutoff) recentCount++;
        }

        if (recentCount >= maxCrashes) return -1L;

    long base = this.backoffBaseMs; // base delay from settings or default
    // Use exponential backoff: base * 2^(recentCount)
    long multiplier = 1L << Math.min(recentCount, 30); // guard shift
    long delay = base * multiplier;
        // Cap the delay to the configured window to avoid excessive waits
        if (delay > windowMs) delay = windowMs;
        return delay;
    }

    /** Helper to persist a crash and optionally prune old entries. */
    public synchronized void recordAndPrune(long epochMillis) {
        recordCrash(epochMillis);
        pruneOld();
    }

    private void pruneOld() {
        long now = System.currentTimeMillis();
        long cutoff = now - windowMs;
        List<Long> timestamps = new ArrayList<>();
        if (historyFile.exists()) {
            try (BufferedReader br = new BufferedReader(new FileReader(historyFile))) {
                String line;
                while ((line = br.readLine()) != null) {
                    try {
                        long t = Long.parseLong(line.trim());
                        if (t >= cutoff) timestamps.add(t);
                    } catch (NumberFormatException ignored) {}
                }
            } catch (Exception ignored) {}
        }
        try {
            com.kiwikodo.eophoenix.managers.LogManager lm = com.kiwikodo.eophoenix.managers.LogManager.getInstance();
            StringBuilder sb = new StringBuilder();
            for (Long t : timestamps) {
                sb.append(Long.toString(t)).append("\n");
            }
            if (lm != null) {
                // Flush pending writes first to keep order
                flushPendingIfPossible(lm);
                lm.writeDeferredFile("crash_history.txt", sb.toString(), false);
                return;
            } else {
                // Buffer the overwrite payload until logger is available
                synchronized (this) {
                    pendingOverwrite = sb.toString();
                }
                return;
            }
        } catch (Exception ignored) {}
    }

        /**
         * Flush any in-memory pending appends/overwrite to the LogManager.
         */
        private synchronized void flushPendingIfPossible(com.kiwikodo.eophoenix.managers.LogManager lm) {
            if (lm == null) return;
            try {
                // If an overwrite payload exists, write it (this replaces file contents)
                if (pendingOverwrite != null) {
                    lm.writeDeferredFile("crash_history.txt", pendingOverwrite, false);
                    pendingOverwrite = null;
                }
                // Flush pending appends in order
                if (!pendingAppends.isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    for (String s : pendingAppends) sb.append(s);
                    lm.writeDeferredFile("crash_history.txt", sb.toString(), true);
                    pendingAppends.clear();
                }
            } catch (Exception ignored) {}
        }
}
