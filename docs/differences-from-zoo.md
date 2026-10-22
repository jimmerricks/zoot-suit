# Differences from Zoo for Zotero

Zoot Suit is a modified version of [Zoo for Zotero](https://github.com/mickstar/Zoo-For-Zotero),
forked at v4.0.0. The main differences:

| | Zoo for Zotero 4.0.0 | Zoot Suit |
|---|---|---|
| Minimum Android | 8.0 | 7.0 |
| Editing PDFs | Prompt after each viewer session, one file at a time | Any number of files; uploaded at the next sync |
| Edit detection | Files opened through the app | Every downloaded file |
| Opening a PDF | Uses the version from the last library sync | Fetches the latest version first when online |
| Upload | Overwrites the server copy | Checks for a newer server version first |
| Search | Every field, on each keystroke | Chosen fields (title, creators, year by default), indexed; runs on Search |
| Filtering | One tag at a time | Several tags plus attachment and edit filters |
| Sidebar | Top-level collections; groups in a flat list | Collection tree; groups with their own collections |
| Rotation and restart | Reloads the library; restarts in My Library | Keeps your place |
| Adding items | Zotero Save, ISBN scanner, share target | Removed |
| Signing out | Clear the app's data | Settings → Sign out |

The commit history lists every change.
