# Google Play and F-Droid readiness

Reviewed on **2026-11-05** against ComicReader 0.8.0 (version code 13) and the official guidance linked below. This is a publication assessment; neither store has approved or received the app.

## Distribution and costs

Selling ComicReader on Google Play and offering a free build through F-Droid are compatible with the project's MIT license. The license also permits others to build and redistribute the code. The purchase would fund development and convenient store distribution; it would not make the source exclusive to buyers.

| Channel | Payment model | Current status |
| --- | --- | --- |
| Google Play | Set an upfront purchase price in Play Console. Developer registration costs US$25 once, plus applicable sales fees and taxes. | Packaging works; policy, signing, account and product work remain. |
| Official F-Droid repository | Free download, with optional developer donation links. | Blocked by bundled Google authorization and Junrar; build recipe also needed. |
| GitHub Releases | Existing APK distribution. | Available; retain its current update and signing contract during store preparation. |

Google requires identity verification and, for sales, a payments profile and verified payment information. Monetizing developers must display their full legal address on Google Play. These details belong in the owner's account, not this repository. See [registration](https://support.google.com/googleplay/android-developer/answer/6112435?hl=en), [required account information](https://support.google.com/googleplay/android-developer/answer/13628312?hl=en) and [payments profiles](https://support.google.com/googleplay/android-developer/answer/7161426?hl=en).

Fees depend on region, program and install history. For the first US$1 million of annual earnings under the applicable small-developer tier, the current EEA schedule lists a 10% service fee plus a 5% Google billing fee for paid purchases. Other markets retain a 15% tier with enrollment requirements; higher tiers differ. This is not a guarantee of net proceeds: confirm enrollment, regional prices, taxes and refunds in Play Console. See the [current fee schedule](https://support.google.com/googleplay/android-developer/answer/112622?hl=en).

Choose paid distribution before offering the app free on Google Play: an app already offered free there cannot later become paid under the same package. Its free availability outside Play does not itself make the Play listing free. Price and country availability remain owner decisions. See [app pricing](https://support.google.com/googleplay/android-developer/answer/6334373).

F-Droid offers [free distribution](https://f-droid.org/en/about/) and [developer donation links](https://f-droid.org/en/docs/FAQ_-_App_Developers/). No paid-download mechanism is proposed for that channel.

## Google Play: required changes

### Updates and installation permission

The current manifest requests `REQUEST_INSTALL_PACKAGES`. `Updater` checks GitHub on foreground startup, downloads APKs and opens the Android installer through `UpdateProvider`. This is a release blocker: Google prohibits using this permission for self-updates. See the [installation permission policy](https://support.google.com/googleplay/android-developer/answer/12085295?hl=en).

Create a Play distribution variant that excludes the installer permission, provider, APK updater and related startup/settings actions. Merely removing the permission leaves broken controls and does not remove the self-update behavior. Play should deliver its own updates; a custom in-app-update SDK is optional. Keep the GitHub path in the direct-download variant.

### Production identity and signing

The publication script currently signs with the Android debug keystore and pins its certificate. Google Play does not accept debug certificates for publication. Use a production upload key and Play App Signing; store keys securely outside Git. Register Drive OAuth against the final package and **Play app-signing certificate**, not only the upload certificate. See [Android signing](https://developer.android.com/studio/publish/app-signing) and the [App Bundle FAQ](https://developer.android.com/guide/app-bundle/faq).

Decide package IDs and the migration route before enrollment. Android cannot normally update an installed package with an unrelated signing certificate. Preserve existing GitHub installations rather than silently replacing their key; use a separate store package if separate identities are chosen. Provider authorities, widget intents and OAuth registrations must follow any package change.

### Drive for public users

`DriveAuth` requests `drive.readonly` and `drive.appdata`; current instructions expect each builder to register OAuth. A paid public app needs a developer-managed Cloud project, consent configuration and tested production authorization. `drive.readonly` is restricted and requires the applicable verification. A security assessment can additionally be required when restricted data is stored or transmitted through servers; do not assume it is always required or always exempt. Reducing access to `drive.file` would change folder-browsing behavior and needs separate design and tests. See [Drive scopes](https://developers.google.com/workspace/drive/api/guides/api-specific-auth).

### Privacy, declarations and reviewer access

Publish a privacy policy on a public HTML URL and link it inside the app. It must identify the developer/contact and explain access, retention, deletion and the SDKs involved. Complete Data safety from actual data flows, including cloud authorization, filenames, comic downloads, progress sync and SDK behavior. Private local storage and the absence of an analytics dependency do not by themselves justify a blanket no-data declaration. See Google's [user data and privacy policy](https://support.google.com/googleplay/android-developer/answer/18265346?hl=en).

Complete content rating, target audience, ads, app access and other applicable Console declarations. Provide review instructions and dedicated test access for cloud features, with redistributable test comics. The current app signs into existing providers rather than creating a ComicReader account. Assess the account-deletion questions accordingly; if app account creation is introduced, implement both required deletion paths. See [account deletion requirements](https://support.google.com/googleplay/android-developer/answer/13327111?hl=en).

### Testing and store assets

Personal developer accounts created after 2023-11-13 need a closed test with at least **12 continuously opted-in testers for 14 days**, followed by a production-access application. Passing this time threshold alone does not guarantee approval. The automated JVM suite does not replace it. See [Play testing requirements](https://support.google.com/googleplay/android-developer/answer/14151465?hl=en).

Prepare English and Spanish listings, a 512×512 PNG icon, a 1024×500 feature graphic and suitable phone/tablet screenshots. The current README screenshots are **411×891**, exceeding Play's maximum 2:1 screenshot aspect ratio; they are not ready to upload unchanged. Capture the real app at an accepted size with project-owned or licensed sample content. See [preview asset requirements](https://support.google.com/googleplay/android-developer/answer/9866151?hl=en-GB).

## F-Droid: required changes

The official repository requires FLOSS application code and dependencies, plus a build from public sources using an accepted toolchain. A non-free dependency flag does not permit bundling proprietary libraries. Its policy also includes a project-maturity requirement of three months of active development, with stated exceptions such as evidence of existing users; the maintainers decide whether an exception applies. See the [inclusion policy](https://f-droid.org/en/docs/Inclusion_Policy/).

| Finding in this repository | Required action |
| --- | --- |
| `play-services-auth:22.0.0` and Google classes in `DriveAuth` | Exclude Google binaries from the F-Droid variant. Preserve Drive through a separately validated FLOSS authorization route, or explicitly omit Drive from that variant. Hiding its button does not remove the dependency. |
| `junrar:8.1.2` under the UnRAR license | Replace the RAR decoder with a FLOSS implementation to preserve CBR/RAR. Removing Junrar without replacement would lose those formats. The existing license restricts use for compression and does not meet the required software freedoms. |
| MEGA native SDK generated into `.local/mega-sdk` | Build the pinned SDK and all required native dependencies from source in the F-Droid recipe. Existing local `.so` files are not a submission recipe. Audit and retain the full native license set. |
| Docker/PowerShell-oriented native setup | Adapt the Bash build to the F-Droid builder, declare source libraries/tool versions and prepare downloads before the isolated build. Verify that the pinned SDK/NDK/Gradle/AGP/JDK are supported there. |
| Automatic GitHub checks and APK installation | Exclude this path from the F-Droid variant and let F-Droid update it. Avoid cross-channel signature failures and unsolicited update checks. |
| No F-Droid metadata | Add localized upstream descriptions, artwork and version-code changelogs, then a tested `fdroiddata` build recipe. |

Junrar's restriction is recorded in [third-party notices](../THIRD_PARTY_NOTICES.md) and its bundled license. F-Droid's [published Twig listing](https://f-droid.org/en/packages/com.twig.app/) confirms that its libre build excludes Junrar for this reason. [Libarchive](https://www.libarchive.org/) is a BSD-licensed RAR-reading candidate, not a verified replacement here. Any replacement must pass compressed/solid RAR4/RAR5, corrupt-input, memory-budget, page-order and cancellation tests before promising equivalent support.

MEGA's SDK license is BSD-2-Clause; it is not the same problem as bundled Google authorization. Keep direct MEGA login if its source build and dependencies pass review. MEGA/Drive service integration may still require a `NonFreeNet` declaration, and unsolicited update checks can trigger `Tracking`; classify the final variant with the packagers. See [anti-features](https://f-droid.org/en/docs/Anti-Features/).

F-Droid can sign its own builds, or verify reproducible builds that retain an upstream signature. Those are different publication paths. Different certificates cannot update each other; choose a package/signature strategy before shipping. Binary reproducibility is needed for the upstream-signature route, rather than a blanket prerequisite for every F-Droid app. See [reproducible builds](https://f-droid.org/en/docs/Reproducible_Builds/).

Use the [submission guide](https://f-droid.org/en/docs/Submitting_to_F-Droid_Quick_Start_Guide/) to add Fastlane-compatible metadata, pin the release commit, test `fdroid lint` and `fdroid build`, then submit a merge request to `fdroiddata`. Upstream metadata can be flavor-specific. Inclusion remains subject to maintainer review.

## Suggested implementation order

1. Choose the price, developer account type, package IDs, production signing and cross-channel migration strategy.
2. Add direct-download, Play and F-Droid variants. Remove the external updater from store builds and isolate the Google authorization dependency.
3. Replace Junrar with a verified FLOSS decoder if CBR/RAR parity is required across stores. Keep MEGA and WebDAV available.
4. Configure production Drive OAuth; implement a suitable FLOSS authorization flow if Drive must also ship on F-Droid.
5. Finish privacy disclosures, store listings, artwork and localized metadata using the actual final variant behavior.
6. Validate a signed Play bundle and the isolated F-Droid build, test real accounts and devices, then complete each store's submission process.

## Checks performed and limits

- Inspected Gradle dependencies, manifest, Drive scopes, native build scripts, licenses, GitHub updater and signing script. No application behavior or production keys were changed.
- Built `:app:bundleRelease` successfully: the current unsigned minified AAB is 21,253,887 bytes. This confirms packaging, not store compliance or a successful Play upload.
- Current configuration targets API 36, matching the published requirement for new phone/tablet submissions from 2026-08-31. See [target API requirements](https://support.google.com/googleplay/android-developer/answer/11926878?hl=en).
- Native reports show 16 KiB `LOAD` alignment for both ABIs. Runtime testing on a 16 KiB device and validation of Play-generated APKs remain pending; see [page-size guidance](https://developer.android.com/guide/practices/page-sizes).
- The previous release passed 85 JVM tests. Store-specific variants, a replacement decoder, real production OAuth, live cloud accounts, Play closed testing and the F-Droid build have not been validated.
