# common-passwords.sha256-8.bin

The passwords `WeakPasswordGuard` refuses when a password is set.

- **What it is:** the UK National Cyber Security Centre's list of the 100,000 passwords seen most
  often in breaches.
- **Taken from:** the SecLists project, file
  `Passwords/Common-Credentials/100k-most-used-passwords-NCSC.txt`
  (<https://github.com/danielmiessler/SecLists>), downloaded 2026-11-21.
- **Licence of SecLists:** MIT, Copyright (c) 2018 Daniel Miessler. The licence text is below.

## Why the file is hashes and not the list

The list is what people really chose as passwords. That includes real phone numbers, real email
addresses and long account-like numbers, and the repository's rule is that none of those are
committed (`scripts/check-fixture-hygiene.sh`). So the list itself is not here.

The file holds, for each password, the first 8 bytes of the SHA-256 of its lowercased UTF-8 form.
The entries are sorted and nothing separates them: 46,483 entries, 371,864 bytes.

This does not keep the list secret, and is not meant to. The list is public, and anyone holding it
can work out which entry each hash is.

## How this copy differs from the source

Every entry is lowercased. Entries shorter than 8 or longer than 72 characters are dropped,
because the length rule already refuses them. Duplicates are removed. The source has 99,840
entries, and 46,483 remain.

## To rebuild or replace it

```
python3 scripts/build-common-password-hashes.py <downloaded-list.txt> \
    backend/src/main/resources/security/common-passwords.sha256-8.bin
```

Then update the count in `WeakPasswordGuardTest`. Do not commit the downloaded list.

## SecLists licence

```
MIT License

Copyright (c) 2018 Daniel Miessler

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
