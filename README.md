# GreenCall

GreenCall is an Android-first offline/online messaging and walkie-talkie project.

## Current state

This repository starts with an Android Jetpack Compose UI prototype. It demonstrates the green interface, conversation/group/friends/settings navigation, mute control, and a push-to-talk interaction state. It is **not yet a working messaging service**: authentication, microphone capture/playback, live audio transport, nearby discovery, relay routing, encryption, and cloud delivery still need implementation and testing.

## Agreed transmission behavior

- **Unmuted + connected to recipient:** transmit live audio immediately.
- **Muted + sender online:** do not interrupt the recipient; save the transmission as a voice message/voicemail.
- **Sender offline:** save it as a queued message and forward it when a direct nearby connection, opted-in relay route, or internet connection becomes available.
- **No route:** keep it queued locally. Offline does not mean unlimited distance.
- **Relay privacy:** only encrypted packets should be forwarded, and relay participation must be opt-in.

A remote offline live call cannot cross a disconnected country or island. Store-and-forward voice clips can travel over a chain of participating devices, but delivery waits until a route exists.

## Build

Open this folder in Android Studio (JDK 17, Android SDK 35), allow Gradle sync, then run the app on an Android 8.0+ device or emulator.

GitHub Actions builds a debug APK on every push and uploads it as an Actions artifact when the build succeeds.

## Engineering milestones

1. Persist conversations and outbox with Room.
2. Implement foreground microphone capture and real-time audio session management.
3. Implement Nearby Connections discovery/transfer and explicit relay opt-in.
4. Implement authenticated backend, account registration, friend/group APIs, WebSocket live delivery and push notifications.
5. Add end-to-end encryption, replay protection, delivery receipts, retry/backoff, and abuse controls.
6. Test on multiple physical Android devices and different OEM background restrictions.

Never ship credentials or private signing keys in the repository.
