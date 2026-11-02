package com.finora.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.finora.AbstractIntegrationTest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V306: a notification_preference_history row names the wording the app showed by version, and
 * notification_notice_versions holds the exact words of each version. This reads each app's switch
 * copy and fails when a switch with a version shows words that are not that version's stored words
 * -- so changing the text without a new version (and a new row) cannot ship, and the record always
 * says what the user actually saw.
 */
class NotificationNoticeVersionsIT extends AbstractIntegrationTest {

    /** One PREF_COPY entry that carries a noticeVersion, in either app. */
    private static final Pattern VERSIONED_ENTRY = Pattern.compile(
            "'([A-Z]+):([A-Z]+)':\\s*\\{\\s*"
                    + "label:\\s*'((?:[^'\\\\]|\\\\.)*)',\\s*"
                    + "description:\\s*'((?:[^'\\\\]|\\\\.)*)',\\s*"
                    + "noticeVersion:\\s*'([^']+)'");
    private static final Pattern ANY_NOTICE_VERSION = Pattern.compile("noticeVersion:\\s*'");

    @Autowired private JdbcTemplate jdbc;

    private record Entry(String category, String channel, String label, String description, String version) {}

    private static List<Entry> versionedEntries(String source) {
        List<Entry> entries = new ArrayList<>();
        Matcher m = VERSIONED_ENTRY.matcher(source);
        while (m.find()) {
            entries.add(new Entry(m.group(1), m.group(2), unescape(m.group(3)), unescape(m.group(4)), m.group(5)));
        }
        return entries;
    }

    private static String unescape(String tsSingleQuoted) {
        return tsSingleQuoted.replace("\\'", "'").replace("\\\\", "\\");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "../frontend/src/pages/settings/NotificationsPane.tsx",
            "../mobile/src/screens/settings/NotificationPreferencesSection.tsx"})
    void everyVersionedSwitchShowsExactlyItsVersionsStoredWords(String file) throws IOException {
        String source = Files.readString(Path.of(file));
        List<Entry> entries = versionedEntries(source);

        // Sanity: the scan found the switch, and every noticeVersion in the file was read -- a
        // reformatted entry the pattern no longer matches must fail here, not pass unchecked.
        assertThat(entries).as("versioned switches in " + file).isNotEmpty();
        assertThat(entries).hasSize((int) ANY_NOTICE_VERSION.matcher(source).results().count());

        for (Entry entry : entries) {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT category, channel, label, description FROM notification_notice_versions WHERE version = ?",
                    entry.version());
            assertThat(rows).as("stored words for " + entry.version() + " (" + file + ")").hasSize(1);
            Map<String, Object> row = rows.get(0);
            assertThat(row.get("category")).isEqualTo(entry.category());
            assertThat(row.get("channel")).isEqualTo(entry.channel());
            assertThat(row.get("label")).as("label of " + entry.version()).isEqualTo(entry.label());
            assertThat(row.get("description")).as("description of " + entry.version()).isEqualTo(entry.description());
        }
    }
}
