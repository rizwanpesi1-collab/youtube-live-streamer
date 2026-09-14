# YouTube Live Streamer

Android-first Flutter app that:

- loads `https://anpesi1-collab.github.io` in an in-app WebView;
- displays it in a fixed 400 logical-pixel capture area;
- accepts a YouTube stream key;
- requests Android screen-capture permission and starts/stops a foreground RTMP stream;
- crops the stream source to the top of the display so the controls below the website are not streamed.

## Run

From this directory on a machine with Flutter and Android SDK installed:

```bash
flutter pub get
flutter run
```

The stream destination is YouTube's standard RTMP endpoint:

`rtmp://a.rtmp.youtube.com/live2/<stream-key>`

The Android implementation uses MediaProjection and the mobile-ffmpeg engine behind
the requested `flutter_ffmpeg` package. Android displays a system confirmation dialog
before each capture session. Keep the stream key private.

This project is intentionally Android-first: iOS requires ReplayKit broadcast
extension setup and cannot use Android's MediaProjection service.