package com.finora.service;

import com.finora.exception.ApiException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

/**
 * Refuses a new password that is easy to guess. Audit F-08 (2026-11-20): the only rule was a
 * length of 8 to 72, so "password" and "12345678" were accepted.
 *
 * <p>Three checks, all made only when a password is being set (sign-up, reset, change, and an
 * admin creating an account). A password already in use is never looked at.
 * <ul>
 *   <li>It is a single word of fewer than 12 characters, with or without digits and symbols
 *       added. See {@link #isOneShortWord}.</li>
 *   <li>It is on the bundled list of commonly used passwords, compared without regard to case,
 *       as typed and again with its symbols and spaces taken out.
 *       See {@code security/common-passwords-SOURCE.md} for where the list comes from. Only
 *       a hash of each entry is bundled; see {@link #hashOf}.</li>
 *   <li>It is the person's own name, email address or phone number, or the product's name,
 *       with nothing added but digits and symbols. "Nothing added" is judged by the letters
 *       alone, so a name followed by twenty random digits is refused as well. That is a known
 *       over-reach, accepted to keep the rule one sentence long.</li>
 * </ul>
 *
 * <p>There is deliberately no rule about capitals, digits or symbols. Those rules are met by
 * "Password1!", which is on the list.
 *
 * <p>Not a Spring bean. The services that set a password each hold one as a plain field, so a
 * service built by hand in a test has the real rule too and cannot be left without it. The list
 * is read once per JVM, when the first of them is created, which in the running server is at
 * startup: a missing or empty list stops the server from starting.
 */
public final class WeakPasswordGuard {

    static final String LIST_RESOURCE = "security/common-passwords.sha256-8.bin";

    static final String COMMON_MESSAGE = "That password is one of the most commonly used, so it is "
            + "easy to guess. Choose a different one.";
    static final String PERSONAL_MESSAGE = "Do not use your name, email address or phone number as "
            + "your password. Choose a different one.";
    static final String PRODUCT_MESSAGE = "Do not use the app's name as your password. Choose a "
            + "different one.";

    static final String SINGLE_WORD_MESSAGE = "A single word, even with numbers or symbols added, is "
            + "easy to guess. Use several words, or 12 or more characters.";

    /** From this length up, one word with digits and symbols is let through. The message says 12. */
    static final int SINGLE_WORD_SAFE_LENGTH = 12;

    /** A name or email name shorter than this is not compared: two letters match too much. */
    private static final int MIN_WORD_LETTERS = 3;
    /** Digits of a phone number that count as "the phone number" when they end it. */
    private static final int MIN_PHONE_DIGITS = 8;
    private static final int NATIONAL_NUMBER_DIGITS = 10;

    private static final Set<String> PRODUCT_NAMES = Set.of("fynora", "finora");

    /** One number per listed password, sorted: see {@link #hashOf}. */
    private final long[] common;

    /** Read on first use and shared: the list is 46,000 entries and never changes while running. */
    private static final class BundledList {
        static final long[] HASHES = load(LIST_RESOURCE);
    }

    public WeakPasswordGuard() {
        this(BundledList.HASHES);
    }

    WeakPasswordGuard(long[] hashes) {
        if (hashes.length == 0) {
            throw new IllegalStateException("The common-password list is empty");
        }
        this.common = hashes.clone();
        Arrays.sort(this.common);
    }

    /** How many passwords the list holds. */
    int size() {
        return common.length;
    }

    /**
     * The first 8 bytes of the SHA-256 of the text, as a number. The file holds this for each
     * listed password and nothing else, so the passwords themselves are not in the repository:
     * what people really chose includes real phone numbers and email addresses. Two different
     * passwords sharing a value is a 1 in 400 trillion chance per check, and its only effect
     * would be one uncommon password refused.
     */
    static long hashOf(String loweredPassword) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(loweredPassword.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(digest, 0, Long.BYTES).getLong();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every Java runtime", e);
        }
    }

    /**
     * @param email       the account's email address, or null
     * @param fullName    the account holder's name, or null
     * @param phoneNumber the account's phone number in any format, or null
     * @throws ApiException 400, with a message that says what to do, when the password is refused
     */
    public void check(String password, String email, String fullName, String phoneNumber) {
        if (password == null) return;
        String lowered = password.strip().toLowerCase(Locale.ROOT);
        // As typed, and again with the symbols taken out. "Hello@123" is not on the list and
        // "hello123" is: a symbol dropped into a common password is the usual way of meeting a
        // "must contain a symbol" habit, and it adds nothing a guesser does not try.
        if (isListed(lowered) || isListed(lettersAndDigitsOf(lowered))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, COMMON_MESSAGE);
        }
        if (PRODUCT_NAMES.contains(lettersOf(lowered))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, PRODUCT_MESSAGE);
        }
        if (isPersonal(lowered, email, fullName, phoneNumber)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, PERSONAL_MESSAGE);
        }
        if (isOneShortWord(lowered)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, SINGLE_WORD_MESSAGE);
        }
    }

    /**
     * One run of letters, with or without digits and symbols around it, in fewer than
     * {@value #SINGLE_WORD_SAFE_LENGTH} characters: "Mumbai@123", "Krishna@108", "Summer@2026".
     * No list holds every town, god, child and cricketer, and this shape is what people reach for
     * when a form has ever told them to add a number and a symbol.
     *
     * <p>It cannot tell a word from letters that only look like one, so "Zxqvbnk@91" is refused
     * too. The owner accepted that when choosing this rule (2026-11-21). A password with no
     * letters at all is not this rule's business.
     */
    private static boolean isOneShortWord(String lowered) {
        if (lowered.codePointCount(0, lowered.length()) >= SINGLE_WORD_SAFE_LENGTH) return false;
        int runs = 0;
        boolean inRun = false;
        for (int i = 0; i < lowered.length(); ) {
            int codePoint = lowered.codePointAt(i);
            boolean letter = isLetterOrVowelSign(codePoint);
            if (letter && !inRun) runs++;
            inRun = letter;
            i += Character.charCount(codePoint);
        }
        return runs == 1;
    }

    private boolean isListed(String loweredPassword) {
        return Arrays.binarySearch(common, hashOf(loweredPassword)) >= 0;
    }

    private static boolean isPersonal(String lowered, String email, String fullName, String phoneNumber) {
        String loweredEmail = email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
        int at = loweredEmail.indexOf('@');
        String emailName = at > 0 ? loweredEmail.substring(0, at) : loweredEmail;
        // The address or the part before the @, exactly. This is what catches an email name
        // with no letters in it, which the letter comparison below cannot see.
        if (!loweredEmail.isEmpty() && (lowered.equals(loweredEmail) || lowered.equals(emailName))) return true;

        String letters = lettersOf(lowered);
        if (letters.isEmpty()) {
            return isPhoneNumber(digitsOf(lowered), digitsOf(phoneNumber));
        }

        if (matchesWord(letters, emailName)) return true;
        if (fullName != null) {
            if (matchesWord(letters, fullName)) return true;
            for (String word : fullName.strip().split("\\s+")) {
                if (matchesWord(letters, word)) return true;
            }
        }
        return false;
    }

    /** True when the password's letters are exactly this word's letters. */
    private static boolean matchesWord(String passwordLetters, String word) {
        String wordLetters = lettersOf(word.toLowerCase(Locale.ROOT));
        return wordLetters.length() >= MIN_WORD_LETTERS && wordLetters.equals(passwordLetters);
    }

    /** A password with no letters in it: is it the phone number, with or without its country code? */
    private static boolean isPhoneNumber(String passwordDigits, String phoneDigits) {
        if (phoneDigits.length() < MIN_PHONE_DIGITS || passwordDigits.length() < MIN_PHONE_DIGITS) return false;
        if (phoneDigits.endsWith(passwordDigits)) return true;
        String national = phoneDigits.length() > NATIONAL_NUMBER_DIGITS
                ? phoneDigits.substring(phoneDigits.length() - NATIONAL_NUMBER_DIGITS)
                : phoneDigits;
        return passwordDigits.endsWith(national);
    }

    private static String lettersOf(String text) {
        StringBuilder out = new StringBuilder(text.length());
        text.codePoints().filter(WeakPasswordGuard::isLetterOrVowelSign).forEach(out::appendCodePoint);
        return out.toString();
    }

    /** Hindi and other Indian scripts write most vowels as marks on a letter, and Unicode does
     *  not class those marks as letters. Without them a name loses most of itself. */
    private static boolean isLetterOrVowelSign(int codePoint) {
        if (Character.isLetter(codePoint)) return true;
        int type = Character.getType(codePoint);
        return type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK;
    }

    private static String lettersAndDigitsOf(String text) {
        StringBuilder out = new StringBuilder(text.length());
        text.codePoints().filter(c -> isLetterOrVowelSign(c) || Character.isDigit(c)).forEach(out::appendCodePoint);
        return out.toString();
    }

    private static String digitsOf(String text) {
        if (text == null) return "";
        StringBuilder out = new StringBuilder(text.length());
        text.codePoints().filter(c -> c >= '0' && c <= '9').forEach(out::appendCodePoint);
        return out.toString();
    }

    private static long[] load(String resource) {
        byte[] bytes;
        try (InputStream in = new ClassPathResource(resource).getInputStream()) {
            bytes = in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + resource, e);
        }
        if (bytes.length % Long.BYTES != 0) {
            throw new IllegalStateException(resource + " is " + bytes.length
                    + " bytes, which is not a whole number of 8-byte entries; the file is damaged");
        }
        long[] hashes = new long[bytes.length / Long.BYTES];
        ByteBuffer.wrap(bytes).asLongBuffer().get(hashes);
        return hashes;
    }
}
