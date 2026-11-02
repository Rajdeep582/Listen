<div align="center">

# 🎧 Listen

**Music meets cinema.**
A premium Android player: streaming-app polish for music, VLC-grade power for video.

![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.1-7F52FF?logo=kotlin&logoColor=white)
![Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?logo=jetpackcompose&logoColor=white)
![Media3](https://img.shields.io/badge/Media3-ExoPlayer-FF6A3D)
![libVLC](https://img.shields.io/badge/libVLC-3.6-FF8800?logo=vlcmediaplayer&logoColor=white)
![Version](https://img.shields.io/badge/version-1.4.0-8B5CF6)

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
| Online | Audius · Jamendo · Deezer · iTunes · Internet Archive · Radio Browser · LRCLIB |

> API endpoints are mapped at the top of `data/online/OnlineSources.kt` (`[API-1…10]`).

---

## 🚀 Build

```bash
# secrets.properties (project root, git-ignored)
JAMENDO_CLIENT_ID=your_id

./gradlew assembleRelease
```

---

<div align="center">

Designed & built by **Rajdeep Biswas** · Kolkata, India

</div>
