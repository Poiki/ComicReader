# Updates and releases

The app checks the latest public stable release of [Poiki/ComicReader](https://github.com/Poiki/ComicReader/releases) through GitHub's unauthenticated [Releases API](https://docs.github.com/en/rest/releases/releases#get-the-latest-release). Branch contents are not executable updates.

Each foreground open checks when Android reports validated Internet access and automatic checks are enabled. A failed automatic check is recorded in settings and does not interrupt local reading. **Settings → App updates → Check now** retries immediately. There is no daily suppression, permanent service or periodic update job. GitHub's unauthenticated rate limits still apply.

## Download and installation

The first compatible ABI is selected, with a universal fallback. Asset names must be exactly:

- `ComicReader-arm64-v8a.apk`
- `ComicReader-x86_64.apk`
- `ComicReader-universal.apk`

Stable tags use `vMAJOR.MINOR.PATCH`, with each component from 0 to 9999. Every release also increments Android's `versionCode`.

The client rejects drafts, prereleases, malformed metadata, duplicate asset names, APKs larger than 200 MiB and missing SHA-256 digests. The initial URL must match this repository, tag and asset. Redirects are restricted to HTTPS on known GitHub asset hosts. Downloaded size, digest, package name, newer version, minimum Android version and signing certificate are checked before opening the system installer.

The APK stays in private cache. A non-exported provider grants read access to that file through a URI. Canceling permission or installation leaves the download available under **Install downloaded update**. A now-installed version's cached APK is removed on the next open. No installer success is claimed before Android completes it.

Android asks for installation permission and confirmation. Play Protect rejected the final installation in a previous emulator test with `INSTALL_FAILED_VERIFICATION_FAILURE`; download, validation and installer launch succeeded. Protections were not disabled. See [testing](testing.md).

## Publish

1. Preserve the original signing key outside Git. A new certificate cannot update existing installations and is rejected by the client.
2. Update `versionCode` and `versionName` in `app/build.gradle`, then add `docs/releases/vX.Y.Z.md`.
3. Prepare the MEGA and archive native libraries, run the relevant [checks](testing.md), and commit on `main`.
4. With JDK 21, Android SDK and authenticated `gh`, run:

```powershell
.\scripts\publish-release.ps1
```

The script defaults to the original local debug keystore. `-KeyStore` can point to a copy of the same key; `COMICREADER_SIGN_PASSWORD` supplies its password. It pins the expected signing certificate rather than generating a replacement.

Publication requires a clean working tree. The script builds and tests standard and F-Droid variants, signs three APKs for each, checks the certificate and 16 KiB alignment, creates `SHA256SUMS`, pushes the commit/tag, uploads a draft release, and publishes it as latest only after all assets are present. Standard asset names remain unchanged for the updater; Google-free assets use `ComicReader-fdroid-<abi>.apk`. These are upstream builds, not a claim of official F-Droid inclusion. It does not overwrite tags, force-push or replace an existing release. Local outputs are under `.local/releases/<tag>/`; APKs and signing material stay out of Git.

Current releases retain a **test signing certificate**, not a Play Store production identity. Do not publish the private key or place it in a public workflow. Existing release tags and binary assets remain immutable when documentation is edited later.
