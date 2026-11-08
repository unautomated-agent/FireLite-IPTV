# FireLite

Ein schlanker IPTV-Player, geschrieben nur für den **Amazon Fire TV Stick (Fire OS 7)**.
Der Code ist klein und gehört dir: kein Fremdprogramm im Hintergrund, keine Werbung, kein Konto.

FireLite liefert **keine Sender**. Du trägst die Adresse einer M3U-Playlist ein, die du nutzen darfst.

## Was FireLite kann

- M3U-Playlist per Internetadresse laden (http oder https) und nach Gruppen anzeigen
- Sender abspielen mit ExoPlayer (HLS `.m3u8`, rohes `.ts`, MP4 und mehr)
- Mit Hoch/Runter im Bild den Sender wechseln
- Favoriten merken
- Die zuletzt geladene Liste bleibt gespeichert, auch wenn der Anbieter einmal nicht antwortet

Nicht dabei (noch): Programmführer (EPG), Xtream-Login (es geht nur die M3U-Adresse), Suche, Aufnahmen, DASH-Streams.

## Fernbedienung

| Taste | In der Liste | Im Bild |
| --- | --- | --- |
| Hoch / Runter | Auswahl bewegen | Sender wechseln |
| Rechts / Links | zwischen Gruppen und Sendern | – |
| OK | Sender abspielen | Infoleiste zeigen |
| Menü (☰) | Favorit an/aus (bei Sendern), Einstellungen (bei Gruppen) | Favorit an/aus |
| Play/Pause | – | Pause / weiter |
| Zurück | App beenden | zurück zur Liste |

## Installation auf dem Fire TV Stick (mit Downloader)

1. Auf dem Stick die App **Downloader** installieren und ihr erlauben, Apps aus unbekannten Quellen zu installieren
   (Einstellungen → My Fire TV → Entwickleroptionen).
2. In Downloader diese Adresse eingeben (mit deinem Namen und Repo-Namen):
   `https://github.com/DEIN-NAME/DEIN-REPO/releases/latest/download/firelite.apk`
3. Herunterladen, **Installieren**, fertig. Neue Versionen installierst du auf demselben Weg, sie ersetzen die alte.

Das Repo muss **öffentlich** sein, sonst kommt Downloader nicht an die Datei.

## Playlist-Adresse bequem vom PC setzen

Eine lange Adresse mit der Fernbedienung zu tippen ist mühsam. Mit `adb` (Stick im selben WLAN, ADB-Debugging an):

```
adb connect 192.168.x.x
adb shell am start -n dev.firetvlite.iptv/.MainActivity --es url "http://dein-anbieter/playlist.m3u"
```

## Selbst bauen

Jedes Hochladen auf GitHub startet den Bau automatisch (Reiter **Actions**, Datei `.github/workflows/build.yml`).
Dabei laufen zuerst die Tests, dann entsteht die APK und wird als **Release** veröffentlicht.

Lokal (JDK 17 und Android SDK nötig): `sh gradlew :app:testDebugUnitTest :app:assembleRelease`

## Hinweise

- `app/release.keystore` ist ein Signaturschlüssel **nur für diese App** und liegt absichtlich im Repo
  (Passwort `firelite`). Dadurch haben alle Builds dieselbe Signatur und Updates klappen.
  Das ist für den privaten Gebrauch in Ordnung; für eine Veröffentlichung für andere solltest du einen eigenen,
  geheimen Schlüssel verwenden.
- Die App erlaubt unverschlüsselte Verbindungen (http), weil viele IPTV-Anbieter nur so liefern.

## Aufbau

```
app/src/main/java/dev/firetvlite/iptv/
  M3uParser.java        liest die Playlist (getestet)
  PlaylistLoader.java   lädt die Playlist herunter
  MainActivity.java     Liste mit Gruppen und Sendern, Einstellungen
  PlayerActivity.java   Wiedergabe mit ExoPlayer
  Prefs.java, Store.java, Channel.java, Playlist.java
app/src/test/...        Tests für den Parser
```
