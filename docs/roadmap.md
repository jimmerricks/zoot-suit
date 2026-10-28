# Roadmap

What is planned or open for Zoot Suit, roughly in order. Bugs and requests go to
[GitHub issues](https://github.com/jimmerricks/zoot-suit/issues); this page covers the
larger work behind them.

## Before 1.0

- **Sign-in with a Zotero account (OAuth), tested to the end.** Requesting a token and
  Zotero's login page work on Android 7.0; authorising and receiving the access token
  have not been tested on a device yet. Signing in with an API key is tested.
- **Android 15 and 16.** The app targets Android 16 (API 36). The library, search,
  filter, sort and sync have been checked on an Android 16 emulator; predictive Back,
  edge-to-edge display, rotation, the item view and attachments still need a
  systematic pass. Android 15 is untested.
- **Tablet layouts.** There are no layouts for large screens yet.

## Known limitations

- Edits to attachments in group libraries are not uploaded (as in Zoo for Zotero).
  Group libraries are requested read-only.
- No adding items; use Zotero itself.
- Signing out warns in general terms but doesn't count the edits still waiting to sync
  (edited attachments and tag edits); tag edits not yet synced are lost.
- Of an item's metadata, only tags can be edited, and only in My Library. Edits are
  kept as operations and replayed onto Zotero's copy at sync, so editing other fields
  can reuse the same path; it needs an editor and a way to settle a field changed both
  here and elsewhere (detected, not yet shown).
- The Zotero File Storage upload path has had less testing than WebDAV. Known gaps:
  an optional `x-amz-security-token` is modelled as required, first uploads send
  `If-Match: *` where `If-None-Match: *` is correct, and one status check expects
  `421` instead of `412`.

## Architecture

The app is a hybrid of upstream's code and newer layers:

| Area | Now |
|---|---|
| Language, build | Kotlin 2.1, KSP (Room, Hilt), JDK 17+ |
| Reactive | RxJava 3 in the library and sync core; coroutines and Flow in `domain/` and the newer screens |
| UI | Fragments/Views for the library screen; Jetpack Compose (Material 3) for newer screens |
| Database | Room 2.7 |
| Tests | JUnit 5 (with JUnit 4 via Vintage), Turbine, mockito-kotlin |

Planned, one small, behaviour-preserving step at a time, each checked on a device:

1. **Break up `LibraryActivityModel`.** It is a ~1,700-line class behind the library
   screen. Done so far: Back-stack navigation (`LibraryNavigator`), list filtering
   (`LibraryItemFilter`) and a `LibraryViewModel` that keeps the library across
   rotation. Next: attachment download/upload, then sync staging, then folding the
   presenter into the ViewModel.
2. **RxJava → coroutines** everywhere, which would remove four RxJava dependencies.
3. **Smaller updates:** Retrofit 2.9 → 2.11; an OkHttp-based OAuth flow to replace
   `oauth.signpost` and its Apache HttpComponents dependency.

Deferred for now: removing `allowMainThreadQueries()` from Room (needs every
blocking query off the main thread first) and enabling R8 minification (needs keep
rules for the Gson/Retrofit models).

## Constraints

- **minSdk 24 (Android 7.0).** New platform APIs must be available on API 24 or
  guarded.
- **Zotero Web API v3** compatibility (`Zotero-API-Version: 3`).

## Ideas, not planned

For the library screen's search, filter and sort:

- A chip bar under the toolbar while a search or filter is active, each chip
  clearable on its own, replacing the subtitle.
- A "whole library" option in search (it currently narrows the list on screen).
- Back clearing an active search first.

Large libraries: the list, sort and search already run in the database (0.2.0). Left:
the edit check at each start still looks at every downloaded file once (about 1 ms
each), and R8 minification would make the APK smaller.
