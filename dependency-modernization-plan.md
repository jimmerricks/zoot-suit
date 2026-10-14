# Dependency & Architecture Modernization Plan

_Last updated: 2026-10-14 (reflects the `cleanup/review-fixes` branch)._

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
| Unit test platform | JUnit Platform (Jupiter + Vintage), Turbine, mockito-kotlin — **now actually runs** (28 tests) |

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

### 3. Minor dependency tidy-ups
- Retrofit is pinned to 2.9.0; current is 2.11.x.
- `com.getbase:floatingactionbutton:1.10.1` (2016, unmaintained) could be
  replaced with the Material3 FAB already on the classpath.
- `oauth.signpost` drags in Apache HttpComponents; a long-term goal is an
  OkHttp-based OAuth flow.

## Constraints that shape all of the above

- **minSdk 24** (Galaxy Tab S2 / Android 7.0). Any new API must be guarded or
  available on API 24.
- **Zotero Web API v10** compatibility must be preserved in `ZoteroAPI`.
- Keep changes small and verifiable — build with
  `docker compose run --rm build` and run tests with
  `docker compose run --rm build ./gradlew testDebugUnitTest`.

## Explicitly deferred (not worth the complexity yet)

- `allowMainThreadQueries()` on Room — removing it risks runtime crashes until
  every blocking query is off the main thread; defer until the Coroutines
  migration removes the blocking calls.
- Encrypting WebDAV credentials / network-security-config — low risk for a
  personal single-device build, and WebDAV needs runtime-configured cleartext.
