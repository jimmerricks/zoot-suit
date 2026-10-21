# Zoot Suit

An Android client for browsing and syncing Zotero libraries: a modified version of [Zoo for Zotero](https://github.com/mickstar/Zoo-For-Zotero) by Michael Johnston, changed since October 2026 (the differences are listed below and in the git history).

**Upstream:** `mickstar/Zoo-For-Zotero`, forked at v4.0.0 (commit `0261040`)  
**Licence:** GPLv3, see [LICENSE](LICENSE). Upstream ships no licence file; its author states GPLv3 in its terms of use.  
**App id:** `nu.n4.zootsuit`: installs beside Zoo for Zotero, not over it.  
**Not affiliated with the Zotero project.**

Test builds are published under [Releases](https://github.com/jimmerricks/zoot-suit/releases). [Privacy policy](https://jimmerricks.github.io/zoot-suit/privacy-policy.html), [terms of use](https://jimmerricks.github.io/zoot-suit/terms-of-use.html).

---

## What this fork does differently

| Change | Upstream | This fork |
|---|---|---|
| App name, icon, colours | Zoo for Zotero | Zoot Suit (vector icon, indigo/red) |
| minSdkVersion | 26 (Android 8.0) | 24 (Android 7.0) |
| Editing PDFs | Prompt after each viewer session, one file at a time | Edit any number of PDFs, online or offline; a sync uploads them all, then downloads |
| Edit detection | Only files opened through the app | Every downloaded file, compared with the version last synced |
| Opening a PDF | Uses the md5 from the last library sync | Asks Zotero for the latest version first (online); opens the local copy offline |
| Filtering | One tag at a time (tag view); only-PDF/only-notes in the sort dialog | One filter dialog: tags (any number), has PDF/notes, PDF on device, edits to sync; AND; live list and counts; remembered; shown in the toolbar |
| Attachment actions | Tap only in the list | Long-press menu: Open, Download to device, Upload my changes, Delete local copy; state shown per attachment, and as a badge on the list's PDF icon (on device / edited here) |
| Sidebar | Top-level collections only; groups in a flat list after Trash, opening one kept My Library's collections | Collection tree, nested and expandable; groups under a collapsible heading, each with its own collections, Unfiled and Trash; listed offline too |
| Rotation and restart | Rotation reloaded the library and lost your place; every start opened My Library | Rotation keeps everything; a restart reopens the library and collection you were in |
| Collection view | Subcollections as rows, then the collection's own items | Items only (subcollections are in the sidebar); optional "Show items from subcollections" |
| Adding items | + button (Zotero Save, ISBN scanner), share target | Removed: add items in Zotero itself |
| Look | Light toolbar controls; photo header in the sidebar; sort dialog in its own style | White toolbar; plain sidebar; sort and filter dialogs alike (live, Cancel restores) |
| WebDAV upload | Unconditional overwrite | Checks for newer remote versions first, conditional replace, optional backup |
| Android 7 + Let's Encrypt | Fails TLS | ISRG Root X1 bundled |
| Signing out | Clear the app's data | Settings → Sign out |
| Zotero File Storage upload | Overwrites | Replaces only the file this device edited (Zotero refuses otherwise) |

Planned divergences are tracked in [dependency-modernization-plan.md](dependency-modernization-plan.md).

---

## Features (inherited from upstream)

- Browse and search your Zotero library
- Collections and subcollections
- Two-way attachment sync (Zotero file storage and WebDAV; WebDAV is what this fork tests)
- Group libraries
- Create, edit, and delete notes
- MVVM architecture, written in Kotlin

---

## Building

1. Copy `apikeys.properties.example` to `apikeys.properties` and supply your Zotero OAuth key/secret from [zotero.org/oauth/apps](https://www.zotero.org/oauth/apps). Without them, sign-in works with an API key only.
2. Build with Android Studio or `./gradlew assembleDebug` (application id `nu.n4.zootsuit.debug`).
3. Release builds: `./gradlew assembleRelease` signs with the keystore named in `keystore.properties` (or the file `$KEYSTORE_PROPERTIES` names); keys `storeFile`, `storePassword`, `keyAlias`, `keyPassword`. Without it the APK is unsigned. Keystores and both properties files are git-ignored.

**Requirements:** Android 7.0+ (API 24), JDK 17+.

---

## Roadmap

See [dependency-modernization-plan.md](dependency-modernization-plan.md) for the full status. In short, the Kotlin 2.1 / KSP / Hilt / RxJava3 / Compose+Nav3 modernization is largely done; what remains is:

- Finish the RxJava3 → Coroutines migration (the app is currently a hybrid)
- Retire the MVP library core (`LibraryActivityModel` god class) in favour of MVVM
- Minor dependency tidy-ups (Retrofit, OAuth stack)

---

## Credits

- [Michael Johnston](https://github.com/mickstar) — original Zoo for Zotero
- Zotero graphical resources — [zotero.org](https://www.zotero.org) (Roy Rosenzweig Center for History and New Media)
