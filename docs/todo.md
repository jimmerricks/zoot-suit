# Open items (updated 2026-10-21, after the release-prep pass)

Release identity: see `release-identity.md`. Test environment:
`../../.claude/reference/test-environment.md`.

## Before release
- **Push and publish** (Jim's go-ahead): push `master` to `origin`, turn on GitHub
  Pages (root of `master`) and issues, attach the release-signed APK to a release.
- **OAuth sign-in end to end**: request token and login page work on API 24; finishing
  needs the scratch account's password (see release-identity.md §1).
- **Android 16**: Back moved to `OnBackPressedCallback` (`4771fd2`), checked on API 24
  only. With targetSdk 36, orientation/resizability limits are ignored on ≥600dp.
  Needs an API 36 emulator.

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

## Done in the 2026-10-21 pass
Search menu (Filter/Sort move to the overflow while searching, icons restored after);
organisation authors shown; OAuth as Zoot Suit with read-only groups and no token
logging; applicationId `nu.n4.zootsuit`; release signing; Sign out; File Storage
uploads use the edited file's base (a second upload failed with 412), tested against
a scratch account including a conflict; WebDAV setup keeps working settings until a
new one connects; lint error; MockWebServer paging tests; LICENSE, readme, policies.
