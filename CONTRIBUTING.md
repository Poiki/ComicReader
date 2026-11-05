# Contributing

Read the [build guide](docs/building.md), [architecture](docs/architecture.md) and [test coverage](docs/testing.md) before changing behavior.

## Changes

- Keep code, comments and maintained documentation in English. Maintain both the English default UI resources and the Spanish translation. User filenames and remote data retain their original text.
- Put app UI text in `app/src/main/res/values/strings.xml`; keep protocol keys, preferences and identifiers independent of display text.
- Use native Android controls and existing helpers. Avoid adding dependencies or abstractions for small changes.
- Preserve local data, validation, explicit errors, accessibility and bounded background work. A failed cloud request must not appear successful or erase cached reading state.
- Add focused regression coverage for non-trivial behavior, including data-loss and malformed-input boundaries.

## Before submitting

```powershell
.\scripts\format-java.ps1
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
.\scripts\format-java.ps1 -Check
git diff --check
```

Run the Android checks when changing UI, storage, decoding, authorization or lifecycle behavior. Use an emulator without personal data. Describe what changed, why it matters, what was tested and any remaining limitation.

The formatter uses four-space AOSP style and verifies a pinned download checksum. Its JAR stays in `.local/`; it adds no runtime dependency.

Never commit signing keys, access tokens, account sessions, personal comics, generated SDK sources, APKs or third-party proprietary binaries. Test fixtures must be synthetic or have a clear redistribution license. Public APKs belong in GitHub Releases, not Git history. See [publishing](docs/updates.md) for the signing and version requirements.
