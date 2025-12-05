# EO Phoenix - Android Photo Frame App

Android slideshow application for the EO2 Photo Frame device, designed to display photos and videos on a schedule.

## Features

- 📸 **Photo & Video Slideshow** - Display images and videos from SD card
- ⏰ **Scheduled Display** - Automatic on/off times with daily schedules
- 🌓 **Brightness Control** - Manual brightness level control
- 🎞️ **Media Management** - Supports images (JPG, PNG) and videos (MP4, AVI, MKV)
- 📊 **Activity Logging** - Track app behavior and errors

## Download

Download the latest APK from [Releases](https://github.com/kiwiKodo/EO_Phoenix/releases/latest)

## Installation

### Sideload via Bluetooth (Recommended for EO2 Frame)
1. Download the APK to your computer
2. Use the [EO Phoenix Editor](https://github.com/kiwiKodo/EO_Phoenix-Editor) desktop app
3. Connect via Bluetooth and sideload the APK

## Configuration

The app is designed to work with the **EO Phoenix Editor** desktop application (but is not required):

1. Follow the setup instructions in the [EO Phoenix Editor](https://github.com/kiwiKodo/EO_Phoenix-Editor)
2. Copy the `settings.json` file and media folders to the device's SD card
3. Insert the SD card into the EO2 Photo Frame
4. The app will automatically load settings and start the slideshow

If you don't use the EO Phoenix Editor to create the settings.json, you must download and manually edit the [settings.json](https://github.com/kiwiKodo/EO_Phoenix/blob/master/settings.json) from this repo

### Settings File Location
- **Default**: `/SD card/EoPhoenix/settings.json`
- The app looks for settings on the external SD card

### Media Folder
- Place photos and videos in: `/SD card/EoPhoenix/Media Folder/`

## Requirements

- **Android Version**: 4.4 (KitKat) or higher
- **Target Device**: Optimized for EO2 Photo Frame (1024x600 resolution)
- **Storage**: External SD card required for media and settings

## Permissions

The app requires the following permissions:
- **Storage**: Read media files from SD card
- **Device Admin**: Maintain kiosk mode and prevent accidental exits
- **Wake Lock**: Keep screen on during slideshow

## Building from Source

### Prerequisites
- Android Studio Arctic Fox or later
- JDK 17 or higher
- Android SDK with API 34

### Build Steps
```bash
# Clone the repository
git clone https://github.com/kiwiKodo/EO_Phoenix.git
cd EO_Phoenix

# Build debug APK
./gradlew assembleDebug

# Build release APK (requires keystore)
./gradlew assembleRelease
```

The APK will be generated in `app/build/outputs/apk/`

## Development

### Project Structure
```
app/src/main/
├── java/com/kiwikodo/eophoenix/
│   ├── MainActivity.java          # Main activity and slideshow logic
│   ├── Settings.java              # Settings data model
│   ├── managers/                  # Feature managers
│   │   ├── MediaManager.java      # Media loading and playback
│   │   ├── ScheduleManager.java   # Schedule handling
│   │   ├── BrightnessManager.java # Brightness control
│   │   └── ...
│   └── services/                  # Background services
└── res/                           # Resources and layouts
```

### Key Technologies
- **ExoPlayer 2.18.7** - Video playback
- **AndroidX** - Modern Android components
- **Gson** - JSON parsing for settings

## Troubleshooting

### App doesn't start slideshow
- Check that `settings.json` exists at `/SD card/EoPhoenix/settings.json`
- Verify media files are in `/SD card/EoPhoenix/Media Folder/`
- Check logs in `/SD card/EoPhoenix/eo-logs.txt`

### Videos don't play
- Ensure videos are in supported formats (MP4, H.264)
- Check that video files aren't corrupted
- Verify SD card has sufficient read speed

### Schedule not working
- Confirm schedule is properly configured in settings
- Check device time and timezone settings
- Review logs for schedule-related errors

## Related Projects

- **[EO Phoenix Editor](https://github.com/kiwiKodo/EO_Phoenix-Editor)** - Desktop app for configuring settings and managing media

## License

This project is provided as-is for use with EO2 Photo Frame devices.

## Support

For issues, questions, or contributions, please visit the [Issues](https://github.com/kiwiKodo/EO_Phoenix/issues) page.
