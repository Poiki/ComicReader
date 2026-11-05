"""Generate our own BMP pages and real RAR test archives; requires the official rar CLI."""
import pathlib
import struct
import subprocess
import tempfile
import zlib
import binascii

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUTPUT = ROOT / "app/src/test/resources/comics"


def bmp(rgb):
    width, height = 120, 180
    pixels = bytes(reversed(rgb)) * width * height
    return (b"BM" + struct.pack("<IHHI", 54 + len(pixels), 0, 0, 54)
            + struct.pack("<IiiHHIIiiII", 40, width, height, 1, 24, 0, len(pixels), 0, 0, 0, 0) + pixels)


def header(body):
    return struct.pack("<H", zlib.crc32(body) & 0xffff) + body


def rar4_stored(files):
    data = b"Rar!\x1a\x07\x00" + header(struct.pack("<BHHHI", 0x73, 0, 13, 0, 0))
    for name, content in files:
        name = name.encode("ascii")
        data += header(struct.pack("<BHHIIBIIBBHI", 0x74, 0x8000, 32 + len(name), len(content),
                                   len(content), 2, zlib.crc32(content), 0x50210000, 20, 0x30, len(name), 0x20) + name) + content
    return data + header(struct.pack("<BHH", 0x7b, 0x4000, 7))


def compressed_rar4_text(source):
    """Keep the upstream BSD test's 20-byte text member and directory; no artwork."""
    archive = source / "libarchive/test/test_read_format_rar_compress_normal.rar.uu"
    lines = archive.read_bytes().splitlines()[1:]
    data = b"".join(binascii.a2b_uu(line) for line in lines if line not in (b"end", b""))
    member = directory = None
    position = 7
    while position + 7 <= len(data):
        kind, flags, size = struct.unpack_from("<BHH", data, position + 2)
        packed = struct.unpack_from("<I", data, position + 7)[0] if kind == 0x74 else 0
        block = data[position:position + size + packed]
        if kind == 0x74:
            name_size = struct.unpack_from("<H", block, 26)[0]
            name = block[32:32 + name_size]
            if name == b"testdir\\test.txt":
                changed = bytearray(block)
                changed[32:32 + name_size] = b"testdir\\page.bmp"
                changed[:2] = struct.pack("<H", zlib.crc32(changed[2:size]) & 0xffff)
                member = bytes(changed)
            elif name == b"testdir":
                directory = block
        position += size + packed
    assert member and directory
    OUTPUT.mkdir(parents=True, exist_ok=True)
    (OUTPUT / "rar4-compressed-text.cbr").write_bytes(
        data[:20] + directory + member + header(struct.pack("<BHH", 0x7b, 0x4000, 7)))
    notice = (source / "libarchive/test/test_read_format_rar.c").read_text().split("*/", 1)[0] + "*/\n"
    (OUTPUT / "libarchive-fixture-LICENSE.txt").write_text(notice, encoding="utf-8")


def bad_solid_predecessor(data):
    """Keep valid header CRCs but a wrong data CRC on a first non-image member."""
    data = bytearray(data)
    offset = 8
    while offset < len(data):
        cursor = offset + 4
        def number():
            nonlocal cursor
            value = shift = 0
            while shift < 64:
                byte = data[cursor]
                cursor += 1
                value |= (byte & 127) << shift
                if not byte & 128:
                    return value
                shift += 7
            raise ValueError("Invalid fixture header")
        length = number()
        end = cursor + length
        kind, flags = number(), number()
        if flags & 1:
            number()
        packed = number() if flags & 2 else 0
        if kind == 2:
            file_flags = number()
            number()
            number()
            if file_flags & 2:
                cursor += 4
            assert file_flags & 4
            data[cursor] ^= 1
            cursor += 4
            number()
            number()
            name_length = number()
            name_end = cursor + name_length
            assert data[name_end - 4:name_end] == b".bmp"
            data[name_end - 4:name_end] = b".txt"
            struct.pack_into("<I", data, offset, zlib.crc32(data[offset + 4:end]))
            return bytes(data)
        offset = end + packed
    raise ValueError("Missing fixture member")


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument("--rar", default=r"C:\Program Files\WinRAR\Rar.exe")
    parser.add_argument("--libarchive", type=pathlib.Path,
                        default=ROOT / ".local/archive-native/source/libarchive-3.8.11")
    args = parser.parse_args()
    OUTPUT.mkdir(parents=True, exist_ok=True)
    compressed_rar4_text(args.libarchive)
    files = [("page10.bmp", bmp((0, 0, 255))), ("page2.bmp", bmp((0, 255, 0))),
             ("page1.bmp", bmp((255, 0, 0))), ("__MACOSX/._page.bmp", bmp((0, 0, 0)))]
    (OUTPUT / "rar4-stored.cbr").write_bytes(rar4_stored(files))
    (OUTPUT / "duplicate.cbr").write_bytes(rar4_stored([files[0], files[0]]))
    temp_root = (ROOT / ".local").resolve()
    temp_root.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(dir=temp_root, prefix="cbr-fixtures-") as temporary:
        work = pathlib.Path(temporary).resolve()
        assert work.is_relative_to(temp_root)
        for name, content in files:
            path = work / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(content)
        for name, switches in [("rar5-solid.cbr", ["-s"]), ("encrypted.cbr", ["-pfixture-only-password"])]:
            subprocess.run([args.rar, "a", "-ma5", "-m3", "-md4m", "-ep1", "-idq", *switches,
                            str(OUTPUT / name), *[name for name, _ in files]], cwd=work, check=True)
        for name in ("rar4-stored.cbr", "rar5-solid.cbr"):
            subprocess.run([args.rar, "t", "-idq", str(OUTPUT / name)], check=True)
    assert (OUTPUT / "rar5-solid.cbr").read_bytes()[:8] == b"Rar!\x1a\x07\x01\x00"
    (OUTPUT / "rar5-bad-predecessor.cbr").write_bytes(
        bad_solid_predecessor((OUTPUT / "rar5-solid.cbr").read_bytes()))
    print("Created and checked RAR4/RAR5 fixtures containing only our synthetic pages.")
