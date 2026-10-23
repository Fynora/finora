package com.finora.activation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class SignupSourcesTest {

    private static SignupSources.Source after(String utm, String host) {
        return SignupSources.classify(true, utm, host, false, false);
    }

    @Test
    void utmSourceWinsOverTheReferrer() {
        assertThat(after("reel", "www.google.com"))
                .isEqualTo(new SignupSources.Source(SignupSourceKind.UTM, "reel"));
        assertThat(after("Instagram", "l.instagram.com"))
                .as("lower-cased so one tag spelt two ways is one row")
                .isEqualTo(new SignupSources.Source(SignupSourceKind.UTM, "instagram"));
    }

    @Test
    void aUtmValueThatLooksLikeContactDetailsIsWithheld() {
        assertThat(after("someone@example.com", null).utmSource()).isEqualTo(SignupSources.WITHHELD_UTM);
        assertThat(after("+91 10000 00001", null).utmSource()).isEqualTo(SignupSources.WITHHELD_UTM);
        assertThat(after("100000000001", null).utmSource()).isEqualTo(SignupSources.WITHHELD_UTM);
        assertThat(after("ref1000000001", null).utmSource()).isEqualTo(SignupSources.WITHHELD_UTM);
        assertThat(after("10000.00001", null).utmSource()).isEqualTo(SignupSources.WITHHELD_UTM);
        assertThat(after("diwali2026", null).utmSource()).as("a year is not a phone number").isEqualTo("diwali2026");
        assertThat(after("2026-10-24", null).utmSource()).as("a date is a campaign").isEqualTo("2026-10-24");
        assertThat(after("newsletter-20261024", null).utmSource()).isEqualTo("newsletter-20261024");
    }

    @Test
    void aBlankUtmSourceFallsBackToTheReferrer() {
        assertThat(after("  ", "www.bing.com").kind()).isEqualTo(SignupSourceKind.BING);
    }

    @Test
    void noRowIsDirectAfterTrackingAndUnknownBefore() {
        assertThat(SignupSources.classify(false, null, null, false, false))
                .isEqualTo(new SignupSources.Source(SignupSourceKind.DIRECT, null));
        assertThat(SignupSources.classify(false, null, null, false, true))
                .isEqualTo(new SignupSources.Source(SignupSourceKind.UNKNOWN_BEFORE_TRACKING, null));
    }

    @Test
    void aReferralComesAfterUtmAndReferrerAndBeforeDirectOrUnknown() {
        assertThat(SignupSources.classify(true, "reel", null, true, false).kind()).isEqualTo(SignupSourceKind.UTM);
        assertThat(SignupSources.classify(true, null, "wa.me", true, false).kind()).isEqualTo(SignupSourceKind.SOCIAL);
        assertThat(SignupSources.classify(false, null, null, true, false).kind()).isEqualTo(SignupSourceKind.REFERRAL);
        assertThat(SignupSources.classify(false, null, null, true, true).kind())
                .as("known from the referrals table even before tracking").isEqualTo(SignupSourceKind.REFERRAL);
    }

    @Test
    void aRowWithNoSourceOrReferrerFollowsTheNoRowRule() {
        // utm_medium/campaign/content only: nothing names where the person came from.
        assertThat(SignupSources.classify(true, null, null, false, false).kind()).isEqualTo(SignupSourceKind.DIRECT);
    }

    @Test
    void aStoredRowIsNeverUnknownEvenIfTheClockSaysBeforeTracking() {
        // A row can only exist once the table did; the row says more than the timestamp.
        assertThat(SignupSources.classify(true, null, "www.google.co.in", false, true).kind()).isEqualTo(SignupSourceKind.GOOGLE);
    }

    @ParameterizedTest
    @CsvSource({
            "google.com, GOOGLE",
            "www.google.com, GOOGLE",
            "www.google.co.in, GOOGLE",
            "google.co.in, GOOGLE",
            "www.google.com.au, GOOGLE",
            "com.google.android.googlequicksearchbox, GOOGLE",
            "WWW.GOOGLE.COM, GOOGLE",
            "mail.google.com, OTHER_SITE",
            "docs.google.com, OTHER_SITE",
            "notgoogle.com, OTHER_SITE",
            "bing.com, BING",
            "www.bing.com, BING",
            "cn.bing.com, BING",
            "duckduckgo.com, OTHER_SEARCH",
            "in.search.yahoo.com, OTHER_SEARCH",
            "yandex.ru, OTHER_SEARCH",
            "www.ecosia.org, OTHER_SEARCH",
            "search.brave.com, OTHER_SEARCH",
            "www.baidu.com, OTHER_SEARCH",
            "m.facebook.com, SOCIAL",
            "l.instagram.com, SOCIAL",
            "t.co, SOCIAL",
            "x.com, SOCIAL",
            "www.linkedin.com, SOCIAL",
            "www.youtube.com, SOCIAL",
            "out.reddit.com, SOCIAL",
            "web.whatsapp.com, SOCIAL",
            "com.whatsapp, SOCIAL",
            "notfacebook.com, OTHER_SITE",
            "www.moneycontrol.com, OTHER_SITE",
            "chatgpt.com, OTHER_SITE",
    })
    void groupsReferrerHosts(String host, SignupSourceKind expected) {
        assertThat(after(null, host).kind()).as(host).isEqualTo(expected);
    }

    @Test
    void medianOfOddEvenAndNone() {
        assertThat(ActivationFunnelService.median(java.util.List.of())).isNull();
        assertThat(ActivationFunnelService.median(java.util.List.of(3.0))).isEqualTo(3.0);
        assertThat(ActivationFunnelService.median(java.util.List.of(9.0, 1.0, 4.0))).isEqualTo(4.0);
        assertThat(ActivationFunnelService.median(java.util.List.of(1.0, 4.0))).isEqualTo(2.5);
        assertThat(ActivationFunnelService.median(java.util.List.of(0.04, 0.12))).as("one decimal").isEqualTo(0.1);
    }
}
