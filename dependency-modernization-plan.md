# Dependency & Architecture Modernization Plan

_Last updated: 2026-10-19, later (reflects the `cleanup/review-fixes` branch)._

This tracks the modernization of the Zoot Suit fork. The original upstream
plan assumed a Kotlin 1.9 / RxJava2 / KAPT baseline; much of that work is now
done, so this document records the **current state** and what remains.

## Current state (done)

| Area | State |
|---|---|
| Kotlin | 2.1.21 |
| Build annotation processing | **KSP** (Room + Hilt); KAPT removed |
| DI | Hilt 2.57 |
| Reactive (legacy layer) | **RxJava3** (3.1.8) — migrated from RxJava2 |
| Reactive (new layer) | Coroutines + Flow in `domain/` and the MVVM screens |
| Room | 2.7.2 (runtime + rxjava3 + KSP compiler) |
| UI (new screens) | Jetpack **Compose** (BOM 2025.06.01), Material3, Navigation3 |
| UI (legacy screens) | Fragment/View + Navigation2 |
| Unit test platform | JUnit Platform (Jupiter + Vintage), Turbine, mockito-kotlin — **now actually runs** (80 tests) |

## Remaining work

### 1. Finish the RxJava3 → Coroutines migration
The app is a hybrid: the legacy library/sync core (`LibraryActivityModel`,
`ZoteroDB`, `ZoteroAPI`, Room DAOs) is RxJava3; the new `domain/` + MVVM layer
is Coroutines. Converging on Coroutines everywhere would let us drop
`rxjava3`, `rxandroid`, `adapter-rxjava3`, and `room-rxjava3`.
High effort — touches the network layer, DAOs, and all presenters.

### 2. Retire the MVP library core
`LibraryActivity` runs an MVP `Contract`/`Presenter` *and* a ViewModel
simultaneously. `LibraryActivityModel` is a ~1100-line god class holding a
`Context`-as-`Activity`, which makes it untestable. Target: decompose into
injectable collaborators and a ViewModel, matching the `AttachmentManager`/
`SyncSetup` pattern. High effort; do this before building major new features
on the library screen.

Slices done so far (each behavior-preserving, unit-tested where pure):
- Activity coupling removed: `finish()` goes through `Presenter.finishLibrary()`.
- `LibraryNavigator`: the back-stack of `LibraryModelState`s (6 tests).
- `LibraryItemFilter`: notes/PDF display filters, item and collection search
  (7 tests).

Next, in rising order of risk: attachment download/upload (largest RxJava
surface, can lose data), then sync staging (`finishLibrarySync`,
`loadPriorState`), then the Presenter → ViewModel swap. Each of these needs
a logged-in emulator with a disposable test library, not the real one: this
code uploads attachments and creates/deletes notes.

This work changes threading/lifecycle behavior (sync staging, back-navigation,
group switching, the Presenter → ViewModel swap) and must be verified on-device,
not just by an APK build. The dockerized Android emulator added in the repo root
provides that path — see the project `README.md` ("Instrumented tests against
the emulator"). Proceed slice by slice, smoke-testing between steps.

### 2b. Attachment sync-back and Web API correctness (2026-10-19)
Done, each in its own commit: WebDAV uploads check Zotero and the remote `.prop`
before writing (`AttachmentUploadPreflight`), then replace via conditional
stage/claim/swap/commit requests (`WebdavUploader`; desktop-compatible, `.prop` as
commit marker), with an optional backup copy and a pre-upload file sanity check;
note creation reads the per-object `failed` list; 429/Retry-After/Backoff are
honoured. Verified end to end on the API 24 emulator against the real library and
WebDAV server, with Zotero desktop picking up the annotation.

Open, in priority order:
1. **Lost-update gap:** the pre-upload check uses the item's md5 from the last sync
   as the base, not the md5 of the file the device actually downloaded/edited, and
   opening doesn't md5-check by default. A stale local copy edited after desktop
   changed the file can overwrite desktop's version (the backup keeps it). Track a
   per-attachment base md5 at download/upload and use it in the check.
2. Note deletion has no confirmation and is a permanent API DELETE.
3. Zotero File Storage path (unused with WebDAV): optional `x-amz-security-token`
   modelled as non-null; `If-Match: *` for first uploads should be
   `If-None-Match: *`; a `421` check meant `412`.

### 3. Minor dependency tidy-ups
- Retrofit is pinned to 2.9.0; current is 2.11.x.
- `com.getbase:floatingactionbutton:1.10.1` (2016, unmaintained) could be
  replaced with the Material3 FAB already on the classpath.
- `oauth.signpost` drags in Apache HttpComponents; a long-term goal is an
  OkHttp-based OAuth flow.

## Constraints that shape all of the above

- **minSdk 24** (Galaxy Tab S2 / Android 7.0). Any new API must be guarded or
  available on API 24.
- **Zotero Web API v3** (`Zotero-API-Version: 3`) compatibility must be preserved in
  `ZoteroAPI`. ("Zotero 10" is the desktop client and its local API, not a Web API version.)
- Keep changes small and verifiable — build with
  `docker compose run --rm build` and run tests with
  `docker compose run --rm build ./gradlew testDebugUnitTest`. On-device
  verification (instrumented tests, visual smoke QA) runs against the dockerized
  emulator; see the repo-root `README.md`.

## Explicitly deferred (not worth the complexity yet)

- `allowMainThreadQueries()` on Room — removing it risks runtime crashes until
  every blocking query is off the main thread; defer until the Coroutines
  migration removes the blocking calls.
- Encrypting WebDAV credentials / network-security-config — low risk for a
  personal single-device build, and WebDAV needs runtime-configured cleartext.
