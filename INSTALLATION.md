# EO Phoenix Installation Guide

## Overview

**EO Phoenix Android app** — the app that runs on the EO2 frame (sideload this onto the device).

The slideshow runs from an SD card inserted into the EO2 frame.

**Note:** Because repeatedly connecting and removing a keyboard or SD card can stress the EO2 device's USB connector, and wall-mounted devices may have clearance issues, we recommend using a right-angle USB cable that can remain connected to the device and plugging peripherals into that cable.

## Initial Access

The EO2 device UI will need to be accessed initially to make device settings and install the EO Phoenix app. A USB keyboard is required for this step to navigate the device UI. Once the app is installed, you should no longer need to access the device UI.

### When keyboard navigation fails

A USB keyboard is normally enough to navigate and configure the frame. If it does not work, try these options:

- **Soft reset:** long‑press the physical button on the back of the frame (top centre).
- **Factory reset:** press and hold the recessed factory reset button on the back for approximately 5 seconds (the button is behind a small hole in the fourth row of holes from the top, left of centre). Keep holding until the display flashes white, then release. Use a toothpick or similar tool to reach it.

## Factory Reset First

Factory-reset the device before starting configuration to ensure the device is in a known state. Press and hold the recessed factory reset button on the back for approximately 5 seconds (the button is behind a small hole in the fourth row of holes from the top, left of centre). Keep holding until the display flashes white, then release. Use a toothpick or similar tool to reach it.

Alternatively, run a factory data reset via the Android menu by going to **Reset**, then **Factory data reset**. **NOTE:** this will erase all data from the device's internal storage.

## Device Information

The EO2 frame runs Android KitKat (API 19). You can control the device using a USB keyboard connected to the port on the back.

### Keyboard shortcuts

- **Windows+L** — use this to access the device UI (brings up the main screen).
- **Tab/arrow keys** — navigate menus.
- **Enter** — select item.
- **Escape** — go back.
- **Ctrl+Alt+Del** — restart the device if it becomes unresponsive.

If a menu cannot be reached, restart with Ctrl+Alt+Del and try again.

### Initial setup notes

The device UI can be accessed at any time. Initially the device may show Google Calendar. If no Google account is recognized you will be offered three choices: **Existing** (use this), **New**, and **Not now**.

If the display stays on the Google Calendar screen, press Delete and then try Windows+L again. If that fails, restart the device with Ctrl+Alt+Del and try again.

When you reach the WiFi settings screen, use Tab to highlight the **Back** option at the top-left, then press Enter to return to the main menu.

## Configuration

### Security

Enable **Unknown sources** (or the equivalent "Install unknown apps") in the device Security settings to allow sideloading the EO Phoenix Android app.

### Scheduling Configuration Settings

The EO Phoenix Android app requires a couple of device-level settings only if you intend to use scheduling. Follow the steps below to set them.

#### Wi‑Fi

Open the device Wi‑Fi menu. Use Tab to reach the On/Off toggle and press Enter to enable Wi‑Fi. **Important:** do not enter any Wi‑Fi credentials in the device Wi‑Fi menu from this screen.

**Note:** if Wi‑Fi connect fails unexpectedly during slideshow initialisation, try accessing the device UI Wi‑Fi menu and toggle Wi‑Fi Off then On.

#### Date & Time

Open the device Date & Time menu and ensure **Automatic date & time** is enabled. This allows the frame to keep accurate time for scheduled events.

You have three options: enable Wi‑Fi and keep Automatic date & time enabled so the app can fetch and maintain the correct clock; or leave Wi‑Fi off and set the timezone/date/time manually — the scheduler will use the device clock either way; or ignore these settings entirely if you do not intend to use scheduling.

## Sideload App

The EO Phoenix app must be sideloaded.

### Prepare Bluetooth

Use Windows+L to access the device UI, and navigate to the Bluetooth menu. Ensure Bluetooth is turned on for both the EO2 device and the PC.

On the EO2 device, select the device code listed to make it visible to other devices. On the PC, open Bluetooth settings and choose **Add device**, then follow the prompts to pair with the frame. Your PC should appear in the device's Paired Devices list once connected.

### Send the APK

Navigate to the device's top-right overflow menu (three stacked dots) and select **Show received files**. On the PC, open Bluetooth settings and choose **Send or receive files via Bluetooth**, then **Send files**. Select your EO2 frame and choose the `eo-phoenix.apk` to begin the transfer.

### Install on device

After the transfer completes on the frame, open the received file and install it. When installation finishes, choose **Open** to launch the app.

### Notes

- Bluetooth transfers can be slow; toggling Bluetooth Off and On on the device sometimes improves transfer speed.
- You may be prompted to **Activate Device Administrator** during install — select **Activate** to continue.
- If prompted to choose a home (launcher) app for the device, select **EO Phoenix** and choose **Always**.

## Slideshow

Once the EO Phoenix app has been installed on the device and started, an overview page will display with a log output.

Install the SD card. The SD card must match the required layout, with the `settings.json` file and at least one media folder within the parent `EoPhoenix` folder.

### SD Card Structure

```
SD Card/
└── EoPhoenix/
    ├── [Media Folder 1]/
    ├── [Media Folder 2]/
    └── settings.json
```

**Note:** You must download and manually edit the `settings.json` from this repo if not using the EO Phoenix Editor desktop app.

The EO Phoenix app will attempt to read the SD card and fetch the settings. The log output will display progress — this will also be saved to the SD Card for debugging if required.

If setup is correct, the slideshow will initialise and begin playing.

Removing the SD card will stop the slideshow and allow you to change settings or upload new media.

## settings.json Configuration

If manually editing the `settings.json` file, do not change the file format or add/remove sections.

Many settings should not require adjustment, however the following settings are important:

### Slideshow Settings

These settings relate to slideshow behaviour. The `folder` name must match the actual folder name that contains the media you want to display.

```json
"folder": "Portrait",
"slideshowDelay": 30,
"shuffle": true,
"loopVideos": true,
"allowFullLengthVideos": true,
"maxVideoSizeKB": 4096,
"maxVideoPixels": 2073600,
"brightness": "7"
```

### Wi-Fi Settings

These settings allow the EO Phoenix app to briefly establish an internet connection and set the device clock (not required if scheduling isn't used).

```json
"wifiSSID": "",
"wifiPassword": "",
"timeZone": "Pacific/Auckland"
```

### Schedule Settings

A schedule can be utilised for on/off periods. Define one or more On/Off slots per day to control when the display is active. If no slots are defined, or if `alwaysOn` is enabled, the EO2 Frame will remain on continuously. During "off" hours the EO Phoenix app will remove displayed media and dim the display; it will not fully power down. Use the device's hardware button to turn the display on or off manually if needed.

```json
"schedule": {
  "monday": [
    {
      "on": "07:00",
      "off": "09:00"
    },
    {
      "on": "17:30",
      "off": "22:45"
    }
  ],
  "tuesday": [
    {
      "on": "00:00",
      "off": "24:00"
    }
  ],
  "wednesday": [],
  "thursday": [],
  "friday": [],
  "saturday": [],
  "sunday": []
},
"alwaysOn": false
```
