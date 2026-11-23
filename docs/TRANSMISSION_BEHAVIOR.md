# GreenCall transmission behavior

| Recipient state | Sender connection | Expected behavior |
|---|---|---|
| Unmuted and reachable | Direct live session | Play live walkie-talkie audio |
| Muted | Online | Store as voice message; do not autoplay |
| Unmuted but not reachable | Online | Queue a voice message and deliver when reachable |
| Any mute state | Offline | Queue locally; deliver via direct/opt-in relay/internet when route returns |
| No route exists | Offline | Keep queued; never claim delivered |

When muted, the recipient should see a voicemail item and can play it manually. The sender's online state alone does not guarantee the recipient is online or reachable. A live session requires both endpoints to be connected and the session established.
