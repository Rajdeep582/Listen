# Listen account API

The Android app talks to this service; Atlas credentials must stay on the server. On startup the service selects the separate `listen_app` database and creates only its required indexes. It does not drop or alter any other database.

## Run the service

1. Install Node.js 22.x and run `npm install` in this directory.
2. Copy `.env.example` to `.env`, then set `MONGODB_URI` and a random `JWT_SECRET` of at least 32 characters. Keep `.env` private; it is ignored by Git.
3. Run `npm start`.
4. Deploy behind an HTTPS endpoint before connecting a release Android build.

Set `ACCOUNT_API_BASE_URL` in the root `secrets.properties` to the deployed base URL, ending in `/`, then rebuild the app. The default placeholder intentionally fails closed. Plain HTTP is accepted only in debug builds.

## API

- `POST /v1/auth/signup`, `POST /v1/auth/signin`, `GET /v1/me`
- `GET /v1/favorites`, `PUT /v1/favorites`, `DELETE /v1/favorites/:songId`
- `GET /v1/history`, `POST /v1/history` (keeps 15 most recent unique songs)
- `GET /v1/searches`, `POST /v1/searches` (keeps 20 recent unique queries)
- `GET /v1/preferences`, `PUT /v1/preferences`

All account routes except signup/signin require a bearer token. Passwords are hashed with bcrypt before storage. User-owned records include the authenticated MongoDB user ID and are indexed so accounts cannot see one another's data.

Signup accepts passwords from 6 to 128 characters and usernames from 3 to 24 letters, numbers, or underscores.
