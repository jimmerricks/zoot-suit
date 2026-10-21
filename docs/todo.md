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

## Startup time
Built 2026-10-21 (`ca71a87`, `10881fa`, `c020e32`, after `ec16c94`): items load with
one query per table instead of Room's relations, off the main thread, and at startup
only the fields the list needs (regular items: `LIST_FIELDS`; attachments: all but
their note); the rest load in the background. On a copy of Jim's library on the API 36
emulator, start to first list went from ~5.2-6.1 s to ~3.9-4.9 s. Measurements and
the reasoning are in those commit messages. Left:
- **Off the main thread is slower** on the emulator (load 1.5 s on main vs 2.35 s at
  display priority, synthetic data): unexplained; main-thread loading would risk ANRs
  on the Tab S2, so it stays in the background.
- **Bytes matter**: results larger than a 2 MB cursor window cost extra. Paging the
  field query by key (`parent > :last ORDER BY parent LIMIT n`) would keep each query
  in one window; not tried.
- **Snapshot file** of the in-memory library (written after load/sync, read at
  startup) remains the bigger, riskier option.
- Not exercised on a device: a kept Abstract/Publication search redone when the rest
  of the fields arrive (unit-tested index flag only). Timings on the Tab S2 itself.

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
