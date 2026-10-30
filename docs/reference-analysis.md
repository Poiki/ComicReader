# Reference APK analysis

ComicReader is an independent implementation informed by inspection of a user-supplied APK. The supplied artifact declares **CDisplayEx 1.3.92**, package `com.progdigy.cdisplay`, version code 6292, minimum API 23 and target API 35. Its publisher authenticity was not established.

```text
Size:   15,134,794 bytes
SHA256: 737108cd3ecf7d488eaad92fde3513d6eaf060546af246ec4dce24727b61d4d0
```

REA/JADX recovered an inventory of 4,751 classes and 1,103 reported resource entries. Selected reader, cloud, lifecycle and resource paths were followed; this was not an exhaustive audit of every obfuscated method or native library.

## Findings used in this project

- Cloud file access and reading-state sync are separate concerns. Selected original paths confirmed MEGA login, 2FA, session restoration, listing and download. Progress routing was confirmed for Drive, Komga and Kavita; MEGA progress sync was not established.
- Another app's private Drive properties and OAuth identity are not reusable. ComicReader uses its own accounts and CRP1 protocol, with no imported clients, keys or tokens.
- The reference already implements directional prefetch, image cleanup and startup profiles. Static cache/thread settings do not establish poor battery life. ComicReader uses explicit resource budgets, but no physical performance or energy comparison has been measured.
- Selected failure paths motivated explicit errors, bounded waits and normal TLS verification. Static findings do not establish that a permissive or failing path was exercised in practice.
- Native library file sizes do not measure RAM or CPU. ComicReader uses Android `PdfRenderer` and a separately built official MEGA SDK; it does not redistribute the reference APK's libraries.

The reference library UI was observed in an isolated emulator: light white/gray surfaces, orange accents and compact navigation informed the app's style. Its reader aborted in the ARM64 translation environment, so reader layout details were inferred from declared resources rather than verified runtime rendering. No accounts were entered into the reference app and no crash protection was bypassed.

## Historical evidence

Detailed investigation reports, inventories, review findings and test records remain in [the v0.5.0 documentation snapshot](https://github.com/Poiki/ComicReader/tree/v0.5.0/docs). Those reports describe the revisions examined and are historical rather than current feature documentation. The current repository consolidates them into maintained English guides.

Primary local evidence IDs:

```text
Inventory: ev_d8db9f4b87507d0aa689aa9d402eacd82c34467fed1a17998769cea1d977c24e
Manifest:  ev_cc84c30685c5a3e898f80b032e47edb41a36a718b0dbaf62a5f9bd9dfbdc7fe4
```

The original APK, decompiled implementation and raw local evidence remain excluded from the public project. Current behavior and limits are described in the [README](../README.md), [architecture](architecture.md) and [testing guide](testing.md).
