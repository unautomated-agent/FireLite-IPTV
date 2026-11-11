# Live TV and crash-report verification

## Environment

- Android TV emulator, API 29, x86, 1920 × 1080.
- Synthetic database fixture: 123 channels in 11 named groups; no provider credentials.
- JDK: `/usr/lib/jvm/java-17-openjdk-amd64`.
- Android SDK: `/home/ubuntu/android-sdk` (`ANDROID_HOME`).
- Build: `bash ./gradlew -I /home/ubuntu/firelite-local-repositories.init.gradle --no-daemon assembleDebug testDebugUnitTest`.
- The local init script substitutes Google's Maven Central mirror because Maven Central returned HTTP 429. It is not part of the project.

## Results

- Switched down from All to Sport to Informacje, then up to Sport. The new group's first channel stays at the same vertical position; the list resets to the top.
- Entered Sport with Right, moved down to Sport 11, then pressed Left. Focus returned to Sport, not the geometrically adjacent group.
- Pressed Right again: focus moved to Sport 01.
- Verification exposed a repeated initial-focus effect that restored All shortly after selecting Sport. A one-time initialization guard fixes that focus theft; temporary diagnostic logging was removed.
- Observed correct labels for 0, 1, 2, 3, 4, 5, 12, 13, 14, 22, 23 and 24 channels.
- Loaded a synthetic crash report in Settings, focused and scrolled its trace, then moved Right and cleared it. The file and section disappeared.
- Crash writing, metadata, previous-handler delegation, missing reports, clearing, and write-failure delegation are covered by JVM tests. No deliberate runtime crash was injected into the emulator.
- Final crash buffer: 0 bytes. The original real Fire TV crashes remain unconfirmed.

Build output:

```text
BUILD SUCCESSFUL in 25s
44 actionable tasks: 10 executed, 34 up-to-date
```

JUnit XML summary: 47 tests, 0 failures, 0 errors, 0 skipped.

## Screenshot evidence

Host directory: `/home/ubuntu/Pictures/firelite-step2/` (not packaged in the APK).

| File | Verified state |
| --- | --- |
| `01-sport-selected.png` | Selected Sport, list at top |
| `02-channels-scrolled.png` | Sport 11 focused after scrolling |
| `03-left-selected-group.png` | Left returns focus to Sport |
| `04-new-group-top.png` | Informacje selected, list reset to first channel |
| `05-right-first-channel.png` | Right returns to Sport 01 |
| `06-polish-plurals.png` | Polish counts including 1, 2, 4, 13, 14 and 23 |
| `08-crash-report.png` | Focusable crash trace with metadata |
| `09-crash-report-scrolled.png` | Trace scrolled with D-pad Down |
| `10-crash-cleared.png` | Settings after deleting the report |

Build log: `/home/ubuntu/Downloads/firelite-step2-build.log`.
