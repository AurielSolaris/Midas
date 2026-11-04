"""Downloads libass and its dependencies for Windows x64 from the official MSYS2 repository (UCRT64)
and puts the DLLs plus their licence files in midasApp-desktop/appResources/windows-x64/libass/.

Usage (from the repo root):  python tools/fetch_libass.py
Needs: Python 3.10+, zstandard (pip install zstandard).

The dependency list follows each package's declared dependencies in the repo database, so the
set stays complete when MSYS2 updates versions.
"""
import io
import os
import re
import shutil
import sys
import tarfile
import tempfile
import urllib.request

import zstandard

REPO = "https://repo.msys2.org/mingw/ucrt64/"
PREFIX = "mingw-w64-ucrt-x86_64-"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "midasApp-desktop", "appResources", "windows-x64", "libass")
# Build tools and data-only packages libass's runtime doesn't need.
SKIP = {"python", "pkgconf", "gcc", "binutils", "crt", "headers", "tzdata", "ca-certificates",
        "python-packaging", "gettext-tools", "xz", "zstd", "libtre", "libsystre", "termcap", "ncurses"}


def fetch(url: str) -> bytes:
    with urllib.request.urlopen(url, timeout=120) as r:
        return r.read()


def untar_zst(data: bytes) -> tarfile.TarFile:
    raw = zstandard.ZstdDecompressor().stream_reader(io.BytesIO(data)).read()
    return tarfile.open(fileobj=io.BytesIO(raw))


def load_db() -> dict:
    """Package name -> (file name, [dependency names]) from ucrt64.db (a tar of desc files)."""
    data = fetch(REPO + "ucrt64.db")
    db = untar_zst(data) if data[:4] == b"\x28\xb5\x2f\xfd" else tarfile.open(fileobj=io.BytesIO(data), mode="r:*")
    pkgs = {}
    for m in db.getmembers():
        if not m.name.endswith("/desc"):
            continue
        text = db.extractfile(m).read().decode("utf-8")
        fields = {}
        for block in text.strip().split("\n\n"):
            lines = block.strip().split("\n")
            fields[lines[0].strip("%")] = lines[1:]
        name = fields["NAME"][0]
        deps = [re.split(r"[<>=]", d)[0] for d in fields.get("DEPENDS", [])]
        pkgs[name] = (fields["FILENAME"][0], deps)
    return pkgs


def main() -> None:
    pkgs = load_db()
    # gcc-libs (libstdc++, libgcc_s) is pulled in by graphite2/harfbuzz but declared as "cc-libs" on some versions.
    want, queue = set(), [PREFIX + "libass", PREFIX + "gcc-libs", PREFIX + "libwinpthread"]
    while queue:
        name = queue.pop()
        short = name.removeprefix(PREFIX)
        if name in want or short in SKIP or name not in pkgs:
            continue
        want.add(name)
        queue.extend(pkgs[name][1])
    print("packages:", ", ".join(sorted(n.removeprefix(PREFIX) for n in want)))

    if os.path.isdir(OUT):
        shutil.rmtree(OUT)
    os.makedirs(os.path.join(OUT, "licenses"))
    with tempfile.TemporaryDirectory() as tmp:
        for name in sorted(want):
            file = pkgs[name][0]
            tar = untar_zst(fetch(REPO + file))
            for m in tar.getmembers():
                if not m.isfile():
                    continue
                if m.name.startswith("ucrt64/bin/") and m.name.endswith(".dll"):
                    target = os.path.join(OUT, os.path.basename(m.name))
                elif m.name.startswith("ucrt64/share/licenses/"):
                    target = os.path.join(OUT, "licenses", m.name.removeprefix("ucrt64/share/licenses/"))
                else:
                    continue
                os.makedirs(os.path.dirname(target), exist_ok=True)
                with open(target, "wb") as f:
                    f.write(tar.extractfile(m).read())
            print("  ", file)
    prune(OUT)
    dlls = sorted(f for f in os.listdir(OUT) if f.endswith(".dll"))
    print(f"{len(dlls)} DLLs in {OUT}: {', '.join(dlls)}")


def pe_imports(path: str) -> list:
    """DLL names a PE file imports (enough of the format for MinGW DLLs)."""
    import struct
    data = open(path, "rb").read()
    pe = struct.unpack_from("<I", data, 0x3C)[0]
    nsec = struct.unpack_from("<H", data, pe + 6)[0]
    opt_size = struct.unpack_from("<H", data, pe + 20)[0]
    opt = pe + 24
    magic = struct.unpack_from("<H", data, opt)[0]
    dirs = opt + (112 if magic == 0x20B else 96)
    imp_rva = struct.unpack_from("<I", data, dirs + 8)[0]
    secs = [struct.unpack_from("<IIII", data, opt + opt_size + i * 40 + 8) for i in range(nsec)]

    def off(rva):
        for vsize, va, rsize, raw in secs:
            if va <= rva < va + max(vsize, rsize):
                return rva - va + raw
        raise ValueError(rva)

    names, d = [], off(imp_rva)
    while True:
        name_rva = struct.unpack_from("<I", data, d + 12)[0]
        if name_rva == 0:
            break
        o = off(name_rva)
        names.append(data[o:data.index(bytes(1), o)].decode())
        d += 20
    return names


def prune(folder: str) -> None:
    """Keeps only libass-9.dll and what it loads, directly or indirectly."""
    have = {f.lower(): f for f in os.listdir(folder) if f.endswith(".dll")}
    keep, queue, missing = set(), ["libass-9.dll"], set()
    while queue:
        d = queue.pop()
        if d in keep:
            continue
        keep.add(d)
        for imp in pe_imports(os.path.join(folder, have[d])):
            i = imp.lower()
            if i in have:
                queue.append(i)
            elif i.startswith("lib") or i.endswith("zlib1.dll"):
                missing.add(i)
    for low, name in have.items():
        if low not in keep:
            os.remove(os.path.join(folder, name))
    if missing:
        raise SystemExit("missing DLLs: " + ", ".join(sorted(missing)))


if __name__ == "__main__":
    sys.exit(main())
