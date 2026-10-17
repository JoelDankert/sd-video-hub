# Android data-layer reliability evidence

## Baseline at `a87a645`

The failures were present in the production path, while the earlier tests covered only
isolated selectors and parsers:

- `OfflineCache.records()` rebuilt every record on every resource request. It derived a
  SHA-256 filename through per-byte `String.format` and called `isFile`/`length` for all
  indexed resources. `shouldInterceptRequest()` called it repeatedly for routing, state,
  thumbnails, and media.
- Each `OfflineCache` created a separate `CacheIndexStore`; its instance-level
  `@Synchronized` methods did not serialize Activity and WorkManager read/modify/write
  cycles against one another. Recovery called `write(recovered)`, which could rotate an
  invalid primary over the known-good backup.
- Login performed a synchronous user-visible manifest validation before constructing
  the WebView. Saved cache sync was enqueued from `onPageCommitVisible`, so a page that
  did not commit also did not schedule its independent repair.
- `CacheClient` treated Android's validated-internet bit as hub reachability. An online
  uncached media route returned `null`, putting Basic authentication back in Chromium's
  native request path. Cached `/api/state` was always filtered, including online sessions.
- Cached video bytes were returned through `WebResourceResponse`, not an HTTP origin
  exercised by Chromium's normal media stack.

These findings came from source inspection before the rework. No Pi/server source or
site HTML/CSS/JavaScript was modified.

## Implemented data path

`LoopbackHttpServer` is the only WebView origin. It binds `ServerSocket` explicitly to
`127.0.0.1` on stable port 18787 when available (otherwise an ephemeral port), requires a 256-bit random session cookie on every
request, rejects non-loopback navigation in the WebView, checks the exact local `Origin`
on mutations, emits no CORS permission, accepts only the site's fixed route families,
and never follows an upstream redirect. The native layer constructs all upstream URLs
from the saved base URL and supplies Basic authentication; neither password nor upstream
URL is added to site JavaScript or media URLs. The server is closed on Activity
destruction or deliberate in-Activity replacement, never by sync lifecycle.

Saved shell and state are served before an asynchronous bounded hub probe. Complete
cached media always wins, online or offline. Missing offline resources return `503`
immediately except the optional cutlist, which returns empty JSON. Online uncached media is authenticated and streamed without whole-file
buffering. `GET` and `HEAD` support full, closed, open-ended, suffix, and unsatisfiable
ranges (`200`/`206`/`416`) with `Content-Length`, `Content-Range`, and `Accept-Ranges`.
Raw encoded targets, including `%2F`, spaces, and Unicode UTF-8 escapes, remain opaque
through lookup and proxying.

`CacheRepository` is process-wide per canonical cache root. It owns one writer lock and
an immutable in-memory index snapshot. One startup pass verifies indexed files and
upgrades legacy size-less records, so migrated videos can populate the first offline
gallery without requiring a card request; subsequent completeness lookups are O(1).
Missing and truncated files are excluded. Manifest planning is the other intentional
verification pass. Cache data is moved
with atomic replacement before its index snapshot is published; an invalid primary is
discarded without rotating it over a good backup. Pruning happens only after every entry
in the final manifest is verified. A manifest/resource size race causes one manifest
refresh and one retry, never an unbounded size-mismatch loop.

WorkManager remains unique `KEEP` work independent of the Activity and is enqueued
immediately when saved credentials are found, independently of Login/UI/page callbacks.
The Login screen remains visible and prefilled until the user performs its one explicit
Login action. Sync orders shell, state, thumbnails, small metadata, then video and
throttles foreground/progress publication to at most once per 350 ms plus a final update
per file. Existing complete data remains readable throughout downloads and failures.
Cache origins are recorded: the legacy cache is retained for its existing saved server,
while a different server/account origin uses a separate cache rather than resetting or
mis-serving old bytes. Version is now `versionCode 2`,
`versionName 1.1`; application ID, signing configuration, launcher assets, cached file
keys, fullscreen behavior, and trusted Back handling are unchanged.

The loopback normally reuses port 18787 (with ephemeral fallback) so Web storage
preferences survive relaunches. `MainActivity` is `singleTask` and closes any prior local
server before replacement. Every request requires the HttpOnly session cookie and exact
loopback Host; mutations additionally require the exact local Origin. Upstream POSTs
replace that Origin with the upstream origin and never forward the loopback cookie.
Cached `/api/state` is always returned without a synchronous network dependency; an
asynchronous probe retains the latest successful full remote snapshot for online use and
falls back to filtered cached state after loss. UNKNOWN/UNREACHABLE thumbnail and media
misses fail immediately, while an absent `.flight-skip.json` returns immediate empty JSON
because each cached state entry already carries `skip_intervals`. The native proxy turns
uncached HEAD into an authenticated `GET bytes=0-0`, derives the full length, flushes
headers immediately, and never appends an error response after response commitment.

## Verification on 2026-10-17

- Parent execution of `./gradlew --no-daemon testDebugUnitTest assembleDebug`: passed
  (Gradle reported 20 s). Final XML reports 30 tests, 0 failures, 0 errors, 0 skipped.
- The integration fixture is a real four-second 160x90 H.264/yuv420p MP4 and a decoded
  PNG. Playwright/Chromium uses the real HttpOnly cookie against the production
  `LoopbackHttpServer`, clicks the actual gallery `Ansehen` button, waits for playback to
  advance, and seeks to 2.1 s. Online uncached: gallery 84 ms, thumbnails 102 ms, playback
  345 ms. Offline cached UNKNOWN: gallery 85 ms, thumbnails 95 ms, playback 325 ms.
  Offline UNKNOWN with missing cutlist and 106 cached entries: gallery 92 ms,
  thumbnails 182 ms, playback 681 ms. All clips report duration 4.0 s and successfully
  seek beyond 2.1 s. No playback case is ignored or conditionally skipped.
- The same production `WebPageScripts.REFRESH` that MainActivity uses after sync is
  executed during all three browser playback scenarios. The player stays open, its
  current source is retained, and playback continues after the data refresh. Sync
  completion never reloads an active video document. Native window/splash/WebView
  backgrounds are dark before first content paint; the ephemeral loopback cookie is
  not synchronously flushed to disk during Login.
- HTTP tests additionally cover cached full/range/HEAD/416 delivery, an upstream that
  explicitly rejects HEAD, exact encoded paths, cookie/Host/Origin rejection, translated
  upstream POST Origin with no cookie, state fallback, immediate UNKNOWN misses,
  interrupted replacement retention, startup migration of legacy videos, exclusion of
  deleted/truncated entries, and 40 concurrent installs without lost index records.
- `python3 -m unittest -v test_offline_manifest.py`: 2 tests passed in 1.016 s
  (1.10 s wall time). No Python/server source was changed.
- The debug APK is `app/build/outputs/apk/debug/app-debug.apk`: 3,169,400 bytes, SHA-256
  `c22ddc3b230216869f63bb49ad27f3e0666c2d15037f9670b85103a37c7ca61f`.
  Its signer SHA-256 is
  `913387a3505087a7f9b26a78ca52989c3ce2e4c18b366802de7535f1623e4110`, exactly matching
  `/tmp/sdh-previous.apk`. There is no data-clearing migration; existing legacy cache files
  and keys remain in place and are verified/upgraded in place.
- `adb devices -l` reported no attached device. Therefore this run proves desktop JVM,
  real Chromium decoding/playback/seeking, and APK assembly, but not OEM Android WebView,
  Activity/Login rendering, fullscreen, predictive Back, or physical-device playback.
