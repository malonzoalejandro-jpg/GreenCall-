# GreenCall

GreenCall is an Android messaging and walkie-talkie app built with Kotlin and Jetpack Compose, with a Node.js + PostgreSQL realtime backend.

## Android app
- Registration uses display name, username, and password (no Google sign-in).
- Friend discovery is designed around registered display names; results show the @username.
- Private and group conversations, message history, and mute settings are part of the Android client.
- GitHub Actions builds a debug APK on every push and uploads it as `GreenCall-debug-apk`.

## Backend
The `server/` directory contains the REST + Socket.IO API, PostgreSQL schema, Dockerfile, and Railway deployment configuration. See [server deployment guide](server/README.md).

The backend supports shared registration/login, display-name search, friends, direct and group conversations, persisted text messages, voicemail storage, and authorized live voice packet forwarding. It must be deployed and its URL configured in the Android app before cross-device online features are available.

## Offline behavior
- Online messages are stored on the server and can be fetched after reconnecting.
- Unmuted connected recipients can receive live voice packets over Socket.IO.
- Muted/offline recipients can receive saved voicemails once connected.
- When internet is unavailable, messages/audio need a direct nearby route or an opted-in relay. The server cannot transmit across a network outage or across islands without any route.
- Nearby Connections transport, on-device audio capture/playback, and end-to-end media encryption remain client-side work and are not claimed as complete.

## Build
Open in Android Studio with JDK 17 and Android SDK 35. The app uses the Android build workflow in GitHub Actions.
