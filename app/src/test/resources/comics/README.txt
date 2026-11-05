Fixtures contain synthetic images and plain text; no third-party comics or artwork.

rar4-compressed-text.cbr: directory and a 20-byte text member from libarchive
3.8.11's test_read_format_rar_compress_normal.rar.uu, renamed to page.bmp solely
to test the raw RAR adapter (its contents are text, not a bitmap). Redistribution
is under the BSD notice in libarchive-fixture-LICENSE.txt; the source test and
fixture are at https://github.com/libarchive/libarchive/tree/v3.8.11/libarchive/test.
Recreate it from the pinned source with compressed_rar4_text in the generator.

rar4-stored.cbr: RAR4 headers with stored red/green/blue BMP pages; hidden resource.
rar5-solid.cbr: real compressed solid RAR5-family archive containing those pages.
rar5-bad-predecessor.cbr: first member renamed to .txt with a wrong data CRC but
valid header CRC, so reading a later image must verify its solid predecessor.
duplicate.cbr: deliberately duplicated page names, rejected by the reader.
encrypted.cbr: those synthetic pages encrypted with fixture-only-password, rejected.

Generate with scripts/create-cbr-fixtures.py and an installed official rar CLI.
The script writes stored RAR4 headers, not a RAR compression algorithm. Compressed
fixtures were created using the official RAR7.23 evaluation CLI already installed
on the development machine. The archiver is not included in this project/app.
Both valid archives were checked by the official rar t command. Test fixtures
and their generator are MIT like the project's own code, except for the explicitly
attributed libarchive compressed-text case above.
