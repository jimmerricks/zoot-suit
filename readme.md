# Zoot Suit

A personal fork of [Zoo for Zotero](https://github.com/mickstar/Zoo-For-Zotero) by Michael Johnston, an Android client for browsing and syncing Zotero libraries.

**Upstream:** `mickstar/Zoo-For-Zotero` — forked at v4.0.0 (commit `0261040`)  
**License:** GPLv3 (inherited)  
**Not affiliated with the Zotero project.**

---

## What this fork does differently

| Change | Upstream | This fork |
|---|---|---|
| App name | Zoo for Zotero | Zoot Suit |
| minSdkVersion | 26 (Android 8.0) | 24 (Android 7.0) |

Planned divergences are tracked in [dependency-modernization-plan.md](dependency-modernization-plan.md).

---

## Features (inherited from upstream)

- Browse and search your Zotero library
- Collections and subcollections
- Two-way attachment sync (Zotero file storage and WebDAV)
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

See [dependency-modernization-plan.md](dependency-modernization-plan.md) for the staged migration plan:

- **Phase 1** — Safe dependency updates (AndroidX, Hilt, Kotlin 2.x, KSP)
- **Phase 2** — Replace RxJava2 with Coroutines throughout
- **Phase 3** — Compose UI migration

---

## Credits

- [Michael Johnston](https://github.com/mickstar) — original Zoo for Zotero
- Zotero graphical resources — [zotero.org](https://www.zotero.org) (Roy Rosenzweig Center for History and New Media)
