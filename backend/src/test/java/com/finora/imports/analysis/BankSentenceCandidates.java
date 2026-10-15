package com.finora.imports.analysis;

import com.finora.imports.pdf.PdfPreviewGenerator;
import com.finora.imports.pdf.acquisition.AcquiredDocument;
import com.finora.imports.pdf.trace.BankSentences;
import com.finora.imports.pdf.trace.PdfTraceRedactor;
import com.finora.util.BankRegistry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Lists candidate bank sentences from the owner's local statements, for him to choose the seed of
 * {@link BankSentences} from (see docs/superpowers/specs/2026-10-15-bank-sentences-design.md).
 *
 * <p>A candidate is a printed line, joined as the redactor joins it ({@link PdfTraceRedactor#printedLines}
 * over the runs the blanked copy acquires), whose normalised text passes
 * {@link BankSentences#requireSafe} -- no digit, at least four words, no '@', no IFSC shape, ASCII
 * only -- and appears in at least {@code minStatements} different statement files.
 *
 * <p>Run only through {@code scripts/bank-sentence-candidates.py}, which refuses any path inside a
 * repository. The candidates go to the output file only; stdout carries the counts line and
 * nothing else from a statement, so the run can be watched without seeing one.
 */
public final class BankSentenceCandidates {

    private BankSentenceCandidates() {}

    record Candidate(int statements, String banks, String line) {}

    record Result(int statementsRead, int unreadable, int linesConsidered, List<Candidate> candidates) {}

    public static void main(String[] args) throws IOException {
        if (args.length != 3) {
            System.err.println("Usage: BankSentenceCandidates <corpus-dir> <out-file> <min-statements>");
            System.exit(2);
            return;
        }
        Result result = candidates(Path.of(args[0]), Integer.parseInt(args[2]));
        write(Path.of(args[1]), result.candidates());
        System.out.println(summary(result));
    }

    static Result candidates(Path dir, int minStatements) throws IOException {
        List<Path> pdfs;
        try (Stream<Path> walk = Files.walk(dir)) {
            pdfs = walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf"))
                    .sorted().toList();
        }
        PdfPreviewGenerator generator = ProbePipelines.standardGenerator();
        Map<String, Set<Integer>> statementsPerLine = new HashMap<>();
        Map<String, Set<String>> banksPerLine = new HashMap<>();
        int read = 0;
        int unreadable = 0;
        int considered = 0;
        for (int n = 0; n < pdfs.size(); n++) {
            List<String> lines;
            String bank;
            try {
                AcquiredDocument acquired = generator.acquire(Files.readAllBytes(pdfs.get(n)), null);
                lines = PdfTraceRedactor.printedLines(acquired.runs());
                bank = bankOf(generator, pdfs.get(n), acquired);
            } catch (Throwable t) {
                // Never the message: a parser's exception can quote the statement's text.
                unreadable++;
                continue;
            }
            read++;
            considered += lines.size();
            for (String line : lines) {
                String normalised = BankSentences.normalise(line);
                if (!isCandidate(normalised)) continue;
                statementsPerLine.computeIfAbsent(normalised, k -> new HashSet<>()).add(n);
                banksPerLine.computeIfAbsent(normalised, k -> new TreeSet<>()).add(bank);
            }
        }
        List<Candidate> candidates = new ArrayList<>();
        statementsPerLine.forEach((line, statements) -> {
            if (statements.size() >= minStatements) {
                candidates.add(new Candidate(statements.size(), String.join(",", banksPerLine.get(line)), line));
            }
        });
        candidates.sort(Comparator.comparingInt(Candidate::statements).reversed().thenComparing(Candidate::line));
        return new Result(read, unreadable, considered, List.copyOf(candidates));
    }

    private static boolean isCandidate(String normalised) {
        try {
            BankSentences.requireSafe(normalised);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** The banks the pipeline detects for the statement's sections, comma-joined, or "unknown". */
    private static String bankOf(PdfPreviewGenerator generator, Path pdf, AcquiredDocument acquired) {
        Set<String> banks = new TreeSet<>();
        for (var section : generator.generateSectionsFromAcquired(UUID.randomUUID(),
                pdf.getFileName().toString(), acquired).sections()) {
            var account = section.detectedAccount();
            if (account != null && account.bank() != null && account.bank().id() != null
                    && !BankRegistry.UNKNOWN_ID.equals(account.bank().id())) {
                banks.add(account.bank().id());
            }
        }
        return banks.isEmpty() ? "unknown" : String.join(",", banks);
    }

    /** One line per candidate: statements, banks, the normalised line, tab-separated. */
    static void write(Path out, List<Candidate> candidates) throws IOException {
        StringBuilder text = new StringBuilder();
        for (Candidate c : candidates) {
            text.append(c.statements()).append('\t').append(c.banks()).append('\t').append(c.line()).append('\n');
        }
        Files.writeString(out, text.toString(), StandardCharsets.UTF_8);
    }

    static String summary(Result result) {
        return "statements read: " + result.statementsRead() + ", lines considered: " + result.linesConsidered()
                + ", candidates: " + result.candidates().size()
                + (result.unreadable() > 0 ? ", unreadable: " + result.unreadable() : "");
    }
}
