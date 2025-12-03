package com.kiwikodo.eophoenix.managers;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Simple background bitmap loader with an in-memory LRU cache.
 * Designed for API19 devices where we want deterministic, low-dependency behavior.
 */
public class BitmapLoader {
    public interface Callback {
        void onSuccess(Bitmap bitmap, boolean fromCache);
        void onError(Exception e);
    }

    private final LruCache<String, Bitmap> cache;
    private ExecutorService executor;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Activity activity;
    private final LogManager logManager;

    public BitmapLoader(Activity activity, LogManager logManager) {
        this.activity = activity;
        this.logManager = logManager;

        // Use 1/8th of available VM memory for cache (tuned conservative)
        final int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
        final int cacheSize = Math.max(4 * 1024, maxMemory / 8);

        cache = new LruCache<String, Bitmap>(cacheSize) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                // size in KB
                return value.getByteCount() / 1024;
            }
        };

        executor = Executors.newSingleThreadExecutor();
    }

    private synchronized ExecutorService getExecutor() {
        if (executor == null || executor.isShutdown() || executor.isTerminated()) {
            executor = Executors.newSingleThreadExecutor();
        }
        return executor;
    }

    private static String keyForFile(File f) {
        if (f == null) return null;
        return f.getAbsolutePath() + ":" + f.lastModified();
    }

    public void load(final File file, final int targetW, final int targetH, final Callback cb) {
        if (file == null) {
            if (cb != null) cb.onError(new IllegalArgumentException("file is null"));
            return;
        }

        final String key = keyForFile(file);
        final Bitmap cached = cache.get(key);
        if (cached != null && !cached.isRecycled()) {
            // Return cached bitmap on main thread
            mainHandler.post(() -> {
                if (cb != null) cb.onSuccess(cached, true);
            });
            return;
        }

        getExecutor().submit(() -> {
            try {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(file.getAbsolutePath(), options);

                options.inSampleSize = calculateInSampleSize(options, targetW, targetH);
                options.inJustDecodeBounds = false;
                options.inPreferredConfig = Bitmap.Config.RGB_565; // memory-saver
                options.inMutable = true; // allow inBitmap on supported platforms

                Bitmap bmp = null;
                int attempts = 0;
                while (attempts < 3) {
                    try {
                        bmp = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
                        if (bmp != null) break;
                    } catch (OutOfMemoryError oom) {
                        logManager.addLog("BitmapLoader OOM on attempt " + attempts + " for " + file.getName());
                        // increase sample and retry
                        options.inSampleSize = Math.max(1, options.inSampleSize * 2);
                        attempts++;
                        // allow GC opportunity but do not call System.gc()
                    }
                }

                if (bmp == null) {
                    throw new RuntimeException("Failed to decode bitmap: " + file.getAbsolutePath());
                }

                // Put in cache
                cache.put(key, bmp);

                final Bitmap result = bmp;
                mainHandler.post(() -> {
                    if (cb != null) cb.onSuccess(result, false);
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    if (cb != null) cb.onError(e);
                });
            }
        });
    }

    public void clearCache() {
        try {
            cache.evictAll();
        } catch (Exception e) {
            // best effort
            logManager.addLog("Error evicting bitmap cache: " + e.getMessage());
        }
    }

    public void shutdown() {
        try {
            if (executor != null) executor.shutdownNow();
        } catch (Exception ignored) {}
        clearCache();
    }

    private static int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        int height = options.outHeight;
        int width = options.outWidth;
        int inSampleSize = 1;

        if (height > reqHeight || width > reqWidth) {
            int halfHeight = height / 2;
            int halfWidth = width / 2;

            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }
        return inSampleSize;
    }
}
