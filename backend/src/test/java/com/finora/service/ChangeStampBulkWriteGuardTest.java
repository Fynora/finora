package com.finora.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ChangeStampService} learns that a row changed from its {@code version}, which JPA bumps on
 * every entity update. A bulk {@code @Modifying} UPDATE bypasses JPA, so it moves nothing unless it
 * bumps the version itself -- and the phone would never learn what it changed.
 *
 * <p>That is exactly the kind of omission that arrives silently the next time someone writes a
 * bulk update, so this reads the repository sources and fails when a bulk UPDATE on one of the
 * tracked entities neither bumps {@code version} nor is listed below with the reason it must not.
 */
class ChangeStampBulkWriteGuardTest {

    private static final Path MAIN = Path.of("src/main/java");

    private static final List<String> TRACKED_ENTITIES =
            List.of("Transaction", "Account", "StatementImport", "Budget", "Goal");

    /** Bulk updates deliberately left out, keyed by method name. */
    private static final Map<String, String> EXEMPT = Map.of(
            "applyCounterpartyTyping",
            "A backfill of a derived column, not something the user did. It must not look like an edit "
                    + "to another device, and bumping the version would make it cause or lose optimistic-lock "
                    + "races against a person genuinely editing the same row (see its own doc comment).");

    private static final Pattern MODIFYING_UPDATE = Pattern.compile(
            "@Modifying[^;]*?@Query\\((?<query>.*?)\\)\\s*(?:int|void|long)\\s+(?<method>\\w+)\\(",
            Pattern.DOTALL);

    private static final Pattern VERSION_BUMP = Pattern.compile(
            "version\\s*=\\s*(?:\\w+\\.)?version\\s*\\+\\s*1", Pattern.CASE_INSENSITIVE);

    /** Every @Modifying query in a repository, as {file, method, query text}. */
    private static List<String[]> bulkQueries() throws IOException {
        List<String[]> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(f -> f.toString().endsWith("Repository.java")).toList()) {
                Matcher m = MODIFYING_UPDATE.matcher(Files.readString(file));
                while (m.find()) {
                    String query = m.group("query").replaceAll("\"\\s*\\+\\s*\"", "").replace("\"\"\"", "");
                    found.add(new String[] {file.getFileName().toString(), m.group("method"), query});
                }
            }
        }
        return found;
    }

    private static boolean updatesTrackedEntity(String query) {
        for (String entity : TRACKED_ENTITIES) {
            if (Pattern.compile("(?i)\\bUPDATE\\s+" + entity + "\\b").matcher(query).find()) return true;
        }
        return false;
    }

    @Test
    void everyBulkUpdateOnATrackedEntityBumpsItsVersion() throws IOException {
        List<String> offenders = new ArrayList<>();
        int inspected = 0;
        for (String[] q : bulkQueries()) {
            if (!updatesTrackedEntity(q[2])) continue;
            inspected++;
            if (!VERSION_BUMP.matcher(q[2]).find() && !EXEMPT.containsKey(q[1])) {
                offenders.add(q[0] + "#" + q[1]);
            }
        }

        // If this reads zero, the pattern has stopped matching the code and the guard guards nothing.
        assertThat(inspected).as("bulk UPDATEs on tracked entities found").isGreaterThanOrEqualTo(2);
        assertThat(offenders)
                .as("A bulk UPDATE on a tracked entity that does not bump version: the phone will not "
                        + "notice what it changed. Add `version = <alias>.version + 1` to the SET clause, or "
                        + "list the method in EXEMPT with the reason it must not.")
                .isEmpty();
    }

    @Test
    void exemptionsStillNameARealBulkUpdate() throws IOException {
        List<String> stale = new ArrayList<>(EXEMPT.keySet());
        for (String[] q : bulkQueries()) {
            if (updatesTrackedEntity(q[2])) stale.remove(q[1]);
        }
        assertThat(stale).as("EXEMPT entries that no longer match a bulk UPDATE; remove them").isEmpty();
    }
}
