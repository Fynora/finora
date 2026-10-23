package com.finora.util;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * The only route a merchant logo takes to Logo.dev: a fixed list of well-known brands, each with its
 * own web address. The web and mobile apps request {@code img.logo.dev/<domain>} for a row whose
 * merchant label names one of these brands, and show initials for everything else. Logo.dev therefore
 * only ever sees an address from this list, never a name or narration from a user's transactions.
 *
 * <p>Why a list and not "look up anything that isn't a person": measured on the local statement
 * corpus (2026-10-24, 34 statements, 1,965 rows), about half the distinct labels the classifier types
 * as BUSINESS are individuals' names (small vendors paid by UPI), and the UNKNOWN labels include
 * people's names and bank text with reference and account numbers. Looking those up by name sent them
 * to a third party. On the same corpus this list gives an address to 536 of the 1,298 rows that were
 * being looked up by name, through 56 distinct domains, and every match is the brand it names.
 *
 * <p>Matching is on whole words of the label, lowercased with punctuation turned into spaces, so
 * "credit" is not CRED, "amazonia" is not Amazon and a shop paid through a Paytm QR ("paytmqr...") is
 * not Paytm. A brand marked {@code fused} also matches the start of a word; only brands that real
 * statements run together with the next word are marked ("zomatoorder", "zeptoonline",
 * "cloudflarecloudflare"). A wrong match shows one business another's logo, so add a brand, or mark
 * one fused, only with evidence, and only with a web address you have checked is its own.
 */
public final class MerchantBrands {

    private record Brand(List<String> words, String domain, boolean fused) {}

    private static final List<Brand> BRANDS = new ArrayList<>();
    private static final java.util.regex.Pattern SEPARATORS = java.util.regex.Pattern.compile("[^a-z0-9]+");

    private static void add(String pattern, String domain) { add(pattern, domain, false); }

    private static void add(String pattern, String domain, boolean fused) {
        List<String> words = List.of(pattern.split(" "));
        if (fused && words.size() != 1) throw new IllegalArgumentException("only a one-word brand can be fused: " + pattern);
        BRANDS.add(new Brand(words, domain, fused));
    }

    static {
        // Food and groceries
        add("zomato", "zomato.com", true);
        add("swiggy", "swiggy.com");
        add("zepto", "zeptonow.com", true);
        add("blinkit", "blinkit.com");
        add("bigbasket", "bigbasket.com");
        add("bbnow", "bigbasket.com");
        add("dmart", "dmart.in");
        add("eatclub", "eatclub.in");
        add("tobox", "eatclub.in");
        add("mcdonald", "mcdonalds.com");
        add("mcdonalds", "mcdonalds.com");
        add("mc donalds", "mcdonalds.com");
        add("mc donald", "mcdonalds.com");
        add("dominos", "dominos.co.in");
        add("domino", "dominos.co.in");
        add("starbucks", "starbucks.com");
        add("cinnabon", "cinnabon.com");
        add("tim hortons", "timhortons.com");
        add("barbequenation", "barbequenation.com");
        add("blue tokai", "bluetokaicoffee.com");
        add("jiomart", "jiomart.com");
        // Shopping
        add("amazon", "amazon.in");
        add("flipkart", "flipkart.com");
        add("myntra", "myntra.com");
        add("meesho", "meesho.com");
        add("decathlon", "decathlon.in");
        add("tata cliq", "tatacliq.com");
        add("ekart", "ekartlogistics.com");
        add("wakefit", "wakefit.co");
        add("dot and key", "dotandkey.com");
        add("mr diy", "mrdiy.com");
        add("reliance retail", "relianceretail.com");
        // Travel and transport
        add("uber", "uber.com");
        add("rapido", "rapido.bike");
        add("irctc", "irctc.co.in");
        add("indian railways", "indianrail.gov.in");
        add("pune metro", "punemetrorail.org");
        add("indigo airline", "goindigo.in");
        add("indigo airlines", "goindigo.in");
        add("goindigo", "goindigo.in");
        add("interglobe", "goindigo.in");
        add("air india express", "airindiaexpress.com");
        add("air india", "airindia.com");
        add("ixigo", "ixigo.com");
        add("le travenues", "ixigo.com");
        add("agoda", "agoda.com");
        add("makemytrip", "makemytrip.com");
        add("goibibo", "goibibo.com");
        // Telecom, apps and subscriptions
        add("airtel", "airtel.in");
        add("jio", "jio.com");
        add("myjio", "jio.com");
        add("google", "google.com");
        add("goog", "google.com");
        add("playstore", "google.com");
        add("spotify", "spotify.com");
        add("netflix", "netflix.com");
        add("prime video", "primevideo.com");
        add("linkedin", "linkedin.com");
        add("bookmyshow", "bookmyshow.com");
        add("bigtree entertainmen", "bookmyshow.com");
        add("bigtree entertainment", "bookmyshow.com");
        add("cloudflare", "cloudflare.com", true);
        add("anthropic", "anthropic.com", true);
        add("urban company", "urbancompany.com");
        add("housingcom", "housing.com");
        // Payments and money
        add("phonepe", "phonepe.com");
        add("paytm", "paytm.com");
        add("one97", "paytm.com");
        add("one 97", "paytm.com");
        add("cred", "cred.club");
        add("credclub", "cred.club");
        add("razorpay", "razorpay.com");
        add("bharatpe", "bharatpe.com");
        add("groww", "groww.in");
        add("zerodha", "zerodha.com");
        add("icici", "icicibank.com");
        add("healthians", "healthians.com");
        add("fitpass", "fitpass.co.in");
        add("indfitpass", "fitpass.co.in");
        // Longest first, so "air india express" wins over "air india".
        BRANDS.sort(Comparator.comparingInt((Brand b) -> b.words().size()).reversed()
                .thenComparing(b -> -b.words().get(0).length()));
    }

    private MerchantBrands() {}

    /** The brand's web address when {@code label} names a listed brand, otherwise null. */
    public static String domainFor(String label) {
        if (label == null || label.isBlank()) return null;
        String[] tokens = SEPARATORS.matcher(label.toLowerCase(Locale.ROOT)).replaceAll(" ").trim().split(" ");
        for (Brand brand : BRANDS) {
            int n = brand.words().size();
            for (int i = 0; i + n <= tokens.length; i++) {
                if (matchesAt(tokens, i, brand)) return brand.domain();
            }
        }
        return null;
    }

    private static boolean matchesAt(String[] tokens, int i, Brand brand) {
        List<String> words = brand.words();
        if (brand.fused()) return tokens[i].startsWith(words.get(0));
        for (int k = 0; k < words.size(); k++) {
            if (!tokens[i + k].equals(words.get(k))) return false;
        }
        return true;
    }

    /** Every address the apps could ever send to Logo.dev for a merchant. */
    public static Set<String> domains() {
        Set<String> out = new TreeSet<>();
        BRANDS.forEach(b -> out.add(b.domain()));
        return out;
    }
}
