# FireLite

A fast, modern IPTV client built only for the **Amazon Fire TV Stick 4K Max (Fire OS 7)**.
Kotlin, Jetpack Compose, Media3. The UI language is Polish; code and documentation are English.

FireLite ships **no channels or content**. You connect sources you are allowed to use.

## Status

`2.0.0-alpha.1` is the foundation: toolchain, design system, navigation and demo screens.
It contains **no player and makes no network requests**; all content on screen is demo data.

Planned (see the concept document): multiple sources (M3U, Xtream Codes, Stalker), live TV with
programme guide (XMLTV), search, movies and series with posters, seasons, history and resume.

## Hard rule: one stream at a time

The provider allows exactly **one** simultaneous connection and blocks the account on a second one.
The player (added in a later package) is built around this:

- a single player instance owns the only stream connection;
- the old connection is closed *before* a new one opens, never the other way round;
- the same channel is never opened twice (previews reuse the playing player);
- the stream stops immediately when the app goes to the background.

## Design principles

- **10-foot UI**: everything works with the Fire TV remote; focus is always visible and animated.
- **Reference canvas**: the UI is laid out on a 960 dp wide canvas and scaled to the real screen
  (`ReferenceDensity`), so it looks the same on every TV density.
- **Smooth on modest hardware**: focus effects run in graphics layers (no re-layout), lists are lazy.
- **Localisation**: all user-facing text is in `res/values/strings.xml` (Polish); resource names are English.

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
3. Install. New builds update the old one.

## Layout

```
app/src/main/kotlin/dev/firetvlite/iptv/
  MainActivity.kt, FireLiteApp.kt   entry point and navigation shell
  ui/theme/                         colours, typography, shapes, reference density
  ui/components/                    FocusSurface, buttons, icons, text helpers
  ui/navigation/                    destinations and the navigation rail
  ui/screens/                       home, live TV and placeholder screens
gradle/libs.versions.toml           all dependency and plugin versions
```
