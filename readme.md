# Zoot Suit

A personal fork of [Zoo for Zotero](https://github.com/mickstar/Zoo-For-Zotero) by Michael Johnston, an Android client for browsing and syncing Zotero libraries.

**Upstream:** `mickstar/Zoo-For-Zotero` — forked at v4.0.0 (commit `0261040`)  
**License:** GPLv3 (inherited)  
**Not affiliated with the Zotero project.**

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

1. Copy `apikeys.properties.example` to `apikeys.properties` and supply your Zotero OAuth key/secret from [zotero.org/settings/applications](https://www.zotero.org/settings/applications).
2. Build with Android Studio or `./gradlew assembleDebug`.

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
