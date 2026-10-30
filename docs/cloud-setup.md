# Cloud setup

ComicReader signs in directly to MEGA and Google Drive. HTTPS WebDAV is an optional connection to a server you configure. Comics are downloaded to private app storage before reading; progress sync is a separate, opt-in operation.

## MEGA

The official MEGA SDK is included in every build. Open **MEGA**, enter your email and password, and supply a six-digit second factor if enabled. Navigate to your comics folder and select **OK** to save it as the library root. The app remembers that folder and caches listings for browsing while offline. Use **Settings → Refresh library** to retrieve changes, including CBR files hidden by older versions.

No application key is needed by the pinned SDK implementation. The app passes `null`; it does not reuse another app's keys or native binaries. See the [upstream issue](https://github.com/meganz/sdk/issues/2706) and [pinned SDK](https://github.com/meganz/sdk/tree/v10.21.0).

Passwords and second-factor codes are not stored. The saved session is encrypted with AES-GCM using Android Keystore. The SDK uses a writable cache under `getNoBackupFilesDir()/mega-sdk`; passing a null cache path would use the working directory rather than disable caching. The SDK's internal cache is not claimed to use the app's Keystore encryption. Session data and library metadata are excluded from Android backup.

Pausing the library or completing a sync requests local logout while preserving the encrypted session. Disconnecting attempts to revoke the remote session, clears the saved session and library selection, and reports a failed remote revocation. Local comics and reading progress remain available.

The catalog identifies remote files by account and handle. Read/favorite marks made before download stay local until the file's SHA-256 is known. Downloaded comics use the same content identity and reading state as local imports. Remote names are never used as local filesystem paths.

Opening the app requests a one-shot background cover job, even when startup goes to connections or the last comic. It prepares local books and traverses subfolders of the saved MEGA root without needing visible comic cells. It can continue after the library Activity closes, and visible covers update as files arrive. Native thumbnails are used first; otherwise a temporary full comic download generates the first-page image and is deleted. This uses MEGA transfer quota and can take time for large or solid archives.

The hard cache limit remains 32 MiB/2,000 files. Background preparation stops at 24 MiB/1,500 files, reserving room for interactive reading and avoiding repeated eviction/download cycles in large libraries. Cached images are skipped; each remote traversal is limited to 50,000 entries. Covers remain available offline.

**Settings → MEGA covers** retains unmetered networks (default), mobile data as well, or downloaded comics only. Low battery/battery saver prevents background generation. Explicit downloads, sync and missing visible covers interrupt the batch and take priority; the batch can be scheduled again afterward. Android stops jobs when their network/storage constraints no longer hold. JNI-owned files remain until terminal callbacks. Cover generation neither creates reading progress nor marks comics as downloaded.

Android 12+ expedited scheduling requests prompt startup; quota rejection retains a regular constrained job. Older Android uses regular scheduling. Expedited jobs reject battery-not-low constraints, so the worker and battery receiver enforce this policy; regular jobs also declare the constraint. Android can postpone or stop execution. Reboot/force-stop does not start a daemon; opening the app requests work again. See the [platform scheduling contract](https://developer.android.com/reference/android/app/job/JobInfo.Builder#setExpedited(boolean)).

Direct MEGA, Drive and WebDAV comic downloads use a separate **Allow comic downloads on mobile / metered networks** switch, off by default. A shared foreground guard checks the initial connection and watches changes during the download. Loss of an allowed network interrupts the worker, reports the connection change and leaves partial files out of the library. This switch governs direct comic downloads, not another app's document provider, small account/listing requests, manual progress sync or a separately confirmed APK update.

Requests wait at most two minutes and transfers at most ten minutes. Cancellation retains files until the SDK's terminal callback, since native code may still use them. Pending requests prevent overlapping operations. Cached listings remain visible after a refresh failure, with an explicit status message.

## Google Drive

Drive uses Google Identity Services `AuthorizationClient` and Drive API v3. A device with compatible Google Play Services and your own OAuth registration is required.

1. Create a Google Cloud project and enable **Google Drive API**.
2. Configure the consent screen and add development test users in Google Auth Platform.
3. Register an **Android OAuth client** for package `org.comicreader.app` and the SHA-1 fingerprint of the certificate signing the installed APK. GIS identifies the app by this pair; no client ID or client secret is pasted into Java.
4. Register each distribution certificate separately. With Play App Signing, use the certificate of the distributed app.
5. Test with an authorized account on a device with Google Play Services.

Check your own build with `./gradlew.bat :app:signingReport`. The current published APKs retain this original test certificate:

```text
Package: org.comicreader.app
SHA-1: 5D:B7:A7:3A:F5:87:1D:3A:58:05:59:E8:AC:9D:53:33:8E:7C:93:C4
```

A different keystore changes this fingerprint. Follow the [Android authorization guide](https://developer.android.com/identity/authorization).

| Scope | Purpose |
| --- | --- |
| `https://www.googleapis.com/auth/drive.readonly` | Browse folders and download selected comics without changing originals |
| `https://www.googleapis.com/auth/drive.appdata` | Read and write this app's hidden progress snapshots |

`drive.readonly` is restricted. Public use may require Google's verification and additional review; successful development login does not establish approval. `appDataFolder` belongs to this app's OAuth identity and cannot expose another reader's private state. See [scope requirements](https://developers.google.com/workspace/drive/api/guides/api-specific-auth) and [application data](https://developers.google.com/workspace/drive/api/guides/appdata).

The access token stays in memory; Play Services manages the account session. Disconnecting posts the token in the body to Google's revocation endpoint and clears its GIS cache. Revocation may affect all grants for that Cloud project, so use a dedicated project. See [OAuth revocation](https://developers.google.com/identity/protocols/oauth2/native-app#tokenrevoke).

Folder listing is bounded to 128 pages and 10,000 children. Repeated cursors, duplicate IDs, malformed responses and incomplete searches fail explicitly. Shared-drive navigation, shortcuts and Google Workspace document export are not implemented. HTTPS uses normal certificate validation and does not redirect bearer tokens to another host. Background authorization fails with a request to reconnect when interaction is required; jobs do not open login dialogs.

## HTTPS WebDAV

Configure an HTTPS server, a dedicated folder and that server's credentials in **Connections and setup**. Credentials are encrypted with Android Keystore. Use the WebDAV server's credentials, not a MEGA or Google password. TLS verification remains enabled.

## Progress storage

Choose one sync provider in settings. Drive stores one `{device-uuid}.crp` per installation in `appDataFolder`; MEGA and WebDAV use `.comicreader-state-v1/`. Each device updates its own snapshot. Limits are 128 snapshots, 8 MiB per document and 64 MiB downloaded per sync.

Duplicate remote state folders/files are rejected rather than automatically deleted. MEGA account versioning may retain older snapshot versions; the app does not prune them. See [synchronization](synchronization.md) for validation, conflicts and manual export.

Automated tests cover local protocol behavior and native SDK loading. Live account login, revocation and synchronization have not been verified by the test suite; Google OAuth setup remains an owner task.
