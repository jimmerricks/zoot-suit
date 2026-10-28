# Zoot Suit

An Android app for reading your Zotero library and syncing the PDFs you edit on the
device. It is a modified version of [Zoo for Zotero](https://github.com/mickstar/Zoo-For-Zotero)
by Michael Johnston, forked at v4.0.0 (commit `0261040`) in October 2026.

- **Licence:** GPLv3, see [LICENSE](LICENSE). Upstream ships no licence file; its
  author states GPLv3 in its terms of use.
- **App id:** `se.jmwhite.zootsuit` (0.1.0 was `nu.n4.zootsuit`, see the
  [changelog](docs/changelog.md)). It installs beside Zoo for Zotero, not over it.
- **Not affiliated with the Zotero project.**
- [Privacy policy](https://jimmerricks.github.io/zoot-suit/privacy-policy.html) ·
  [Terms of use](https://jimmerricks.github.io/zoot-suit/terms-of-use.html)

## Status

Early test releases. Tested on Android 7.0 (Samsung Galaxy Tab S2) and Android 14,
with attachments stored on WebDAV. Zotero File Storage works but has had less testing.
Android 16 has been checked on an emulator (library, search, filter, sort, sync);
Android 15 not yet. Signing in with an API key is tested; signing in with your Zotero
account (OAuth) has not been tested to the end yet.

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

Coming from 0.1.0: 0.2.0 has a new app id and installs as a separate app. Sync your
edits in 0.1.0 first, then uninstall it once 0.2.0 is set up.

To edit PDFs you need a PDF app that saves changes back to the file it opened.

## What it does

- Browse and search your library and group libraries, online or offline.
- Sidebar with the full collection tree; each group has its own collections.
- Search titles, creators and years, or the fields you choose (publication, abstract,
  tags, notes, other fields); every word must match, accents are ignored.
- One filter for tags, PDFs, notes, files on the device and unsynced edits. Search and
  filter narrow the list on screen and clear when you open another collection, or stay
  (for each library) if you choose so in Settings. Sort is kept per library.
- Edit any number of PDFs, online or offline. The next sync uploads them, then
  downloads what changed elsewhere.
- Detects edits to every downloaded file, not just files opened through the app.
- Before uploading, checks whether someone else changed the file on the server,
  so a newer version is not overwritten.
- Long-press an attachment to open it, download it, upload your changes or delete the
  local copy.
- Create, edit and delete notes. Add and remove tags, offline too; the next sync
  uploads them without losing tags added elsewhere (My Library only).
- Works on Android 7 with Let's Encrypt servers (the root certificate is bundled).
- Made for large libraries (tested with 18,000 items): the list, sorting and search
  read from the database page by page instead of holding the library in memory.
- A first sync of a large library keeps the display on while it downloads, and picks up
  where it stopped if the device slept anyway.

## Known limitations

- Edits to attachments in group libraries are not uploaded.
- No adding items: use Zotero itself. The upstream ISBN scanner and share target
  are removed.
- Of an item's metadata, only tags can be edited.
- No tablet-specific layouts yet.

## Differences from Zoo for Zotero

See [docs/differences-from-zoo.md](docs/differences-from-zoo.md).

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

## Credits

- [Michael Johnston](https://github.com/mickstar): Zoo for Zotero
- Zotero graphical resources: [zotero.org](https://www.zotero.org) (Roy Rosenzweig
  Center for History and New Media)
