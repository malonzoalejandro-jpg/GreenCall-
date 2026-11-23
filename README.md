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
- Nearby Connections supports direct text messages between two GreenCall users within Bluetooth/Wi-Fi radio range. Both users must open the same direct chat, grant Nearby permissions, and have their devices discoverable.
- Direct messages queued on the device are sent when the selected friend connects nearby; they also retry through the server when an internet session reconnects.
- Nearby group messaging, offline live voice, and offline voice-mail transfer are not implemented yet.
- Online unmuted recipients can receive live voice packets over Socket.IO. Muted/offline recipients can receive saved voicemails after they reconnect to the internet.
- The nearby transport currently trusts the selected username advertised by the peer; it is not end-to-end encrypted. Do not use it for sensitive information until identity verification and encryption are added.
- A server cannot deliver messages across an internet outage unless a direct nearby route or another relay exists.

## Build
Open in Android Studio with JDK 17 and Android SDK 35. The app uses the Android build workflow in GitHub Actions.
