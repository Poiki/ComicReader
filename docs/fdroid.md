# F-Droid distribution

Build the F-Droid variant with `-Pdistribution=fdroid`. It excludes Google Play Services and Google Drive authorization; local reading, MEGA and HTTPS WebDAV remain available. The About screen and English/Spanish listing descriptions disclose that difference.

Upstream APKs use `ComicReader-fdroid-<abi>.apk` in [GitHub Releases](https://github.com/Poiki/ComicReader/releases/latest). Standard and Google-free upstream builds share a package and signing certificate: installing one over the other switches variants and preserves local data; they are not separate co-installed apps. Official F-Droid signing may require a different migration strategy.

```powershell
.\gradlew.bat -Pdistribution=fdroid :app:testDebugUnitTest :app:assembleRelease :app:lintDebug
```

Use the same prepared native sources as the standard build; see [building](building.md). This variant does not request package-install permission or run the GitHub APK updater. F-Droid should manage updates.

`fastlane/metadata/android/` contains the F-Droid listing text. It is deliberately specific to this variant and must not be reused unchanged for the Google-enabled build. Metadata for the eventual `fdroiddata` submission should include:

```yaml
Donate: https://ko-fi.com/A0A71AC06Z
```

Donations are optional. The app uses an explicit browser link, with no embedded payment script or WebView. The README support button uses local vector artwork and the same dark/cyan palette as the app.

The variant and listing metadata do not establish official repository inclusion. A source-build recipe for the MEGA SDK and archive decoder, isolated build validation, final license/anti-feature review and a signing strategy still need to be agreed with the F-Droid packagers. See [store readiness](store-readiness.md).
