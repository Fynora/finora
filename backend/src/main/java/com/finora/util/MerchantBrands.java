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
 * to a third party. On the same corpus this list gives an address to 544 of the 1,298 rows that were
 * being looked up by name, through 56 distinct domains, and every match is the brand it names.
 *
 * <p>Matching is on whole words of the label, lowercased with punctuation turned into spaces. A brand
 * marked {@code fused} also matches the start of a word, because banks run words together
 * ("zomatoorder", "zeptoonline"); short brands never do, so "credit" is not CRED and "uberoi" is not
 * Uber. Add a brand only with a web address you have checked is its own.
 */
public final class MerchantBrands {

    private record Brand(List<String> words, String domain, boolean fused) {}

    private static final List<Brand> BRANDS = new ArrayList<>();

    private static void add(String pattern, String domain) { add(pattern, domain, false); }

    private static void add(String pattern, String domain, boolean fused) {
        List<String> words = List.of(pattern.split(" "));
        if (fused && words.size() != 1) throw new IllegalArgumentException("only a one-word brand can be fused: " + pattern);
        BRANDS.add(new Brand(words, domain, fused));
    }

    static {
        // Food and groceries
        add("zomato", "zomato.com", true);
        add("swiggy", "swiggy.com", true);
        add("zepto", "zeptonow.com", true);
        add("blinkit", "blinkit.com", true);
        add("bigbasket", "bigbasket.com", true);
        add("bbnow", "bigbasket.com");
        add("dmart", "dmart.in", true);
        add("eatclub", "eatclub.in", true);
        add("tobox", "eatclub.in", true);
        add("mcdonald", "mcdonalds.com", true);
        add("mcdonalds", "mcdonalds.com");
        add("mc donalds", "mcdonalds.com");
        add("mc donald", "mcdonalds.com");
        add("dominos", "dominos.co.in");
        add("domino", "dominos.co.in");
        add("starbucks", "starbucks.com", true);
        add("cinnabon", "cinnabon.com", true);
        add("tim hortons", "timhortons.com");
        add("barbequenation", "barbequenation.com", true);
        add("blue tokai", "bluetokaicoffee.com");
        add("jiomart", "jiomart.com", true);
        // Shopping
        add("amazon", "amazon.in", true);
        add("asspl", "amazon.in");
        add("flipkart", "flipkart.com", true);
        add("myntra", "myntra.com", true);
        add("meesho", "meesho.com", true);
        add("decathlon", "decathlon.in", true);
        add("tata cliq", "tatacliq.com");
        add("ekart", "ekartlogistics.com");
        add("wakefit", "wakefit.co", true);
        add("dot and key", "dotandkey.com");
        add("mr diy", "mrdiy.com");
        add("reliance retail", "relianceretail.com");
        // Travel and transport
        add("uber", "uber.com");
        add("rapido", "rapido.bike", true);
        add("irctc", "irctc.co.in", true);
        add("indian railways", "indianrail.gov.in");
        add("pune metro", "punemetrorail.org");
        add("indigo", "goindigo.in");
        add("interglobe", "goindigo.in", true);
        add("air india express", "airindiaexpress.com");
        add("air india", "airindia.com");
        add("ixigo", "ixigo.com", true);
        add("le travenues", "ixigo.com");
        add("agoda", "agoda.com", true);
        add("makemytrip", "makemytrip.com", true);
        add("goibibo", "goibibo.com", true);
        // Telecom, apps and subscriptions
        add("airtel", "airtel.in", true);
        add("jio", "jio.com");
        add("myjio", "jio.com");
        add("google", "google.com", true);
        add("goog", "google.com");
        add("playstore", "google.com");
        add("spotify", "spotify.com", true);
        add("netflix", "netflix.com", true);
        add("prime video", "primevideo.com");
        add("linkedin", "linkedin.com", true);
        add("bookmyshow", "bookmyshow.com", true);
        add("bigtree entertainmen", "bookmyshow.com");
        add("bigtree entertainment", "bookmyshow.com");
        add("cloudflare", "cloudflare.com", true);
        add("anthropic", "anthropic.com", true);
        add("urban company", "urbancompany.com");
        add("housingcom", "housing.com");
        // Payments and money
        add("phonepe", "phonepe.com", true);
        add("paytm", "paytm.com", true);
        add("one97", "paytm.com", true);
        add("one 97", "paytm.com");
        add("cred", "cred.club");
        add("credclub", "cred.club");
        add("razorpay", "razorpay.com", true);
        add("bharatpe", "bharatpe.com", true);
        add("groww", "groww.in");
        add("zerodha", "zerodha.com", true);
        add("icici", "icicibank.com", true);
        add("healthians", "healthians.com", true);
        add("fitpass", "fitpass.co.in", true);
        add("indfitpass", "fitpass.co.in");
        // Longest first, so "air india express" wins over "air india".
        BRANDS.sort(Comparator.comparingInt((Brand b) -> b.words().size()).reversed()
                .thenComparing(b -> -b.words().get(0).length()));
    }

    private MerchantBrands() {}

    /** The brand's web address when {@code label} names a listed brand, otherwise null. */
    public static String domainFor(String label) {
        if (label == null || label.isBlank()) return null;
        String[] tokens = label.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim().split(" ");
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
