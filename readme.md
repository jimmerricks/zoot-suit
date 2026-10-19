# Zoot Suit

A personal fork of [Zoo for Zotero](https://github.com/mickstar/Zoo-For-Zotero) by Michael Johnston, an Android client for browsing and syncing Zotero libraries.

**Upstream:** `mickstar/Zoo-For-Zotero` — forked at v4.0.0 (commit `0261040`)  
**License:** GPLv3 (inherited)  
**Not affiliated with the Zotero project.**

---

## What this fork does differently

| Change | Upstream | This fork |
|---|---|---|
| App name, icon, colours | Zoo for Zotero | Zoot Suit (vector icon, indigo/red) |
| minSdkVersion | 26 (Android 8.0) | 24 (Android 7.0) |
| Editing PDFs | Prompt after each viewer session, one file at a time | Edit any number of PDFs, online or offline; a sync uploads them all, then downloads |
| Edit detection | Only files opened through the app | Every downloaded file, compared with the version last synced |
| Download | Always opens the viewer | "Download to device" without opening; state shown per attachment |
| WebDAV upload | Unconditional overwrite | Checks for newer remote versions first, conditional replace, optional backup |
| Android 7 + Let's Encrypt | Fails TLS | ISRG Root X1 bundled |

Planned divergences are tracked in [dependency-modernization-plan.md](dependency-modernization-plan.md).

---

## Features (inherited from upstream)

- Browse and search your Zotero library
- Collections and subcollections
- Two-way attachment sync (Zotero file storage and WebDAV; WebDAV is what this fork tests)
- Group libraries
- Create, edit, and delete notes
- Barcode scanner for ISBN lookup
- MVVM architecture, written in Kotlin

---

## Building

1. Copy `apikeys.properties.example` to `apikeys.properties` and supply your Zotero OAuth key/secret from [zotero.org/settings/applications](https://www.zotero.org/settings/applications).
2. Build with Android Studio or `./gradlew assembleDebug`.

**Requirements:** Android 7.0+ (API 24), JDK 17+.

---

## Roadmap

See [dependency-modernization-plan.md](dependency-modernization-plan.md) for the full status. In short, the Kotlin 2.1 / KSP / Hilt / RxJava3 / Compose+Nav3 modernization is largely done; what remains is:

- Finish the RxJava3 → Coroutines migration (the app is currently a hybrid)
- Retire the MVP library core (`LibraryActivityModel` god class) in favour of MVVM
- Minor dependency tidy-ups (Retrofit, the legacy FAB library, OAuth stack)

---

## Credits

- [Michael Johnston](https://github.com/mickstar) — original Zoo for Zotero
- Zotero graphical resources — [zotero.org](https://www.zotero.org) (Roy Rosenzweig Center for History and New Media)
