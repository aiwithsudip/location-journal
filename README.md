# Location journal

Android app that draws the path you walk, saves a position every 20 seconds plus one hourly fix, maps places you stayed for 30 minutes or more, and plays photos from those visits.

Tracking keeps a notification on screen. Take a photo, or turn on a photo every 20 seconds. Load sample day fills the map with a practice route. Photos and the location log stay on the phone.

The debug APK is attached to the GitHub release. Install it with “Install unknown apps” allowed for your browser or file manager. Android 8 or newer.

Open the project in Android Studio from the `android` folder, or build with the Android SDK:

```
cd android
gradle assembleDebug
```

The installable file is `android/app/build/outputs/apk/debug/app-debug.apk`.
