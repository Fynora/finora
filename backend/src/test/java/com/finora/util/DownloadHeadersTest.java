package com.finora.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.ContentDisposition;

/** The download header for any file name: unchanged for plain ASCII, readable for everything else. */
class DownloadHeadersTest {

    @Test
    void anAsciiName_isWrittenExactlyAsBefore() {
        for (String name : new String[]{"invoice.pdf", "fynora-export-2026-11-10.zip", "a \"quoted\" name.png"}) {
            assertThat(DownloadHeaders.attachment(name))
                    .isEqualTo(ContentDisposition.attachment().filename(name).build().toString());
        }
    }

    @Test
    void aNameOutsideAscii_getsAStandInAndItsRealNameInFilenameStar() {
        assertThat(DownloadHeaders.attachment("बैंक.csv"))
                .isEqualTo("attachment; filename=\"____.csv\"; filename*=UTF-8''%E0%A4%AC%E0%A5%88%E0%A4%82%E0%A4%95.csv");
        // An emoji is one character, so one stand-in, not two.
        assertThat(DownloadHeaders.attachment("📄 receipt.pdf"))
                .isEqualTo("attachment; filename=\"_ receipt.pdf\"; filename*=UTF-8''%F0%9F%93%84%20receipt.pdf");
    }

    @Test
    void theRealNameRoundTrips_throughSpringsOwnParser() {
        for (String name : new String[]{"स्क्रीनशॉट रिपोर्ट.png", "Kontoauszug März (1).pdf", "x;y=z,'q'.txt"}) {
            ContentDisposition parsed = ContentDisposition.parse(DownloadHeaders.attachment(name));
            assertThat(parsed.isAttachment()).isTrue();
            assertThat(parsed.getFilename()).isEqualTo(name);
        }
    }

    @Test
    void controlCharacters_neverReachTheHeader() {
        String header = DownloadHeaders.attachment("a\r\nSet-Cookie: x=1é.txt");
        assertThat(header).doesNotContain("\r").doesNotContain("\n");
    }
}
