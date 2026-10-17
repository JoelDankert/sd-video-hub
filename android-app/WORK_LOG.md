# Work log

## Completed

- Added the standalone Kotlin Android project, Android Keystore AES-GCM login storage,
  authenticated WebView loading, manifest synchronization, app-private atomic cache,
  online-first/offline-fallback interception, and seekable cached range responses.
- Added the authenticated server manifest and end-to-end server tests.
- Replaced the Font Awesome launcher artwork with the vendored Phosphor Icons regular
  Drone SVG, updated the MIT attribution, and regenerated adaptive and legacy assets.
- Moved the WebView synchronization indicator to the bottom-right with a 12 dp margin
  plus the system bottom inset.
- Replaced Activity-owned cache synchronization with a non-exported foreground data-sync
  service, compact progress notification, duplicate-run guard, lifecycle-safe status
  broadcasts, Android 13 notification permission handling, and retry-on-open behavior.
- Extracted deterministic manifest/cache planning and added JVM coverage for changed,
  new, unchanged, and stale entries.

## Verification (2026-10-17)

- `./gradlew --no-daemon testDebugUnitTest assembleDebug`: **passed**, 41 tasks
  (8 executed, 33 up-to-date) after the final foreground-sync changes.
- Updated APK: `app/build/outputs/apk/debug/app-debug.apk`, 2,676,828 bytes,
  SHA-256 `f1df0ffe184dd25eeafc4f44e5496c2a6122ee74daad5e0d51fe8ae689174321`.
- `python3 -m unittest -v test_offline_manifest.py`: **passed**, 2 tests.
- `python3 -m py_compile app.py test_offline_manifest.py`: **passed**.
- `./gradlew --no-daemon testDebugUnitTest assembleDebug`: **passed**, 41 tasks
  (14 executed, 27 up-to-date).
- APK: `app/build/outputs/apk/debug/app-debug.apk`, 2,621,457 bytes,
  SHA-256 `4c7cd63ebf368b6bc3657559f90fc2150f64bf70fdc65dc0c46d4dbc20f7bcf1`.

## Build notes

The first sandboxed server-test attempt could not bind localhost (`PermissionError:
Operation not permitted`); the identical test was rerun with localhost permission and
passed. The first Gradle attempt could not write the read-only user Gradle cache, and the
next exposed a missing SDK location. Permission was granted for the existing cache and an
ignored `local.properties` now points to `/home/joeld/Android/Sdk`; the requested final
build then passed. Gradle reports only its general Gradle-9 deprecation notice; it does not
prevent this Gradle 8.14.3 build. No requested verification remains unable to run.
