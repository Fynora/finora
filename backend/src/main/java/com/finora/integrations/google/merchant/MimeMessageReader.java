package com.finora.integrations.google.merchant;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a raw email (what Gmail's "Show original" downloads as a {@code .eml}) far enough to give
 * an admin authoring a merchant template what the production pipeline would have seen: the
 * headers that matter, and the decoded HTML and plain-text bodies.
 *
 * <p>Deliberately small. It reads headers, walks {@code multipart/*} parts, and decodes the body
 * transfer encodings ({@code base64}, {@code quoted-printable}, 7bit/8bit) in the part's own
 * charset. It does not interpret attachments, nested {@code message/rfc822} messages, or anything
 * it does not need, and it is bounded: a hostile or accidental file cannot make it recurse or loop
 * without limit, because this runs on admin-supplied input before anything else has looked at it.
 *
 * <p>Nothing here logs, stores, or returns anything but what the caller asked for, and nothing is
 * kept: the email is personal data (addresses, orders) and passes through this class in memory only.
 */
final class MimeMessageReader {

    /** The most nesting a message may have before the rest is ignored. Real mail is 2 to 4 deep. */
    private static final int MAX_DEPTH = 10;

    /** The most parts one message may have before the rest are ignored. */
    private static final int MAX_PARTS = 300;

    /** A raw email is refused above this size, so a file that is not an email cannot be walked. */
    static final int MAX_RAW_CHARS = 5_000_000;

    private static final Pattern HEADER_END = Pattern.compile("\\r?\\n\\r?\\n");
    private static final Pattern ENCODED_WORD = Pattern.compile("=\\?([^?\\s]+)\\?([bBqQ])\\?([^?\\s]*)\\?=");
    private static final Pattern ENCODED_WORD_GAP = Pattern.compile("(\\?=)\\s+(=\\?)");

    /** What was read. {@code headers} keys are lower-case; each value list is in message order. */
    record Parsed(Map<String, List<String>> headers, String html, String text) {

        /** The first value of a header, or null. */
        String firstHeader(String name) {
            List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
            return values == null || values.isEmpty() ? null : values.get(0);
        }
    }

    private MimeMessageReader() {
    }

    static Parsed read(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("The email is empty.");
        }
        if (raw.length() > MAX_RAW_CHARS) {
            throw new IllegalArgumentException("The email is too large (over " + MAX_RAW_CHARS / 1_000_000
                    + " MB). Download the original message, not one with large attachments.");
        }
        MimePart root = split(raw);
        Collected collected = new Collected();
        walk(root, 0, collected);
        return new Parsed(root.headers, collected.html, collected.text);
    }

    /** Decodes RFC 2047 encoded words ({@code =?UTF-8?B?...?=}) in a header value such as a display
     *  name. Anything it cannot decode is left as written. */
    static String decodeHeaderWords(String value) {
        if (value == null || !value.contains("=?")) return value;
        String joined = ENCODED_WORD_GAP.matcher(value).replaceAll("$1$2");
        Matcher m = ENCODED_WORD.matcher(joined);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String decoded = decodeWord(m.group(1), m.group(2), m.group(3));
            m.appendReplacement(out, Matcher.quoteReplacement(decoded != null ? decoded : m.group()));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String decodeWord(String charsetName, String encoding, String text) {
        try {
            byte[] bytes;
            if (encoding.equalsIgnoreCase("B")) {
                bytes = Base64.getMimeDecoder().decode(text);
            } else {
                bytes = decodeQuotedPrintable(text.replace('_', ' '));
            }
            return new String(bytes, charsetFor(charsetName));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------------------------------

    private static final class Collected {
        String html;
        String text;
        int parts;
    }

    private static final class MimePart {
        Map<String, List<String>> headers = new HashMap<>();
        String body = "";
    }

    private static MimePart split(String raw) {
        MimePart entity = new MimePart();
        Matcher end = HEADER_END.matcher(raw);
        String head;
        if (end.find()) {
            head = raw.substring(0, end.start());
            entity.body = raw.substring(end.end());
        } else {
            head = raw;
        }
        // Unfold: a line starting with whitespace continues the previous header.
        String unfolded = head.replaceAll("\\r?\\n[ \\t]+", " ");
        for (String line : unfolded.split("\\r?\\n")) {
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            if (name.isEmpty() || name.contains(" ")) continue;
            entity.headers.computeIfAbsent(name, k -> new ArrayList<>()).add(line.substring(colon + 1).trim());
        }
        return entity;
    }

    private static void walk(MimePart entity, int depth, Collected out) {
        if (depth > MAX_DEPTH || ++out.parts > MAX_PARTS) return;

        String contentType = first(entity, "content-type");
        String type = contentType == null ? "text/plain" : mediaType(contentType);

        if (type.startsWith("multipart/")) {
            String boundary = parameter(contentType, "boundary");
            if (boundary == null || boundary.isBlank()) return;
            for (String part : partsOf(entity.body, boundary)) {
                walk(split(part), depth + 1, out);
            }
            return;
        }

        String disposition = first(entity, "content-disposition");
        if (disposition != null && disposition.toLowerCase(Locale.ROOT).startsWith("attachment")) return;

        if (type.equals("text/html") && out.html == null) {
            out.html = decodeBody(entity, contentType);
        } else if (type.equals("text/plain") && out.text == null) {
            out.text = decodeBody(entity, contentType);
        }
    }

    private static String first(MimePart entity, String name) {
        List<String> values = entity.headers.get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private static String mediaType(String contentType) {
        int semicolon = contentType.indexOf(';');
        String type = semicolon < 0 ? contentType : contentType.substring(0, semicolon);
        return type.trim().toLowerCase(Locale.ROOT);
    }

    /** One parameter of a header such as Content-Type, quoted or not; null when absent. */
    private static String parameter(String header, String name) {
        if (header == null) return null;
        Matcher m = Pattern.compile("(?i)[;\\s]" + Pattern.quote(name) + "\\s*=\\s*(\"([^\"]*)\"|[^;\\s]*)").matcher(header);
        if (!m.find()) return null;
        return m.group(2) != null ? m.group(2) : m.group(1);
    }

    private static List<String> partsOf(String body, String boundary) {
        List<String> parts = new ArrayList<>();
        Matcher m = Pattern.compile("(?m)^--" + Pattern.quote(boundary) + "(--)?[ \\t]*\\r?$").matcher(body);
        int start = -1;
        while (m.find()) {
            if (start >= 0) {
                parts.add(stripOneTrailingBreak(body.substring(start, m.start())));
            }
            if (m.group(1) != null) {
                break;
            }
            start = m.end();
            if (body.startsWith("\r\n", start)) start += 2;
            else if (body.startsWith("\n", start)) start += 1;
            if (parts.size() >= MAX_PARTS) break;
        }
        return parts;
    }

    private static String stripOneTrailingBreak(String s) {
        if (s.endsWith("\r\n")) return s.substring(0, s.length() - 2);
        if (s.endsWith("\n")) return s.substring(0, s.length() - 1);
        return s;
    }

    private static String decodeBody(MimePart entity, String contentType) {
        String encoding = first(entity, "content-transfer-encoding");
        encoding = encoding == null ? "7bit" : encoding.trim().toLowerCase(Locale.ROOT);
        Charset charset = charsetFor(parameter(contentType, "charset"));

        try {
            switch (encoding) {
                case "base64":
                    return new String(Base64.getMimeDecoder().decode(entity.body), charset);
                case "quoted-printable":
                    return new String(decodeQuotedPrintable(entity.body), charset);
                default:
                    return entity.body;
            }
        } catch (IllegalArgumentException undecodable) {
            return null;
        }
    }

    private static Charset charsetFor(String name) {
        if (name == null || name.isBlank()) return StandardCharsets.UTF_8;
        String cleaned = name.trim().replace("\"", "");
        // Mail declares us-ascii for text that in practice carries 8-bit UTF-8; UTF-8 reads both.
        if (cleaned.equalsIgnoreCase("us-ascii")) return StandardCharsets.UTF_8;
        try {
            return Charset.forName(cleaned);
        } catch (IllegalArgumentException unknown) {
            return StandardCharsets.UTF_8;
        }
    }

    /**
     * Quoted-printable ({@code =XX} for a byte, a trailing {@code =} for a soft line break).
     * Literal characters above 0x7F, which only a file that was already decoded once contains, are
     * written as UTF-8 so they survive.
     */
    static byte[] decodeQuotedPrintable(String encoded) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(encoded.length());
        int n = encoded.length();
        int i = 0;
        while (i < n) {
            char c = encoded.charAt(i);
            if (c == '=') {
                if (i + 2 < n && isHex(encoded.charAt(i + 1)) && isHex(encoded.charAt(i + 2))) {
                    out.write(Integer.parseInt(encoded.substring(i + 1, i + 3), 16));
                    i += 3;
                } else if (i + 1 < n && encoded.charAt(i + 1) == '\n') {
                    i += 2;
                } else if (i + 2 < n && encoded.charAt(i + 1) == '\r' && encoded.charAt(i + 2) == '\n') {
                    i += 3;
                } else {
                    // A lone '=' that starts no valid escape is kept as written, as most readers do.
                    out.write('=');
                    i += 1;
                }
            } else if (c < 0x80) {
                out.write(c);
                i += 1;
            } else {
                int codePoint = encoded.codePointAt(i);
                byte[] utf8 = new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8);
                out.write(utf8, 0, utf8.length);
                i += Character.charCount(codePoint);
            }
        }
        return out.toByteArray();
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'A' && c <= 'F') || (c >= 'a' && c <= 'f');
    }
}
