package com.finora.architecture;

import com.finora.architecture.registry.GuardianRule;
import com.finora.architecture.registry.GuardianSelfTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two migrations have now collided on a version number twice in this repository's history --
 * {@code V75} (two parallel branches) and {@code V81} (#110 and a same-day commit, eight minutes
 * apart). Both times the collision was invisible until after the merge, because each PR branch
 * contained only one of the two colliding files: {@code git} has nothing to diff against a file
 * that does not exist yet on either side. What actually surfaces the problem is Flyway itself,
 * refusing to boot with {@code Found more than one migration with version 81} -- which means every
 * integration test in the same run fails on a context-load error, several {@code Caused by} levels
 * above the one line that actually explains it.
 *
 * <p>This rule re-derives the same fact Flyway would have found, but as a filesystem scan with no
 * Docker and no Spring context, so it fails in seconds on a rebase onto a colliding {@code main} --
 * exactly the moment a developer would otherwise discover this by reading a stack trace.
 *
 * <p>It does not, and cannot, catch either historical incident on the PR that introduced it: each
 * branch was internally consistent, and the second file did not exist yet in that branch's own
 * working tree. It catches every case that follows -- a branch rebased onto a now-colliding
 * {@code main}, and {@code main}'s own post-merge CI.
 */
class FlywayMigrationVersionUniquenessTest {

    /**
     * Flyway's default versioned-migration filename: {@code V<version>__description.sql}. The
     * version may contain {@code _} or {@code .} as a minor-version separator (Flyway treats them
     * identically) -- this repo has used only plain integers so far, but a future {@code V12.1}
     * hotfix version must not silently fall outside what this scan recognizes as a version at all.
     * Repeatable migrations ({@code R__...}) carry no version and are correctly invisible to this
     * pattern: Flyway allows any number of them to share a description.
     */
    private static final Pattern VERSIONED_MIGRATION = Pattern.compile("V([0-9]+(?:[._][0-9]+)*)__.*\\.sql");

    private static final Path REAL_MIGRATION_DIR =
            Paths.get("src", "main", "resources", "db", "migration");

    /** @return every version string that names more than one file in {@code migrationDir}, mapped
     *  to the colliding filenames -- empty if every version is unique. Pure function of the
     *  directory contents, so the self-test below can exercise it against a synthetic fixture
     *  without touching the real migration directory. */
    private Map<String, List<String>> collidingVersions(Path migrationDir) {
        Map<String, List<String>> byVersion = new TreeMap<>();
        try (Stream<Path> files = Files.list(migrationDir)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                Matcher m = VERSIONED_MIGRATION.matcher(file.getFileName().toString());
                if (!m.matches()) continue;
                byVersion.computeIfAbsent(m.group(1), v -> new ArrayList<>())
                        .add(file.getFileName().toString());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return byVersion.entrySet().stream()
                .filter(e -> e.getValue().size() > 1)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (a, b) -> a, TreeMap::new));
    }

    @GuardianRule(
            id = "FG-032",
            // CORRECTNESS: Flyway's own failure mode for this is silent right up until startup,
            // where it becomes a context-load stack trace several `Caused by` levels away from the
            // one line (the duplicate version number) that actually explains it -- exactly the
            // category's own definition, not merely an untidy migration directory.
            category = GuardianRule.Category.CORRECTNESS,
            intent = "No two Flyway migration files under db/migration declare the same version.",
            source = "Incident: V75 and V81 each collided twice across independent branches, "
                    + "discovered only after merge as a Flyway startup failure (issue #112)",
            introduced = "2026-09-06",
            owner = "architecture",
            verification = GuardianRule.Verification.SELF_TEST)
    @Test
    @DisplayName("no two Flyway migrations share a version number")
    void noTwoMigrationsShareAVersion() {
        Map<String, List<String>> collisions = collidingVersions(REAL_MIGRATION_DIR);

        assertThat(collisions)
                .as("""
                        Two or more files under db/migration declare the same Flyway version. \
                        Flyway will refuse to start with "Found more than one migration with \
                        version N", which surfaces as a context-load failure in every integration \
                        test rather than as this one line. Renumber the newer file to the next \
                        free version -- never renumber a migration that has already been merged.""")
                .isEmpty();
    }

    /**
     * Proves the detector actually fires, against a synthetic fixture -- not the real migration
     * directory, which is (and must stay) collision-free. Two files sharing {@code V7} plus one
     * unrelated {@code V8} confirms both that a genuine collision is caught and that a
     * non-colliding version does not produce a false positive.
     */
    @GuardianSelfTest(rule = "FG-032")
    @Test
    @DisplayName("the detector fires on a deliberately colliding fixture")
    void detectsADeliberateCollision(@TempDir Path fixture) throws IOException {
        Files.writeString(fixture.resolve("V7__first.sql"), "-- fixture");
        Files.writeString(fixture.resolve("V7__second.sql"), "-- fixture");
        Files.writeString(fixture.resolve("V8__unrelated.sql"), "-- fixture");

        Map<String, List<String>> collisions = collidingVersions(fixture);

        assertThat(collisions).containsOnlyKeys("7");
        assertThat(collisions.get("7")).containsExactlyInAnyOrder("V7__first.sql", "V7__second.sql");
    }

    /**
     * Proves the rule is not vacuous: the real migration directory has, in fact, well over a
     * hundred versioned files for the scan to check. A rule that silently scanned an empty or
     * wrong directory would pass every time for a reason that has nothing to do with migrations
     * being collision-free.
     */
    @GuardianSelfTest(rule = "FG-032")
    @Test
    @DisplayName("the scan actually reaches the real migration directory")
    void scanIsNotVacuous() {
        long versionedFileCount;
        try (Stream<Path> files = Files.list(REAL_MIGRATION_DIR)) {
            versionedFileCount = files
                    .filter(f -> VERSIONED_MIGRATION.matcher(f.getFileName().toString()).matches())
                    .count();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        assertThat(versionedFileCount)
                .as("if this scan finds close to zero files, it is reading the wrong directory")
                .isGreaterThan(100);
    }

    /** @return every content hash shared by more than one versioned migration file in
     *  {@code migrationDir}, mapped to the colliding filenames -- empty if every file's content is
     *  unique. Pure function of the directory contents, so the self-test below can exercise it
     *  against a synthetic fixture without touching the real migration directory. */
    private Map<String, List<String>> collidingContent(Path migrationDir) {
        Map<String, List<String>> byHash = new TreeMap<>();
        try (Stream<Path> files = Files.list(migrationDir)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                if (!VERSIONED_MIGRATION.matcher(file.getFileName().toString()).matches()) continue;
                byHash.computeIfAbsent(sha256(Files.readAllBytes(file)), h -> new ArrayList<>())
                        .add(file.getFileName().toString());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return byHash.entrySet().stream()
                .filter(e -> e.getValue().size() > 1)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (a, b) -> a, TreeMap::new));
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @GuardianRule(
            id = "FG-033",
            // CORRECTNESS: two different version numbers were each independently minted for the
            // same renumbering of V166/V167 (#1249 and #1254), and because they carry different
            // version numbers FG-032's same-version check cannot see them. Flyway applied the lower
            // number, then the higher one failed with "column already exists" (42701) -- silent
            // until a fresh bootstrap, exactly FG-032's own failure mode one level removed.
            category = GuardianRule.Category.CORRECTNESS,
            intent = "No two Flyway migration files under db/migration are byte-identical.",
            source = "Incident: V172/V187 and V173/V188 were each byte-identical duplicates minted "
                    + "by two independent renumbering PRs (#1249, #1254), breaking main's CI and "
                    + "production bootstraps with a duplicate-column error",
            introduced = "2026-09-09",
            owner = "architecture",
            verification = GuardianRule.Verification.SELF_TEST)
    @Test
    @DisplayName("no two Flyway migrations are byte-identical")
    void noTwoMigrationsShareContent() {
        Map<String, List<String>> collisions = collidingContent(REAL_MIGRATION_DIR);

        assertThat(collisions)
                .as("""
                        Two or more files under db/migration have byte-identical content under \
                        different version numbers. This is what happens when the same renumbering \
                        is minted twice by independent branches: Flyway applies the lower number, \
                        then the higher one fails with "column already exists" (or an equivalent \
                        already-applied error) on any fresh bootstrap. Determine which version \
                        number production's own flyway_schema_history has actually recorded as \
                        applied -- that file must not move -- and delete the other. Never renumber \
                        a migration that has already been applied anywhere real.""")
                .isEmpty();
    }

    @GuardianSelfTest(rule = "FG-033")
    @Test
    @DisplayName("the content-duplicate detector fires on a deliberately duplicated fixture")
    void detectsADeliberateContentDuplicate(@TempDir Path fixture) throws IOException {
        Files.writeString(fixture.resolve("V7__first.sql"), "ALTER TABLE t ADD COLUMN c INT;");
        Files.writeString(fixture.resolve("V9__second.sql"), "ALTER TABLE t ADD COLUMN c INT;");
        Files.writeString(fixture.resolve("V8__unrelated.sql"), "ALTER TABLE t ADD COLUMN d INT;");

        Map<String, List<String>> collisions = collidingContent(fixture);

        assertThat(collisions).hasSize(1);
        assertThat(collisions.values().iterator().next())
                .containsExactlyInAnyOrder("V7__first.sql", "V9__second.sql");
    }

    @GuardianSelfTest(rule = "FG-033")
    @Test
    @DisplayName("the content-duplicate scan actually reaches the real migration directory")
    void contentScanIsNotVacuous() {
        long versionedFileCount;
        try (Stream<Path> files = Files.list(REAL_MIGRATION_DIR)) {
            versionedFileCount = files
                    .filter(f -> VERSIONED_MIGRATION.matcher(f.getFileName().toString()).matches())
                    .count();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        assertThat(versionedFileCount)
                .as("if this scan finds close to zero files, it is reading the wrong directory")
                .isGreaterThan(100);
    }
}
