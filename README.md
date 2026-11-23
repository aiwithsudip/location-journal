# Location journal

Android app that saves your location once an hour, maps places you stayed for 30 minutes or more, and plays gallery photos from those visits as slideshows.

Tracking keeps a notification on screen and records a fix about every five minutes so a half-hour stay can be seen. Photos and the location log stay on the phone.

The debug APK is attached to the GitHub release. Install it with “Install unknown apps” allowed for your browser or file manager. Android 8 or newer.

Open the project in Android Studio from the `android` folder, or build with the Android SDK:

```
cd android
gradle assembleDebug
```

The installable file is `android/app/build/outputs/apk/debug/app-debug.apk`.
