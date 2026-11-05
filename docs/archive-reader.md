# Embedded RAR reader

`RarPages` keeps the existing comic-page interface while using the project's native JNI adapter in `app/src/main/cpp/rar_jni.c`. Libarchive 3.8.11 supplies the RAR4/RAR5 algorithms under its BSD licenses; the adapter and Java integration are project code. No external extractor app, desktop process, directory extraction or source-file modification is involved.

The source build is pinned by release archive SHA-256. `scripts/libarchive-memory.patch` adds narrow resource guards, rejects multi-volume/split/encrypted entries, and exposes solid-state information to the adapter. Upstream checksum checks remain enabled. The build fails if the expected patch no longer applies.

The adapter opens a private input in read-only mode, uses a 64 KiB input/output buffer and checks thread cancellation between chunks. Metadata is indexed once, natural display order is separate from physical order, and a reader is retained for forward access. Non-solid predecessors can be skipped with seeks. Solid predecessors are decoded and checksum-verified before returning a dependent page. The decoder is reopened for backwards access when needed. One repeated page of at most 8 MiB can remain cached.

Limits remain 1 GiB compressed input, 50,000 entries, 10,000 visible image pages, 64 MiB per entry and 4 GiB total declared unpacked data. Additional name limits are 4 KiB per name and 4 MiB in total. Dictionary/PPMd/filter allocations are capped individually at 64 MiB; libarchive can hold several such buffers, so this is not a claim that total decoder memory stays below 64 MiB. Links, encrypted members, split archives, unknown lengths, oversized data and invalid UTF-8 names fail explicitly.

The library's native code and the JNI boundary need the same malformed-input, lifecycle and device validation as other decoders. A static resource bound or desktop timing result does not establish battery savings on Android. See [testing](testing.md) and [building](building.md).

## Development-host comparison

A Windows x64/JDK 21 check compared the previous Junrar 8.1.2 adapter with this adapter, using six project-generated 800×1200 BMP pages (17.3 MB raw) in stored RAR4 and compressed solid RAR5. Each measurement includes opening/indexing, reading all six pages and closing; two warmups precede the median of five runs. Disk data was warm. The native host build uses zlib for checksums, as the Android build does. Bitmap rendering, cloud transfer and Android runtime behavior are excluded.

| Archive and page order | Previous adapter | Native adapter |
| --- | ---: | ---: |
| Stored RAR4, forward | 10.61 ms | 8.73 ms |
| Stored RAR4, backward | 9.68 ms | 8.80 ms |
| Solid RAR5, forward | 45.54 ms | 28.36 ms |
| Solid RAR5, backward | 136.77 ms | 67.38 ms |

These are small synthetic cases on one development host, not a general speed guarantee. The repeated-page cache was also exercised, but its benefit depends on access patterns. Physical Android throughput, memory peaks and battery consumption remain unmeasured.
