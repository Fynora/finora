package com.finora.activation;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Puts a signup in a {@link SignupSourceKind} bucket from what {@code signup_attributions} (V286)
 * stored for them, and nothing else.
 *
 * <ol>
 *   <li>A {@code utm_source} wins: the link was tagged on purpose, so the tag says more than the
 *       page it was clicked on (an Instagram bio link tagged {@code utm_source=reel} is the reel).</li>
 *   <li>Otherwise the referrer host, grouped: Google, Bing, other search engines, social sites,
 *       other sites.</li>
 *   <li>Otherwise a redeemed friend's code ({@code referrals}) makes it
 *       {@link SignupSourceKind#REFERRAL}, whenever the account was made.</li>
 *   <li>Otherwise nothing was recorded. That is {@link SignupSourceKind#DIRECT} only for an
 *       account made after tracking began; before it, it is
 *       {@link SignupSourceKind#UNKNOWN_BEFORE_TRACKING}, since nobody was recording then.</li>
 * </ol>
 *
 * Hosts match on the whole name or a dot-bounded suffix ({@code m.facebook.com} is facebook.com;
 * {@code notfacebook.com} is not). The stored host is already lower-case (SignupAttributionService),
 * lower-cased again here so the rules never depend on that.
 */
public final class SignupSources {

    private SignupSources() {}

    /** One bucket; {@code utmSource} is set for {@link SignupSourceKind#UTM} only. */
    public record Source(SignupSourceKind kind, String utmSource) {}

    /**
     * google.com, google.co.in, www.google.com.au -- Google search on any country domain. Not
     * mail.google.com or docs.google.com: those are Google's other products, a link in an email or
     * a document, which is "another site", not a search.
     */
    private static final Pattern GOOGLE_SEARCH = Pattern.compile("^(www\\.)?google(\\.[a-z]{2,3}){1,2}$");

    /** The Google app on Android sends this as its referrer ({@code android-app://...}). */
    private static final String GOOGLE_APP = "com.google.android.googlequicksearchbox";

    /**
     * Shown instead of a tag value that looks like it names a person -- an email address or a phone
     * number pasted into {@code utm_source}. The page shows counts, never who; a tag is free text
     * from a URL, so it is checked rather than trusted.
     */
    public static final String WITHHELD_UTM = "(withheld: looks like contact details)";
    /** A phone number once spaces, dots, dashes, brackets and a leading + are taken out: 10 to 15
     *  digits and nothing else (an Indian mobile is 10; with a country code, 12). */
    private static final Pattern PHONE_NUMBER = Pattern.compile("^\\d{10,15}$");
    /** Ten digits in a row anywhere in the tag ({@code ref1000000001}). */
    private static final Pattern TEN_DIGIT_RUN = Pattern.compile(".*\\d{10,}.*");

    private static final List<String> BING = List.of("bing.com");

    private static final List<String> OTHER_SEARCH = List.of(
            "duckduckgo.com", "search.yahoo.com", "search.yahoo.co.jp", "yandex.ru", "yandex.com", "ya.ru",
            "baidu.com", "ecosia.org", "search.brave.com", "startpage.com", "qwant.com", "search.aol.com",
            "naver.com", "seznam.cz");

    private static final List<String> SOCIAL = List.of(
            "facebook.com", "fb.com", "fb.me", "instagram.com", "threads.net", "twitter.com", "x.com", "t.co",
            "linkedin.com", "lnkd.in", "youtube.com", "youtu.be", "reddit.com", "quora.com", "pinterest.com",
            "pin.it", "tiktok.com", "snapchat.com", "whatsapp.com", "wa.me", "t.me", "telegram.org",
            "telegram.me", "sharechat.com", "discord.com",
            // The same apps' Android referrers (android-app://<package>).
            "com.facebook.katana", "com.instagram.android", "com.twitter.android", "com.linkedin.android",
            "com.google.android.youtube", "com.reddit.frontpage", "com.whatsapp", "org.telegram.messenger");

    /**
     * @param hasRow         whether {@code signup_attributions} holds a row for the account
     * @param referred       whether the account redeemed a friend's referral code
     * @param beforeTracking whether the account was made before attribution was recorded at all
     */
    public static Source classify(boolean hasRow, String utmSource, String referrerHost, boolean referred,
                                  boolean beforeTracking) {
        if (hasRow) {
            String utm = blankToNull(utmSource);
            if (utm != null) return new Source(SignupSourceKind.UTM, displayableUtm(utm.toLowerCase(Locale.ROOT)));
            String host = blankToNull(referrerHost);
            if (host != null) return new Source(groupHost(host.toLowerCase(Locale.ROOT)), null);
            // A row with only medium, campaign or content: tagged, but with no source to name and no
            // referrer. Still "nothing says where from", so the same rule as no row at all.
        }
        if (referred) return new Source(SignupSourceKind.REFERRAL, null);
        return new Source(beforeTracking ? SignupSourceKind.UNKNOWN_BEFORE_TRACKING : SignupSourceKind.DIRECT, null);
    }

    /** A date such as {@code 2026-10-24} (eight digits) is a campaign, not a person, and stays. */
    public static String displayableUtm(String utm) {
        String stripped = utm.replaceAll("[\\s().+-]", "");
        boolean contact = utm.contains("@") || PHONE_NUMBER.matcher(stripped).matches()
                || TEN_DIGIT_RUN.matcher(utm).matches();
        return contact ? WITHHELD_UTM : utm;
    }

    static SignupSourceKind groupHost(String host) {
        if (GOOGLE_SEARCH.matcher(host).matches() || host.equals(GOOGLE_APP)) return SignupSourceKind.GOOGLE;
        if (matchesAny(host, BING)) return SignupSourceKind.BING;
        if (matchesAny(host, OTHER_SEARCH)) return SignupSourceKind.OTHER_SEARCH;
        if (matchesAny(host, SOCIAL)) return SignupSourceKind.SOCIAL;
        return SignupSourceKind.OTHER_SITE;
    }

    private static boolean matchesAny(String host, List<String> domains) {
        for (String domain : domains) {
            if (host.equals(domain) || host.endsWith("." + domain)) return true;
        }
        return false;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
