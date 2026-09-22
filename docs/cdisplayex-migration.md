# Migrating a CDisplayEx library into Absolutex — feasibility (§6 M6)

**Status: scoped, not started. One question decides whether it is buildable at all, and it takes
five minutes on a device to answer.**

This is a spike, not a design. It says what a migration would have to do, what Absolutex already
provides, and — the part that matters — which single unknown gates the whole thing. Nothing here
describes CDisplayEx's internals, because none of it can be observed from a build container. Where
that knowledge is required, this says so rather than guessing.

## The blocker is Android, not CDisplayEx

The instinct is "read their database and import the rows". On API 33 that is not available to us,
and no amount of format knowledge changes it.

An Android app's private storage lives under `/data/data/<package>/`, owned by that app's own UID
with `0700` permissions. Absolutex runs as a different UID. We cannot open their database, their
preferences, or their cache — not with SAF, not with `MANAGE_EXTERNAL_STORAGE`, not with any
permission a Play-distributed app can hold. Only root or ADB can cross that boundary, and §1 rules
out touching the user's phone with adb at all.

So **there is no direct migration path.** Whatever we build has to be handed to us by the user,
which leaves exactly three shapes:

| Route | Requires | Feasible? |
|---|---|---|
| A — import CDisplayEx's own export/backup file | that it has one, and its format | **unknown — this is the question** |
| B — read something it wrote to shared storage | that it writes outside its sandbox | unknown, and unlikely on API 33 |
| C — rescan the same folders, start progress fresh | nothing | already works today |

C is not migration — it is what already happens when a user points Absolutex at their comics
folder. Their books appear; their reading positions do not. That is the fallback if A and B fail,
and it is worth saying plainly that it may be the honest answer.

## The one thing to check on the device

**Does CDisplayEx have an export, backup or sync feature, and what does it produce?**

Everything else follows from that. Concretely, in order:

1. Open CDisplayEx, look for export / backup / "sync reading position" in its settings.
2. If it exports, produce a file from a library with at least two books, at least one of them
   part-read, and at least one bookmark.
3. Put that file somewhere Absolutex could read via SAF and note: is it SQLite, XML, JSON, a
   zip? Does it name books by full path, by filename, or by an internal id? Does it record a
   page number, and from which base?
4. If it does **not** export, route A is closed, route B is a long shot, and M6's answer is "not
   feasible; document route C as the migration story".

Until step 1 is answered, anything further is speculation, so this document stops here rather
than designing an importer for a file that may not exist.

## What Absolutex already provides — verified in-repo

The receiving end is not the hard part. It is built and it is the same machinery the Komga and
Kavita sync already uses.

**Identity.** `BookIdentity.of(displayName, sizeBytes)` → `"$displayName:$sizeBytes"`. Everything
keys on it: `reading_progress.bookId`, `bookmark.bookId`, `library_book.contentKey`.

**The useful consequence: we do not need CDisplayEx to record file sizes.** A migration happens on
the device where the books already live, so given a path or even just a filename we stat the file
ourselves and build the identity. The import only needs, per book:

- something that locates the file — a full path, or a filename we can match within the folders the user has granted us
- a page position
- optionally, bookmarks and their page positions

That is a much weaker requirement than "understand their schema", and it is why route A is worth
checking before concluding anything.

**Matching.** `remote/sync/BookMatcher.kt` is the precedent and the rule: match exactly, and when
a record matches zero books or more than one, **leave it alone and never guess** — its own comment
puts it as "syncing to the wrong book is worse than not syncing". A CDisplayEx importer inherits
that rule unchanged. A user who imports and finds three books silently jumped to the wrong page
has been given something worse than nothing.

**Where the rows go.**

| Table | Shape | Note |
|---|---|---|
| `reading_progress` | `(bookId, pageIndex, pageCount, updatedAt)` | `updatedAt` is epoch millis; §5.5 sync is last-write-wins, so an import must set it deliberately rather than "now" |
| `bookmark` | `(bookId, pageIndex, createdAt)`, composite PK | dedupes on its own key |
| `library_book` | `contentKey` = the same identity | populated by the scanner already |

`AbsolutexDatabase` is at version 6 with AutoMigrations throughout and `exportSchema = true`. An
importer needs **no schema change** — it writes rows through the existing DAOs.

## Two hazards worth naming now

**Page base.** `BookMatcher` carries `komgaPageToIndex` and `kavitaPageToIndex` because Komga
counts pages from 1 and Kavita from 0, each verified against that service's source and still on
the device checklist for live confirmation. CDisplayEx's base is unknown and **must not be
assumed**. An off-by-one lands every imported book one page from where the user left it — wrong
in a way that looks like a bug in the reader rather than in the import, and quietly destroys the
position it was supposed to preserve.

**`updatedAt` and last-write-wins.** Imported rows compete with §5.5 sync. Stamping them with the
import time makes a stale imported position beat a fresher one already synced from Komga or
Kavita. If CDisplayEx records its own timestamps, carry them; if it does not, the safer default is
to **not overwrite an existing row** rather than to invent a timestamp that wins.

## What this spike does not claim

- Nothing about CDisplayEx's storage format, schema, file locations or feature set. None of it is
  observable from here, and a plausible-sounding guess is worse than an admitted gap.
- No estimate of effort. Route A's size depends entirely on the export format, which is the
  unknown; quoting a number before step 1 would be inventing precision.
- Whether migration is worth building at all. If CDisplayEx cannot export, the answer is probably
  no, and route C is the story we tell.
