#!/usr/bin/env python3
"""Scans tracked repository content for identifiers that appear in the REAL statement corpus.

WHY THIS EXISTS, AND WHY IT IS DIFFERENT FROM THE OTHER TWO
-----------------------------------------------------------
check-fixture-hygiene.sh answers "does this look like customer data?" from patterns -- long digit
runs, IFSC shapes, email shapes. That is the right question for content nobody has ground truth
about, and it has a hard limit: it cannot tell a real UPI reference from an invented one, and it does
not fire at all on a real first name or a VPA like `paybill@xy` that contains no suspicious digits.

This script asks a different question: "does this value actually occur in a real customer statement?"
It has the source documents, so its answer is evidence rather than heuristic. A sweep of this kind is
what found 23 real identifiers across 11 files that months of pattern-based checks had passed.

    pattern scan   ->  might be customer data      ->  runs in CI, guesses
    corpus scan    ->  IS customer data, verified  ->  local only, knows

The cost is that it needs the corpus, which lives outside the working tree by policy. So this can
never run in CI. It is a local pre-merge check, and the pattern scans are its CI-runnable
approximation -- not its replacement.

PREFIXES, NOT JUST WHOLE VALUES
-------------------------------
A real 12-digit UPI reference was quoted three times in a comment as "UPI/1240089..." -- truncated
for display. Every scan in this repository looked for 10+ digit runs, so a 7-digit fragment of a real
identifier evaded all of them, including the first version of this sweep. Whole-value matching is not
sufficient: a leak that has been shortened for readability is still a leak.

So proper prefixes of length >= 8 are matched too, because display truncation cuts the tail. Prefixes
containing a run of four or more identical characters are dropped first -- `00000000` matches every
UUID in the tree and identifies nobody. That filter is the same predicate check-fixture-hygiene.sh
already trusts, and without it this sweep reports 101 lines of noise instead of the 17 real ones.

Suffix and interior fragments are NOT matched, and that is a stated limit rather than an oversight:
they raise the false-positive rate sharply while display truncation does not produce them. If a
leaked interior fragment is ever found, this comment is where the decision to revisit that should
start.

CASE-INSENSITIVE, BECAUSE THE SAME HANDLE IS WRITTEN BOTH WAYS
--------------------------------------------------------------
A statement prints a UPI handle in capitals, and the code that derives a key from it lowercases it.
The first version of this sweep matched case-sensitively, so a real gateway handle that had been
replaced in its printed form survived in five tests and a doc comment as a lowercased `masked:` key.
Matching is therefore case-insensitive on the repository side.

SEVERAL SOURCES, AND STATEMENTS IT CANNOT READ
----------------------------------------------
Statements do not all live in one folder: testers' statements have sat loose at the top of the
Downloads folder, outside both corpus folders, and a real handle from one of them reached a test
that every scan of the corpus folders passed. So any number of directories may be given, and each
one's top-level PDFs and CSV exports are read.

A password-protected PDF cannot be read, and the first version skipped it without a word -- its
identifiers were simply never checked, and the scan still said "clean". Now an unreadable statement
fails the run (exit 2) and is named. Supply its password with --passwords FILE, a tab-separated
`<pdf file name>\t<password>` file kept OUTSIDE this repository; the passwords are handed to the PDF
reader and never printed. --allow-unreadable downgrades the failure to a warning, for a run where
the gap is known and accepted.

A scanned statement opens without error and has no text, which is the same silent gap in another
form. A page with almost no text is read by OCR when tesseract is installed (the page image goes to
tesseract on stdin, never to disk); a document that still has no text counts as unreadable. OCR
misreads characters, so those pages are listed as read with caveats -- a match there is real, but a
miss is weaker evidence than on a text page.

EXIT CODES, AND ONE WAY TO LOSE THEM
------------------------------------
    0   no corpus identifier occurs in tracked content, and every statement was read
    1   at least one does
    2   none was found, but at least one statement could not be read, so the answer is incomplete

Findings go to stderr and the clean message to stdout, so `... | tail` reports the exit status of
tail rather than of this script. That is not hypothetical -- it is how the first reading of this
scanner's own output was misreported as passing. Check the status directly, or use `set -o pipefail`.

WHAT IT DOES NOT DO
-------------------
It does not judge severity, and it does not distinguish a customer's account number from a bank's
public IFSC or a merchant's VPA -- all three occur in a statement. Classification is a human
decision against repository policy. This reports occurrence.

It does not match a UPI id that a bank printed cut short with no "@" left ("airtelautopay.p"), nor
any other free-text fragment: those have no shape that separates them from ordinary words, and a
dotted-id rule tried on 2026-10-03 matched production constants and PDF font names as often as it
matched copies. A fixture built from a statement's narration still needs a person to check it.
"""

import argparse
import re
import shutil
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
MIN_PREFIX = 8

# Lifted deliberately from check-fixture-hygiene.sh so the two agree on what is not identifying.
REPEATED_RUN = re.compile(r"(.)\1{3,}")

VPA = re.compile(r"[A-Za-z0-9._%+-]{3,}@[A-Za-z0-9.-]{2,}")    # email and UPI VPA

PATTERNS = [
    re.compile(r"[0-9]{10,}"),                      # account, card, transaction reference
    re.compile(r"\b[A-Z]{4}0[A-Z0-9]{6}\b"),        # IFSC
    VPA,
    re.compile(r"\b[6-9][0-9]{9}\b"),               # Indian mobile
]

# Whitespace around a handle's "@" (OCR's "name @bank", or a wrap just before the "@"), and a wrap
# INSIDE the name ("samplepoun d@okicici", seen on real statements): the two pieces before the "@"
# are also read joined. Both only add candidates; the text as printed is always read too.
# The domain must start right after the "@": prose like "interest @ 3.5%" has a space on both sides.
AT_GAP = re.compile(r"(?<=[A-Za-z0-9._%+-])[ \t]*\n?[ \t]*@(?=[A-Za-z0-9])")
SPLIT_NAME = re.compile(r"([A-Za-z0-9._%+-]+)[ \t]*\n?[ \t]*([A-Za-z0-9._%+-]+@[A-Za-z0-9.-]{2,})")

SKIP = re.compile(r"^(backend/target/|.*-lock\.(json|yaml)$|.*\.pdf$)")

# ---------------------------------------------------------------- separated identifiers
#
# A card number written "1234 5678 9012 3456" and the same number written contiguously are the same
# identifier, and every digit rule in this repository saw only the second. That is not hypothetical:
# a real card number and a real account number sat in a fixture through seven green automated gates
# because their separators hid them, on BOTH sides of the comparison -- so normalisation has to apply
# to the corpus extraction as well as the repository scan, or the two disagree by construction.
#
# Deliberately narrow, because the failure mode of a broad rule here is a scanner people bypass:
#   - at least MIN_SEPARATED_DIGITS digits in total, which excludes dates (8 at most) and ordinary
#     quantities;
#   - groups joined by a SINGLE space or hyphen only, never a dot or comma, which excludes monetary
#     amounts;
#   - the UUID shape 8-4-4-4-12 is rejected outright, because an all-digit UUID would otherwise
#     qualify on length alone.
MIN_SEPARATED_DIGITS = 12
SEPARATED = re.compile(r"(?<![\d.,-])\d{2,6}(?:[ -]\d{2,6}){1,5}(?![\d.,])")
UUID_GROUPS = (8, 4, 4, 4, 12)


def separated_digits(text: str) -> set:
    """Digit-only forms of separator-split identifiers. Empty set is the normal case."""
    out = set()
    for m in SEPARATED.finditer(text):
        raw = m.group()
        groups = [len(g) for g in re.split(r"[ -]", raw)]
        if tuple(groups) == UUID_GROUPS:
            continue
        digits = re.sub(r"[ -]", "", raw)
        if len(digits) >= MIN_SEPARATED_DIGITS and not REPEATED_RUN.fullmatch(digits):
            out.add(digits)
    return out


def _self_test() -> int:
    cases = [
        ("card, spaced",      "CREDIT CARD ACCOUNT  4000 1111 2222 3333", {"4000111122223333"}),
        ("card, hyphenated",  "card 1234-5678-9012-3456",                 {"1234567890123456"}),  # synthetic-ok: sequential test pattern, absent from the corpus
        ("account, 3-6-3",    "SAVINGS ACCOUNT-RES  100-111111-002",      {"100111111002"}),
        ("uuid, all digits",  "id 12345678-1234-1234-1234-123456789012",  set()),  # synthetic-ok: sequential test pattern, absent from the corpus
        ("date",              "txn on 22/07/2026 and 15-07-2026",         set()),
        ("amount",            "Total 1,817.00 Minimum 200.00",            set()),
        ("short quantity",    "rows 12 34",                               set()),
        ("synthetic fixture", "card 0000 0000 0000 0000",                 set()),
    ]
    bad = 0
    for name, text, want in cases:
        got = separated_digits(text)
        ok = got == want
        bad += 0 if ok else 1
        print(f"  {'ok  ' if ok else 'FAIL'} {name:<20} -> {sorted(got) if got else '(none)'}")

    # Handles split by OCR or a wrap: `want` must be among the needles, `not_want` must not.
    handle_cases = [
        ("handle, as printed", "UPI/DR/samplestore1@zzbank/x", {"samplestore1@zzbank"}, set()),
        ("handle, OCR gap",    "UPI DR samplestore1 @zzbank REF", {"samplestore1@zzbank"}, set()),
        ("handle, name wrap",  "PAID TO samplepoun d@okzzbank", {"samplepound@okzzbank"}, set()),
        ("handle, wrap at @",  "samplestore1\n@zzbank", {"samplestore1@zzbank"}, set()),
        ("prose 'at'",         "meet at the bank @ noon", set(), {"bank@noon", "thebank@noon"}),
    ]
    for name, text, want, not_want in handle_cases:
        got = needles(text)
        ok = want <= got and not (not_want & got)
        bad += 0 if ok else 1
        print(f"  {'ok  ' if ok else 'FAIL'} {name:<20} -> {sorted(want & got) if want else '(none wanted)'}")
    total = len(cases) + len(handle_cases)
    print(f"\n  {total - bad} passed, {bad} failed")
    return 1 if bad else 0


# CSV only: a bank's own export is a statement. A .txt is not -- reading every one in a Downloads
# folder pulled in a page of the developer's own command notes, whose test-account values then
# "matched" the e2e fixtures that legitimately use them.
TEXT_SOURCES = (".csv",)


# Kept as one raw string so the Java reads as Java. See corpus_text for why each piece exists.
CORPUS_DUMP_JAVA = r'''
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import javax.imageio.ImageIO;
import java.io.*;
import java.nio.file.Files;
import java.util.HashMap;

public class CorpusDump {
  static final int MIN_PAGE_CHARS = 40;

  public static void main(String[] a) throws Exception {
    PrintStream out = System.out;
    System.setOut(System.err);
    var pw = new HashMap<String, String>();
    if (!a[1].isEmpty()) for (String l : Files.readAllLines(new File(a[1]).toPath())) {
      l = l.replace("\r", "");
      int t = l.indexOf('\t');
      if (t > 0) pw.put(l.substring(0, t), l.substring(t + 1));
    }
    String tess = a[2];
    for (File f : new File(a[0]).listFiles((d, n) -> n.toLowerCase().endsWith(".pdf"))) {
      String p = pw.get(f.getName());
      try (var d = p == null ? Loader.loadPDF(f) : Loader.loadPDF(f, p)) {
        StringBuilder text = new StringBuilder(new PDFTextStripper().getText(d));
        int pages = d.getNumberOfPages(), empty = 0, ocred = 0;
        PDFRenderer renderer = new PDFRenderer(d);
        for (int i = 0; i < pages; i++) {
          var s = new PDFTextStripper();
          s.setStartPage(i + 1);
          s.setEndPage(i + 1);
          if (s.getText(d).replaceAll("\\s+", "").length() >= MIN_PAGE_CHARS) continue;
          String ocr = tess.isEmpty() ? "" : ocr(tess, renderer, i);
          if (ocr.replaceAll("\\s+", "").length() >= MIN_PAGE_CHARS) { text.append('\n').append(ocr); ocred++; }
          else empty++;
        }
        out.println(text);
        if (pages > 0 && empty == pages) System.err.println("UNREADABLE\t" + f.getName() + "\tNoTextLayer");
        else if (empty > 0) System.err.println("PARTIAL\t" + f.getName() + "\t" + empty + "/" + pages + " pages without text");
        if (ocred > 0) System.err.println("OCR\t" + f.getName() + "\t" + ocred + "/" + pages + " pages read by OCR");
      } catch (Exception e) {
        System.err.println("UNREADABLE\t" + f.getName() + "\t" + e.getClass().getSimpleName());
      }
    }
  }

  static String ocr(String tess, PDFRenderer renderer, int page) throws Exception {
    var png = new ByteArrayOutputStream();
    ImageIO.write(renderer.renderImageWithDPI(page, 300), "png", png);
    Process proc = new ProcessBuilder(tess, "stdin", "stdout")
        .redirectError(ProcessBuilder.Redirect.DISCARD).start();
    try (var in = proc.getOutputStream()) { in.write(png.toByteArray()); }
    String text = new String(proc.getInputStream().readAllBytes());
    return proc.waitFor() == 0 ? text : "";
  }
}
'''


def corpus_text(corpus: Path, passwords: Path = None, unreadable: list = None,
                notes: list = None) -> str:
    """Every statement's text in one directory, concatenated: its top-level PDFs (through the
    backend's PDFBox, which must be on the classpath) and its top-level CSV exports.

    A PDF that cannot be read -- protected, broken, or a scan with no text that could not be OCR'd --
    is appended to `unreadable` (file name and reason only); when no list is passed, the caller has
    said it does not care, and the skip stays silent. Scanned pages that were OCR'd, and pages left
    without text in an otherwise readable document, go to `notes`."""
    cp_file = REPO_ROOT / "backend" / "target" / "corpus-classpath.txt"
    classes = REPO_ROOT / "backend" / "target" / "classes"
    if not cp_file.is_file():
        sys.exit("No backend/target/corpus-classpath.txt. Run a corpus-run.py first, or:\n"
                 "  cd backend && ./mvnw -q -o dependency:build-classpath "
                 "-Dmdep.outputFile=target/corpus-classpath.txt -Dmdep.includeScope=test")

    # The password file is read by the Java side, so a password never passes through this process's
    # output. Only the exception's class is reported for a skipped file -- PDFBox messages can quote
    # document content.
    #
    # System.out is re-pointed at stderr before PDFBox loads: its font warnings are logged to the
    # console, and while they shared stdout with the extracted text they became corpus "identifiers"
    # (font and class names) that then matched ordinary code. Text goes to the saved stream only.
    #
    # A page with almost no text is a scanned image. When tesseract is available it is rendered and
    # read through tesseract's stdin/stdout, so no page image is ever written to disk; when it is not,
    # a document with no text at all is reported as unreadable rather than passed as clean.
    src = REPO_ROOT / "backend" / "target" / "CorpusDump.java"
    src.write_text(CORPUS_DUMP_JAVA)
    cp = f"{classes}:{cp_file.read_text().strip()}"
    subprocess.run(["javac", "-cp", cp, "-d", str(REPO_ROOT / "backend" / "target" / "corpusdump"),
                    str(src)], check=True, capture_output=True)
    args = [str(corpus), str(passwords) if passwords else "", shutil.which("tesseract") or ""]
    out = subprocess.run(["java", "-cp",
                          f"{REPO_ROOT / 'backend' / 'target' / 'corpusdump'}:{cp}",
                          "CorpusDump", *args], capture_output=True, text=True)
    if out.returncode != 0:
        sys.exit(f"reading the PDFs in {corpus} failed (java exit {out.returncode})")
    skipped = []
    for line in out.stderr.splitlines():
        parts = line.split("\t")
        if len(parts) != 3:
            continue
        if parts[0] == "UNREADABLE":
            skipped.append((corpus / parts[1], parts[2]))
        elif parts[0] in ("PARTIAL", "OCR") and notes is not None:
            notes.append((parts[0], corpus / parts[1], parts[2]))
    if unreadable is not None:
        unreadable.extend(skipped)

    texts = [out.stdout]
    for f in sorted(corpus.iterdir()):
        if f.is_file() and f.suffix.lower() in TEXT_SOURCES:
            texts.append(f.read_text(errors="ignore"))
    text = "\n".join(texts)
    if not text.strip() and not skipped:
        sys.exit(f"no text extracted from any statement in {corpus}")
    return text


def split_handles(text: str) -> set:
    """Handles as they read once a split at or before the "@" is closed up; see AT_GAP and
    SPLIT_NAME. Applied to the corpus AND the repository, for the reason given under SYMMETRY."""
    out = set()
    joined = AT_GAP.sub("@", text)
    for m in VPA.finditer(joined):
        out.add(m.group())
    for m in SPLIT_NAME.finditer(joined):
        out.add(m.group(1) + m.group(2))
    return {v for v in out if len(v) >= 10 and VPA.fullmatch(v) and not REPEATED_RUN.fullmatch(v)}


def needles(text: str) -> set:
    """Whole identifiers plus their proper prefixes. See the module docstring on why prefixes."""
    whole = set()
    for p in PATTERNS:
        for m in p.finditer(text):
            v = m.group()
            if len(v) >= 10 and not REPEATED_RUN.fullmatch(v):
                whole.add(v)
    whole |= split_handles(text)

    out = set(whole) | separated_digits(text)
    for v in whole:
        if not v.isdigit():
            continue                                # truncation of a VPA is not a distinct shape
        for n in range(MIN_PREFIX, len(v)):
            frag = v[:n]
            if not REPEATED_RUN.search(frag):       # 00000000 identifies nobody
                out.add(frag)
    return out


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("corpus", type=Path, nargs="*",
                    help="one or more directories of real statements, OUTSIDE this repository; "
                         "each one's top-level PDF and CSV files are read")
    ap.add_argument("--passwords", type=Path,
                    help="tab-separated '<pdf file name>\\t<password>' lines for protected statements, "
                         "kept OUTSIDE this repository")
    ap.add_argument("--allow-unreadable", action="store_true",
                    help="warn about statements that could not be read instead of failing (exit 2)")
    ap.add_argument("--self-test", action="store_true",
                    help="verify separator normalisation and its false-positive guards; no corpus needed")
    args = ap.parse_args()

    if args.self_test:
        return _self_test()
    if not args.corpus:
        ap.error("corpus directory required (or pass --self-test)")

    # Same refusal as corpus-run.py and trace-capture.sh, for the same reason -- and the password
    # file is held to it too: a statement's password is as much the customer's as the statement.
    passwords = args.passwords.resolve() if args.passwords else None
    if passwords is not None:
        if not passwords.is_file():
            sys.exit(f"not a file: {passwords}")
        if passwords.is_relative_to(REPO_ROOT):
            sys.exit(f"REFUSED: {passwords} is inside the repository.")

    texts, unreadable, notes = [], [], []
    for given in args.corpus:
        corpus = given.resolve()
        if not corpus.is_dir():
            sys.exit(f"not a directory: {corpus}")
        if corpus.is_relative_to(REPO_ROOT):
            sys.exit(f"REFUSED: {corpus} is inside the repository.")
        texts.append(corpus_text(corpus, passwords, unreadable, notes))

    ns = needles("\n".join(texts))
    print(f"corpus identifiers + prefixes to match: {len(ns)}", file=sys.stderr)

    # Patterns go to git grep on STDIN, so the needle list is never written anywhere. An earlier
    # version persisted it to backend/target/corpus-needles.txt: thousands of real customer
    # identifiers, materialised inside the repository tree on every run. Gitignored, so it would never
    # have been committed -- and that is exactly the reasoning this incident exists to reject. The
    # rule is that real customer data must not become a development artefact, not merely that it must
    # not be committed, and a build directory is a development artefact.
    # -i: see "CASE-INSENSITIVE" in the module docstring.
    # An empty pattern list is not "nothing to find": git grep reads it as one empty pattern, which
    # matches every line in the repository. With no needles there is simply no verdict (exit 2 below).
    hits = subprocess.run(["git", "grep", "-niF", "-f", "-", "--", "."],
                          cwd=REPO_ROOT, input="\n".join(sorted(ns)),
                          capture_output=True, text=True).stdout.splitlines() if ns else []
    hits = [h for h in hits if not SKIP.match(h.split(":", 1)[0])]

    # SYMMETRY. The pass above greps literally, so it only catches a corpus value that is spaced in
    # the document and contiguous in the tree. The real card number was the other direction --
    # contiguous in the statement, spaced 4x4 in the fixture -- and no literal needle can see that.
    # So the repository side is normalised the same way the corpus side is, and the comparison is
    # made on digits alone. Without this, "separator-tolerant" would be true of one side only, which
    # is the same disagreement-by-construction the corpus normalisation exists to prevent.
    corpus_digits = {n for n in ns if n.isdigit() and len(n) >= MIN_SEPARATED_DIGITS}
    # The same symmetry for handles: a statement's wrap ("name piec e@bank") copied into a fixture
    # is invisible to the literal pass, whose needle is the joined handle.
    corpus_handles = {n.lower() for n in ns if "@" in n}
    literal = {":".join(h.split(":", 2)[:2]) for h in hits}
    tracked = subprocess.run(["git", "ls-files"], cwd=REPO_ROOT,
                             capture_output=True, text=True).stdout.splitlines()
    for rel in tracked:
        if SKIP.match(rel):
            continue
        f = REPO_ROOT / rel
        try:
            body = f.read_text(errors="ignore")
        except OSError:
            continue
        for n_, line in enumerate(body.splitlines(), 1):
            if "@" in line and f"{rel}:{n_}" not in literal and \
                    {v.lower() for v in split_handles(line)} & corpus_handles:
                hits.append(f"{rel}:{n_}: split corpus handle")
            if "synthetic-ok" in line:
                continue
            for d in separated_digits(line) & corpus_digits:
                hits.append(f"{rel}:{n_}: separator-split corpus identifier ({len(d)} digits)")

    # Fails the run rather than warning: a scanner that leaves customer identifiers behind has
    # created the problem it exists to find.
    leftover = [p for p in (REPO_ROOT / "backend" / "target").glob("corpus-needles*")]
    if leftover:
        sys.exit(f"REFUSED: this scan persisted corpus identifiers to {leftover[0]}. "
                 "Needles must stay in memory; see the comment above the git grep call.")

    # NAMES ONLY -- a file name and a reason. The statement's content never reaches here.
    if notes:
        print("\nREAD WITH CAVEATS (OCR misreads characters, so a copied value can escape a match on "
              "these pages):", file=sys.stderr)
        for kind, path, what in notes:
            print(f"  {path}  ({what})", file=sys.stderr)
        if not shutil.which("tesseract"):
            print("tesseract is not installed, so scanned pages were not read at all.", file=sys.stderr)
    if unreadable:
        level = "WARNING" if args.allow_unreadable else "INCOMPLETE"
        print(f"\n{level}: {len(unreadable)} statement(s) could not be read, so their identifiers "
              "were NOT checked:", file=sys.stderr)
        for path, why in unreadable:
            print(f"  {path}  ({why})", file=sys.stderr)
        if any(why == "NoTextLayer" for _, why in unreadable) and not shutil.which("tesseract"):
            print("A NoTextLayer statement is a scan: install tesseract and its pages are read by OCR.",
                  file=sys.stderr)
        print("Supply a protected statement's password with --passwords FILE (see the module "
              "docstring),\nor pass --allow-unreadable to accept the gap knowingly.", file=sys.stderr)

    if not hits:
        if not ns:
            print("no identifiers were extracted from any statement -- nothing was compared, so "
                  "there is no verdict.", file=sys.stderr)
            return 2
        if unreadable and not args.allow_unreadable:
            print("no corpus identifier found in what was read -- but the scan is INCOMPLETE (see above).")
            return 2
        print("clean -- no corpus identifier occurs in tracked content.")
        return 0

    # LOCATIONS AND CATEGORIES ONLY -- never the value, and never the matching line. The line would
    # carry the identifier straight into a terminal, a CI log, a pasted report or a redirected file,
    # which is how this scanner's own diagnostic output became a copy of the data during the incident.
    # A file and a line number is enough to act on; the value is not needed to fix it.
    print("\nCORPUS-DERIVED IDENTIFIERS FOUND IN TRACKED CONTENT:", file=sys.stderr)
    for h in hits:
        parts = h.split(":", 2)
        where = ":".join(parts[:2]) if len(parts) >= 2 else h
        kind = parts[2].strip() if len(parts) > 2 and parts[2].strip().startswith(
            ("separator-split", "split corpus handle")) else "matches a corpus identifier"
        print(f"  {where}  {kind}", file=sys.stderr)
    print(f"\n{len(hits)} line(s). These are verified occurrences, not pattern guesses.\n"
          "Replace with deterministic synthetic values that preserve what each test asserts.\n"
          "Do NOT paste the offending values into a file in this repository to track the work.",
          file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
