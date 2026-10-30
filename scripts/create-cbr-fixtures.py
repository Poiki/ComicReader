"""Generate our own BMP pages and real RAR test archives; requires the official rar CLI."""
import pathlib
import struct
import subprocess
import tempfile
import zlib

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


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument("--rar", default=r"C:\Program Files\WinRAR\Rar.exe")
    args = parser.parse_args()
    OUTPUT.mkdir(parents=True, exist_ok=True)
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
    print("Created and checked RAR4/RAR5 fixtures containing only our synthetic pages.")
