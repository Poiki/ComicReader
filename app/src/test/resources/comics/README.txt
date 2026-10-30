Synthetic fixtures authored for ComicReader; no third-party comics or artwork.

rar4-stored.cbr: RAR4 headers with stored red/green/blue BMP pages; hidden resource.
rar5-solid.cbr: real compressed solid RAR5-family archive containing those pages.
duplicate.cbr: deliberately duplicated page names, rejected by the reader.
encrypted.cbr: those synthetic pages encrypted with fixture-only-password, rejected.

Generate with scripts/create-cbr-fixtures.py and an installed official rar CLI.
The script writes stored RAR4 headers, not a RAR compression algorithm. Compressed
fixtures were created using the official RAR7.23 evaluation CLI already installed
on the development machine. The archiver is not included in this project/app.
Both valid archives were checked by the official rar t command. Test fixtures
and their generator are MIT like the project's own code.
