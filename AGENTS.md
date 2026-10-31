# AirTether (Compose Multiplatform) - Agent Guidelines

## 1. Project Overview
AirTether is an ultra-fast wireless/wired camera tethering viewer application built with Kotlin Multiplatform (KMP) and Compose Multiplatform.
- Target Platforms: Android, iOS, Desktop (JVM)
- Main Focus: Real-time photo ingestion from cameras via FTP (Port 2121) / USB PTP, zero-lag embedded preview rendering, and field culling.

## 2. Core Architecture Rules
- Pattern: MVI / UDF (Unidirectional Data Flow) with single StateFlow<TetheringUiState>.
- Responsive Layout: Tablet landscape-first design.
  - Left Panel (28%): Filmstrip list with transfer progress, format badges (RAW/JPG), and timestamps.
  - Right Panel (72%): Main interactive viewer with pinch-to-zoom, double-tap 1:1 pixel zoom, and floating EXIF overlay.
- High-Speed RAW Pipeline:
  - DO NOT demosaic full RAW Bayer sensor data.
  - Extract embedded full-size Preview JPEG directly from TIFF/ISOBMFF byte offsets in <50ms.
- Concurrency: Kotlin Coroutines & SharedFlow for event piping from background services to UI.

## 3. Tech Stack & Dependencies
- UI: Compose Multiplatform (Material 3)
- Image Loading: Coil 3 (Multiplatform)
- Local DB: Room KMP
- Networking: Ktor Sockets / Embedded FTP listener
- Metadata: metadata-extractor / kotlinx-io byte buffer parsing

## 4. Module Boundaries
- composeApp/commonMain:
  - UI Composables, ViewModels, Domain Models, RAW Embedded JPEG Parsers, XMP Generator.
- composeApp/androidMain:
  - Foreground Service (FTP Port 2121), MediaStore Scoped Storage with IS_PENDING.
- composeApp/iosMain:
  - iOS-specific photo library export and lifecycle management.
