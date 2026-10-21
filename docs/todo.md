# Open items (updated 2026-10-21, after the release-prep pass)

Release identity: see `release-identity.md`. Test environment:
`../../.claude/reference/test-environment.md`.

## Before release
- **Push and publish** (Jim's go-ahead): push `master` to `origin`, turn on GitHub
  Pages (root of `master`) and issues, attach the release-signed APK to a release.
- **OAuth sign-in end to end**: request token and login page work on API 24; finishing
  needs the scratch account's password (see release-identity.md §1).
- **Android 16**: Back moved to `OnBackPressedCallback` (`4771fd2`). With targetSdk 36,
  orientation/resizability limits are ignored on ≥600dp. The API 36 emulator exists
  (tablet screen, scratch account): sign-in, the library list and the Search, Filter
  and Sort dialogs render; no systematic pass yet (predictive Back, edge-to-edge,
  rotation, the item view, attachments).

## Smaller
- Custom Path direct lookup (`c0bcf6f`): each missing file logs a provider error line
  (harmless). Writes still list the folder.
- `LibraryActivity` is `singleInstance`; `singleTask` would be the usual choice, but
  it changes how the task behaves around external PDF viewers: test before changing.
- `ZoteroDB` derived maps rebuild after `items` is published (brief inconsistency).
- `AttachmentInfo.downloadedFrom` says WEBDAV for group downloads (unused label).
- Sign out doesn't count edits waiting to sync; the dialog only warns in general.
- Lint: 0 errors, ~150 warnings (mostly HardcodedText, UseKtx).
- Earlier open work (handover list in `../../.claude/CLAUDE.md`) still stands.

## Startup time: load only what the list needs (not built)
Sync is already incremental (`since=` the library version); the cost is reading the
stored library into memory at every start. On the API 36 emulator with 10k items, 10k
attachments and 2k notes, reading took ~3 s after blank fields stopped being stored
(`ec16c94`, from ~4.4 s); the Tab S2 is several times slower. Jim's library: 21,859
items, ~278k non-blank field rows after that change, 13.7k tag links, 24.6k creators.
Almost all of it is Room's `@Relation` loading every `ItemData` row of every item into
objects, though the list shows a handful of fields.

The proposal: load in two stages.
1. **At startup**, per item only what lists, filters and the default search need:
   `ItemInfo`, and from `ItemData` just `itemType`, `title` (and `caseName`/`nameOfAct`/
   `note` for those types), `date`, `dateAdded`, `parentItem`, `contentType`, `linkMode`,
   `filename`, `inPublications`, `md5`/`mtime` for attachments; plus creators, tags and
   collections (small tables). One query with `WHERE name IN (…)` instead of the
   relation, into a lighter list type or into `Item` with `itemData` partly filled.
2. **On demand**, the rest: when an item's view opens (one item's rows, instant), and
   in the background after the list shows, for search fields beyond title, creators
   and year (abstract, publication, other fields) and for the share dialog. The search
   index already builds lazily; it would wait for (or trigger) stage 2 when such a
   field is ticked.

What has to change: `Item.data` and `getItemData` assume all fields are there (make
"full" a state, or a separate type for list rows); `ItemDao.getItemsForGroup` and
`ZoteroDB.loadItemsFromDatabase`; the item view and share dialog fetch their item;
`LibrarySearch` knows which fields need stage 2; the sync writer stays as is. The
gain is uneven: attachments (half of Jim's items) need most of their ~10 fields in
stage 1, while parent items drop most of theirs, including the long ones (abstract,
extra, URLs) that are most of the bytes. Before building, count rows and bytes stage 1
would read on Jim's database (read-only query on the API 24 device) against today's.

Alternative, not exclusive: a **snapshot** file of the in-memory library written after
each load or sync and read at startup instead of the database; fast to read, but it
must be kept in step with every sync and edit, and it duplicates the database.

## Library view controls: ideas, not built
Search, Filter and Sort now act alike on the list on screen: each narrows or orders
whatever is shown (a collection, unfiled items, a tag view; not Trash or My
Publications), each applies only on its dialog's button, the subtitle names search and
filter with the count, and an empty list offers to clear them. Search and filter clear
when another collection opens unless "Keep the search/filter across collections" is on
(then saved per library). What's left of the 2026-10-21 assessment:
- **Chip bar** under the toolbar, shown only while something is active:
  `[🔍 "foucault" ✕] [Has PDF ✕] [to-read ✕] … 42 items`. Each chip opens its
  dialog, its ✕ clears just that part. Replaces the subtitle and gives search and
  filter a clear button on screen in every state, not only on an empty list. Sort
  needs no chip (it always has a value); an ↑/↓ by its icon would show the direction.
- **Tag views become filter tags**: tapping a tag in the item view adds it to the
  filter (a chip) instead of opening "Tag: x" on the Back stack. One tag mechanism
  instead of three (filter tags, tag views, the search Tags field); changes what the
  tag tap does, so it needs Jim's call. The "tag:" filter text on the state stack would
  then go, and with it the last use of `filterEntries`.
- **Search scope**: search narrows the list on screen, so a whole-library search
  means opening My Library first. A "whole library" option in the dialog is possible
  if that proves awkward.
- **Kept search and filter** are easy to forget behind a subtitle; the chip bar would
  make them visible. Kept per library is untested with groups (the scratch account has
  none).
- **Back** doesn't clear a search (it doesn't clear a filter either). Many apps clear
  search on Back first; reconsider with the chip bar.
- Dialog buttons: Filter Clear/Cancel/Done, Sort Cancel/Done, Search Clear/Cancel/
  Search; all apply on the button (Clear at once). The filter dialog lost its live
  "N items match"; a cheap count (no sort, no redraw) could come back if missed.

## Done in the 2026-10-21 pass
Search as a dialog over the list on screen, like Filter (index over chosen fields,
applied on Search, named in the subtitle, cleared from the dialog or an empty list);
Filter and Sort apply on Done; search/filter clear per collection unless kept; "has
PDF" from a precomputed set; tag views apply "has PDF"/"has notes"; sort keys computed
once;
organisation authors shown; OAuth as Zoot Suit with read-only groups and no token
logging; applicationId `nu.n4.zootsuit`; release signing; Sign out; File Storage
uploads use the edited file's base (a second upload failed with 412), tested against
a scratch account including a conflict; WebDAV setup keeps working settings until a
new one connects; lint error; MockWebServer paging tests; LICENSE, readme, policies.
