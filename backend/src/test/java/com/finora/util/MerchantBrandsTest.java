package com.finora.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The apps send Logo.dev only a web address from this list, never text from a user's transactions.
 * The shapes below are how merchant labels come out of real statements (a measured corpus run on
 * 2026-10-24): brand first, sometimes fused with the next word, sometimes cut short.
 */
class MerchantBrandsTest {

    @ParameterizedTest
    @CsvSource({
            "Zomato, zomato.com",
            "zomato limited, zomato.com",
            "zomatoorder, zomato.com",
            "zepto marketplace pr, zeptonow.com",
            "zeptoonline, zeptonow.com",
            "swiggy instamart, swiggy.com",
            "amazon pay india pvt, amazon.in",
            "request from amazon pay, amazon.in",
            "flipkart payments, flipkart.com",
            "blinkit rzp, blinkit.com",
            "uber india systems, uber.com",
            "jio recharge, jio.com",
            "cred clu, cred.club",
            "credclub, cred.club",
            "cloudflarecloudflare, cloudflare.com",
            "mc donalds, mcdonalds.com",
            "air india express led, airindiaexpress.com",
            "air india, airindia.com",
            "irctc ticket booking, irctc.co.in",
            "bigtree entertainmen, bookmyshow.com",
            "indigo airline, goindigo.in",
            "interglobe aviation, goindigo.in",
            "zeptonow, zeptonow.com",
            "anthropicanthropic, anthropic.com",
            "one97 co, paytm.com",
            "paytm, paytm.com",
            "UPI/DR/600000000002/ZOMATO/YESB, zomato.com",
    })
    void aKnownBrandGivesItsOwnWebAddress(String label, String domain) {
        assertThat(MerchantBrands.domainFor(label)).isEqualTo(domain);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // People: their names must never produce a lookup.
            "Sample Person", "Sample Vendor Name", "uberoi", "jiotsna", "dominic",
            // A short brand inside a longer word is not that brand.
            "account transfer credit ref", "googly",
            // Bank text with identifiers: nothing in it names a brand.
            "UPI/DR/600000000002/SAMPLE SHOP/YESB", "31700ppf000000000624", "nwd 416021xxxxxx5853",
            // Another business that only starts with a brand's letters must not wear its logo: a shop
            // paid through a Paytm QR is not Paytm, and Indigo Paints is not the airline.
            "amazonia restaurant", "paytmqr100000000001", "indigo paints", "airtelxyz traders",
            // An abbreviation no one has confirmed as the brand stays initials.
            "asspl",
            "", "   "})
    void anythingElseGivesNothing(String label) {
        assertThat(MerchantBrands.domainFor(label)).isNull();
    }

    @Test
    void nullGivesNothing() {
        assertThat(MerchantBrands.domainFor(null)).isNull();
    }

    @Test
    void everyAddressIsABareLowercaseDomain() {
        assertThat(MerchantBrands.domains()).isNotEmpty()
                .allMatch(d -> d.matches("[a-z0-9-]+(\\.[a-z0-9-]+)+"), "a bare lowercase domain");
    }
}
