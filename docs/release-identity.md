# Release identity: what's needed before testers get an APK

Rule: **no credentials in the repo.** Secrets live in git-ignored `apikeys.properties`
(or env vars of the same names, upper case, for CI); keystores and `keystore.properties`
are git-ignored too. Keep a copy of each in a password manager.

## 1. OAuth client (Jim)
- The local `apikeys.properties` holds a real 20-hex key/secret of **unknown origin**
  (upstream only ever committed dummies). Treat it as not ours.
- Register a new client at <https://www.zotero.org/oauth/apps> (name "Zoot Suit").
  Put key/secret in `apikeys.properties` (`zotero_api_key`, `zotero_api_secret`).
- Change the callback scheme `zooforzotero://oauth_callback` (two places:
  `ZoteroAccountAuthViewModel`, `ZoteroOAuthWebView`) to e.g. `zootsuit://…`.
- Consider `all_groups=read` instead of `write` (group uploads aren't supported).
- OAuth sign-in has never been tested in this fork (signpost + commonshttp + apache
  legacy). Decide: test it, or ship API-key sign-in only for the limited release.

## 2. applicationId (Jim picks the id)
- Change only `applicationId` in `app/build.gradle`; keep `namespace`.
  Segments must start with a letter (so `nu.4n…` is invalid; e.g. `nu.n4.zootsuit`).
- Already prepared: FileProvider authority uses `${applicationId}` (`9d960d5`).
- Restart versioning (`versionCode 1`, own `versionName`), not upstream's 53/4.0.0.
- Effect: a new app beside the old one. Tablet and emulator need a fresh sign-in,
  WebDAV setup and Custom Path. Afterwards the upgrade code can go (Room migrations
  1→6, `adoptAttachmentBases`/`backfillAttachmentBases`).

## 3. Crash reports (Jim picks the address)
- Done (`7ddc8e6`): address read from `crash_report_email` in `apikeys.properties`;
  empty means crash reporting is off. mickstar's address is gone.
- Before enabling: reports include LOGCAT, and release logs contain item titles,
  keys, WebDAV host and the OAuth URL. Drop `ReportField.LOGCAT` or strip
  `Log.d/i/v` in release (R8 `-assumenosideeffects`). Mention in privacy policy.

## 4. Signing
- Create a release keystore (`keytool -genkeypair …`), store it outside the repo,
  and add a `signingConfigs.release` reading `keystore.properties`. Losing the key
  means testers can't update. Upstream's `zooforzotero.jks` was removed (`18b6b9f`).
- `minifyEnabled false`: R8 needs keep rules for Gson/Retrofit models; test first.

## 5. Licence and source (GPLv3)
- Add `LICENSE` (GPLv3 text; none in the repo). Mark the fork as modified (README).
- Distributing the APK requires offering the source: choose a remote (none yet).

## 6. Privacy policy / terms
- `common/Constants.kt` links to `mickstar.github.io`; `privacy-policy.html`,
  `terms-of-use.html` name Zoo for Zotero and mickstarify. Rewrite, host, relink.

Left as is (internal, invisible): Kotlin package and class/theme names,
`zooforzotero_*` pseudo collection keys (stored in prefs).
