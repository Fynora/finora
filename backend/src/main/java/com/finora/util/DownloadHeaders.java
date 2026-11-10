package com.finora.util;

import java.nio.charset.StandardCharsets;
import org.springframework.http.ContentDisposition;

/**
 * The {@code Content-Disposition} value for a file download, safe for any filename.
 *
 * <p>{@code ContentDisposition.attachment().filename(name)} writes the name as it is. For a name with
 * characters outside ASCII -- a Hindi or accented file name, which customers upload -- Tomcat drops
 * the whole header without an error (found 2026-11-10: a support attachment named in Hindi came
 * back with no Content-Disposition at all), so the browser opens the file in the page instead of
 * downloading it, under no name. Spring's {@code filename(name, UTF_8)} keeps the header but also
 * rewrites plain ASCII names into an encoded-word ({@code filename="=?UTF-8?Q?invoice.pdf?="}).
 *
 * <p>So: an ASCII name is written exactly as before. Any other name gets an ASCII stand-in in
 * {@code filename} (each other character becomes {@code _}) for old clients, and the real name in
 * {@code filename*} (RFC 6266 / RFC 5987), which every current browser prefers.
 */
public final class DownloadHeaders {

    private DownloadHeaders() {}

    public static String attachment(String filename) {
        if (isPlainAscii(filename)) {
            return ContentDisposition.attachment().filename(filename).build().toString();
        }
        return ContentDisposition.attachment().filename(asciiStandIn(filename)).build()
                + "; filename*=UTF-8''" + encode(filename);
    }

    private static boolean isPlainAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c > 0x7E) return false;
        }
        return true;
    }

    private static String asciiStandIn(String s) {
        StringBuilder out = new StringBuilder(s.length());
        s.codePoints().forEach(cp -> out.append(cp >= 0x20 && cp <= 0x7E ? (char) cp : '_'));
        return out.toString();
    }

    /** RFC 5987 {@code value-chars}: attr-char as is, every other UTF-8 byte as {@code %XX}. */
    private static String encode(String s) {
        StringBuilder out = new StringBuilder();
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || "!#$&+-.^_`|~".indexOf(c) >= 0) {
                out.append((char) c);
            } else {
                out.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 0xF, 16)));
            }
        }
        return out.toString();
    }
}
