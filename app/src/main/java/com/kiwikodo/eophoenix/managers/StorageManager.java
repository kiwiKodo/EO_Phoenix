package com.kiwikodo.eophoenix.managers;

import android.content.Context;
import java.io.File;

/**
 * Centralized helper for resolving the effective EoPhoenix storage root.
 * Prefer removable external storage when available; fall back to mounted path otherwise.
 */
public class StorageManager {
    private final Context context;
    private final SDCardManager sdCardManager;
    private final LogManager logManager;
    public StorageManager(Context context, SDCardManager sdCardManager, LogManager logManager) {
        this.context = context;
        this.sdCardManager = sdCardManager;
        this.logManager = logManager;
    }

    /**
     * Returns removable storage root if present, otherwise null.
     */
    public String getRemovableRoot() {
        if (sdCardManager == null) return null;
        try {
            // Delegate to static helper for easier unit testing
            java.io.File[] dirs = null;
            try {
                dirs = (java.io.File[]) context.getClass().getMethod("getExternalFilesDirs", java.lang.Class.class).invoke(context, (Object) null);
            } catch (Exception ignored) {
            }
            return guessRemovableRootFromExternalFilesDirs(dirs);
        } catch (Exception e) {
            // Always record storage detection failures into the pending file buffer so the
            // problem is visible on-device even if adb/logcat is not available.
            if (logManager != null) {
                logManager.addPendingFileLog("[STORAGE_ERROR] error detecting removable root: " + e.getMessage() + "\n" + android.util.Log.getStackTraceString(e));
            }
            return null;
        }
    }

    /**
     * Pure helper for unit tests: given an array returned by Context.getExternalFilesDirs(null),
     * guess the removable storage root path (e.g. /storage/XXXX) or null.
     */
    public static String guessRemovableRootFromExternalFilesDirs(java.io.File[] dirs) {
        if (dirs == null || dirs.length < 2) return null;
        java.io.File sec = dirs[1];
        if (sec == null) return null;
        java.io.File root = sec;
        for (int i = 0; i < 4; i++) {
            if (root == null) break;
            root = root.getParentFile();
        }
        if (root != null && root.exists()) return root.getAbsolutePath();
        if (sec.exists()) return sec.getAbsolutePath();
        return null;
    }

    /**
     * Returns the best effective root to use for EoPhoenix files. Prefer removable root,
     * then mounted path, otherwise null.
     */
    public String getEffectiveRoot() {
        String rem = getRemovableRoot();
        if (rem != null) return rem;
        if (sdCardManager != null && sdCardManager.getMountedPath() != null) return sdCardManager.getMountedPath();
        return null;
    }

    /**
     * Returns (and ensures) the EoPhoenix directory under the effective root, or null.
     */
    public File getEoPhoenixDir() {
        String root = getEffectiveRoot();
        if (root == null) return null;
        File d = new File(root, "EoPhoenix");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public File getEoPhoenixSubdir(String sub) {
        File rootDir = getEoPhoenixDir();
        if (rootDir == null) return null;
        File subdir = new File(rootDir, sub);
        return subdir;
    }

    // Note: init_status file writes have been deprecated in favor of buffering short
    // status messages via LogManager.addPendingFileLog(...) so a single background
    // flusher can persist diagnostics to removable storage when available.
}
