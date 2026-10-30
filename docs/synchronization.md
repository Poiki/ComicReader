# Progress synchronization

ComicReader identifies each comic by the SHA-256 of its complete file contents. It syncs page position, page count, completion, favorites and bookmarks. It does not upload comic files, titles, paths, credentials or account sessions. Repacking an archive changes its identity even when its images look the same.

Enable sync and select one provider in settings. Automatic work uses Android `JobScheduler` with network and battery constraints; unmetered networks are the default. Manual sync reports failures directly. Export/import through Android's document picker provides an independent progress backup.

## Snapshots and conflicts

Each installation has a stable device UUID and writes one mutable `{device-uuid}.crp` snapshot. Drive uses its private `appDataFolder`; MEGA and WebDAV use `.comicreader-state-v1/`. Sync is serialized within the process. This is a snapshot protocol, not an event journal.

For each comic, the greatest `(revision, device UUID)` pair wins. Revisions are logical counters, independent of wall-clock time. The entire winning record replaces the losing record: concurrent changes to unrelated fields, such as a bookmark and a favorite, can overwrite one another. The reader applies only its locally changed fields to the latest SQLite record when saving, avoiding stale in-process replacements.

All downloaded snapshots are validated and the merged result checked for exportability before the SQLite transaction. An upload failure leaves accepted local changes intact and reports sync as pending. An absent record is not a deletion instruction. Remote duplicates are reported rather than removed automatically.

Pre-download MEGA read/favorite marks are local catalog metadata until the content hash is available. CRP1 does not import another reader's private cloud state.

## CRP1 binary format

Integers use Java `DataOutputStream` big-endian encoding. Strings use `writeUTF`: a two-byte unsigned length followed by modified UTF-8. The permitted identifiers are ASCII, so their encoded byte lengths are fixed.

| Field | Encoding and constraint |
| --- | --- |
| Magic | Four bytes: `CRP1` |
| Record count | Signed 32-bit integer, 0–50,000 |
| Comic ID | `writeUTF`, 64 lowercase hexadecimal SHA-256 characters |
| Device ID | `writeUTF`, 36-character UUID |
| Revision | Signed 64-bit integer, 1–1,000,000,000,000 |
| Page | Signed 32-bit integer, zero-based and below page count |
| Page count | Signed 32-bit integer, 1–10,000 |
| Completed | One-byte boolean |
| Favorite | One-byte boolean |
| Bookmark count | Signed 32-bit integer |
| Bookmarks | Sorted, unique zero-based 32-bit page indices within the page count |

Fields after record count repeat once per record. Duplicate comic IDs, malformed identifiers, invalid ranges, truncated data and trailing bytes are rejected. Imported page counts must match a local comic when present. A remote revision more than ten million beyond the local logical clock is rejected rather than silently resetting that clock.

Limits: **8 MiB per document**, **64 MiB downloaded per sync**, **128 device snapshots**, **50,000 records**. Failure does not permit partial snapshot import. Keep manual exports if you need recovery from account deletion or competing offline edits.
