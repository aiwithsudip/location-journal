# Location journal

A private, offline-first Android journal for the places you go. Version 2.0.0 is a native
Kotlin/Jetpack Compose app. It records GPS points while you choose to track, derives visits and
frequent places, keeps photos and notes, and exports your own data. There is no account, backend,
analytics, or upload.

The previous WebView application is replaced. The old `js/`, `index.html`, and related web files in
this repository remain only as a reference for behavior and data compatibility; they are not
packaged in the APK and are not the app interface.

## Features

- Native Material 3 Compose UI with Home, Map, Timeline, Places, Photos, Notes, Insights, Reels,
  and Settings screens.
- Foreground location service with start, pause, resume, stop, notification actions, and boot
  restart. A low-priority notification is shown while tracking is active.
- GPS fixes every configurable interval (default 20 seconds) plus an hourly point. Stay detection
  groups nearby samples into visits (default: 200 m radius, 30 minutes, 70-minute maximum gap).
- OSM/OpenStreetMap map rendering with route polyline, visit rings, markers, street/satellite tile
  sources, and route replay at 1x/2x/4x. Map tiles need a network connection; saved routes, visits,
  and all journal data work offline.
- Smart places: automatic grouping by proximity, rename/categorize, duplicate merge, visit notes,
  and per-place history.
- Android Photo Picker import (with a fallback on Android 8–12), EXIF time/location matching to
  visits, captions/labels/favorites, and photo-to-visit attachment.
- Journal entries, timeline search and filters, hourly history, and insight totals with weekly
  buckets and top places.
- Versioned JSON backup/restore, GPX and CSV export, plus a one-time migration from the old
  `points.json` and legacy IndexedDB stores.
- Reels with local bundled clips, likes, saves, views, comments, share/reshare counters, mute, and
  playback through Media3. These interactions stay on-device.
- Night-only flashlight control from 8:00 PM until 6:00 AM, using the camera torch API. It turns itself off when the app stops
  or the callback reports the torch unavailable.
- Distance units, theme, tracking/stay thresholds, visible rings, follow-on-start, optional auto
  photo, night hours, and a privacy exclusion window.

## Privacy and permissions

- Location is used only while tracking is on, or for a single fix when you press **Save a fix now**.
- Background location is optional and only needed to keep tracking while the screen is off.
- Location, photos, notes, and settings are stored locally in Room and DataStore. Android backup and
  device transfer are explicitly disabled for app data.
- No reverse geocoding or third-party network request is made for journal data. The only network use
  is fetching map tiles from OpenStreetMap when you open the map.
- Reels media is bundled in the APK; reels state and comments are local.

## Architecture

Single `:app` module with package boundaries:

- `data/` — Room entities/DAOs/database, DataStore preferences, repositories, JSON/GPX/CSV codecs,
  Photo Picker and EXIF import, one-time legacy migration, local reels asset store.
- `domain/` — Haversine distance, stay detection, photo matching, replay frames, summaries,
  formatting, tracking state machine, and reels catalog.
- `tracking/` — `LocationManager` foreground service, notification actions, boot receiver, and
  camera torch controller.
- `ui/` — Compose/Navigation screens, `JournalViewModel`, theme, and permission helpers.
- `di/` — Hilt singleton graph.

Room version 2 includes exported schemas under `android/app/schemas`. Hilt, KSP, Coroutines/Flow,
WorkManager dependencies, Navigation Compose, and Media3 are used where appropriate.

## Build

Requirements: JDK 17, Android SDK platform 34, Android build tools, and Gradle 9.5.0.

```bash
export JAVA_HOME="$HOME/Developer/jdk17"
export ANDROID_HOME="$HOME/Library/Android/sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
cd android
"$HOME/Developer/gradle-9.5.0/bin/gradle" assembleDebug --no-daemon
```

The APK is `android/app/build/outputs/apk/debug/app-debug.apk`. Install it with “Install unknown
apps” allowed for your file manager or browser. Minimum Android 8 (API 26).

## Tests

```bash
cd android
"$HOME/Developer/gradle-9.5.0/bin/gradle" testDebugUnitTest --no-daemon
"$HOME/Developer/gradle-9.5.0/bin/gradle" connectedDebugAndroidTest --no-daemon
```

Unit tests cover distance, stay detection, photo matching, summaries, formatting, the tracking state
machine, backup validation/round-trip, GPX/CSV output, and legacy point/IndexedDB parsing. Room
integration tests verify visit rebuilds, place statistics, and the privacy exclusion window.
Connected tests require a running device or emulator.

## Migration from 1.4.2

On first launch after upgrade, the app reads `filesDir/points.json`, then uses an unattached,
headless WebView only to read the legacy IndexedDB stores (`points`, `photos`, `meta`, `reelState`,
`reelComments`) from the old origin. It writes them into Room and marks migration complete. That
WebView is destroyed immediately and is never shown as UI. Legacy names, units, theme, reel state,
and comments are preserved where the old data contains them.

Known migration limits: legacy hourly/annotation structures that have no direct native equivalent
are approximated; a failed or unavailable IndexedDB read still completes onboarding with whatever
native point data was available. Keep an old JSON backup if you need an exact historical snapshot.

## Known limitations

- Map tiles require network access; there is no bundled offline tile set.
- The reels catalog is local and does not connect to a social service.
- Insights and some history lists use bounded recent views, so very large datasets show the most
  recent items rather than unbounded totals.
- Photo caption/label/favorite editing is display-only in this version; import, matching, and
  attachment work.
- The flashlight depends on device hardware and can be reported unavailable when the camera is in
  use.
