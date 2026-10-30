# Changelog

## Unreleased

New:
- Select items in the library list: a long press starts, a tap adds or removes, and
  the toolbar shows how many are selected. Select all, Invert selection and Clear
  selection are in its menu; ✕ or Back ends it. Only items in the list on screen can
  be selected, so an action never reaches one you can't see.
- Bulk actions on the selection, all in the toolbar's menu:
  - Add tag… and Remove tag…, offline like a tag added in the item dialog.
  - Add to collection… (the collections as a tree) and, while a collection is open,
    Remove from this collection. Offline too: the collection shows the change at once
    and the next sync uploads it. Attachments and notes are left out; only top-level
    items can be in collections.
  - Download attachments…: PDFs, web snapshots (HTML) or all attachments, each with
    how many are still to download. Files are downloaded in the background, one after
    another, and the toolbar shows how far it got; the app can be left meanwhile, and
    a download cut off (no network, or Android stopping the app) carries on later.
    Stop downloads is in the menu while it runs. This is the bulk download that 0.2.3
    removed with the Attachment Manager, now for whatever you select.
  - Delete files from this device…, by the same kinds. Files with edits that haven't
    been uploaded are named first; you can keep those and delete the rest.
  Edits are My Library only, as before; downloads and deletions work in groups too.

- Filter: "HTML snapshot downloaded" beside "PDF downloaded" under On this device.
  "PDF downloaded" now means a PDF: it used to match any downloaded attachment.
- The attachment menu (long press) offers only what applies to the file as it stands:
  Download when it isn't here, Check for a newer version when it is, Download the newer
  version when Zotero has one, Upload changes when it was edited here, and only Open for
  linked files and web links.
- Search, filter and sort are hidden over an empty list (an empty trash, say) unless a
  search or filter is what made it empty.

Changed:
- Tag and collection changes go up 50 items to a request instead of one item at a
  time: tagging 250 items syncs in seconds. An addition to a collection deleted in
  Zotero meanwhile is dropped with a message; the item's other changes still go up.

Security:
- The Zotero API key is no longer sent to Zotero's storage host. A file download is
  redirected there, and the key went along with every download; it now goes in a header
  that is dropped when a redirect leaves api.zotero.org.
- Debug builds no longer write the API key to the device log.
- An attachment's filename can't place its file outside the attachment folder. Zotero
  refuses such names today; the app no longer relies on that.
- Signing out also forgets the Zotero sign-in page's cookies, so the next sign-in asks
  for an account, and any queued bulk downloads.

Fixed:
- Downloading Zotero's newer version of a file keeps the copy on the device until the
  new one is complete. A dropped connection used to leave no copy at all.
- Saving a note during a sync no longer starts a second download of the library beside
  the running one.
- A server error while fetching collections shows as a failed sync instead of a
  finished one that fetched nothing.
- WebDAV: the authentication setting (Automatic, Basic, Digest) is saved when chosen and
  shown when the screen opens again. Before, a choice wasn't saved and the field always
  read Automatic.
- WebDAV: an address ending in "/" no longer breaks downloads.
- "Delete files from this device" counts the files it deleted, not the ones it tried, and
  removes their emptied folders.
- A downloaded file is recorded only once it matches Zotero's checksum.
- An upload whose file Zotero already has is recognised however Zotero spaces its answer.
- A first sync's percentage in the toolbar no longer starts again from 0 for attachments
  and notes. The sync asks Zotero how many of each there are as it starts, and each pass
  counts by its share.
- The item dialog shows the dates added, modified and accessed in the device's time zone
  and date format, not as stored ("2026-10-30T01:23:36Z").
- The WebDAV server screen has a toolbar with a back arrow, like Settings.
- Settings: "Back up files before uploading" moved to the WebDAV section and is greyed out
  while WebDAV is off; "WebDAV server" shows the address set up, or "Not set up".

## 0.2.3

Updates 0.2.2 in place (same app id and signing key).

Fixed:
- Signing out names the edits not synced yet that it would delete (new notes, tag
  changes, edited attachments), and says to sync first. Before, it warned in general
  terms only, and tag changes and new notes waiting to sync were lost without a count.
- A file changed in Zotero since the last sync (edited on another device, or uploaded
  from this one) can be downloaded again before the next sync. It was checked against
  the md5 from the last sync, reported as "Download damaged" and deleted.

Removed:
- The Attachment Manager (overflow menu). Its "Download all attachments" downloaded
  every file in My Library, stopped when you left the screen, and could report success
  for downloads that had failed; to see which items have their files on the device, use
  the "on device" filter. Downloading a whole collection or filter at once may come back
  later in a different form.

Changed, inside:
- The code behind the library screen was reorganised into separate parts (lists,
  sync, attachments, item edits, groups), with no change meant to how the app
  behaves. Messages and dialogs raised while the app is in the background, or during
  a rotation, now appear when it is back on screen instead of going nowhere.

## 0.2.2

Updates 0.2.1 in place (same app id and signing key).

New:
- Add notes from the item dialog ("Add note" under Notes), online or offline, like
  tags: the note shows at once and the next sync uploads it. Until then, editing or
  deleting it changes only the device's copy. My Library only.
- Choose the theme in Settings → Theme: auto (follow the system), light or dark. On
  Android 9 and older, which have no system dark mode, auto means light and Dark is
  the way to a dark app.

Changed:
- The item dialog's Share and Add note buttons are gone; notes are added from the
  new row. Close is the dialog's only button.

Fixed, in dark mode:
- Settings, dialogs, checkboxes and switches used the dark brand purple, nearly
  invisible on dark surfaces; they now use a light purple.
- The WebDAV server screen had a white card under light text.
- The overflow menu was a light sheet; it is dark now.
- The sidebar's selected row was unreadable.
- The Attachment Manager's top bar was light purple, unlike the other screens.

## 0.2.1

Updates 0.2.0 in place (same app id and signing key).

Faster on large libraries:
- The first sync downloads up to four pages of items at once, Zotero's documented
  limit: about a quarter of the time it took before (18,300 items in ~2 min instead of
  ~8 on the emulator). If Zotero answers "too many requests", the app pauses all its
  requests and continues one at a time.
- A first sync opens the library once its top-level items are in, then downloads
  attachment details and notes in the background (files still download when opened).
  The list fills in as they arrive, and an item opened meanwhile gets its own at once.
  Until the sync ends, "Has PDF", "Has notes" and searching notes miss what hasn't
  arrived yet. An interrupted sync continues where it stopped, in either part.

## 0.2.0

**New app id: `se.jmwhite.zootsuit`.** 0.1.0 was `nu.n4.zootsuit`. Android treats
the new id as a different app, so 0.2.0 doesn't update 0.1.0: it installs beside it,
with its own sign-in, library and downloads. Sync any edits in 0.1.0 first, sign in to
0.2.0, then uninstall 0.1.0. Releases from 0.2.0 on update each other as usual.

New:
- Add and remove tags in the item dialog, online or offline. The next sync uploads
  them; tags added elsewhere in the meantime are kept. My Library only.
- The item view is a dialog like Filter and Sort, with fields shown as label/value rows.

Faster on large libraries:
- The list is read from the database a page at a time; opening a library no longer
  loads every item into memory.
- Sorting and searching run in the database. The first search after a start no longer
  builds an index of the whole library.
- Downloaded attachments are no longer hashed again at every start to look for edits.
- Tag counts in the filter dialog, and the network client, are prepared off the main
  thread; the sidebar's rows are built after the first screen is drawn.

Fixed:
- A library that changed on the server during a sync could end up with items missing,
  and later syncs said "Already up to date".
- A sync that found nothing new now says so; a sync asked for while another runs is
  run afterwards instead of being dropped.
- Cancelling an attachment download now stops it.
- Opening an item no longer creates empty folders for its attachments, which were then
  counted as downloaded.
- Items that sort equal keep a fixed order.
- Tapping a tag in the item dialog opened a separate tag list that Clear filter didn't
  leave; tags now narrow the list through the filter only.

## 0.1.0

First release of the fork. See [differences-from-zoo.md](differences-from-zoo.md).
