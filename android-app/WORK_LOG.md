# Work log

## Completed

- Added Android WebView HTML5 video fullscreen support using the standard
  `WebChromeClient` custom-view contract. The site's existing video view is layered over
  the Activity root, system bars become transient-swipe immersive, and their independent
  prior visibility plus controller behavior are restored on exit. Back exits native
  fullscreen before retaining the existing trusted viewer-close/root-consume behavior,
  and guarded session state ensures each custom-view callback is completed once.
- Added focused JVM coverage for rejecting overlapping fullscreen sessions and making
  fullscreen exit idempotent. The site video markup remains the existing single
  `controls playsinline` player; server and cache routing were unchanged.

- Fixed legacy cache-index migration so an existing hashed cache file supplies its
  actual byte length as the runtime expected size. Cached shell/state can render
  immediately after upgrade, while manifest planning still redownloads same-version
  entries when the remote byte size differs and never treats a missing file as complete.

- Corrected saved-login startup to render a complete cached root/state immediately and
  defer new sync enqueueing until that page is visibly committed. Cache-first sessions
  now serve every verified local resource while online, then reload from cache after a
  successful independent WorkManager run.
- Added expected byte size to durable cache records with safe legacy parsing. Cache
  completeness and sync planning now require matching version, manifest size, and exact
  on-disk length; a missing/wrong-size file can no longer produce `Up to date`.
- Made active unique work outrank stale completed history, with terminal status accepted
  only for a run observed active in this Activity. Foreground progress announces the
  actual `Syncing N item(s)` after the remote plan is known.
- Preloaded WebView HTTP-auth credentials for the configured host and `SD Video Hub`
  realm while retaining the matching auth callback. Verified routing keeps complete
  media local even online and leaves uncached online media to native WebView streaming.
- Added unit coverage for byte-size planning/migration, cache-first routes, and active
  WorkInfo selection. Existing cached-state filtering and Back viewer-close behavior
  remain intact.

- Replaced the custom `OfflineSyncService`/broadcast/pending-flag model with a unique
  persisted WorkManager job using `KEEP`, connected-network constraints, foreground
  `dataSync` execution only while running, observable progress, and exponential retry.
- Made the cache index durable with fsynced temporary writes and last-known-good backup
  recovery. Sync planning now ignores index records whose content file is absent, keeps
  completed content on temporary failures, and prunes only after a wholly successful run.
- Enforced the media rule: cached `/media/` always comes from local storage; online
  uncached media remains entirely in native WebView loading. Offline cached state still
  filters out every item without complete cached media.
- Added focused recovery/planning and exact routing tests; retained cached-state coverage.

- Added the standalone Kotlin Android project, Android Keystore AES-GCM login storage,
  authenticated WebView loading, manifest synchronization, app-private atomic cache,
  online-first/offline-fallback interception, and seekable cached range responses.
- Added the authenticated server manifest and end-to-end server tests.
- Replaced the Font Awesome launcher artwork with the vendored Phosphor Icons regular
  Drone SVG, updated the MIT attribution, and regenerated adaptive and legacy assets.
- Moved the WebView synchronization indicator to the bottom-right with a 12 dp margin
  plus the system bottom inset.
- Extracted deterministic manifest/cache planning and added JVM coverage for changed,
  new, unchanged, and stale entries.
- Made offline login and WebView routing use Android's validated-network state, filtered
  cached state to complete media, and left uncached online streams to native WebView.
- Added trusted same-origin viewer-only Back handling for legacy and Android 13+ APIs;
  Back at the site root now keeps the Activity open.
- Added JVM coverage for representative cached-state filtering and stream/cache routing.

## Verification (2026-10-17)

- `./gradlew --no-daemon testDebugUnitTest assembleDebug`: **passed**, 41 tasks
  (8 executed, 33 up-to-date) in 15s for WebView native video fullscreen support.
  JVM result: **20 tests, 0 failures, 0 errors**. Debug APK: 5,343,790 bytes,
  SHA-256 `ec92d08e05102edb9e9ab12caa8c93f054fc8f6324d862a1b5a4724a49801d03`.
  Gradle reported only the existing deprecation warnings.

- `./gradlew --no-daemon testDebugUnitTest --tests com.sdvideohub.app.CacheIndexStoreTest
  --tests com.sdvideohub.app.SyncPlannerTest`: **passed** after the legacy-file
  effective-size regression fix.
- `./gradlew --no-daemon testDebugUnitTest assembleDebug`: **passed**, 41 tasks
  (2 executed, 39 up-to-date) in 11s. JVM result: **18 tests, 0 failures, 0 errors**.
  Debug APK: 5,343,790 bytes, SHA-256
  `ec673119dc7634a8f184000d3a0f76b8db65d6a12c6808a01cff49c255093701`.
  Gradle reported only the existing deprecation warning.

- `./gradlew --no-daemon testDebugUnitTest assembleDebug`: **passed**, 41 tasks
  (8 executed, 33 up-to-date) in 15s after the final corrective cache/auth/status pass.
  JVM result: **18 tests, 0 failures, 0 errors**. Debug APK: 5,343,790 bytes,
  SHA-256 `0c0faaadf2ca9ae3fc15d91cbfc55735fc677626778eb407ea7c7ceb83da9787`.
  Gradle reported only the existing API/Gradle deprecation warnings.
- `adb devices -l` returned no attached devices. No connected physical-device install,
  launch, login, streaming, or Back-navigation test was possible.

- `./gradlew --no-daemon testDebugUnitTest assembleDebug`: **passed**, 41 tasks
  (7 executed, 34 up-to-date) in 13s for the final WorkManager/cache durability revision.
  JVM result: **13 tests, 0 failures, 0 errors**. The first run failed compilation due
  to a missing `IOException` import; after correcting it, the exact requested command
  passed. Debug APK: 5,343,790 bytes, SHA-256
  `29f028453d5568fdf2ea6d6bdcd99dfc42871f1de457c566ca44906ebeee9c25`.
  Gradle reported its existing Gradle-9 deprecation notice only.

- `./gradlew --no-daemon testDebugUnitTest assembleDebug`: **passed**, 41 tasks
  (7 executed, 34 up-to-date) after the final offline routing, sync durability, and Back review.
- JVM result: **11 tests, 0 failures, 0 errors**. Updated debug APK SHA-256:
  `4ded767d80e71bafd0c303a60c4345da091a3987994575a7f608d48632608441`.
- The intentional tests-first run failed at test compilation because
  `CachedStateFilter`, `RequestRouting`, and `RequestRoute` had not yet been implemented.

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
