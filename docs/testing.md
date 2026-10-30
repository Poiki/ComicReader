# Testing

Use JDK 21 and the prepared official MEGA SDK. See [building](building.md) for prerequisites and commands.

## Automated coverage

| Suite | Coverage |
| --- | --- |
| JVM tests | 39 tests covering file identity, progress validation/conflicts, Drive requests, WebDAV parsing and bounds, transfer files, release metadata and RAR decoding |
| Android framework JVM tests | 3 Robolectric/native-graphics tests for painted icon centers/size, real library/reader/settings layouts, expanded overflow touch targets and reader glyph alignment across eight configurations |
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

The 0.7.1 suite defines 39 core JVM tests, 3 Android framework JVM tests and 19 instrumented Android groups. Its three new JVM tests cover waiting for delayed native completion, interruption without releasing native ownership, and an explicit bounded-timeout diagnostic. The UI suite now includes bottom destinations, settings search and persistent inline reader switches. The previous 0.6.2 Android results do not establish correctness of the redesigned screens. The first-download regression starts by moving a private-cache CBZ into a library that no local import has initialized. Cover tests use the actual decoder, persistent thumbnail cache and a simulated remote loader; they verify reuse, interruption and disk eviction. Network tests cover validated/unmetered/metered policy, and UI checks persist the mobile-download switch. Update checks use intercepted HTTP to prove that a recent attempt does not suppress the next foreground open. Language tests switch the actual activity to Spanish, open its localized reader and restore English while checking retained data.

Native MEGA checks verify JNI loading, private cache access and a real authentication-error callback for a reserved nonexistent account. They do not verify successful account login, thumbnail retrieval or comic transfer. Those account operations require user testing. ARM64 emulator results use native translation and do not establish physical-device performance.

A previous real GitHub update download passed digest, package, version and signing checks and opened Android's installer. Play Protect then rejected installation with `INSTALL_FAILED_VERIFICATION_FAILURE`. No protection was disabled, and end-to-end automatic installation is not claimed.

Live MEGA/Drive/WebDAV account workflows, real Wi-Fi/mobile handovers during transfers, Google OAuth approval, physical OnePlus Pad 2 behavior, energy consumption and long-session performance are not established by these tests. Local comic copies have no additional application-level content encryption. Do not interpret bounded caches as a measured battery improvement.

Grouped settings checks verify semantic section headings, current-value summaries, visible scrolling to lower sections, direct cover/sync preferences and retained provider routes. Existing theme, startup, language and mobile-data checks use the reorganized panel. Tablet/phone portrait and landscape layouts require separate inspection; physical hardware remains outside these checks.

## 0.7.0 validation

The debug and minified release builds, 39 JVM tests, Java formatting and Android lint are checked locally. The updated 19-group Android runner has not been executed for this version: the test emulator fails to initialize its WHPX partition (`0x80070005`), and software emulation does not boot. There is no connected physical device. Screen appearance, the new UI routes and live MEGA cancellation therefore still require device testing.

## 0.7.1 layout validation

All 42 JVM tests pass. The new tests use Robolectric 4.17 and native Android 14/API 34 graphics on the host, with the app's actual resources and view classes. They reproduced the old 4dp horizontal displacement in a 56dp button and the undersized glyph before the fix. The corrected factory passes centering and painted-size checks for toolbar, primary and wider buttons.

The screen test renders the actual library, reader and settings in phone/tablet sizes, light/dark/OLED themes, English/Spanish and 1.7x font scale. It checks centered unclipped image buttons, 48dp overflow touch delegation with a real menu action, and the shared reader-icon baseline. The 24 PNGs are generated under `app/build/reports/ui/` and inspected locally. Test fixtures seed closed synthetic archives; Android import behavior remains covered separately by the instrumented runner.

Robolectric is a test-only dependency. The first test run downloads its Android runtime; it is not packaged in release APKs. These renders do not establish physical-device/OEM behavior, hardware clipping, battery use or live cloud operations. The 19-group instrumented suite still requires an Android device/emulator.
