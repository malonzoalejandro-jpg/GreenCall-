# GreenCall API

Node.js + Express + Socket.IO + PostgreSQL service for shared GreenCall accounts, friend discovery, persistent conversations, groups, offline message storage, voicemail storage, and live voice packet relay.

## Deploy on Railway

1. Create a Railway project from this repository.
2. Add a PostgreSQL service.
3. Add a service using the repository root and set the Dockerfile path to `server/Dockerfile`.
4. Add `DATABASE_URL` from the Railway PostgreSQL service and a strong random `JWT_SECRET`.
5. Deploy. Railway provides `PORT`; the API listens on it and exposes `/health`.

The schema is applied at startup. Back up PostgreSQL before production upgrades.

## Endpoints

- `POST /v1/auth/register` — displayName, username, password
- `POST /v1/auth/login` — username, password
- `GET /v1/users/search?name=` — searches display names
- `POST /v1/friends/:friendId`, `GET /v1/friends`
- `POST /v1/conversations/direct`
- `POST /v1/groups`, `GET /v1/conversations`
- `GET/POST /v1/conversations/:id/messages`
- `GET /v1/voicemails`, `POST /v1/voicemails`, `GET /v1/voicemails/:id/audio`
- Socket.IO events: `conversation:join`, `message:new`, `voice:start`, `voice:chunk`, `voice:end`, `voicemail:new`

Clients authenticate REST with `Authorization: Bearer <token>` and Socket.IO with `auth: { token }`.

## Important behavior

Messages are stored in PostgreSQL, so recipients can fetch them after reconnecting. Voice packets are forwarded only to authorized conversation members currently connected; the app must save muted/offline audio as a voicemail via the voicemail endpoint. This server does not create a radio path when devices have no internet. Nearby Connections transport and end-to-end media encryption still need to be implemented in the Android client before describing voice as production-ready.

## Security notes

Passwords use bcrypt cost 12; API tokens expire after 30 days. Use a strong unique JWT secret, TLS, restricted CORS, backups, and a production object-storage strategy before scaling. Current voicemail payload storage is intentionally size-limited and PostgreSQL-backed for the first deployment.
