<div align="center">

# 🎧 Listen

**Music meets cinema.**
A premium Android player: streaming-app polish for music, VLC-grade power for video.

![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.1-7F52FF?logo=kotlin&logoColor=white)
![Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?logo=jetpackcompose&logoColor=white)
![Media3](https://img.shields.io/badge/Media3-ExoPlayer-FF6A3D)
![libVLC](https://img.shields.io/badge/libVLC-3.6-FF8800?logo=vlcmediaplayer&logoColor=white)
![Version](https://img.shields.io/badge/version-1.6.0-8B5CF6)

</div>

---

## ✨ What it does

| 🎵 Music | 🎬 Video | 📻 Radio |
|---|---|---|
| Local library, albums, artists, playlists | Plays every codec (MKV, HEVC, AC3, DTS…) | Vintage tuner UI with knobs + dial |
| Spinning-CD now playing + synced lyrics | Multi audio tracks + all subtitle formats | All India Radio + 40k stations |
| Free streams: Audius · Jamendo · Archive | Brightness / volume / seek / zoom gestures | Presets, scan, sleep timer |
| Smart search, mixes, suggestions | Picture-in-picture, resume, delete | Live HLS streams |

**Plus:** 3 colour themes per mode · equalizer · background playback · offline-first · no ads, no accounts.

---

## 🛠 Tech

| Layer | Stack |
|---|---|
| UI | Kotlin · Jetpack Compose · Material 3 · Navigation Compose |
| Music engine | Media3 ExoPlayer + MediaSession (HLS, lock screen, notification) |
| Video engine | libVLC (hardware decoding, tracks, subtitles) |
| Data | Room · DataStore · MediaStore |
| Images | Coil 3 (+ embedded-art fetcher, OkHttp) · Palette |
| Online | JioSaavn · Audius · Jamendo · Openverse · FreeToUse · Incompetech · ccMixter · Archive · Deezer · iTunes · Radio Browser · LRCLIB |
| AI Engine | Groq (Llama / Qwen ~200ms completions) + Pollinations fallback |

> API endpoints are mapped at the top of `data/online/OnlineSources.kt` (`[API-1…17]`).

---

## 🔑 Release Signing & Update Compatibility

To prevent Android package signature conflicts (`INSTALL_FAILED_UPDATE_INCOMPATIBLE` / *"Package conflicts with an existing package"*):
- Release builds use the project keystore located at [`app/listen.keystore`](app/listen.keystore).
- **Certificate SHA-256:** `DE:1B:55:0C:FA:FF:1F:1F:35:68:11:FA:50:6C:D5:C9:AC:E6:FD:C2:8E:D5:9A:20:73:E8:A2:D6:3E:EF:88:88`
- **Certificate SHA-1:** `07:5A:2E:44:5E:60:05:56:67:69:F0:32:AD:36:9B:2E:71:7A:72:49`
- Keystore credentials: Store password `android`, key alias `androiddebugkey`, key password `android`.
- Every release APK (v1.5.0 and all future releases) built on any workstation or CI server is signed with this exact certificate, guaranteeing seamless in-place updates.

---

## 🤖 AI Music Suggestions

Listen includes an AI recommendation and auto-complete engine:
1. **Search Auto-Suggest:** As you type queries (>= 2 characters), Groq AI generates instant, context-aware song and artist suggestions in parallel with Google QuerySuggest.
2. **Suggested for You:** On the Home screen, the AI analyzes your listening history, favourites, and top artists to generate 18 tailored recommendations, resolving them to playable full streams across JioSaavn, Audius, Jamendo, Openverse, FreeToUse, and previews.

To configure fast AI (~200ms) completions:
Add your free Groq API key to `secrets.properties` in the project root:
```properties
GROQ_API_KEY=gsk_...
JAMENDO_CLIENT_ID=your_id
```
*(If no key is provided, the app automatically falls back to keyless Pollinations).*

---

## 🚀 Build

```bash
# Clone the repository
git clone https://github.com/your-repo/Listen.git
cd Listen

# Optional: add secrets.properties for API keys (git-ignored)
# GROQ_API_KEY=...
# JAMENDO_CLIENT_ID=...

# Build signed release APKs
./gradlew assembleRelease
```
The output APKs are generated in `app/build/outputs/apk/release/`:
- `app-arm64-v8a-release.apk` (recommended for standard 64-bit devices)
- `app-universal-release.apk` (universal APK supporting all ARM architectures)

---

<div align="center">

Designed & built by **Rajdeep Biswas** · Kolkata, India

</div>
