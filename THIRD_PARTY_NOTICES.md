# Third-party notices

Original ComicReader code is licensed under MIT. Dependency licenses remain separate:

| Component | Purpose | License |
| --- | --- | --- |
| [OkHttp 4.12.0](https://github.com/square/okhttp/tree/parent-4.12.0) and [Okio](https://github.com/square/okio) | HTTPS and WebDAV | Apache-2.0 |
| [Kotlin standard library](https://github.com/JetBrains/kotlin) | OkHttp and MEGA dependencies | Apache-2.0 |
| [AndroidX SwipeRefreshLayout 1.2.0](https://developer.android.com/jetpack/androidx/releases/swiperefreshlayout) and AndroidX view support | Pull-to-refresh on library and provider lists | Apache-2.0; included in `assets/licenses/androidx.txt` |
| [Java API desugaring](https://github.com/google/desugar_jdk_libs) | Android compatibility | GPL-2.0 with Classpath Exception; toolchain notices apply |
| [Google Play services Auth](https://developers.google.com/android/guides/overview) | Direct Drive authorization in the standard build only | Google SDK terms; not original MIT code |
| [MEGA SDK 10.21.0](https://github.com/meganz/sdk/tree/v10.21.0) | Native MEGA API and cryptography | BSD-2-Clause plus native dependency licenses, bundled as assets |
| [Libarchive 3.8.11](https://github.com/libarchive/libarchive/tree/v3.8.11) | Native read-only RAR4/RAR5 decoding through the project's JNI adapter | BSD licenses and per-file terms; included in `assets/licenses/libarchive.txt`. Pinned sources and the narrow safety patch are built by `scripts/archive-native-build.sh`. |
| [Robolectric 4.17](https://github.com/robolectric/robolectric/tree/robolectric-4.17) | Android framework layout and rendering tests only | Apache-2.0; not bundled with the app |
| [JUnit 4.13.2](https://github.com/junit-team/junit4) | JVM tests only | EPL-1.0 |
| [Gradle](https://github.com/gradle/gradle) | Build wrapper | Apache-2.0 |
| [Google Material Icons](https://github.com/google/material-design-icons/tree/10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7) | Interface vector icons, converted from upstream SVGs | Apache-2.0; included in `assets/licenses/material-icons.txt`. Source commit and conversion are marked in each vector. |
| [Lucide Icons](https://github.com/lucide-icons/lucide/tree/a630cec77eac6568b989e87e4947a8bfe45caacd) | Outline interface vectors, converted from upstream SVGs | ISC and inherited Feather MIT notices, included in `assets/licenses/lucide-icons.txt` |
| [google-java-format 1.37.0](https://github.com/google/google-java-format/releases/tag/v1.37.0) | Development-only formatter | Apache-2.0; downloaded locally, not bundled with the app |

Local reading and MEGA do not require a Google account. The standard build's Drive authorization requires compatible Google Play services. The F-Droid variant excludes Google Play Services and Drive authorization. Dependency licenses remain separate from the project's MIT license.

ComicReader's launcher artwork and synthetic test images are project-authored. Interface icons retain the licenses listed above. The proprietary RAR archiver used to generate compressed test fixtures is not distributed.

Widget picker previews are rendered from the app's native layouts using original synthetic covers drawn by `WidgetTest`. They contain no third-party comic artwork.

README screenshots are native UI renders from `UiScreensTest` with project-generated sample comics. The documentation icon is adapted from the project's launcher artwork. These assets contain no personal library data or third-party comic artwork.

The compressed RAR4 plain-text test case is adapted from libarchive's BSD-licensed test fixture. Its notice is retained beside the fixture in `app/src/test/resources/comics/libarchive-fixture-LICENSE.txt`. The Ko-fi support button is project-authored vector artwork.
