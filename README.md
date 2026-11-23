# Location journal

Android app (1.3.0) that draws the path you walk, saves a position every 20 seconds plus one hourly fix, maps places you stayed for 30 minutes or more, and keeps photo copies from those visits.

The map is the main screen. Route, Stays, Photos, and Hourly sit on the bottom bar. Settings holds distance units, appearance, map rings, follow-on-start, optional auto photo, a versioned local backup, and delete. Choose photos uses the Android photo picker. The flashlight is an explicit control in the installed app and turns off when you leave the camera or the app. Nothing is uploaded.

Tracking keeps a notification on screen. Load sample day fills the map with a practice route. Photos and the location log stay on the phone.

The debug APK is attached to the GitHub release. Install it with “Install unknown apps” allowed for your browser or file manager. Android 8 or newer.

Open the project in Android Studio from the `android` folder, or build with the Android SDK:

```
cd android
gradle assembleDebug
```

The installable file is `android/app/build/outputs/apk/debug/app-debug.apk`.
