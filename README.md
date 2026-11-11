# FireLite

A fast, modern IPTV client built only for the **Amazon Fire TV Stick 4K Max (Fire OS 7)**.
Kotlin, Jetpack Compose, Media3. The UI language is Polish; code and documentation are English.

FireLite ships **no channels or content**. You connect sources you are allowed to use.

## Playlist refresh and source compatibility

- DASH, HLS and SmoothStreaming factories are bundled at the same Media3 version and retained for
  reflective loading in release builds. Source construction/preparation failures become saved playback
  errors instead of escaping the playback coroutine.
- On startup (or activating a saved source), an app-version or parser-revision change triggers one
  successful playlist re-import, regardless of the normal 24-hour cache. Failed imports retain the
  previous channels/metadata and are retried on the next startup. Settings also offers manual refresh.
- Re-import retains channel IDs for unchanged stream URLs, including duplicate entries; favourites and
  recents remain keyed by stream URL. No playback request is made by the importer.
- Diagnostics records the actual last-import User-Agent, provider `# DEVICE TYPE:` comment and number
  of channels carrying raw catch-up attributes (including header defaults and explicit opt-outs).
  Unknown old-import metadata is shown as `—`. Increment `PlaylistImporter.PARSER_REVISION` when
  changing parser-dependent fields without changing the app version.

## License

FireLite is distributed under GPL-3.0 (see [LICENSE](LICENSE)) because it includes the GPL-3.0
Jellyfin FFmpeg decoder. Keep the license and provide corresponding source, including the decoder,
when distributing builds. Decoder source: https://github.com/jellyfin/jellyfin-androidx-media/tree/v1.5.0+1.
Other bundled dependencies retain their respective licenses.

## Status

`2.2.0-alpha.1`: live TV. An M3U playlist is imported into a local database, browsed by group (favourites,
recently watched, all channels), played with Media3, and can be rewound and fast-forwarded where the stream
allows it. App updates are installed from inside the app.

Start shows the active playlist's history and favourites. Programme Guide offers the XMLTV grid without preview;
Search finds channels by name. Movies and Series are not exposed as placeholder destinations.

Planned (see the concept document): additional source protocols (Xtream Codes, Stalker), movies and series
with posters, seasons, history and resume.

## Live TV

- **Provider compatibility**: M3U guide attributes `x-tvg-url`, `url-tvg`, `tvg-url` accept mixed case,
  double/single quotes or bare values, multiple comma/space-separated feeds and separate option headers.
  XMLTV feeds are streamed in bounded batches, merged by case-insensitive IDs (including `.pl` aliases),
  and duplicate channel/time intervals keep the first feed's programme. Matching also uses saved `tvg-name`
  and normalized display names. Offset-free XMLTV timestamps follow the specification's UTC default.
  With no guide URL, Xtream `get.php` or live paths derive the metadata-only `xmltv.php` endpoint.
- **Archive metadata**: `catchup-type`, header defaults, `tvg-rec`, `timeshift`, `catchup-time` and
  `default/append/shift/flussonic/fs/xc` formats are recognized. `catchup-time` values of at least 86400
  are interpreted as seconds; smaller values as days. A declared archive without retention uses 7 days;
  explicit zero days disables it. Xtream paths use duration rounded up to minutes, the live extension
  (`.ts`/`.m3u8`) and server-local `YYYY-MM-DD:HH-MM` (UTC fallback).
  After parsing an Xtream playlist, including manual and normal daily playlist refreshes, `player_api.php`
  metadata supplies archive retention by stream ID and missing EPG IDs. A working M3U archive wins ties
  or longer retention; otherwise positive API retention enables XC. Explicit M3U zero can therefore be
  upgraded by positive API entitlement. A successful API refresh replaces the cache, including removals;
  failures retain the previous same-account metadata without failing the playlist import. Account changes
  never reuse another account's cache. Both API calls use the ordinary metadata HTTP path, 5-second
  connect/read timeouts, a 15-second deadline per response (plus at most one blocked read timeout),
  a 32-million-character/200,000-row limit and no redirects. JSON is read one object at a time.
  `server_info.timezone` supplies historical DST rules; missing/invalid zones fall back to UTC, while
  a failed server-info request retains the last known timezone. Device or server clock drift is not added
  to absolute programme timestamps. Diagnostics shows API outcomes, `tv_archive` count and the timezone.
  Detection is not proof of provider entitlement. Diagnostic archive type counts aggregate declarations
  with positive retention; they do not probe URLs or validate provider availability.
- **Diagnostics**: Settings → Diagnostyka shows active-source guide URLs, import time/count/error,
  matched channels, archive counts/types and the last crash. Usernames, passwords and query values are
  masked before display/export. The QR contains UTF-8 text limited to 1800 bytes (the crash tail may be
  truncated); scan and copy it with a phone. QR generation is local and never contacts a stream.
- **Screen saver**: the shared PlayerView keeps the screen awake while visible playback is running or
  buffering, including preview/overlay. Pausing, closing or disposing it releases that request.

- **Playlist**: Settings, section "Lista kanalow". Users of FireLite 1.x keep their playlist address automatically.
- **Start**: recent channels and up to 30 favourites from the active playlist; OK starts fullscreen playback.
  No preview/autoplay runs on Start, Programme Guide, Favourites or Search. Back from their fullscreen player
  stops the stream and restores the played row; only Live TV returns to a continuing preview.
- **Programme Guide**: the full-width paged Live TV grid with group switching, but without preview.
  Rewind/Fast-forward shift the window by one hour; Play/Pause jumps to now. Future/unavailable cells cannot play.
- **Favourites**: active-playlist favourites, paged with current EPG; OK plays, long OK removes the favourite.
- **Search**: OK edits the remote-first search field. Queries are debounced 300 ms, limited to 200 channels,
  and scoped to the active playlist. ASCII case and Polish diacritics are normalized on both sides of LIKE;
  SQL wildcard characters in names are literal. Other languages' Unicode case folding is not guaranteed.
- **Browsing**: groups, favourites (Menu button), recently watched. Lists are paged from the database, so tens of
  thousands of channels stay fast.
- **Watching**: OK starts a channel. Up/Down change the channel. Left/Right jump 10 s where the stream is seekable
  (HLS with a server-provided time-shift window). Fast-forward jumps 30 s. Seeking before that window
  enters the **archive (catch-up)** at the requested absolute time when the playlist declares it
  (`catchup`, `catchup-source`, `catchup-days`; types default, append, shift,
  flussonic).
- **Live preview** reuses the single stream and the same PlayerView/ExoPlayer as fullscreen. Focus a channel
  for 800 ms to switch preview; OK expands it without reconnecting. Back shrinks fullscreen into the browser
  and restores focus to the playing channel. Leaving the Live tab stops playback.
- **Fullscreen remote**: Menu or Rewind opens the current channel's programme history; short OK opens the
  channel overlay; long-press OK toggles favourite without opening the overlay.
  Back returns to preview. Up/Down or Channel Up/Down zap; Left/Right seek 10 seconds;
  Play/Pause pauses/resumes live or archive playback; fast-forward seeks 30 seconds or returns to live.
  Long-press Left jumps to the current programme's start when catch-up is available. Archive seeks stay inside
  a seekable archive or re-request it after debounce; reaching the live edge returns to live.
- **Programme history**: a translucent right-side list leaves video full-size behind it. Na żywo is at the top,
  followed by programmes newest first. Up/Down select; one OK starts the programme from its start (clipped to
  the archive/window limit), including the current programme when rewind is available. Na żywo returns live.
  The next programme is shown dimmed above the list, not selectable. Without archive or a seekable window,
  only Na żywo is selectable. Back or Menu closes without changing playback. Opening/browsing history never
  previews, prefetches or probes a stream; only final OK uses the existing gated playback path.
- **Overlay remote**: Up/Down browse without zapping; Right on a channel opens its history;
  OK plays and closes the panel; Menu toggles the focused
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

### Playback compatibility and diagnostics

- Audio selection prefers Polish (`pl`/`pol`), then the device language. Preview video limits remain unchanged.
- Media3 1.5.1 uses the published Jellyfin FFmpeg extension `1.5.0+1`, with extension renderers preferred
  and platform decoder fallback enabled. FFmpeg audio decodes supported formats to PCM in-app;
  device/output capabilities and the provider's actual media formats still determine successful playback.
- Settings diagnostics retain the last handled playback error and the last successful playback snapshot,
  including live/archive/preview mode, selected and available formats, and the audio decoder name.
  Credentials and stream URL paths are hidden before writing either record to internal storage.
  Both compact records precede the guide/crash details in the QR report, capped at 1800 UTF-8 bytes.
- Behind-live-window errors recover at the live edge on the same URL/session. A parsing failure on a
  `.m3u8` URL permits one progressive MPEG-TS/container-sniffing fallback per session, but only before
  any HLS manifest has loaded or playback has reached READY. Segment errors retain HLS; a failed
  progressive attempt restores HLS only if it never reached READY. Once progressive playback succeeds,
  subsequent retries retain that source mode for the session. Recovery stops the
  old load and waits for all local HTTP connections to close before preparing again. A channel switch or
  close cancels pending recovery. The connection gate and 400 ms grace remain unchanged; there are no probes.

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
