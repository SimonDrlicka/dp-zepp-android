# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run

```bash
# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease

# Run unit tests
./gradlew test

# Run instrumented (device) tests
./gradlew connectedAndroidTest

# Clean build
./gradlew clean
```

Single module project — all build tasks target `:app`.

## Architecture

Android app that receives real-time IMU (Inertial Measurement Unit) sensor data from an external device (Zepp wearable) over HTTP, detects gestures, and visualizes the data.

**Data flow:** External device → HTTP POST → `ImuHttpServer` (parse, detect, score) → `MainActivity` (display, export)

### Key components

- **ImuHttpServer** — NanoHTTPD server on port 8080. Receives packed 6-DOF sensor data (3-axis gyro + 3-axis accel) via `/gyro-data-full` endpoint. Handles gesture detection by checking acceleration values against configurable bands, manages WAITING/GESTURE mode transitions, and counts points based on gyroscope rotation thresholds. Thread-safe with `synchronized` blocks.

- **MainActivity** — Manages server lifecycle, refreshes UI at ~3Hz from server buffers, handles CSV export to Downloads (session samples, gesture segments, points). Gesture segments auto-export when a gesture completes.

- **GraphView** — Custom `View` for real-time multi-series line graphs with bands, auto-scaling, grid, and legend.

- **GestureConfig** — Defines gesture acceleration bands (Hand up/Hand down) and point scoring thresholds.

### Data format

HTTP payload: JSON with `"data"` field containing pipe-delimited samples. Each sample: `gx,gy,gz,ax*100,ay*100,az*100,timestamp` (accelerometer values are multiplied by 100).

## Tech stack

- Kotlin, min SDK 24, target/compile SDK 36, Java 11
- NanoHTTPD 2.3.1 for embedded HTTP server
- AndroidX (AppCompat, ConstraintLayout, Material)
- Gradle with Kotlin DSL and version catalog (`gradle/libs.versions.toml`)
