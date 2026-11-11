# FireLite

A fast, modern IPTV client built only for the **Amazon Fire TV Stick 4K Max (Fire OS 7)**.
Kotlin, Jetpack Compose, Media3. The UI language is Polish; code and documentation are English.

FireLite ships **no channels or content**. You connect sources you are allowed to use.

## Status

`2.2.0-alpha.1`: live TV. An M3U playlist is imported into a local database, browsed by group (favourites,
recently watched, all channels), played with Media3, and can be rewound and fast-forwarded where the stream
allows it. App updates are installed from inside the app.

Planned (see the concept document): multiple sources (M3U, Xtream Codes, Stalker), a programme guide (XMLTV),
search, movies and series with posters, seasons, history and resume.

## Live TV

- **Playlist**: Settings, section "Lista kanalow". Users of FireLite 1.x keep their playlist address automatically.
- **Browsing**: groups, favourites (Menu button), recently watched. Lists are paged from the database, so tens of
  thousands of channels stay fast.
- **Watching**: OK starts a channel. Up/Down change the channel. Left/Right jump 10 s where the stream is seekable
  (HLS with a server-provided time-shift window). Rewind/fast-forward jump 30 s. Seeking before that window
  enters the **archive (catch-up)** at the requested absolute time when the playlist declares it
  (`catchup`, `catchup-source`, `catchup-days`; types default, append, shift,
  flussonic).
- **Live preview** reuses the single stream and the same PlayerView/ExoPlayer as fullscreen. Focus a channel
  for 800 ms to switch preview; OK expands it without reconnecting. Back shrinks fullscreen into the browser
  and restores focus to the playing channel. Leaving the Live tab stops playback.
- **Fullscreen remote**: Menu opens the channel overlay; long-press OK toggles favourite (short OK shows info).
  Back returns to preview. Up/Down zap; Left/Right seek 10 seconds; rewind/fast-forward seek or enter catch-up.
  Long-press Left jumps to the current programme's start when catch-up is available. Archive seeks stay inside
  a seekable archive or re-request it after debounce; reaching the live edge returns to live.
- **Overlay remote**: Up/Down browse without zapping; OK plays and closes the panel; Menu toggles the focused
  channel's favourite. Left reveals groups, Right returns to channels, Left from groups or Back closes the overlay.

EPG progress follows the absolute time of the watched frame, not the stream duration. The info bar shows the
programme at that time, its successor and a lighter live-edge marker when behind. Server window timestamps
are authoritative; otherwise live offset or the archive request's start plus playback position determines time.
Streams without timestamps use the session start plus position as a best-effort clock. Catch-up cannot create
history a provider does not serve, and the live HLS window is limited by that provider, not the app's session.

The shell keeps one default SurfaceView-backed PlayerView at a stable composition slot and parent, resizing
its bounds rather than re-parenting or swapping surfaces. SurfaceView avoids the extra GPU composition/copy
cost of TextureView for 4K on the Fire TV 4K Max. The view detaches from the player on release. Preview requests
960x540 / 1.5 Mbps maximum tracks; fullscreen lifts these limits. Providers without lower-resolution variants
may still deliver their sole track: track selection cannot transcode or reduce the bitrate of a fixed stream.

## Live programme grid

The Live browser uses a paged 2.5-hour programme grid. Up/Down preserve the selected absolute time;
Left/Right move between programmes, and Left from a channel name returns to its group. Rewind/fast-forward
shift the window by an hour; Play/Pause returns to now. Menu toggles the row's favourite. OK plays a current
programme live or a retained catch-up programme from its start; future/unavailable cells only show details.
The group determines history bounds (at most seven days); future browsing ends at the guide's end, capped
at three days. Focusing a row always previews live, including after leaving archive playback. The fullscreen
overlay remains a simple channel list.

## Hard rule: one stream at a time

The provider allows exactly **one streaming session** (account/IP), not one HTTP request. HLS playlist
refreshes and segment downloads within that session may overlap, but a second channel must never overlap it.
`PlaybackController` is the only code that opens a stream:

- a single player instance owns the only streaming session;
- the old connection is closed *before* a new one opens, and a short grace period lets the server notice;
- requests within one streaming session may overlap (HLS playlists and segments); a new session waits for
  every old request to close, and late requests from superseded media sources are rejected;
- a new prepare waits for all previous HTTP data sources to close; `FireLiteStream` logs open/close ordering
  using opaque URI hashes (never provider URLs or credentials);
- rapid channel changes are merged, only the channel the user stops on connects (`StreamGate` is unit tested);
- the same stream is never opened twice;
- the stream stops when the app is paused (Home, screensaver, system dialogs) and resumes on return.

## Design principles

- **10-foot UI**: everything works with the Fire TV remote; focus is always visible and animated.
- **Reference canvas**: the UI is laid out on a 960 dp wide canvas and scaled to the real screen
  (`ReferenceDensity`), so it looks the same on every TV density.
- **Smooth on modest hardware**: focus effects run in graphics layers (no re-layout), lists are lazy and paged.
- **Localisation**: all user-facing text is in `res/values/strings.xml` (Polish); resource names are English.

## In-app updates

On start, FireLite asks GitHub for the latest release of the repository it was built in. If it is newer,
a banner on the home screen offers **Zainstaluj teraz** (install now); Settings has a manual check.
One press downloads the APK and opens Android's installer.

- Release tags are `build-<N>`; the installed `versionCode` is `100 + N` (see `app/build.gradle.kts`).
- The repository is baked in at build time from `GITHUB_REPOSITORY`; local builds have updates disabled.
- The first time, Android asks you to allow FireLite to install unknown apps.
- The repository must be public so the app can read its releases without a token.

## Build

CI builds on every push (`.github/workflows/build.yml`). Locally you need JDK 17 and the Android SDK:

```
./gradlew :app:testDebugUnitTest :app:assembleRelease
```

The APK is signed with `app/release.keystore`. It is committed on purpose and is **not a secret**
(password `firelite`): it only guarantees that all builds share one signature so updates install over
each other. Use your own private key if you ever distribute the app to others.

## Install on the Fire TV Stick

1. Install **Downloader** on the stick and allow it to install unknown apps.
2. In Downloader open `https://github.com/<OWNER>/<REPO>/releases/latest/download/firelite.apk`.
3. Install. From then on, updates are offered inside the app.

## Layout

```
app/src/main/kotlin/dev/firetvlite/iptv/
  MainActivity.kt, FireLiteApp.kt   entry point and navigation shell
  AppContainer.kt                   manual dependency injection
  data/db, data/playlist, data/library   Room database, M3U parser and importer, channel list repository
  playback/                         single-stream player, catch-up URLs, zapping logic
  update/                           in-app updates from GitHub releases
  ui/viewmodel/                     view models (library, player)
  ui/theme/                         colours, typography, shapes, reference density
  ui/components/                    FocusSurface, buttons, icons, logos, text helpers
  ui/navigation/                    destinations and the navigation rail
  ui/screens/                       home, live TV, player, settings and placeholder screens
gradle/libs.versions.toml           all dependency and plugin versions
```
