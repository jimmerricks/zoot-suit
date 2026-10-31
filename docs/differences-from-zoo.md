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
| Tags | Read only | Add and remove, offline too, on one item or a selection; uploaded at the next sync |
| Collections | Read only | Add a selection to a collection or remove it from one, offline too |
| New notes | Online only, uploaded at once | Offline too; uploaded at the next sync |
| Large libraries | Whole library held in memory | List, sort and search read from the database |
| Sidebar | Top-level collections; groups in a flat list | Collection tree; groups with their own collections |
| Rotation and restart | Reloads the library; restarts in My Library | Keeps your place |
| Theme | Follows the system | Settings → Theme: auto, light or dark (dark on Android 7–9 too) |
| List rows | Type icon, title, first creator | Settings → Appearance: compact rows, year and publication after the creator, type icons on or off, and Zotero's coloured and emoji tags before the title |
| Adding items | Zotero Save, ISBN scanner, share target | Removed |
| Downloading many attachments | Attachment Manager: the whole library, while the screen stays open | The selected items' PDFs, snapshots or all files, in the background; resumes after interruptions |
| Sharing an item | Share button in the item view | Removed |
| Signing out | Clear the app's data | Settings → Sign out, which first lists the edits not synced yet |

The commit history lists every change.
