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
  completed media, thumbnails, and the cut-list used by the current web UI. Sync writes
  into app-private storage via temporary files and publishes each cache entry atomically.
  Stale entries are removed only after the entire new manifest has downloaded.
- Same-origin GETs prefer the authenticated server. On a connection failure, WebView
  receives the cached response. Cached byte ranges produce `206`, `Content-Range`, and a
  bounded stream, so synchronized video remains seekable offline.
- A first successful online login/sync is required before offline use. The login button
  starts the opening sync; saved fields are prefilled. If a sync fails, the last complete
  cache remains intact.
- Server-created, one-time flight-only exports are intentionally not manifest resources.
  Dynamically injected third-party resources are out of scope and are not cached.
- Cleartext HTTP is enabled because the required default is a private-LAN HTTP URL.
  Android Keystore protects credentials at rest, but HTTP cannot protect them in transit;
  use an HTTPS base URL on untrusted networks.

Launcher icon attribution is in `licenses/ICON-LICENSE.md`; all artwork is bundled and
the app performs no runtime icon download.
