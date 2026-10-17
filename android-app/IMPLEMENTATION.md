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
  completed media, thumbnails, and the cut-list used by the current web UI. A unique,
  persisted WorkManager one-time request owns synchronization independently of the
  Activity. It requires a connected network, uses exponential retry backoff, and keeps
  an already active request when enqueue is called again. Minimizing, destroying, or
  removing the Activity task does not cancel it. Android force-stop is intentionally not
  survivable; Android will resume eligible persisted work after the app is used again.
- The Worker becomes a foreground `dataSync` task only after it actually starts, updates
  per-item progress, and WorkManager removes the foreground notification whenever that
  run returns success, failure, or retry. Waiting for constraints/backoff has no ongoing
  notification. Android 13+ notification permission controls notification-drawer
  visibility, not whether WorkManager can schedule the job.
- Sync writes resources into app-private storage via temporary files and publishes each
  entry atomically. The cache index persists version, MIME type, and expected byte size,
  is fsynced, and rotates through a last-known-good backup. A corrupt/missing primary
  recovers from that backup. Legacy records without size remain readable but are treated
  as incomplete and refreshed. Planning trusts an entry only when its version and stored
  expected size match the manifest and its file has exactly that byte length. Stale
  entries are removed only after every
  replacement has downloaded, and temporary failures never delete completed cache data.
- If saved matching credentials plus complete cached root and state exist, the WebView
  renders the cached root before the Activity enqueues any new hub work. That session is
  cache-first regardless of connectivity: every complete cached root, state, thumbnail,
  cut-list, and media response remains local. Missing ordinary resources can use the
  authenticated network when Android reports validated connectivity; missing online
  media remains native WebView traffic. After a successful background sync, the WebView
  switches/reloads in cache-first mode so newly installed entries appear. A new login
  without a usable cached shell still validates online normally.
- Cached `/api/state` responses retain the original top-level state but filter `files`
  to entries whose `video_url` has an atomically installed cache entry. Partial `.part`
  downloads and missing media therefore do not create offline cards.
- Cached `/media/` requests always use the local cache, including while online. Online
  uncached `/media/` and generated `/flight-download/` requests are left directly to the
  native WebView pipeline and never wrapped in a `WebResourceResponse`. Before the site
  loads, WebView stores the username/password for the exact configured host and realm
  `SD Video Hub`; the matching HTTP-auth callback is retained for native requests.
  Offline cached byte ranges produce `206`,
  `Content-Range`, and a bounded stream, so synchronized video remains seekable.
- A first successful online login/sync is required before offline use. Successful login
  stores credentials and enqueues (or observes) the unique job; opening the app with
  stored credentials enqueues it too. If a cached root exists while offline, it opens
  immediately and the constrained job simply remains queued until networking returns.
- Android 13+ asks for notification permission once, after a successful login. Denial
  does not block the foreground Worker: Android still tracks the foreground task, while
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
- Work status selection always prefers `ENQUEUED`, `RUNNING`, or `BLOCKED` unique work
  over historical terminal records. Only a currently observed run can report completion;
  `Up to date` is emitted only after its size-verified plan contains zero downloads.

Launcher icon attribution is in `licenses/ICON-LICENSE.md`; all artwork is bundled and
the app performs no runtime icon download.
