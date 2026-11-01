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
- **Tablet layouts.** There are no layouts for large screens yet: the sidebar overlays
  the list, and a wide screen could show the list and an item side by side.
- **Tested on a real device.** So far the app is tested on emulators (Android 7.0, 14
  and 16).

## Open work

Smaller items, none blocking a release:

- **Not yet tested:** a WebDAV server that redirects to another host (the login now
  goes only to the host set up, so such a server needs its final address entered);
  leaving a group library (its items are now removed from the device); tag colours
  and kept searches in group libraries; Android 15.
- **Size:** the release APK is about 2.6 MB. Keeping only the classes Gson, Room and
  Hilt reach by reflection, instead of all of the app's own, would save a few hundred
  KB more; the navigation library is used for a single screen.
- **Uploads:** a file is hashed before it is streamed, so one saved during an upload
  can end up on WebDAV with a `.prop` that doesn't match it (Zotero's own storage
  rejects such an upload).
- **Code:** the download-and-check logic exists twice (single and bulk downloads);
  RxJava is being replaced by coroutines part by part; the library screen's dialogs
  and navigation still go presenter to view rather than through the ViewModel; the
  library list's derived maps are rebuilt just after a new list is published, so they
  can briefly disagree with it; lint reports about 150 warnings (mostly hardcoded text).
- **Library screen:** tag views and searches are not restored after a restart (the
  collection is); whether `singleTask` would serve the library screen better than
  `singleInstance` needs testing with external PDF viewers.

## Known limitations

- Edits to attachments in group libraries are not uploaded (as in Zoo for Zotero).
  Group libraries are requested read-only.
- No adding items; use Zotero itself.
- Of an item's metadata, only tags (one item or many) and, in bulk, collection
  membership can be edited, and only in My Library. Edits are kept as operations and
  replayed onto Zotero's copy at sync, 50 items to a request, so editing other fields
  can reuse the same path; it needs an editor and a way to settle a field changed both
  here and elsewhere (detected, not yet shown).
- The Zotero File Storage upload path has had less testing than WebDAV (uploads and a
  conflict were tested against a test account).

## Architecture

The app is a hybrid of upstream's code and newer layers:

| Area | Now |
|---|---|
| Language, build | Kotlin 2.1, KSP (Room, Hilt), JDK 17+ |
| Reactive | RxJava 3 in the library and sync core; coroutines and Flow in `domain/` and the newer screens |
| UI | Views and Fragments (Material Components) throughout; the sign-in screens moved off Jetpack Compose after 0.4.3, which took 1.3 MB of the app |
| Database | Room 2.7 |
| Tests | JUnit 5 (with JUnit 4 via Vintage), Turbine, mockito-kotlin |

The library screen used to run on a ~1,800-line `LibraryActivityModel` behind an MVP
presenter. That class is gone: `LibraryViewModel` holds the screen's state and
logic and outlives the activity, and `LibraryComponents` wires the parts that do the
work, each on its own: `OpenLibrary` (which library is open, the Back stack),
`LibraryLists`, `LibrarySync`, `LibraryAttachments` (with `AttachmentUploader` and
`EditedAttachments`), `LibraryItemEdits`, `LibraryDeviceState` and `LibraryGroups`.
The list's selection is an `ItemSelection` of keys in `LibraryListViewModel`; bulk
downloads run in a WorkManager worker (`ZoteroStorage/Downloads/`) from a queue kept in
a file, so they outlive the screen and the process.

Planned, one small, behaviour-preserving step at a time, each checked on a device:

1. **RxJava → coroutines** everywhere, which would remove four RxJava dependencies.
   The parts above are where it would start, one at a time.
2. **Smaller updates:** Retrofit 2.9 → 2.11; an OkHttp-based OAuth flow to replace
   `oauth.signpost` and its Apache HttpComponents dependency.

Deferred for now: removing `allowMainThreadQueries()` from Room (needs every
blocking query off the main thread first). Release builds are shrunk with R8, keeping
all of the app's own classes (`proguard-rules.pro`).

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

Large libraries: the list, sort and search run in the database (0.2.0); a first sync
fetches four pages at once and opens the library before attachments and notes (0.2.1). Left:
the edit check at each start still looks at every downloaded file once (about 1 ms
each); paging the startup query by key would keep each result within one cursor
window; a snapshot of the loaded library would make the start faster still.
