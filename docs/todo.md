# Open items after the 2026-10-21 review pass

Release identity: see `release-identity.md`. Test environment:
`../../.claude/reference/test-environment.md`.

## Before release
- **Sign-out** (none in the app; the revoked-key message says "clear app data").
  One place now: `AuthenticationStorageImpl.destroyCredentials` + `deleteEverything`.
- **Zotero File Storage upload**: request format fixed to Zotero's docs and
  unit-tested (`b4b2848`, `5212215`), never run against the server. Also check the
  S3 multipart part (`@Part("file")` has no filename).
- **Android 16**: Back moved to `OnBackPressedCallback` (`4771fd2`), checked on API 24
  only. With targetSdk 36, orientation/resizability limits are ignored on ≥600dp.
- Lint: 1 error left (`BaseViewModel.kt:47`, StateFlow.value in composition), 146 warnings.

## Smaller
- Custom Path direct lookup (`c0bcf6f`): each missing file logs a provider error line
  (harmless). Writes still list the folder.
- `LibraryActivity` is `singleInstance` and exported with a custom action; consider
  `singleTask`, unexported, explicit intent from `SearchResultsActivity`.
- `ZoteroDB` derived maps rebuild after `items` is published (brief inconsistency).
- WebDAV setup saves and enables WebDAV before its connection test passes.
- `AttachmentInfo.downloadedFrom` says WEBDAV for group downloads (unused label).
- Sync fixes `37c89ca`/`f5750a2` have no tests: `ZoteroAPI` hard-codes `BASE_URL`;
  inject it to test sync against MockWebServer.
- Earlier open work (handover list in `../../.claude/CLAUDE.md`) still stands.
