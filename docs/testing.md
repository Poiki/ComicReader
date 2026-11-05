# Testing

Use JDK 21 and the prepared MEGA and archive native libraries. See [building](building.md) for prerequisites and commands.

## Automated coverage

| Suite | Coverage |
| --- | --- |
| JVM tests | 39 tests covering file identity, progress validation/conflicts, Drive requests, WebDAV parsing and bounds, transfer files, release metadata and RAR decoding |
| Android framework JVM tests | 3 Robolectric/native-graphics tests for painted icon centers/size, real library/reader/settings layouts, expanded overflow touch targets and reader glyph alignment across eight configurations |
| MEGA lifecycle JVM tests | 12 Robolectric tests using SDK protocol doubles for folder recovery, bounded retries, visible-library session retention, idle cleanup, explicit sign-out, cached-list error presentation and explicit refresh |
| Pull-to-refresh JVM tests | 7 Robolectric tests for empty/local lists, scroll and busy guards, new Drive/WebDAV entries and failure retention |
| Widgets and series JVM tests | 24 tests covering numbering, source/cache identity, responsive RemoteViews, bitmap/IPC budgets, reader completion and cloud continuation |
| Android smoke runner | 19 groups covering library/reader behavior, first cloud download without a prior local import, MEGA metadata, filters/themes, cloud JSON, updater verification, cover caching/cancellation/budget, repeated startup checks and bilingual UI |
| Optional native checks | MEGA JNI loading, writable SDK cache and bounded remote callback behavior without real account credentials |
| Android lint | Run on the debug variant; warnings are reviewed rather than suppressed globally |
| Formatting | Pinned, checksum-verified google-java-format in AOSP mode |

The smoke runner uses an isolated emulator and synthetic data. Its intercepted HTTP tests verify local requests and parsing, not access to live cloud accounts. Native ARM64 tests run through the emulator's native bridge and do not replace physical ARM64 testing.

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease :app:lintDebug
.\scripts\format-java.ps1 -Check
```

Install the debug and instrumentation APKs on your test emulator, then run:

```powershell
adb -s emulator-5554 shell am instrument -w org.comicreader.app.test/org.comicreader.app.SmokeInstrumentation
```

Add `-e mega true -e network true` before the component to enable native checks that contact MEGA without a real account. Target the test emulator explicitly; the runner modifies its app's test library and settings.

## Archive fixtures

`app/src/test/resources/comics/` contains project-generated synthetic RAR4/RAR5 files, including solid compression, duplicate entries and encryption rejection. The same fixtures are packaged into Android tests. They contain no third-party comics. See their [fixture README](../app/src/test/resources/comics/README.txt) and [license notices](../THIRD_PARTY_NOTICES.md).

The reader bounds archive size, page count, entry size, dictionary size and total unpacked size. Tests cover natural ordering, repeated page access, unsupported/encrypted archives and malformed input. Solid RAR extraction validates predecessor pages rather than silently skipping decompression errors.

The additional background tests verify the actual startup JobService creates a cover while the app displays connections with no comic cells, existing covers are skipped, parcelled job identity is stable, and expedited quota rejection uses constrained regular scheduling. A controlled generation completes after its originating Activity is destroyed; concurrent requests reuse a single result. Network and battery constraints, cache budgets and foreground cancellation remain covered. Actual cloud-account background transfers and physical-device scheduling remain unverified.

## Known verification limits

The 0.7.2 suite defines 39 core JVM tests, 3 Android framework JVM tests, 9 MEGA lifecycle JVM tests and 19 instrumented Android groups. The core suite covers waiting for delayed native completion, interruption without releasing native ownership, and an explicit bounded-timeout diagnostic. The UI suite includes bottom destinations, settings search and persistent inline reader switches. The previous 0.6.2 Android results do not establish correctness of the redesigned screens. The first-download regression starts by moving a private-cache CBZ into a library that no local import has initialized. Cover tests use the actual decoder, persistent thumbnail cache and a simulated remote loader; they verify reuse, interruption and disk eviction. Network tests cover validated/unmetered/metered policy, and UI checks persist the mobile-download switch. Update checks use intercepted HTTP to prove that a recent attempt does not suppress the next foreground open. Language tests switch the actual activity to Spanish, open its localized reader and restore English while checking retained data.

Native MEGA checks verify JNI loading, private cache access and a real authentication-error callback for a reserved nonexistent account. They do not verify successful account login, thumbnail retrieval or comic transfer. Those account operations require user testing. ARM64 emulator results use native translation and do not establish physical-device performance.

A previous real GitHub update download passed digest, package, version and signing checks and opened Android's installer. Play Protect then rejected installation with `INSTALL_FAILED_VERIFICATION_FAILURE`. No protection was disabled, and end-to-end automatic installation is not claimed.

Live MEGA/Drive/WebDAV account workflows, real Wi-Fi/mobile handovers during transfers, Google OAuth approval, physical-device behavior, energy consumption and long-session performance are not established by these tests. Local comic copies have no additional application-level content encryption. Do not interpret bounded caches as a measured battery improvement.

Grouped settings checks verify semantic section headings, current-value summaries, visible scrolling to lower sections, direct cover/sync preferences and retained provider routes. Existing theme, startup, language and mobile-data checks use the reorganized panel. Tablet/phone portrait and landscape layouts require separate inspection; physical hardware remains outside these checks.

## 0.7.0 validation

The debug and minified release builds, 39 JVM tests, Java formatting and Android lint are checked locally. The updated 19-group Android runner has not been executed for this version: the test emulator fails to initialize its WHPX partition (`0x80070005`), and software emulation does not boot. There is no connected physical device. Screen appearance, the new UI routes and live MEGA cancellation therefore still require device testing.

## 0.7.1 layout validation

All 42 JVM tests pass. The new tests use Robolectric 4.17 and native Android 14/API 34 graphics on the host, with the app's actual resources and view classes. They reproduced the old 4dp horizontal displacement in a 56dp button and the undersized glyph before the fix. The corrected factory passes centering and painted-size checks for toolbar, primary and wider buttons.

The screen test renders the actual library, reader and settings in phone/tablet sizes, light/dark/OLED themes, English/Spanish and 1.7x font scale. It checks centered unclipped image buttons, 48dp overflow touch delegation with a real menu action, and the shared reader-icon baseline. The 24 PNGs are generated under `app/build/reports/ui/` and inspected locally. Test fixtures seed closed synthetic archives; Android import behavior remains covered separately by the instrumented runner.

Robolectric is a test-only dependency. The first test run downloads its Android runtime; it is not packaged in release APKs. These renders do not establish physical-device/OEM behavior, hardware clipping, battery use or live cloud operations. The 19-group instrumented suite still requires an Android device/emulator.

## 0.7.2 MEGA folder validation

All 51 JVM tests pass. Five regression tests first failed against the previous implementation: missing SDK folders were not reloaded, idle cleanup waited on an unfinished request, background cleanup disconnected a resumed library, and automatic refresh failures opened a modal over cached entries. The corrected tests exercise the actual `MegaClient` and `MainActivity` with SDK protocol doubles and Android framework views/databases. Additional cases cover root-folder recovery, valid-tree reuse, a single initial fetch, interruption and explicit sign-out while browsing.

The tests retain the cached folder after failure, expose a localized inline error and retry action, and verify that an explicit retry still opens its error dialog. A truly unavailable folder remains a failure after one tree fetch. The SDK constructor is shadowed for these tests; no native MEGA account login or live folder lookup is established by them. The existing 24 layout renders and build checks pass; instrumented device tests remain unrun for this release.

## 0.7.3 pull-to-refresh validation

All 61 JVM tests pass: 39 core, 3 layout, 12 MEGA lifecycle and 7 pull-to-refresh tests. Motion-event sequences exercise empty lists, new local entries, horizontal movement, scrolling away from the top and busy-operation rejection. Drive uses intercepted HTTP, WebDAV uses a protocol double, and MEGA uses its existing SDK doubles. Tests verify that a failed Drive refresh retains the old list and stops the indicator, a subsequent success shows new entries, an empty MEGA picker can refresh and save its updated listing, and the library retains its current folder/filter while fetching new MEGA data exactly once.

Robolectric does not deliver the refresh circle's View-animation completion callback; the gesture helper completes that callback explicitly after dispatching the real down/move/up events. These tests establish application wiring and state handling, not physical touch/animation timing. Two additional native framework renders cover empty and populated provider dialogs. Existing 24 library/reader/settings renders and the instrumented build remain covered; the Android instrumentation suite and live providers still require device testing.

## 0.8.0 widget and series validation

All 85 JVM tests pass. The new suites cover conservative issue matching, numeric order, folder/account/year/volume separation, duplicate-number rejection, retained cloud progress after deleting a local copy, replaced provider content, WebDAV password changes and account validation. Reading and widget shortcuts preserve the last folder when identical content has several origins. Native Android 14 RemoteViews are applied at five sizes in light/dark/OLED and Spanish with 1.7x font scale. Forty-nine widget renders check child bounds, visible entry counts, progress information and cover actions. Tests also verify private components, responsive publication, no polling interval, and a shared cover/progress bitmap bank within the smallest widget's budget with an IPC payload below 512 KiB.

Reader tests finish the actual synthetic archive and verify that last-page arrival alone does not complete it, progress commits before navigation, different folders do not cross, failed saves retain the reader, and paused activities cannot open another comic. Provider doubles exercise a full Drive next-issue refresh/download/import/open flow, rejection of a changed account, metered-download rejection and a simulated metered handover that interrupts work and unregisters its callback. The archive is closed before import moves its file; these flows run on the Windows host without bypassing the real importer.

The 19-group Android instrumentation suite remains compiled but unrun. Host tests and synthetic provider doubles do not establish launcher behavior on physical Android devices, live account transfers, physical touch timing, energy use or long-session performance. The widget render tests run on API 34; pre-31 resizing uses guarded legacy paths and still needs device testing. Preview images are synthetic native widget renders, not user content.

## 0.9.0 decoder and distribution validation

The standard build passes 92 JVM tests; `-Pdistribution=fdroid` passes 95, including three variant checks. Both compile debug, instrumentation and minified release APKs and pass lint (14 standard warnings, 13 F-Droid warnings, no errors). Ten raw RAR adapter tests use the actual compiled JNI library on the host. They cover stored/compressed RAR4, compressed solid RAR5, natural order, repeated/backwards reads, duplicate/encrypted/split rejection, declared size limits, checksums, unchanged source files, Unicode paths, interruption and closing. One compressed-text fixture is BSD-attributed to libarchive and does not test bitmap rendering.

The original stored-RAR corruption regression initially failed with the native decoder because its final EOF path skipped the CRC comparison. The narrow source patch now checks it; both stored and compressed RAR4 bad-checksum cases fail explicitly. Archive paths are never output destinations. Allocation guards are per buffer/dictionary, not a promise of a 64 MiB total working set.

REA/JADX statically inspected the F-Droid release package (API 28 minimum, target 36, version code 14) and its complete class-name inventory. The package requests only Internet and network-state permissions. Its 498 decompiler class rows contain no GMS, Junrar, SLF4J, Updater or UpdateProvider names; the resolved release dependency graph also excludes these dependency libraries. Obfuscated class-name searches alone are not provenance proof. The native ELF reports show 16 KiB load alignment and system-only dependencies. Signatures are verified separately during release publication.

Support tests check that Ko-fi opens only after an explicit positive-button tap, cancellation opens nothing, and a missing browser shows a localized message. The native OLED dialog render was inspected. App data, credentials, live provider behavior, physical throughput/energy, 16 KiB-device runtime and official F-Droid packaging remain outside these host checks.
