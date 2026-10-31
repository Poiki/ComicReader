# Architecture

ComicReader is a native Java Android app with SQLite storage. It has no web UI, analytics SDK, storage-wide scanner or persistent service.

## Source map

All first-party classes live in `app/src/main/java/org/comicreader/app`.

| Area | Classes |
| --- | --- |
| Library UI and navigation | `MainActivity`, `Ui`, `AppLanguage`, `CoverCache` |
| Reading and rendering | `ReaderActivity`, `PageView`, `PageSource`, `RarPages` |
| Local data and import | `Library`, `Progress`, `ComicFiles`, `Importer` |
| Cloud and account access | `Cloud`, `SyncJob`, `Secrets`, `DriveAuth`, `DriveClient`, `MegaClient`, `MegaLibrary`, `WebDav`, `TransferFile` |
| Release updates | `ReleaseInfo`, `Updater`, `UpdateProvider` |

UI text is defined in English and Spanish Android resources. `AppLanguage` uses the Android 13+ locale framework, with a context override on API 28–32. Protocol fields, filenames, database columns and preference keys are not translated. Provider/technical diagnostics can retain their original text.

## Library and import

Imported files are hashed once with SHA-256, validated and moved or copied into private app storage. The hash identifies progress across providers and duplicate imports. Local comic contents have no additional encryption. Deleting a copy preserves progress and leaves the original external file intact.

`Library` owns `library.db`, containing local books, progress records and a logical revision clock. The separate `mega-library.db` caches account-scoped handles, folder listings and content-hash links. A missing MEGA folder triggers one authenticated tree reload before failure. A failed refresh retains the previous listing; automatic checks show a retryable inline status, while explicit refreshes also report errors in a dialog. Choosing a folder stores its root; subfolder browsing fetches only folders the user visits.

## Reader

The UI requests rendering on a serial worker. At most the active request and latest pending render remain relevant; stale work cannot replace a newer page. One page is prefetched in the current direction unless the app is paused, Android reports low RAM or battery saver is enabled.

Decoded bitmaps are capped at 3 megapixels; the reader cache is 24 MiB. `CoverStore` owns an application-context 8 MiB memory cache and 32 MiB/2,000-file disk cache, with thumbnails at most 192×288. It serializes generation so UI and background requests reuse a result. `CoverCache` keeps weak cell targets, a 32-entry UI queue and a coalesced directory observer. Closing an Activity cancels its view work without clearing shared cover data.

`CoverJob` is a launch-triggered, one-shot `JobScheduler` service. It prepares local books and traverses the saved MEGA root independently of Activity lifetime. A 24 MiB/1,500-file background budget reserves space for interactive requests and prevents eviction loops. Explicit UI work and sync take priority through a balanced cancellation gate; completion can schedule the batch again. Native file-ownership rules and provider/decoder validation remain intact. Expedited startup has a regular constrained alternative for older Android or rejected quota. Battery checks remain in the worker/receiver because expedited jobs disallow that scheduler constraint.

Stopping the reader clears its visible bitmap references and reader cache. A decode already in progress may finish; RAR decoder state can remain until reader destruction.

ZIP uses `ZipFile`, PDF uses Android `PdfRenderer`, and RAR uses Junrar with a 64 MiB dictionary budget. RAR pages are extracted synchronously into a declared-size array, never into archive-provided filesystem paths. Solid predecessors are replayed and verified explicitly. Backward seeks in solid archives can cost more CPU.

## Cloud and lifecycle

Network work stays off the UI thread. `Secrets` encrypts saved sessions and WebDAV credentials using Android Keystore/AES-GCM. Google access tokens remain in memory. Account data is excluded from backups and device transfer. MEGA receives an explicitly writable private no-backup directory for its native cache; its internal cache is not described as wholly encrypted by Keystore.

Progress sync is opt-in and coalesced through a non-periodic `JobScheduler` task. Default constraints require an unmetered network and a battery that is not low. Manual sync is explicit and may use mobile data. MEGA retains its loaded tree while a library Activity is resumed. Idle cleanup on library pause or after background work requests local logout while keeping the encrypted resumable session; it defers if a browser, native request or transfer is active. Canceled cover work leaves session cleanup to the next idle opportunity. Explicit sign-out still revokes the session.

The update client uses its own worker, checks on each foreground open and reuses OkHttp. It has no recurring job or permanent download service. The private update provider exposes only one verified cached APK through read-only URI grants.

## Data boundaries

Comics are limited to 1 GiB, 10,000 pages and 64 MiB per archive image. Image dimensions are checked before allocation. RAR additionally limits total unpacked data to 4 GiB and rejects encrypted or multi-volume archives. Cloud parsers bound response sizes and entries, reject duplicates and confine URLs. TLS verification stays enabled. Download cancellation preserves temporary files still owned by JNI until terminal callbacks complete.

See [synchronization](synchronization.md) for record validation and conflict semantics, and [testing](testing.md) for what has actually been verified.
