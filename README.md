# ComicReader

A native Android reader for **CBZ, ZIP, CBR, RAR and PDF**, with offline reading and direct MEGA, Google Drive and HTTPS WebDAV access. Requires Android 9 or later.

[Download the latest release](https://github.com/Poiki/ComicReader/releases/latest) · [ARM64 APK](https://github.com/Poiki/ComicReader/releases/latest/download/ComicReader-arm64-v8a.apk)

## Install

Use the **ARM64** APK for ARM64 devices. Releases also include x86_64 and universal APKs, plus `SHA256SUMS`.

Install over your existing version to keep your library, accounts and progress. Do not uninstall first. The app checks GitHub Releases whenever it opens in the foreground; use **Settings → App updates → Check now** to retry immediately. Android asks you to confirm installation.

[Version 0.7.2](https://github.com/Poiki/ComicReader/releases/tag/v0.7.2) reloads missing MEGA folders before reporting failure and keeps automatic refresh errors inside the library. See its release notes for validation limits.

Releases currently retain the original **test signing certificate**. The signing key is private and is not included in this repository. Play Protect rejected a previous emulator installation; successful download and signature verification do not guarantee system approval. See [testing](docs/testing.md).

## Read

- Import local files with **+**, or choose **MEGA**, sign in, navigate to a folder and select **OK** to save it as your library.
- Browse subfolders and filter by reading, unread, read or favorites. Tap a remote comic to download an offline copy; hold a comic to change its reading state or delete only its local copy.
- Tap the sides of a page to turn, or the center to toggle controls. Pinch or double-tap to zoom. The reader includes manga direction, bookmarks, page jumps, brightness and optional volume-key navigation.
- Browse source cards and reading filters, then use Library, Recent, Favorites and Downloads in the bottom navigation. Cover tiles include file actions; the floating **+** imports files or connects a provider.
- Choose list or cover view, light/dark/OLED themes, blue/orange/teal accents, and whether startup opens your library, connections or the last unfinished comic. Settings open as a full-screen, searchable page with five groups and inline switches.
- Reader controls provide direct bookmarks, brightness, reading direction, page fit and volume-key navigation. The page stays clear of visible controls; the progress bar is optional. New installations default to OLED and blue.
- English and Spanish follow the device language. Choose **Settings → App language** to override it; Android 13+ also exposes the app language in system settings.
- Opening the app starts background cover preparation, including subfolders of the saved MEGA library. It continues while reading or outside the library screen; visible covers update as they arrive. Android may defer or pause it for network, battery or scheduling limits. If MEGA has no thumbnail, generating a cover downloads the comic once and discards the temporary file. This uses transfer quota. Default: Wi-Fi/unmetered networks; change it under **Settings → MEGA covers**.
- Direct cloud comic downloads also default to Wi-Fi/unmetered networks. Enable mobile data under **Comic downloads** if wanted. A download stops if its connection becomes disallowed.

After upgrading from a version that hid CBR files, use **Settings → Refresh library** to retrieve the folder listing again.

MEGA needs no application key for the bundled SDK. Google Drive requires your own OAuth registration. Progress sync is opt-in, uses one selected provider, and respects unmetered-network and battery constraints by default. It syncs reading state, not your comic files. [Cloud setup](docs/cloud-setup.md) · [Sync format and conflicts](docs/synchronization.md)

## Build

Prerequisites: **JDK 21**, Android SDK **platform 37**, Build Tools **36.0.0**, and Docker Desktop with its Linux engine. Set `JAVA_HOME` and `ANDROID_HOME`. Gradle and dependency versions are pinned.

```powershell
# First build: compile the official MEGA SDK into the local cache.
.\scripts\build-mega-native.ps1

.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

APKs are written to `app/build/outputs/apk/debug/`. Release builds use R8 and remain unsigned until the publication script signs them. [Build and test guide](docs/building.md) · [Release process](docs/updates.md)

## Limits

Comics are downloaded before reading. Limits are **1 GiB per comic**, **10,000 pages**, **64 MiB per archive image** and **3 megapixels per decoded bitmap**. RAR archives must be single-volume and unencrypted, with a dictionary of at most 64 MiB and total unpacked data of at most 4 GiB. Solid RAR files can take longer to seek backwards.

7z, continuous scrolling, double-page spreads and automatic series navigation are not implemented. Battery life and performance have not been benchmarked on physical hardware. Local comic copies and cached covers have no additional content encryption. Concurrent offline progress conflicts are resolved per comic, not per field.

## Project

- [`app/`](app/) — Android UI, reader, library, cloud clients and tests.
- [`mega-sdk/`](mega-sdk/) — official MEGA SDK bindings and native-library integration.
- [`scripts/`](scripts/) — native build, Java formatting, release publication and test-fixture generation.
- [`docs/`](docs/README.md) — setup, architecture, protocol and verification guides.

Contributions: [CONTRIBUTING.md](CONTRIBUTING.md). Original project code is [MIT licensed](LICENSE); dependencies retain their own terms, including Google's SDK terms and Junrar's UnRAR license. [Third-party notices](THIRD_PARTY_NOTICES.md)
