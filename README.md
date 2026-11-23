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
- Nearby Connections supports direct text and push-to-talk PCM audio between GreenCall users in radio range. Direct chats and groups can connect to the selected friends' usernames without using the server.
- Both devices must have GreenCall open in the conversation, grant Nearby/Bluetooth permissions, enable Nearby mode, and remain within Bluetooth/Wi-Fi radio range.
- Incoming nearby voice is played immediately when unmuted. When muted, the receiving device stores the audio locally in its voicemail list.
- Text messages queued on-device retry through the server when internet connectivity returns. Nearby transfers are direct and do not need the server.
- Offline background delivery to a device that is out of range is not possible without another connected relay. Background push-to-talk and offline voice-mail relay are not implemented.
- Online unmuted recipients can receive live voice over Socket.IO. Muted/offline recipients can receive server-saved voicemails after reconnecting to the internet.
- Nearby traffic currently uses the platform transport without GreenCall end-to-end encryption or verified cryptographic identity. Do not use it for sensitive information yet.

## Build
Open in Android Studio with JDK 17 and Android SDK 35. The app uses the Android build workflow in GitHub Actions.
