package com.kiwikodo.eophoenix.managers;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.os.Handler;
import android.view.View;
import android.widget.ImageView;
import android.util.DisplayMetrics;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.ui.PlayerView;
import com.kiwikodo.eophoenix.managers.MediaManager.MediaFile;
import com.kiwikodo.eophoenix.R;
import com.kiwikodo.eophoenix.Settings;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SlideshowManager {
    private final Activity activity;
    private final LogManager logManager;
    private final MediaManager mediaManager;
    private final BrightnessManager brightnessManager;
    private final SettingsManager settingsManager;
    
    private List<MediaFile> shuffledMediaList = new ArrayList<>();
    private ImageView mediaView;
    private ExoPlayer exoPlayer;
    private PlayerView playerView;
    private int currentIndex = 0;
    private int screenWidth;
    private int screenHeight;
    private boolean isPlayingFullLengthVideo = false;
    private boolean isRunning = false;
    private boolean cleanupInProgress = false;
    private Handler slideshowHandler;
    private Runnable slideshowRunnable;
    private Handler periodicCheckHandler = new Handler();
    private Runnable periodicCheckRunnable;
    private Handler loopTimeoutHandler = new Handler();
    private Runnable loopTimeoutRunnable;
    private Handler videoDurationHandler = new Handler();
    private Runnable videoDurationRunnable;    
    private Handler brightnessDebugHandler = new Handler();
    private Runnable brightnessDebugRunnable;
    private boolean isBrightnessDebugging = false;
    private BitmapLoader bitmapLoader;
    private Bitmap currentBitmap;
    private android.view.View dimOverlay;
    
    // Method to start/stop brightness debugging
    public void toggleBrightnessDebugging(boolean enable) {
        isBrightnessDebugging = enable;
        
        if (enable) {
            startBrightnessDebugging();
        } else {
            stopBrightnessDebugging();
        }
    }
    
    private void startBrightnessDebugging() {
        if (brightnessDebugRunnable != null) {
            brightnessDebugHandler.removeCallbacks(brightnessDebugRunnable);
        }
        
        brightnessDebugRunnable = new Runnable() {
            @Override
            public void run() {
                if (isBrightnessDebugging && isRunning) {
                    // Get comprehensive brightness info from BrightnessManager
                    String brightnessInfo = brightnessManager.getActualBrightnessInfo();
                    logManager.addLog("BRIGHTNESS DEBUG: " + brightnessInfo);
                    
                    // Check current media state
                    boolean isShowingVideo = playerView != null && 
                                            playerView.getVisibility() == View.VISIBLE;
                    boolean isShowingImage = mediaView != null && 
                                            mediaView.getVisibility() == View.VISIBLE;
                    
                    // Log current media state
                    if (isShowingVideo) {
                        logManager.addLog("BRIGHTNESS DEBUG: Currently showing video");
                        
                        // Add ExoPlayer state if available
                        if (exoPlayer != null) {
                            logManager.addLog("BRIGHTNESS DEBUG: Video playback state: " + 
                                getPlaybackStateString(exoPlayer.getPlaybackState()));
                        }
                    } else if (isShowingImage) {
                        logManager.addLog("BRIGHTNESS DEBUG: Currently showing image");
                    } else {
                        logManager.addLog("BRIGHTNESS DEBUG: No media currently visible");
                    }
                    
                    // Continue debugging every 5 seconds
                    brightnessDebugHandler.postDelayed(this, 5000);
                }
            }
        };
        
        brightnessDebugHandler.post(brightnessDebugRunnable);
        logManager.addLog("Brightness debugging started");
    }
    
    private String getPlaybackStateString(int state) {
        switch (state) {
            case Player.STATE_IDLE: return "IDLE";
            case Player.STATE_BUFFERING: return "BUFFERING";
            case Player.STATE_READY: return "READY";
            case Player.STATE_ENDED: return "ENDED";
            default: return "UNKNOWN";
        }
    }
    
    private void stopBrightnessDebugging() {
        if (brightnessDebugRunnable != null) {
            brightnessDebugHandler.removeCallbacks(brightnessDebugRunnable);
            brightnessDebugRunnable = null;
        }
        logManager.addLog("Brightness debugging stopped");
    }



    public SlideshowManager(Activity activity, LogManager logManager, 
                          MediaManager mediaManager, BrightnessManager brightnessManager,
                          SettingsManager settingsManager) {
        this.activity = activity;
        this.logManager = logManager;
        this.mediaManager = mediaManager;
        this.brightnessManager = brightnessManager;
        this.settingsManager = settingsManager;
        
        initializeScreenDimensions();
        slideshowHandler = new Handler();
        bitmapLoader = new BitmapLoader(activity, logManager);
    }
    
    private void initializeScreenDimensions() {
        DisplayMetrics displayMetrics = new DisplayMetrics();
        activity.getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);
        screenWidth = displayMetrics.widthPixels;
        screenHeight = displayMetrics.heightPixels;
    }

    public void initializeSlideshow() {
        try {        
            isRunning = true;    
            // Check MediaManager status
            if (mediaManager == null || mediaManager.getMediaFiles() == null) {
                logManager.addLog("MediaManager not properly initialized");
                return;
            }
            
            // Prepare everything before view switch
            logManager.addLog("Preparing slideshow components");
            
            new Handler().postDelayed(() -> {
                try {
                    // Now switch the view after delay
                    logManager.addLog("Switching to presentation view");
                    activity.setContentView(R.layout.activity_view);
                    
                    // Find and validate views
                    ImageView mediaView = activity.findViewById(R.id.imageView);
                    PlayerView playerView = activity.findViewById(R.id.playerView);
                    android.view.View dimOverlay = activity.findViewById(R.id.dimOverlay);
                    
                    if (mediaView == null || playerView == null) {
                        logManager.addLog("Error: Required views not found in layout");
                        return;
                    }
                    
                    // Store the PlayerView reference and dim overlay
                    this.playerView = playerView;
                    this.mediaView = mediaView;
                    // Inform BrightnessManager about the dim overlay
                    try { 
                        brightnessManager.setViews(mediaView, playerView, dimOverlay);
                        this.dimOverlay = dimOverlay;
                    } catch (Exception ignored) {}
                    
                    logManager.addLog("Views initialized successfully");

                    startSlideshow();
                    
                } catch (Exception e) {
                    logManager.addLog("Error during view transition: " + e.getMessage());
                    e.printStackTrace();
                }
            }, settingsManager.getCurrentSettings().getStartupDelay() * 1000L);
        } catch (Exception e) {
            logManager.addLog("Critical error in initializeSlideshow: " + e.getMessage());
            e.printStackTrace();
        }
    }
        
    public void startSlideshow() {
        try {
            logManager.addLog("Setting up media view");
            brightnessManager.setViews(mediaView, playerView);
            setupExoPlayer();
            prepareMediaList();
            startMediaRotation();
        } catch (Exception e) {
            logManager.addLog("Error in startSlideshow: " + e.getMessage());
        }
    }    

    private void setupExoPlayer() {
        if (exoPlayer == null) {
            exoPlayer = new ExoPlayer.Builder(activity).build();
            playerView.setPlayer(exoPlayer);
            
            // Hide player controls
            playerView.setUseController(false);
            
            // Configure player behavior
            exoPlayer.setPlayWhenReady(true);
            
            logManager.addLog("ExoPlayer initialized");
        }
    }

    private void prepareMediaList() {
        try {
            List<MediaFile> mediaFiles = mediaManager.getMediaFiles();
            if (mediaFiles == null || mediaFiles.isEmpty()) {
                logManager.addLog("No media files available");
                return;
            }
    
            // Clear and reload our working copy
            shuffledMediaList.clear();
            shuffledMediaList.addAll(mediaFiles);
            
            if (settingsManager.getCurrentSettings().isShuffle()) {
                Collections.shuffle(shuffledMediaList);
                logManager.addLog("Media files shuffled: " + shuffledMediaList.size() + " files");
            }
        } catch (Exception e) {
            logManager.addLog("Error in prepareMediaList: " + e.getMessage());
        }
    }   

    private void startMediaRotation() {
        if (slideshowRunnable != null) {
            slideshowHandler.removeCallbacks(slideshowRunnable);
        }
        
        slideshowRunnable = new Runnable() {
            @Override
            public void run() {
                // Skip to next media only if we're not playing a full-length video
                if (!isPlayingFullLengthVideo) {
                    displayNextMedia();
                    int delay = settingsManager.getCurrentSettings().getSlideshowDelay() * 1000;
                    slideshowHandler.postDelayed(this, delay);
                } else {
                    // If we're playing a full-length video, the video completion handler
                    // will call moveToNextMedia(), which will restart this timer
                    logManager.addLog("Slideshow timer paused - waiting for full-length video to complete");
                }
            }
        };
        
        // Only start rotation if the slideshow is in running state
        if (isRunning) {
            slideshowHandler.post(slideshowRunnable);
        } else {
            logManager.addLog("Slideshow rotation suppressed because isRunning=false");
        }
    }

    private void displayNextMedia() {
        if (!isRunning) {
            logManager.addLog("displayNextMedia skipped because slideshow is paused");
            return;
        }

        if (shuffledMediaList.isEmpty()) {
            // Reload the list if it's empty (first run or SD card was removed)
            prepareMediaList();
            
            if (shuffledMediaList.isEmpty()) {
                // Still empty after trying to reload
                logManager.addLog("No media files to display - SD card may have been removed");
                
                // Stop the slideshow loop to prevent repeated messages
                if (slideshowHandler != null && slideshowRunnable != null) {
                    slideshowHandler.removeCallbacks(slideshowRunnable);
                    logManager.addLog("Slideshow paused until media becomes available");
                    
                    // Check every 5 seconds if media has become available again
                    if (isRunning) {
                        periodicCheckRunnable = this::checkForMediaAndResume;
                        periodicCheckHandler.postDelayed(periodicCheckRunnable, 5000);
                    }
                }
                return;
            }
        }
        
        if (currentIndex >= shuffledMediaList.size()) {
            currentIndex = 0;
            prepareMediaList(); // Reshuffle if enabled
        }
        
        MediaFile mediaFile = shuffledMediaList.get(currentIndex++);
        if (!isRunning) {
            logManager.addLog("Aborting displayNextMedia after selecting media because isRunning=false");
            return;
        }
        try {
            displayMedia(mediaFile);
            logManager.addLog("Displaying media: " + mediaFile.getName());
        } catch (Exception e) {
            logManager.addLog("Error displaying media: " + e.getMessage());
            slideshowHandler.post(slideshowRunnable); // Skip to next media
        }
    }    

    private void checkForMediaAndResume() {
        if (!isRunning) {
            logManager.addLog("Canceling media check as slideshow is stopped");
            return; // Don't schedule another check if we're not running
        }
        
        List<MediaFile> mediaFiles = mediaManager.getMediaFiles();
        if (!mediaFiles.isEmpty()) {
            logManager.addLog("Media files detected - resuming slideshow");
            startMediaRotation();
        } else {
            // Only keep checking if we're still running
            if (isRunning) {
                periodicCheckRunnable = this::checkForMediaAndResume;
                periodicCheckHandler.postDelayed(periodicCheckRunnable, 5000);
            }
        }
    }

    private void displayMedia(MediaFile mediaFile) {
        if (!isRunning) {
            logManager.addLog("displayMedia skipped because slideshow is paused");
            return;
        }
        try {
            if (mediaFile == null || mediaFile.getFile() == null) {
                logManager.addLog("Invalid media file");
                return;
            }
    
            String fileName = mediaFile.getName();
            String filePath = mediaFile.getFile().getAbsolutePath();
            
            // Check if the file is a video
            if (mediaFile.isVideo()) {
                // Resolution checking
                MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                try {
                    retriever.setDataSource(filePath);
                    String width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
                    String height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
                    
                    if (width != null && height != null) {
                        int w = Integer.parseInt(width);
                        int h = Integer.parseInt(height);
                        long pixels = (long)w * h;
                        
                        logManager.addLog("Processing video: " + fileName + " (" + w + "x" + h + ")");
                        
                        // Skip videos with resolution higher than the configured maximum
                        if (pixels > settingsManager.getCurrentSettings().getMaxVideoPixels()) {
                            logManager.addLog("RESOLUTION TOO HIGH: Skipping " + fileName);
                            slideshowHandler.removeCallbacks(slideshowRunnable);
                            slideshowHandler.post(slideshowRunnable);
                            return;
                        }
                    }
                } catch (Exception e) {
                    logManager.addLog("Error checking video resolution: " + e.getMessage());
                } finally {
                    try {
                        retriever.release();
                    } catch (Exception ignored) {}
                }
                
                displayVideo(mediaFile);
            } else {
                displayImage(mediaFile);
            }
        } catch (Exception e) {
            logManager.addLog("Error in displayMedia: " + e.getMessage());
            slideshowHandler.removeCallbacks(slideshowRunnable);
            slideshowHandler.post(slideshowRunnable);
        }
    }
        
    private void displayImage(MediaFile mediaFile) {
        try {
            String filePath = mediaFile.getFile().getAbsolutePath();
            
            File f = mediaFile.getFile();
            // Use the BitmapLoader to decode and cache
            bitmapLoader.load(f, screenWidth, screenHeight, new BitmapLoader.Callback() {
                @Override
                public void onSuccess(Bitmap bitmap, boolean fromCache) {
                    try {
                        // Recycle previous non-cached bitmap if necessary
                        if (currentBitmap != null && !currentBitmap.isRecycled()) {
                            // If it is the same instance as cached, don't recycle
                            if (currentBitmap != bitmap) {
                                try { currentBitmap.recycle(); } catch (Exception ignored) {}
                            }
                        }
                        currentBitmap = bitmap;

                        activity.runOnUiThread(() -> {
                            try {
                                if (mediaView != null) {
                                    // Hide player and overlay when showing images
                                    if (playerView != null) {
                                        try {
                                            playerView.setPlayer(null);
                                        } catch (Exception ignored) {}
                                        playerView.setVisibility(View.GONE);
                                    }
                                    if (dimOverlay != null) {
                                        dimOverlay.setVisibility(android.view.View.GONE);
                                    }

                                    mediaView.setVisibility(View.VISIBLE);
                                    mediaView.setImageBitmap(bitmap);
                                    logManager.addLog("Bitmap set successfully (cached=" + fromCache + ")");
                                } else {
                                    logManager.addLog("MediaView is null");
                                }
                            } catch (Exception e) {
                                logManager.addLog("Error setting bitmap: " + e.getMessage());
                            }
                        });
                    } catch (Exception e) {
                        logManager.addLog("Error in bitmap callback: " + e.getMessage());
                    }
                }

                @Override
                public void onError(Exception e) {
                    logManager.addLog("BitmapLoader error: " + e.getMessage());
                }
            });
        } catch (OutOfMemoryError oom) {
            logManager.addLog("Out of memory error: " + oom.getMessage());
        } catch (Exception e) {
            logManager.addLog("Error in displayImage: " + e.getMessage());
        }
    }
    
    private void displayVideo(MediaFile mediaFile) {
        try {
            String filePath = mediaFile.getFile().getAbsolutePath();
            final int slideshowDelay = settingsManager.getCurrentSettings().getSlideshowDelay() * 1000;
            final boolean shouldLoopVideos = settingsManager.getCurrentSettings().isLoopVideos();
            final boolean allowFullLengthVideos = settingsManager.getCurrentSettings().isAllowFullLengthVideos();
            
            // Double-check file size again for safety
            long fileSizeKB = mediaFile.getFile().length() / 1024;
            int maxVideoSizeKB = settingsManager.getCurrentSettings().getMaxVideoSizeKB();
            if (fileSizeKB > maxVideoSizeKB) {
                logManager.addLog("Safety check: Skipping large video file: " +
                                mediaFile.getName() + " (" + fileSizeKB + "KB > " + maxVideoSizeKB + "KB limit)");
                moveToNextMedia();
                return;
            }
            
            activity.runOnUiThread(() -> {
                try {
                    // Clean up memory before attempting to play
                    if (mediaView != null) {
                        mediaView.setImageBitmap(null);
                        mediaView.setVisibility(View.GONE);
                    }
                    
                    // Clear bitmap cache before playing videos to free memory deterministically
                    try {
                        if (bitmapLoader != null) bitmapLoader.clearCache();
                    } catch (Exception ignored) {}
                    
                    playerView.setVisibility(View.VISIBLE);
                    
                    // Release any existing player
                    releaseExoPlayer();
                    
                    // Reuse or create ExoPlayer instance
                    if (exoPlayer == null) {
                        exoPlayer = new ExoPlayer.Builder(activity).build();
                        playerView.setPlayer(exoPlayer);
                        exoPlayer.setPlayWhenReady(true);
                        logManager.addLog("ExoPlayer created/reused");
                    } else {
                        // ensure playerView has the current player
                        playerView.setPlayer(exoPlayer);
                        logManager.addLog("ExoPlayer reused for new video");
                    }
                    
                    // Set up a timeout for video preparation
                    final Handler timeoutHandler = new Handler();
                    timeoutHandler.postDelayed(() -> {
                        isPlayingFullLengthVideo = false; // Reset flag in case of timeout
                        releaseExoPlayer();
                        moveToNextMedia();
                    }, 5000); // 5 second timeout
                    
                    // Use a DefaultDataSourceFactory + ProgressiveMediaSource for file playback
                    try {
                        android.net.Uri uri = android.net.Uri.fromFile(new File(filePath));
                        // DefaultDataSourceFactory is available via ExoPlayer util
            com.google.android.exoplayer2.upstream.DefaultDataSource.Factory dataSourceFactory =
                new com.google.android.exoplayer2.upstream.DefaultDataSource.Factory(activity);

                        com.google.android.exoplayer2.source.MediaSource mediaSource =
                                new com.google.android.exoplayer2.source.ProgressiveMediaSource.Factory(dataSourceFactory)
                                        .createMediaSource(MediaItem.fromUri(uri));

                        // Stop current playback and prepare new media
                        exoPlayer.stop();
                        exoPlayer.clearMediaItems();
                        exoPlayer.setRepeatMode(shouldLoopVideos ? Player.REPEAT_MODE_ALL : Player.REPEAT_MODE_OFF);
                        exoPlayer.setMediaSource(mediaSource, /* resetPosition= */ true);
                        exoPlayer.prepare();
                        exoPlayer.setPlayWhenReady(true);
                    } catch (Exception e) {
                        logManager.addLog("Error preparing media source: " + e.getMessage());
                        releaseExoPlayer();
                        moveToNextMedia();
                        return;
                    }

                    // Prepare timeout watchdog: use settings value if present
                    int prepareTimeoutMs = 15000; // default 15s
                    try { prepareTimeoutMs = settingsManager.getCurrentSettings().getVideoPrepareTimeoutMs(); } catch (Exception ignored) {}
                    final Handler prepareWatchdog = new Handler();
                    final Runnable watchdogRunnable = () -> {
                        logManager.addLog("Video prepare watchdog fired for " + mediaFile.getName());
                        // if not ready, release and move on
                        if (exoPlayer != null && exoPlayer.getPlaybackState() != Player.STATE_READY) {
                            releaseExoPlayer();
                            moveToNextMedia();
                        }
                    };
                    prepareWatchdog.postDelayed(watchdogRunnable, prepareTimeoutMs);
                    
                    // Get video duration handler - for non-looping videos
                    final Handler videoDurationHandler = new Handler();
                    
                    // Set up error listener
                    exoPlayer.addListener(new Player.Listener() {
                        @Override
                        public void onPlaybackStateChanged(int state) {
                            if (state == Player.STATE_READY) {
                                // Cancel the timeout since playback started successfully
                                    timeoutHandler.removeCallbacksAndMessages(null);
                                    prepareWatchdog.removeCallbacks(watchdogRunnable);
                                
                                // Set the flag if this is a full-length video
                                if (!shouldLoopVideos && allowFullLengthVideos) {
                                    long videoDuration = exoPlayer.getDuration();
                                    if (videoDuration > slideshowDelay) {
                                        isPlayingFullLengthVideo = true;
                                        logManager.addLog("Playing full-length video: " + 
                                            mediaFile.getName() + " (" + (videoDuration/1000) + "s)");
                                    }
                                }
                                
                                logManager.addLog("Video playback started successfully: " + mediaFile.getName());
                                
                                // If not allowing full-length videos AND not looping,
                                // set up a timer based on slideshow delay
                                if (!shouldLoopVideos && !allowFullLengthVideos) {
                                    long videoDuration = exoPlayer.getDuration();
                                    logManager.addLog("Video length: " + (videoDuration/1000) + "s, Slideshow delay: " +
                                                    (slideshowDelay/1000) + "s");
                                    
                                    // Only set a timer if video is longer than slideshow delay
                                    if (videoDuration > slideshowDelay) {
                                        // Cancel any existing timeout
                                        if (videoDurationRunnable != null) {
                                            videoDurationHandler.removeCallbacks(videoDurationRunnable);
                                        }
                                        
                                        videoDurationRunnable = () -> {
                                            // Only log if we're still running
                                            if (isRunning) {
                                                logManager.addLog("Slideshow delay reached - cutting video short");
                                            }
                                            isPlayingFullLengthVideo = false;
                                            releaseExoPlayer();
                                            moveToNextMedia();
                                        };
                                        
                                        videoDurationHandler.postDelayed(videoDurationRunnable, slideshowDelay);
                                    }
                                }
                                
                            } else if (state == Player.STATE_ENDED && !shouldLoopVideos) {
                                // Cancel any pending duration handler
                                videoDurationHandler.removeCallbacksAndMessages(null);
                                
                                logManager.addLog("Video playback completed naturally");
                                // Reset the flag when video ends
                                isPlayingFullLengthVideo = false;
                                releaseExoPlayer();
                                moveToNextMedia();
                            }
                        }
                        
                        @Override
                        public void onPlayerError(PlaybackException error) {
                            timeoutHandler.removeCallbacksAndMessages(null);
                            videoDurationHandler.removeCallbacksAndMessages(null);
                            logManager.addLog("Video error - skipping to next item: " + error.getMessage());
                            // Reset the flag in case of error
                            isPlayingFullLengthVideo = false;
                            releaseExoPlayer();
                            moveToNextMedia();
                        }
                    });
                    
                    // Start playback
                    exoPlayer.prepare();
                    
                    // Set timer for loop mode
                    if (shouldLoopVideos) {
                        // Cancel any existing timeout
                        if (loopTimeoutRunnable != null) {
                            loopTimeoutHandler.removeCallbacks(loopTimeoutRunnable);
                        }
                        
                        loopTimeoutRunnable = () -> {
                            // Only log if we're still running
                            if (isRunning) {
                                logManager.addLog("Loop mode timeout - moving to next item");
                            }
                            isPlayingFullLengthVideo = false; // Reset flag when timer completes
                            releaseExoPlayer();
                            moveToNextMedia();
                        };
                        
                        loopTimeoutHandler.postDelayed(loopTimeoutRunnable, slideshowDelay);
                    }
                    
                } catch (Exception e) {
                    logManager.addLog("Video setup error - skipping: " + e.getMessage());
                    isPlayingFullLengthVideo = false; // Reset flag in case of exception
                    releaseExoPlayer();
                    moveToNextMedia();
                }
            });
        } catch (Exception e) {
            logManager.addLog("General video error - skipping: " + e.getMessage());
            isPlayingFullLengthVideo = false; // Reset flag in case of exception
            moveToNextMedia();
        }
    }
    
    // Helper methods for cleaner code
    private void releaseExoPlayer() {
        try {
            if (exoPlayer != null) {
                exoPlayer.stop();
                exoPlayer.release();
                exoPlayer = null;
            }
        } catch (Exception e) {
            logManager.addLog("Error releasing player: " + e.getMessage());
        }
    }

    private boolean isVideoResolutionTooHigh(String filePath) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(filePath);
            String width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            String height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            
            if (width != null && height != null) {
                int w = Integer.parseInt(width);
                int h = Integer.parseInt(height);
                
                // Calculate total pixels
                long pixels = (long)w * h;
                
                // Limit to 921,600 pixels (equivalent to 1280×720)
                return pixels > 921600;
            }
        } catch (Exception e) {
            logManager.addLog("Error checking video resolution: " + e.getMessage());
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {}
        }
        return false;
    }
        
    private void moveToNextMedia() {
        slideshowHandler.removeCallbacks(slideshowRunnable);
        slideshowHandler.postDelayed(slideshowRunnable, 100);
    }
    
    private int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
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

    public boolean isRunning() {
        return isRunning;
    }

    public void cleanup() {
        // Prevent re-entrant cleanup
        if (cleanupInProgress) {
            logManager.addLog("cleanup() called but already in progress - ignoring");
            return;
        }
        cleanupInProgress = true;
        try {
            logManager.addLog("Safe cleanup started due to SD card removal");
            
            // Set running flag to false first to prevent any new operations
            isRunning = false;
            
            // Immediately cancel any periodic checks
            if (periodicCheckHandler != null && periodicCheckRunnable != null) {
                periodicCheckHandler.removeCallbacks(periodicCheckRunnable);
                periodicCheckRunnable = null;
            }
            
            // Immediately cancel any slideshow tasks
            if (slideshowHandler != null && slideshowRunnable != null) {
                slideshowHandler.removeCallbacks(slideshowRunnable);
                slideshowRunnable = null; // Make sure it can't be reused
            }
    
            // Clean up brightness debugging (uncomment and keep this)
            if (brightnessDebugHandler != null) {
                brightnessDebugHandler.removeCallbacksAndMessages(null);
                brightnessDebugRunnable = null;
            }
    
            // Force video playback to stop immediately
            try {
                if (exoPlayer != null) {
                    exoPlayer.setPlayWhenReady(false);
                    exoPlayer.stop();
                    exoPlayer.release();
                    exoPlayer = null;
                    logManager.addLog("ExoPlayer released successfully");
                    
                    // Make sure PlayerView is detached properly
                    if (playerView != null) {
                        playerView.setPlayer(null);
                    }
                }
            } catch (Exception e) {
                logManager.addLog("Error releasing ExoPlayer: " + e.getMessage());
            }

            // Explicitly clear any pending operations on all handlers
            try {
                if (slideshowHandler != null) {
                    slideshowHandler.removeCallbacksAndMessages(null);
                }
                if (periodicCheckHandler != null) {
                    periodicCheckHandler.removeCallbacksAndMessages(null);
                }
                if (loopTimeoutHandler != null) {
                    loopTimeoutHandler.removeCallbacksAndMessages(null);
                    loopTimeoutRunnable = null;
                }
                if (videoDurationHandler != null) {
                    videoDurationHandler.removeCallbacksAndMessages(null);
                    videoDurationRunnable = null;
                }
            } catch (Exception e) {
                logManager.addLog("Error cleaning handlers: " + e.getMessage());
            }
            
            // Safely clear lists
            try {
                if (shuffledMediaList != null) {
                    shuffledMediaList.clear();
                }
            } catch (Exception e) {
                logManager.addLog("Error clearing media list: " + e.getMessage());
            }
            
            // Reset variables
            currentIndex = 0;
            isPlayingFullLengthVideo = false;
            
            // Clear image references safely
            try {
                if (mediaView != null) {
                    activity.runOnUiThread(() -> {
                        try {
                            mediaView.setImageBitmap(null);
                            // Recycle the current bitmap if it is not cached
                            try {
                                if (currentBitmap != null && !currentBitmap.isRecycled()) {
                                    try { currentBitmap.recycle(); } catch (Exception ignored) {}
                                    currentBitmap = null;
                                }
                            } catch (Exception ignored) {}
                            logManager.addLog("MediaView bitmap cleared");
                        } catch (Exception e) {
                            logManager.addLog("Error clearing mediaView: " + e.getMessage());
                        }
                    });
                }
            } catch (Exception e) {
                logManager.addLog("Error with UI thread for clearing image: " + e.getMessage());
            }
            
            // Force garbage collection to release any lingering resources
            // Do not call System.gc(); we rely on deterministic release and cache eviction
            try {
                if (bitmapLoader != null) {
                    bitmapLoader.clearCache();
                    bitmapLoader.shutdown();
                }
            } catch (Exception ignored) {}
            
            logManager.addLog("Safe cleanup completed successfully");
            cleanupInProgress = false;
        } catch (Exception e) {
            // Catch-all to prevent any cleanup exceptions from crashing the app
            try {
                logManager.addLog("Critical error during safe cleanup: " + e.getMessage());
            } catch (Exception ignored) {
                // Even logging might fail, just silently ignore at this point
            }
        }
    }

    /**
     * Pause slideshow for scheduled sleep without releasing heavy resources.
     * This hides media views and stops rotation but keeps caches and ExoPlayer
     * available so waking can be fast.
     */
    public void pauseForSleep() {
        try {
            logManager.addLog("Pausing slideshow for sleep");
            // Stop rotation
            if (slideshowHandler != null && slideshowRunnable != null) {
                slideshowHandler.removeCallbacks(slideshowRunnable);
            }
            // Pause ExoPlayer if present
            try {
                if (exoPlayer != null) {
                    exoPlayer.setPlayWhenReady(false);
                }
            } catch (Exception ignored) {}

            // Hide UI elements to present a black screen
            activity.runOnUiThread(() -> {
                try {
                    if (mediaView != null) mediaView.setVisibility(View.GONE);
                    if (playerView != null) playerView.setVisibility(View.GONE);
                    if (dimOverlay != null) dimOverlay.setVisibility(View.VISIBLE);
                } catch (Exception ignored) {}
            });

            isRunning = false;
        } catch (Exception e) {
            logManager.addLog("Error pausing for sleep: " + e.getMessage());
        }
    }

    /**
     * Resume slideshow after wake. This will make media views visible and restart
     * rotation. It prefers to reuse existing ExoPlayer and caches when possible.
     */
    public void resumeFromSleep() {
        try {
            logManager.addLog("Resuming slideshow from sleep");
            activity.runOnUiThread(() -> {
                try {
                    if (mediaView != null) mediaView.setVisibility(View.VISIBLE);
                    if (playerView != null) playerView.setVisibility(View.VISIBLE);
                    if (dimOverlay != null) dimOverlay.setVisibility(View.GONE);
                } catch (Exception ignored) {}
            });

            // Re-prepare media list if necessary and start rotation
            prepareMediaList();
            startSlideshow();
        } catch (Exception e) {
            logManager.addLog("Error resuming from sleep: " + e.getMessage());
        }
    }

    public void resetCleanupGuard() {
        cleanupInProgress = false;
    }
}
