
package com.kiwikodo.eophoenix.managers;

import android.content.Context;
import android.webkit.MimeTypeMap;
import com.kiwikodo.eophoenix.R;
import com.kiwikodo.eophoenix.Settings;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class MediaManager {
    private final Context context;
    private final LogManager logManager;
    private final UIManager uiManager;
    private final SettingsManager settingsManager;
    private final StorageManager storageManager;
    
    private List<MediaFile> mediaFiles = new ArrayList<>();
    private int imageCount = 0;
    private int videoCount = 0;

    public List<MediaFile> getMediaFiles() {
        return new ArrayList<>(mediaFiles);
    }

    public MediaManager(Context context, LogManager logManager, UIManager uiManager, SettingsManager settingsManager, StorageManager storageManager) {
        this.context = context;
        this.logManager = logManager;
        this.uiManager = uiManager;
        this.settingsManager = settingsManager;
        this.storageManager = storageManager;
    }
    
    public boolean scanMediaDirectory(String sdCardPath, String folderName) {
        try {
            logManager.addLog(context.getString(R.string.starting_media_scan));
            File mediaDir;
            if (storageManager != null) {
                mediaDir = storageManager.getEoPhoenixSubdir(folderName);
            } else {
                String effectiveRoot = sdCardPath;
                if ((effectiveRoot == null || effectiveRoot.isEmpty())) {
                    effectiveRoot = null; // will cause File construction to fail below
                }
                mediaDir = new File(effectiveRoot, "EoPhoenix/" + folderName);
            }
            
            if (!mediaDir.exists() || !mediaDir.isDirectory()) {
                logManager.addLog(context.getString(R.string.error_media_dir_not_found_fmt, folderName));
                return false;
            }
            
            mediaFiles.clear();
            imageCount = 0;
            videoCount = 0;
            java.util.List<String> skippedVideos = new ArrayList<>();  // Track skipped videos (filenames)
            
            File[] files = mediaDir.listFiles();
            if (files == null || files.length == 0) {
                logManager.addLog("Error: No media files found in " + folderName);
                return false;
            }
            
            logManager.addLog("Raw file count: " + files.length);
            
            // Get settings for video compatibility
            Settings current = settingsManager.getCurrentSettings();
            int maxVideoSizeKB = current != null ? current.getMaxVideoSizeKB() : 0;
            int maxVideoPixels = current != null ? current.getMaxVideoPixels() : 0;
            
            for (File file : files) {
                try {
                    String mimeType = getMimeType(file.getName());
                    if (mimeType != null) {
                        if (mimeType.startsWith("image/")) {
                            imageCount++;
                            mediaFiles.add(new MediaFile(file, MediaType.IMAGE));
                        } else if (mimeType.startsWith("video/")) {
                            // Check video file size before adding
                            long fileSizeKB = file.length() / 1024;
                                if (fileSizeKB > maxVideoSizeKB) {
                                    logManager.addLog("Warning: Large video skipped: " + file.getName() + 
                                        " (" + fileSizeKB + "KB > " + maxVideoSizeKB + "KB limit)");
                                    skippedVideos.add(file.getName());
                                    continue;
                                }
                                // Also perform a quick resolution check now to avoid later playback skips
                                try {
                                    android.media.MediaMetadataRetriever retriever = new android.media.MediaMetadataRetriever();
                                    try {
                                        retriever.setDataSource(file.getAbsolutePath());
                                        String wStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
                                        String hStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
                                        if (wStr != null && hStr != null && maxVideoPixels > 0) {
                                            int w = Integer.parseInt(wStr);
                                            int h = Integer.parseInt(hStr);
                                            long pixels = (long) w * h;
                                            if (pixels > maxVideoPixels) {
                                                logManager.addLog("Warning: High-resolution video skipped: " + file.getName() + " (" + w + "x" + h + ")");
                                                skippedVideos.add(file.getName());
                                                continue;
                                            }
                                        }
                                    } finally {
                                        try { retriever.release(); } catch (Exception ignored) {}
                                    }
                                } catch (Exception e) {
                                    logManager.addLog("Resolution check failed for " + file.getName() + ": " + e.getMessage());
                                    // If resolution check fails, don't block the file here - let playback handle it
                                }
                            videoCount++;
                            mediaFiles.add(new MediaFile(file, MediaType.VIDEO));
                        }
                    }
                } catch (Exception e) {
                    logManager.addLog("Error processing file: " + file.getName() + " - " + e.getMessage());
                }
            }
            
            int totalCount = imageCount + videoCount;
            int skippedCount = skippedVideos.size();
            logManager.addLog(String.format("Found %d media files (%d images, %d videos, %d skipped videos)",
                totalCount, imageCount, videoCount, skippedCount));

            if (uiManager != null) {
                if (totalCount == 0 && skippedCount > 0) {
                    // All media were skipped - show explicit operator guidance with file list (up to 5 names)
                    int maxShow = 5;
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < Math.min(skippedVideos.size(), maxShow); i++) {
                        if (i > 0) sb.append(", ");
                        sb.append(skippedVideos.get(i));
                    }
                    if (skippedVideos.size() > maxShow) sb.append(", ...");
                    String fileList = sb.toString();
                    String msg = context.getString(R.string.media_all_skipped_by_size_fmt, skippedCount, maxVideoSizeKB, fileList);
                    uiManager.updateMediaInfo(msg);
                } else {
                    String updateText = context.getString(R.string.media_count_detailed_fmt,
                        totalCount, imageCount, videoCount, skippedCount);
                    uiManager.updateMediaInfo(updateText);
                }
            } else {
                logManager.addLog("Error: UIManager is null");
            }

            return totalCount > 0;
            
        } catch (Exception e) {
            logManager.addLog("Media scan failed: " + e.getMessage() +
                             "\nStack trace: " + Arrays.toString(e.getStackTrace()));
            return false;
        }
    }

    private String getMimeType(String fileName) {
        // Try Android's built-in detection first
        String cleanFileName = fileName.replaceAll("\\s+|[()]", "_");
        String extension = MimeTypeMap.getFileExtensionFromUrl(cleanFileName);
        String mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.toLowerCase());
        
        // If Android couldn't determine the type, use extension-based backup
        if (mimeType == null) {
            String ext = fileName.substring(fileName.lastIndexOf(".") + 1).toLowerCase();
            // Common image formats
            if (Arrays.asList("jpg", "jpeg", "png", "gif", "bmp", "webp").contains(ext)) {
                return "image/" + ext;
            }
            // Common video formats
            else if (Arrays.asList("mp4", "3gp", "webm", "mkv", "mov").contains(ext)) {
                return "video/" + ext;
            }
        }
        
        return mimeType;
    }
    
    public enum MediaType {
        IMAGE,
        VIDEO
    }
    
    public static class MediaFile {
        private final File file;
        private final MediaType type;
        
        MediaFile(File file, MediaType type) {
            this.file = file;
            this.type = type;
        }
    
        public File getFile() { return file; }
        public MediaType getType() { return type; }
        public String getName() { return file.getName(); }
        
        public boolean isVideo() {
            // Simply check the type that's already stored
            return type == MediaType.VIDEO;
        }
    }    

    public boolean hasMedia() {
        return mediaFiles != null && !mediaFiles.isEmpty();
    }

    public void clearMedia() {
        mediaFiles.clear();
        imageCount = 0;
        videoCount = 0;
    }    
}