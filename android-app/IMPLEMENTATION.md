# SD Video Hub Android wrapper

Standalone Android project, separated from the Python server in the same repository.
The existing site is rendered unchanged; the native layer owns authentication, local
HTTP delivery, complete-file storage and background synchronization.

## Build and tests

Requirements: JDK 17+, Android SDK Platform 35, Node.js, Playwright with its Chromium
browser, and ffmpeg. Point `ANDROID_HOME` or ignored `local.properties` at the SDK.
The real-browser tests require Playwright; set `SDH_PLAYWRIGHT_MODULE` to its absolute
module directory when it is not at the build host's existing default location. For
example, a separate test-only installation can be prepared with:

```sh
npm install --prefix /tmp/sdh-browser-tests playwright
/tmp/sdh-browser-tests/node_modules/.bin/playwright install chromium
export SDH_PLAYWRIGHT_MODULE=/tmp/sdh-browser-tests/node_modules/playwright
cd android-app
./gradlew --no-daemon testDebugUnitTest assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`. Use `adb install -r` to update
without deleting app data. Preserve the existing package ID and signing key.
Server regression tests, from the repository root:

```sh
python3 -m unittest test_offline_manifest.py
```

## Data path

- The login form keeps saved server URL (default `http://10.66.66.200:8787`), username
  and password prefilled. Credentials are one Android-Keystore AES-GCM encrypted payload.
  Login opens the local copy without first probing the remote server.
- WebView uses a loopback-only HTTP server. Every request requires an unpredictable
  HttpOnly session cookie; Host/navigation are restricted and mutation Origin is checked.
  Remote authentication is added by native code, not website JavaScript or video URLs.
  No WebView media interceptor or native media Basic-auth callback is required.
- Cached shell and completed media always win, even online. HEAD and bounded byte
  ranges support local playback and seeking. Missing online media is streamed through
  the authenticated native proxy. Encoded remote media paths remain opaque.
- Reachability and the latest remote state are refreshed asynchronously. They cannot
  delay cached page/state/thumbnail/media requests. Offline state lists only complete
  local videos. A missing optional flight-skip file returns an immediate empty map so
  the unchanged site's awaited metadata fetch cannot block its gallery.
- One process-shared cache repository owns an in-memory index and a serialized writer.
  File publication and index persistence are atomic; the last valid index backup remains
  recoverable. Existing size-less legacy records are verified and migrated in place,
  not discarded or blindly downloaded again. Version plus exact size controls sync.
- WorkManager unique KEEP work is enqueued independently of page callbacks, including
  on app opening with saved credentials. It downloads shell/state/thumbnails before
  large media, verifies complete files, retains successful earlier downloads, and only
  prunes after a fully verified sync. Progress is throttled. Temporary network failures
  retry with backoff. Normal Activity/task closure does not cancel the job; explicit
  Android force-stop cannot be bypassed.
- Sync completion refreshes the existing website state loader instead of reloading an
  active player document. Fullscreen and Android Back remain native-wrapper features:
  Back exits native fullscreen first, then closes the site viewer, and stays at root.
- App windows and WebView have dark initial backgrounds. A single-task Activity and a
  preferred loopback port avoid overlapping cookie sessions and unnecessary Web storage
  origin changes. A separate server origin does not overwrite the legacy server cache.

## Limits and evidence

A first online synchronization is necessary before content can exist offline. Partial
files are never advertised as available. Temporary server-generated flight-only exports,
server-side SD-card actions and external injected resources cannot be generated offline.
Cleartext upstream HTTP is supported as requested; Keystore protects storage, while the
WireGuard tunnel or HTTPS must protect transport outside the trusted local path.

See [RELIABILITY.md](RELIABILITY.md) for actual HTTP, browser playback, thumbnail, seeking,
legacy migration, concurrency and signing checks. Those desktop integration tests do not
replace physical Android WebView, Activity lifecycle, notification or fullscreen tests.
Icon attribution: [licenses/ICON-LICENSE.md](licenses/ICON-LICENSE.md).
