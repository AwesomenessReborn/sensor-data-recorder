# IMU Data Recorder for Android

## Purpose

A minimal Android app to record high-frequency IMU data (accelerometer + gyroscope) from Android devices at **~200 Hz** (or max supported rate) and export to CSV. This app was built to overcome limitations in existing tools that could not reliably sustain high sampling rates on modern Android devices.

## Features

*   **High-Frequency Recording:** Targets `SENSOR_DELAY_FASTEST` to achieve maximum hardware sampling rates (typically ~200-400Hz on modern devices).
*   **Foreground Service:** Ensures recording continues uninterrupted even when the screen is off or the app is in the background.
*   **Monotonic Timestamps:** Uses `event.timestamp` (nanoseconds since boot) to avoid jitter from NTP clock updates.
*   **CSV Export:** Saves data to standard CSV format for easy analysis in Python/Pandas.
*   **Live Preview:** Displays real-time sensor data and effective sampling rates.
*   **Sync Markers:** Includes a "Sync Tap" button to insert manual markers in the data stream for synchronizing with other devices or video.

## Technical Details

### Sampling Rate
The app uses `SENSOR_DELAY_FASTEST`. On modern Android devices, this typically yields:
- **Accelerometer:** ~200-400 Hz (depending on hardware and OS scheduling)
- **Gyroscope:** ~200-400 Hz

### Data Format

The app exports three CSV files for each session:

**Accelerometer.csv / Gyroscope.csv**
```csv
timestamp_ns,x,y,z
783012345678900,0.0234,-9.7891,0.1456
...
```

**Annotation.csv** (Markers)
```csv
timestamp_ns,label
783012345678900,sync_tap
```

**Metadata.json**
Contains session details including device model, start time (epoch), and total sample counts.

## Usage

1.  **Start Recording:** Press the "Start Recording" button. The app will lock the CPU awake and begin logging data.
2.  **Sync (Optional):** If recording with multiple devices, you can perform a "clap" or tap the devices together and press "Sync Tap" to mark the event.
3.  **Stop & Export:** Press "Stop Recording". Use the "Share Recording" button to export the session data as a ZIP file via email, Google Drive, or other apps.

## Requirements

*   **Min SDK:** 29 (Android 10)
*   **Target SDK:** 35 (Android 15)
*   **Permissions:**
    *   `FOREGROUND_SERVICE`: To record in background.
    *   `POST_NOTIFICATIONS`: To show the recording status.
    *   `HIGH_SAMPLING_RATE_SENSORS`: To access 200Hz+ data.