# Changelog

## 0.5.2

Updates 0.5.1 in place (same app id and signing key).

Changed:
- Bulk downloads show in the sync dialog, not under the title: while they run the
  toolbar's sync icon turns as it does during a sync, and tapping it opens the sync
  dialog with the downloads' progress and Stop downloads under the sync. Manage
  attachments no longer shows the downloads itself.
- The sync button stays in the toolbar, first, so it no longer appears and disappears
  or moves the buttons beside it. A tap opens the sync dialog in any state: edits
  waiting (they show as a dot on the icon), the running or last sync, the downloads,
  and Sync now. Pulling the list down syncs at once.
- Dialogs: the text under the title starts where the title does (it was 4dp further
  left), in the item dialog too.
- Signing out with edits waiting says how to sync first: pull the list down, or tap
  the sync icon and Sync now.

Security:
- Signing in with a Zotero account accepts Zotero's answer only for the sign-in it
  started (the request token is checked).

Fixed:
- Bulk downloads could delete a file already on the device, edits and all, if the app
  was stopped while it checked whether that file was there: the next round took it for
  a half-finished download. Only files actually being downloaded count as that now.
- In App storage, a downloaded file is moved into place in one step rather than
  copied, so a half-written copy can't be left behind; downloads also write half as
  much.
- Downloads from Zotero's file storage stop before they would leave less than 100 MB
  free, as WebDAV downloads already did, and say so.
- A WebDAV upload whose item changed in Zotero during the upload (a tag added
  elsewhere) now records the new file on the item; it was left out, and the next upload
  took the file for a change made on another device.
- A sync asked for while another runs no longer replaces one already waiting: a group
  refresh and My Library's sync both run, one after the other.

Removed:
- The hidden "Re-download library" action and its code.

## 0.5.1

Updates 0.5.0 in place (same app id and signing key).

Changed:
- Settings' descriptions checked against what each setting does. Coloured and emoji
  tags lead an item's second line (they moved there in 0.5.0). "Upload edited
  attachments" covers any changed file and WebDAV, not only PDFs and Zotero. "Upload
  timeout" is "Write timeout": it limits how long sending to Zotero's server may stall,
  not how long an upload takes, and file uploads have their own limits. "Check files
  before opening" says it applies to files the app has no record of downloading. The
  keep-search and keep-filter switches describe what On does, as the others do. Signing
  out with edits waiting says to tap the sync icon.
- Manage attachments: Download and Remove are full-width buttons under the kinds, with
  their counts in words ("Download 6,183 files"); only Close is left at the bottom. With
  the counts on three buttons in a row, a phone stacked them.
- Every dialog now follows Manage attachments: a line on what it applies to or how it
  works, grey hints, choices with a grey second line where they need one, and the action
  as a full-width button that says what it does ("Apply 2 changes", "Apply filter",
  "Sort", "Search", "Sync now", "Add tag", "Save note", "Remove 5 unedited files"), with
  only Cancel or Close left at the bottom. In the dialogs with long lists (filter, tags,
  search, sync) the button stays below the list, which scrolls, so it is on screen on a
  phone with the keyboard up. Clear (filter, search) shows only while there is something
  to clear. Search, Filter, Sort and the note dialogs used Android's older dialog, whose
  title looked different on newer Android versions; all dialogs are the same kind now.
- A dialog's buttons, Cancel or Close included, sit on one line when all their labels
  fit (a tablet, short labels). When they don't, the coloured ones go side by side or
  stack, with Cancel or Close under them. Cancel or Close is always at the right, the
  other buttons to its left, so it stays put (Close doesn't move when Sync now goes as a
  sync starts). On one line, the buttons keep their own width, with Cancel's spacing
  around the label, from the left. Decided as the dialog opens; while it is open
  they may stack if a label grows, but don't jump back.
- Settings' dialogs are built like the library's: Theme, List rows, Second line and
  Attachment folder list their choices with Apply and Cancel (a tap no longer applies at
  once); Write timeout shows a mistake under its field; changing the attachment folder
  and signing out ask with full-width answers.
- The WebDAV server opens as a dialog over Settings instead of its own screen: address
  and login, Advanced for the rest, then Save and connect, Remove server (while one is
  set up) and Cancel. It closes once the connection works.
- "Change collections" replaces "Add to collection" and "Remove from this collection":
  one screen, like "Add and remove tags", with a field to find a collection and a box per
  collection that shows whether all, some or none of the selected items are in it. Tap to
  put them all in, again to take them all out; nothing changes until Apply. Adding no
  longer happens on the first tap, and items can be taken out of any collection, not only
  the open one.
- Pulling the list down shows no round spinner any more: the toolbar's sync icon fades
  in and turns as you pull, snaps (with a short vibration) once letting go would sync,
  and keeps turning as the sync runs. One sign for one sync, where the spinner and the
  turning icon showed it twice.

Security:
- WebDAV: the login goes only to the server's own scheme, host and port. A server set up
  with https:// that redirected to http:// on the same host was sent the password
  unencrypted.
- A linked file's path (set on another computer, or by any member of a group) can't
  reach outside the attachment folder: "..", "." and empty parts are dropped.

Fixed:
- Editing a note showed its HTML ("<div data-schema-version=…><p>…") and saved what was
  typed as markup. A note of plain paragraphs now opens as text and is saved back as
  paragraphs; a note with formatting (headings, bold, links, citations, images) isn't
  opened for editing, as saving it as text would strip that everywhere: edit it in
  Zotero. A change that failed to save no longer shows as saved.
- Edit note from an item's note opened a second editor after the first; one now.
- No crash when no app opens a linked file, or when a file finishes downloading while
  the app is in the background and no app opens it: the app offers a PDF reader.
- A bulk download that Android stopped mid-file no longer deletes that file when it
  resumes, if it was downloaded from the attachment menu in the meantime.
- An item trashed in Zotero while the trash was syncing could be missed for good.
- A file Zotero refuses to send (missing, 404) no longer leaves its connection open.
- The 0.5.0 move out of shared storage takes only attachment folders.
- Removing one file from the device no longer runs on the main thread.
- Choosing another attachment folder releases the app's access to the old one.

Faster:
- Bulk downloads no longer rewrite their whole queue for every file (a whole library's
  queue is thousands of entries): the list is written when it changes, the progress in a
  small file beside it.
- The trash syncs 100 items per request instead of 25.

## 0.5.0

Updates 0.4.3 in place (same app id and signing key). Downloaded files move to the
app's private storage at the first start (see below); they open as before and don't
count as edited.

Changed:
- Selected items: "Add tag" and "Remove tag" are one screen, "Add and remove tags". It
  lists the tags on the selection, each with a box that shows whether all, some or none
  of the items have it; a tap puts the tag on all of them, the next takes it off all of
  them. The field finds any tag in the library or adds a new one, and your coloured tags
  are listed first. Apply makes every change at once.
- Selected items: "Download attachments" and "Remove attachments from device" are one
  screen, "Manage attachments". It counts the selection's files by kind (PDFs, HTML
  snapshots, other attachments) with how many are on this device; Download and Remove act
  on the kinds ticked, and while downloads run it shows how far they are, with Stop. It
  copes with a whole library selected: the downloads are queued and the files removed
  off the main thread, with progress.
- "Manage attachments" is also in the library's menu, where it acts on every item the list
  shows: the whole library, a collection, or what a search or filter leaves.
- Settings → Attachments no longer has "Remove all files from this device": Manage
  attachments in each library's menu does it, with a choice of kinds and the edited
  files named before anything is removed.
- Dialog buttons follow one pattern: the button that acts names what it does (Apply,
  Add, Remove, Download), Cancel backs out of anything that would change something, and
  Close leaves a dialog that only shows things or acts through its own buttons. Filter
  and Sort say Apply rather than Done; removal prompts say Cancel rather than Keep.
- "N edits to sync" and "Syncing 45%" open the same sync dialog: the edits waiting, then
  the running or last sync stage by stage (or when the library last synced), with Sync
  now while no sync runs. When a sync ends, the dialog lists what is still waiting.
- The sign-in screens are built like the rest of the app (they used Jetpack Compose,
  which the app now leaves out): they look the same, the API key field no longer opens
  the keyboard before it's tapped, and Zotero's login page survives turning the device.
- Item-type icons are Zotero 7's, sharp at any size, with their dark-mode versions.
  Datasets, preprints and standards get their own icons; an item's attachments show
  Zotero's icon for their kind (PDF, EPUB, web snapshot, linked file, link).
- WebDAV setup warns, under the address, when it starts with http://: the password
  and files travel unencrypted.
- "App storage" for downloaded attachments is now the app's private storage. Before, it
  was the app's folder in shared storage, which on Android 7 to 9 any app with storage
  permission could read, and which Android could empty to free space. Files already
  downloaded move there in the background at the first start after the update, keeping
  their dates (so they don't count as edited); until each is moved it opens from where it
  was, and if space runs short the rest wait for a later start. Opening a file in another
  app, and saving changes back from it, work as before. A file manager no longer sees
  these files: to keep them visible, choose a folder in Settings → Attachments.
- The sidebar's section titles ("Collections", "Group libraries") look like the headers in
  Settings and dialogs, and its icons are grey beside the text, coloured only for the
  library or collection open.
- The app reopens with the search and filter it was left with, in the collection it
  was left in, whether or not they are kept across collections. Before, only the
  collection came back.
- The toolbar shows syncing the same way at any width: the sync icon turns while a sync
  runs (it said "Syncing 45%" on tablets), and a tap shows its details. The spinner that
  pulled down over the list is gone: pulling down still starts a sync.
- While edits wait to sync, the toolbar shows the sync icon with a dot (it was a cloud on
  phones, "2 edits to sync" on tablets); a tap lists them, with Sync now. With nothing
  waiting and no sync running there's no sync icon: pull the list down to sync.
- An empty collection says "Pull down to sync", as an empty library already did: items
  added elsewhere arrive with a sync.
- Coloured and emoji tags show at the start of an item's second line (creator, year…),
  not before its title, so every title starts in the same place.
- A double tap on the toolbar takes the list back to its top.
- "Clear search and filters" is no longer in the menu. Settings → Library → "Tap the
  title to clear search and filter" (off at first) makes a single tap on the toolbar's
  title clear them; the search and filter screens keep their Clear buttons.
- "Stop downloads" is no longer in the menu: Manage attachments shows the downloads at
  work, with Stop.
- The library's menu is Filter and Sort (on phones), Select items, Manage attachments:
  nothing in it comes and goes any more.
- Settings is at the foot of the sidebar, below the libraries and collections, rather
  than in the menu.
- The menu while selecting starts with Stop selecting, then Manage attachments, then the
  tag and collection actions, so the entries that are always there keep their places.
- The app icon is zoomed in on the suit.

Fixed:
- A file saved again while it was being uploaded to WebDAV could reach the server with a
  `.prop` describing the earlier version. The upload now checks the bytes it zipped and,
  if they changed, sends nothing and tries again at the next sync.
- An item opened during a first sync, before its attachments had arrived, closed and
  opened again when they came, scrolled to the bottom. They now appear in the open
  dialog, which stays where it was.
- Back in Settings closed the app instead of returning to the library, after you'd
  switched to another app and back.
- The item screen no longer makes numbers (pages, years, ISBNs, DOIs) into links that open
  the phone app. Web and email addresses are still links.
- "Select items" and "Stop selecting" let the menu close before the toolbar changes:
  the closing menu showed the other mode's items for a moment. Selection mode also
  starts and ends without the pause it had.

Removed:
- The crash-report code (ACRA), which was never switched on. Report crashes as GitHub
  issues.

## 0.4.3

Updates 0.4.2 in place (same app id and signing key).

Added:
- Settings → About: the version (tap to copy it with the Android version and device),
  Report a problem (a GitHub issue with those filled in), source code and licence.
- Adding a tag (to one item or to the selection): your coloured tags are buttons above
  the field, in Zotero's order; one tap adds that tag. Tags the item has are left out.

Changed:
- Settings: Syncing comes before Attachments.
- The toolbar is spaced like the list below it: the menu button as far from the left
  edge as ⋮ is from the right, and the title closer to it.

Security:
- WebDAV: clearing the server address (Save with the address empty) also switched
  "Verify the server's certificate" off, so the next server you set up went
  unchecked. It now stays on. If you ever cleared your server and set it up again,
  open Settings → WebDAV server → Advanced and check that the switch is on.
- WebDAV: your username and password go only to the server you set up. If that
  server redirected to another host, the other host could ask for them and get them.
  A server that redirects to a different host name now fails to sign in: enter the
  address it redirects to.
- The Zotero sign-in page shows only zotero.org, over https. Links to other sites
  open in your browser; other kinds of link are ignored.

Fixed:
- Signing out no longer freezes the app while it clears a large library: it runs in
  the background behind a "Signing out…" message.
- Two rare crashes: a network error arriving after a download was cancelled, and a
  failed reload of the library from the database.

## 0.4.2

Updates 0.4.1 in place (same app id and signing key).

Changed:
- Sync now in the "N edits to sync" dialog keeps the dialog open: it becomes the
  sync's details (your edits, then the download), updated as the sync goes on, and
  stays until you close it.
- Less space beside the item type icons in the library list, scaled to the row
  density: the compact list is tighter than the comfortable one. The list now runs to
  the screen's edges, as does the highlight when you tap or select an item.

## 0.4.1

Updates 0.4.0 in place (same app id and signing key).

Changed:
- On a narrow screen (a phone upright) Filter and Sort are always in the library's
  menu, rather than moving between the toolbar and the menu; from 600dp wide (a phone
  on its side, a tablet) they are toolbar buttons beside Search.
- The filter lists your coloured tags first, with their dots, in their Zotero order
  (as at the top of Zotero's tag selector), then the ticked tags, then the rest.

Fixed:
- The filter's tag search is no longer hidden by the keyboard: the whole dialog
  scrolls as one list, and tapping the search moves it to the top with the matching
  tags below it, above the keyboard. Done on the keyboard brings the rest back.
- Removing or adding a tag in an item's dialog keeps the dialog where it was scrolled
  to (at the bottom, by the tags), rather than jumping.

## 0.4.0

Updates 0.3.1 in place (same app id and signing key).

New:
- Settings → Appearance sets up the library list:
  - List rows: Comfortable (as before) or Compact, with one-line titles and smaller
    rows, which fits about a fifth more items on a screen.
  - Second line: Creator (as before), Creator · year, or Creator · year · publication
    or publisher. The publication is the journal, book, proceedings, website and so on;
    a book or thesis shows its publisher or university.
  - Item type icons: on (as before) or off, which starts the text at the edge. While
    selecting, the icons' place shows a tick or an empty circle on every row.
  - Coloured and emoji tags: the tags you coloured in Zotero show as dots in their
    colours before an item's title, and tags with an emoji as that emoji, in Zotero's
    order. On by default. The colours come from Zotero with every sync, for My Library
    and each group.
- Clear search and filters in the library's menu, shown while a search or filter is set.

Changed:
- Appearance is first in Settings, above Library.
- On a phone held upright, the toolbar shows a sync icon while syncing and an upload
  icon for edits waiting to sync, instead of "45%" and "N edits to sync". A long press
  shows the words; a tap opens the same details as before. Wider screens keep the words.

Removed:
- "Upload edited attachments" in the library's menu: the toolbar's "N edits to sync"
  lists the edits and uploads them with Sync now.

## 0.3.1

Updates 0.3.0 in place (same app id and signing key).

New:
- Settings → Attachments → Remove all files from this device, with how many files
  there are and the space they take. Asks first, like removing a selection's files,
  and names edited files not uploaded yet, which can be kept. Stops a bulk download
  that is running.

Changed:
- Settings in a new order: Library, Attachments, WebDAV, Syncing, Appearance, and
  Account (Sign out) last. "Sync method", a list with one choice, is gone; the upload
  timeout moved from Advanced to Syncing.
- The WebDAV server screen looks like Settings: no card, a Server header, switches
  instead of checkboxes, and Advanced as a row that opens below it. The keyboard no
  longer opens with the screen; the password can be shown; shorter help texts.
- Settings → Attachment folder: the choices are now "App storage" and "A folder you
  choose" (were "External Cache" and "Custom Path"). The setting names the folder and
  says what signing out does to its files. Changing it asks first when files are there,
  as they aren't moved; cancelling the folder picker goes back to app storage.
- Removing files from a folder you chose (one file, a selection or all of them) says
  that they are deleted there, and gone for other apps that use it. A single file there
  always asks first.
- Select items, in the toolbar's menu, starts selecting without a long press; Stop
  selecting, in the menu while selecting, ends it like ✕ and Back.
- Removing attachments from the device says "Remove" throughout, as its menu entry
  does: the confirmation, its buttons and the message after it, and in the attachment
  menu of a single file ("Remove from this device").

Fixed:
- Removing a file from a folder you chose left its empty item folder behind, and the
  app still counted the file as downloaded.
- On a large library the sync could end "incomplete" with "Trash: failed (offline)"
  although the device was online: the trash request gave up after 10 seconds, and a
  slow answer was reported as being offline. It now waits as long as the item pages
  do (30 seconds), and a slow answer reads "Zotero didn't answer in time".

## 0.3.0

Updates 0.2.3 in place (same app id and signing key).

New:
- Select items in the library list: a long press starts, a tap adds or removes, and
  the toolbar shows how many are selected, with buttons to Select all and Invert
  selection; ✕ or Back ends it. Only items in the list on screen can be selected, so
  an action never reaches one you can't see. While selecting, a long press selects
  everything from the item pressed before up to it, like Shift-click on a computer;
  after unticking an item, the same long press unselects the range.
- Bulk actions on the selection, all in the toolbar's menu:
  - Add tag… and Remove tag…, offline like a tag added in the item dialog.
  - Add to collection… (the collections as a tree) and, while a collection is open,
    Remove from this collection. Offline too: the collection shows the change at once
    and the next sync uploads it. Attachments and notes are left out; only top-level
    items can be in collections.
  - Download attachments: PDFs, web snapshots (HTML) or all attachments, each with
    how many are still to download. Files are downloaded in the background, one after
    another, and the toolbar shows how far it got; the app can be left meanwhile, and
    a download cut off (no network, or Android stopping the app) carries on later.
    Stop downloads is in the menu while it runs. This is the bulk download that 0.2.3
    removed with the Attachment Manager, now for whatever you select.
  - Remove attachments from device, by the same kinds. Files with edits that haven't
    been uploaded are named first; you can keep those and delete the rest.
  Edits are My Library only, as before; downloads and deletions work in groups too.

- Sync progress in the toolbar: while a sync runs it shows how far it got ("45%", or
  "Syncing 45%" on wider screens); tap it for each stage's details. "N edits to sync"
  now lists the edits waiting, with Sync now, instead of syncing straight away.
- A first sync opens the library list at once and fills it as pages arrive.
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
- "Remove attachments from device" counts the files it deleted, not the ones it tried, and
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
