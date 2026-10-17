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
- With a validated active Android network, same-origin document/API GETs prefer the
  authenticated server and fall back to cache. Without one, GETs go directly to cache;
  there is no timeout or fixed delay. If saved credentials and a complete cached root
  exist, Login opens it immediately without manifest I/O. Android's validated-network
  signal can still lag unusual captive-portal or VPN transitions.
- Cached `/api/state` responses retain the original top-level state but filter `files`
  to entries whose `video_url` has an atomically installed cache entry. Partial `.part`
  downloads and missing media therefore do not create offline cards.
- Online uncached `/media/` and generated `/flight-download/` requests are left to the
  native WebView pipeline (including its Basic-auth callback). Cached media can fall
  back after a failed online request. Offline cached byte ranges produce `206`,
  `Content-Range`, and a bounded stream, so synchronized video remains seekable.
- A first successful online login/sync is required before offline use. Successful login
  stores credentials and starts (or reuses) the service; opening the app with stored
  credentials also retries sync. A process-wide guard prevents concurrent runs. The
  sync request is persisted before service launch, and redelivered after ordinary process
  interruption. The service enters the foreground immediately, reports indeterminate/
  per-item progress, prevents concurrent runs, and removes its notification on every
  handled finish. Success clears the pending flag; failure retains it for the next
  online login/open retry. Removing the Activity task does not stop active sync because
  `stopWithTask=false`. Android force-stop is intentionally not survivable.
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
- While the trusted same-origin site is displayed, legacy Back and Android 13+
  predictive Back call the site's existing `closeViewer()` only when `#viewer` is
  visible. Back at the site root is consumed and does not finish the Activity.

Launcher icon attribution is in `licenses/ICON-LICENSE.md`; all artwork is bundled and
the app performs no runtime icon download.
