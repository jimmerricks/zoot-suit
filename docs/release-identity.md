# Release identity: what testers' APKs rest on

Rule: **no credentials in the repo.** Secrets live in git-ignored `apikeys.properties`
(or env vars of the same names, upper case, for CI); keystores and `keystore.properties`
are git-ignored too. Keep a copy of each in a password manager.

Decided 2026-10-21 (Jim); state after the release-prep commits of that day.

## 1. OAuth client
- Registered by Jim at <https://www.zotero.org/oauth/apps> for Zoot Suit; key/secret
  in `apikeys.properties` (`zotero_api_key`, `zotero_api_secret`).
- The registered website and callback URL are `http://localhost` (placeholders). The
  app sends its callback, `zootsuit://oauth_callback` (`OAUTH_CALLBACK` in
  `ZoteroOAuthWebView.kt`), with each request; the WebView catches it, no intent
  filter registers the scheme. Zotero accepted it for the request token. Set the
  registered callback to the same value if Zotero ever starts enforcing it.
- Groups are requested read-only (`all_groups=read`): the app writes only to the
  personal library.
- Tested on API 24: the request token and Zotero's login page work (signpost +
  commonshttp + apache legacy). Log in → authorise → access token is untested: it
  needs the scratch account's password (Jim, via VNC).

## 2. applicationId: done
- `nu.n4.zootsuit` (namespace and Kotlin packages keep upstream's names);
  `versionCode 1`, `versionName "0.1.0"`. Debug builds: `nu.n4.zootsuit.debug`,
  "Zoot Suit (debug)", so a debug and a release build can share a device.
- FileProvider authority uses `${applicationId}` (`9d960d5`); the library-filter
  intent is explicit and LibraryActivity unexported, so Zoo for Zotero installed
  beside it can't intercept it.
- No installs of this id predate 0.1.0, so the upgrade code (Room migrations 1→6,
  `adoptAttachmentBases`/`backfillAttachmentBases`) could go. Left in for now: it is
  inert on fresh installs, and removing it is a refactor of its own.

## 3. Crash reports: off
- The address comes from `crash_report_email` in `apikeys.properties`; it is unset,
  so crash reporting is off (`7ddc8e6`).
- Before enabling: reports include LOGCAT, and release logs contain item titles,
  keys, WebDAV host and URLs. Drop `ReportField.LOGCAT` or strip `Log.d/i/v` in
  release (R8 `-assumenosideeffects`), and say so in the privacy policy.

## 4. Signing: done
- Release keystore: `zotero-sync/.secrets/zootsuit-release.jks` (PKCS12, RSA 4096,
  alias `zootsuit`, valid ~27 years), passwords in `.secrets/keystore.properties`
  (mode 600). Certificate SHA-256
  `FA:92:B9:89:83:CB:8A:21:78:32:FA:8E:3B:E5:D3:E2:25:75:41:5F:67:29:93:91:91:60:36:A4:04:AC:D5:36`.
  **Copy both files into the password manager**: losing them means testers can't update.
- `app/build.gradle` reads `keystore.properties` next to `settings.gradle`, else the
  file `$KEYSTORE_PROPERTIES` names; the Docker `build` service mounts `.secrets` at
  `/secrets` read-only and sets that variable.
- `minifyEnabled false`: R8 needs keep rules for Gson/Retrofit models; test first.

## 5. Licence and source
- `LICENSE` holds the GPLv3 text. Upstream ships no licence file; its author states
  GPLv3 in its terms of use (`77f34e4`). The readme marks Zoot Suit as modified.
- Source and releases: fork `github.com/jimmerricks/zoot-suit` (remote `origin`).

## 6. Privacy policy / terms: written
- `privacy-policy.html` and `terms-of-use.html` rewritten for Zoot Suit; the app
  links to `jimmerricks.github.io/zoot-suit/…` (`common/Constants.kt`). Needs GitHub
  Pages on the fork (branch `master`, root) once pushed, and issues enabled there
  (the policy's contact).

Left as is (internal, invisible): Kotlin package and class/theme names,
`zooforzotero_*` pseudo collection keys (stored in prefs).
