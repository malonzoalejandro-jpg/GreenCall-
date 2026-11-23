# GreenCall

GreenCall is an Android messaging and walkie-talkie app project built with Kotlin and Jetpack Compose.

## Current Android build

The app now includes:
- A custom native vector launcher icon and a refreshed forest-green interface.
- Email registration and sign-in stored locally on the device.
- Empty-by-default Chats, Friends, and Groups screens (no seeded demo people).
- Local contact saving, local group creation, and conversation UI.
- A mute preference and clear offline/device-mode status.

## Important limitations

This build is an offline UI and local-account foundation, not a deployed multi-user messaging service.

- Local accounts are device-local and are not synced across phones.
- Adding a friend currently saves a local contact; it does not send a network friend request.
- Group creation is local; inviting other accounts requires the online service.
- Chat composition is a local UI preview; messages are not delivered to another device yet.
- Google sign-in requires a Google OAuth Web Client ID and a backend that validates Google ID tokens. These credentials are not present in this repository, so the Google button explains the missing setup instead of pretending to authenticate.
- Live walkie-talkie audio, voicemail recording/playback, Nearby Connections transport, end-to-end encryption, and relay forwarding are not yet connected.

## Transmission behavior target

- Unmuted + connected recipient: play incoming live voice.
- Muted: save incoming voice as a voicemail.
- No route: keep messages queued locally until a direct, opted-in relay, or internet route exists.
- Relay devices must only forward encrypted packets and must opt in.

Offline does not mean unlimited distance: a disconnected phone cannot deliver a live transmission across an island or country without a network route.

## Build

Open the project in Android Studio with JDK 17 and Android SDK 35, sync Gradle, and run on Android 8.0+.

GitHub Actions builds a debug APK on every push and uploads it as `GreenCall-debug-apk` when the build succeeds.
