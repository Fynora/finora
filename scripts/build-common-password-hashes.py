#!/usr/bin/env python3
"""Build backend/src/main/resources/security/common-passwords.sha256-8.bin.

usage: python3 scripts/build-common-password-hashes.py <source-list.txt> <out.bin>

The source is a plain list of commonly used passwords, one per line. See
backend/src/main/resources/security/common-passwords-SOURCE.md for which list and where it
comes from. The source itself is NOT kept in this repository: people's real passwords include
real phone numbers and email addresses, and none of that belongs here.

What is kept is, for each password, the first 8 bytes of the SHA-256 of its lowercased UTF-8
form. The file is those 8-byte values, sorted, with nothing between them. WeakPasswordGuard
hashes a candidate the same way and looks it up.

Entries shorter than 8 or longer than 72 characters (or 72 bytes) are dropped, because the
length rule already refuses them.
"""
import hashlib
import sys


def main() -> int:
    if len(sys.argv) != 3:
        print(__doc__, file=sys.stderr)
        return 2
    source, out = sys.argv[1], sys.argv[2]
    with open(source, encoding="utf-8") as handle:
        lines = handle.read().split("\n")
    passwords = set()
    for line in lines:
        lowered = line.strip("\r").lower()
        if 8 <= len(lowered) <= 72 and len(lowered.encode("utf-8")) <= 72:
            passwords.add(lowered)
    hashes = {hashlib.sha256(p.encode("utf-8")).digest()[:8] for p in passwords}
    if len(hashes) != len(passwords):
        print("two passwords share an 8-byte hash; lengthen the hash", file=sys.stderr)
        return 1
    with open(out, "wb") as handle:
        handle.write(b"".join(sorted(hashes)))
    print(f"{len(lines)} lines read, {len(passwords)} passwords kept, {len(hashes) * 8} bytes written")
    return 0


if __name__ == "__main__":
    sys.exit(main())
