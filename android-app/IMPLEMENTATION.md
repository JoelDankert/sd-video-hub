# SD Video Hub Android wrapper

This directory is a standalone Android application. It does not import or package the
Python server. It displays the configured existing site without replacing its UI.

## Build and test

Requirements: JDK 17 or newer and Android SDK Platform 35. If the SDK is not in the
standard location, set `ANDROID_HOME` or create an untracked `local.properties` with
`sdk.dir=/path/to/Android/Sdk`.

```sh
cd android-app
./gradlew --no-daemon testDebugUnitTest assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Install with:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Server tests from the repository root:

```sh
python3 -m unittest test_offline_manifest.py
```

## Design and limits

- Login data is one AES-GCM encrypted payload. Its non-exportable AES key is generated
  and held by Android Keystore; neither preferences nor logs contain plaintext secrets.
- The authenticated `/api/offline-manifest` lists the rendered root document, state API,
  completed media, thumbnails, and the cut-list used by the current web UI. A dedicated
  foreground `dataSync` service owns synchronization, so minimizing or destroying the
  Activity does not cancel it. Sync writes into app-private storage via temporary files
  and publishes each cache entry atomically. Stale entries are removed only after the
  entire new manifest has downloaded.
- Same-origin GETs prefer the authenticated server. On a connection failure, WebView
  receives the cached response. Cached byte ranges produce `206`, `Content-Range`, and a
  bounded stream, so synchronized video remains seekable offline.
- A first successful online login/sync is required before offline use. Successful login
  stores credentials and starts (or reuses) the service; opening the app with stored
  credentials also retries sync. A process-wide guard prevents concurrent runs. The
  service enters the foreground immediately, reports indeterminate/ per-item progress,
  and stops after success or a handled failure. If a sync fails, installed cache entries
  remain usable and stale entries are not pruned, so the next login/open can retry.
- Android 13+ asks for notification permission once, after a successful login. Denial
  does not block the foreground service: Android still tracks the foreground task, while
  the bottom-right in-app status explains that notification display is unavailable.
  Exact foreground-task presentation remains controlled by the Android version and the
  user's notification settings.
- Server-created, one-time flight-only exports are intentionally not manifest resources.
  Dynamically injected third-party resources are out of scope and are not cached.
- Cleartext HTTP is enabled because the required default is a private-LAN HTTP URL.
  Android Keystore protects credentials at rest, but HTTP cannot protect them in transit;
  use an HTTPS base URL on untrusted networks.

Launcher icon attribution is in `licenses/ICON-LICENSE.md`; all artwork is bundled and
the app performs no runtime icon download.
