import struct
import sys

# Minimal ELF symbol lookup: finds the symbol that contains a virtual address.
#
# It exists because the NDK's llvm-nm reported zero symbols for every library in
# this project (including ones that certainly had them), and a crash offset is
# worthless without a name. No external tool is required here - just the ELF
# section headers, which the linker always writes.


def cstr(buf, off):
    end = buf.find(b"\0", off)
    if end < 0:
        end = len(buf)
    return buf[off:end].decode("utf-8", "replace")


def parse(path):
    data = open(path, "rb").read()
    if data[:4] != b"\x7fELF":
        raise SystemExit("%s is not an ELF file" % path)

    is64 = data[4] == 2
    endian = "<" if data[5] == 1 else ">"

    if is64:
        (shoff,) = struct.unpack_from(endian + "Q", data, 0x28)
        shentsize, shnum, shstrndx = struct.unpack_from(endian + "HHH", data, 0x3A)
    else:
        (shoff,) = struct.unpack_from(endian + "I", data, 0x20)
        shentsize, shnum, shstrndx = struct.unpack_from(endian + "HHH", data, 0x2E)

    sections = []
    for i in range(shnum):
        base = shoff + i * shentsize
        if is64:
            fields = struct.unpack_from(endian + "IIQQQQIIQQ", data, base)
        else:
            fields = struct.unpack_from(endian + "IIIIIIIIII", data, base)
        sections.append(
            {
                "name": fields[0],
                "type": fields[1],
                "addr": fields[3],
                "offset": fields[4],
                "size": fields[5],
                "link": fields[6],
                "entsize": fields[9],
            }
        )

    symbols = []
    for section in sections:
        if section["type"] != 2:  # SHT_SYMTAB
            continue
        strings = sections[section["link"]]
        strtab = data[strings["offset"]:strings["offset"] + strings["size"]]
        entsize = section["entsize"] or (24 if is64 else 16)
        count = section["size"] // entsize
        for i in range(count):
            base = section["offset"] + i * entsize
            if is64:
                name, info, _other, shndx, value, size = struct.unpack_from(
                    endian + "IBBHQQ", data, base
                )
            else:
                name, value, size, info, _other, shndx = struct.unpack_from(
                    endian + "IIIBBH", data, base
                )
            if shndx == 0:  # SHN_UNDEF
                continue
            symbols.append((value, size, info & 0xF, cstr(strtab, name)))

    return symbols


def main():
    if len(sys.argv) < 3:
        raise SystemExit("usage: nearest_symbol.py <library> <offset> [offset...]")

    symbols = parse(sys.argv[1])
    print("symbols parsed: %d" % len(symbols))
    if not symbols:
        return

    symbols.sort()
    for raw in sys.argv[2:]:
        target = int(raw, 16)

        best = None
        nxt = None
        for value, size, kind, name in symbols:
            if value <= target:
                best = (value, size, kind, name)
            elif nxt is None:
                nxt = value
                break

        if best is None:
            print("%s: before the first symbol" % raw)
            continue

        value, size, kind, name = best
        span = size if size else (nxt - value if nxt else 0)
        inside = span == 0 or (target - value) < span
        print(
            "%s -> %s (starts 0x%x, +0x%x, %s%s)"
            % (
                raw,
                name,
                value,
                target - value,
                ("size 0x%x" % size) if size else "no size recorded",
                "" if inside else ", OUTSIDE that symbol's range",
            )
        )


if __name__ == "__main__":
    main()
