# Zoot Suit

Zoot Suit is an Android app for your Zotero library. It keeps a copy of the library on
your phone or tablet, so you can browse and search it and open the files you have
downloaded without a connection, and it sends what you change on the device back to
Zotero: PDFs you annotate in a PDF app, notes, tags and collections. Files sync through
Zotero Storage or your own WebDAV server.

It is a modified version of [Zoo for Zotero](https://github.com/mickstar/Zoo-For-Zotero)
by Michael Johnston, forked at v4.0.0 (commit `0261040`) in October 2026. What changed:
[Differences from Zoo for Zotero](docs/differences-from-zoo.md).

## Features

- Browse, search, filter and sort your library and group libraries, online or offline,
  with the full collection tree in the sidebar.
- Edit PDFs in any PDF app that saves to the file; the next sync uploads them to Zotero
  Storage or your WebDAV server, without overwriting a newer version made elsewhere.
- Add notes, tags and items to collections, offline too; changes made elsewhere are
  kept (My Library only).
- Select many items at once to tag them, file them, or download or remove their
  attachments in the background.
- Built for large libraries (tested with 18,000 items): a fast first sync that resumes
  if interrupted, with progress in the toolbar.
- Light and dark themes; runs on Android 7 and later.

## Status

Early test releases. Tested on devices running Android 7.0 and Android 14, with
attachments stored on WebDAV. Zotero File Storage works but has had less testing.
Android 16 has been checked on an emulator (library, search, filter, sort, sync);
Android 15 not yet. Signing in with an API key and with your Zotero account (OAuth)
have both been tested.

## Known limitations

- Edits to attachments in group libraries are not uploaded.
- No adding items: use Zotero itself. The upstream ISBN scanner and share target
  are removed.
- Of an item's metadata, only tags and collections can be edited.
- No tablet-specific layouts yet.

## Install

1. Download the APK from [Releases](https://github.com/jimmerricks/zoot-suit/releases).
2. Check it (optional): its SHA-256 is listed with each release, and every release is
   signed with the same certificate:
   ```
   SHA-256: FA:92:B9:89:83:CB:8A:21:78:32:FA:8E:3B:E5:D3:E2:25:75:41:5F:67:29:93:91:91:60:36:A4:04:AC:D5:36
   ```
   `apksigner verify --print-certs zoot-suit-*.apk` shows the certificate of a
   downloaded file.
3. Allow installs from your browser or file manager when Android asks, and open the APK.
4. Sign in with your Zotero account or an API key, then set up WebDAV in Settings if
   you use it.

To edit PDFs you need a PDF app that saves changes back to the file it opened.

## Building

1. Copy `apikeys.properties.example` to `apikeys.properties` and add a Zotero OAuth
   key and secret from [zotero.org/oauth/apps](https://www.zotero.org/oauth/apps).
   Without them, sign-in works with an API key only.
2. `./gradlew assembleDebug` builds `se.jmwhite.zootsuit.debug`, which installs beside the
   release app.
3. `./gradlew assembleRelease` signs with the keystore named in `keystore.properties`
   (or in the file `$KEYSTORE_PROPERTIES` points to): keys `storeFile`,
   `storePassword`, `keyAlias`, `keyPassword`. Without it the APK is unsigned.

Requires JDK 17 or newer. Planned work is in [docs/roadmap.md](docs/roadmap.md);
signing and releases in [docs/releases.md](docs/releases.md); changes per release in
[docs/changelog.md](docs/changelog.md).

## Reporting problems

Open an issue on [GitHub](https://github.com/jimmerricks/zoot-suit/issues) with your
Android version, device, and whether you use WebDAV or Zotero File Storage.

## Licence and policies

- **Licence:** GPLv3, see [LICENSE](LICENSE). Upstream ships no licence file; its
  author states GPLv3 in its terms of use.
- [Privacy policy](https://jimmerricks.github.io/zoot-suit/privacy-policy.html)
- [Terms of use](https://jimmerricks.github.io/zoot-suit/terms-of-use.html)

## Credits

- [Michael Johnston](https://github.com/mickstar): Zoo for Zotero
- Zotero graphical resources: [zotero.org](https://www.zotero.org) (Roy Rosenzweig
  Center for History and New Media)
