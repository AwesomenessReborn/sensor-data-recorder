# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Android app for high-frequency IMU (accelerometer + gyroscope) data recording at ~200Hz. Built to overcome limitations of third-party tools that cannot sustain high sampling rates on modern Android devices. Designed for multi-device recording scenarios with synchronization capabilities.

## Build and Run

The project uses Gradle with Kotlin DSL. All commands run from the `sensor_recorder/` directory:

```bash
cd sensor_recorder

# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease

# Install to connected device
./gradlew installDebug

# Clean build
./gradlew clean

# Run tests
./gradlew test
./gradlew connectedAndroidTest
```

## Architecture

### Core Components

**SensorRecorderService** (`SensorRecorderService.kt`):
- Foreground service that handles sensor data collection at SENSOR_DELAY_FASTEST
- Buffers samples in memory using `ConcurrentLinkedQueue` and flushes to disk every 1 second (disk I/O at 200Hz would drop samples)
- Maintains wake lock during recording to prevent sleep
- Tracks sampling rates using a circular buffer of inter-sample intervals
- Records epoch offset once at start: `System.currentTimeMillis() * 1e6 - SystemClock.elapsedRealtimeNanos()`
- Generates three output files: `Accelerometer.csv`, `Gyroscope.csv`, `Annotation.csv` plus `Metadata.json`

**MainActivity** (`MainActivity.kt`):
- Jetpack Compose UI that binds to `SensorRecorderService`
- Polls service every 100ms for live stats display
- Handles start/stop recording, sync tap markers, and export sharing
- Requests POST_NOTIFICATIONS permission for Android 13+

**ExportUtils** (`ExportUtils.kt`):
- Creates ZIP archives of recording sessions
- Uses FileProvider to share recordings via Android share sheet
- Cleans up old ZIPs from cache on app start

### Data Flow

1. User starts recording → MainActivity sends intent to SensorRecorderService
2. Service registers `SensorEventListener` at `SENSOR_DELAY_FASTEST`
3. `onSensorChanged()` adds CSV lines to in-memory queues (not directly to disk)
4. Background coroutine flushes queues to CSV files every 1 second
5. On stop, final flush writes remaining data + metadata JSON
6. User exports via share sheet → ExportUtils creates ZIP and invokes Android share

### Output Format

**CSV Files** (`Accelerometer.csv`, `Gyroscope.csv`):
```csv
timestamp_ns,x,y,z
783012345678900,0.0234,-9.7891,0.1456
```

**Metadata** (`Metadata.json`):
```json
{"recording_start_epoch_ms": 1234567890123, "epoch_offset_ns": 456789012345678, "accel_sample_count": 12000, "gyro_sample_count": 12000}
```

The `timestamp_ns` field uses `event.timestamp` (nanoseconds since boot, monotonic clock) to avoid NTP jitter. The `epoch_offset_ns` enables wall-clock alignment across devices in post-processing.

## Key Technical Decisions

- **Buffer-and-flush pattern**: Writing every sample directly to disk causes dropped samples. The 1-second flush interval balances data loss risk with I/O overhead.
- **Circular buffer for rate tracking**: Last 50 inter-sample intervals provide stable real-time Hz calculations without accumulating unbounded history.
- **Monotonic timestamps**: `event.timestamp` is immune to wall-clock adjustments, making it suitable for signal processing. Epoch offset recorded once at start maps boot time to wall time.
- **Foreground service**: Required for screen-off recording on Android 10+. Uses `dataSync` service type with persistent notification.

## Requirements

- Min SDK 29 (Android 10)
- Target SDK 36 (Android 15)
- Permissions: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `POST_NOTIFICATIONS`, `HIGH_SAMPLING_RATE_SENSORS`, `WAKE_LOCK`
