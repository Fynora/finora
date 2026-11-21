package com.finora.service;

import com.finora.exception.ApiException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** Audit F-08 (2026-11-20). Uses the real bundled list, read the way the running server reads it. */
class WeakPasswordGuardTest {

    private static final String EMAIL = "meera.krishnan@example.com";
    private static final String NAME = "Meera Krishnan";
    private static final String PHONE = "+919000047163";

    private static WeakPasswordGuard guard;

    @BeforeAll
    static void loadTheRealList() {
        guard = new WeakPasswordGuard();
    }

    private static ApiException refused(String password) {
        return catchThrowableOfType(ApiException.class, () -> guard.check(password, EMAIL, NAME, PHONE));
    }

    private static void assertAccepted(String password) {
        assertThatCode(() -> guard.check(password, EMAIL, NAME, PHONE)).doesNotThrowAnyException();
    }

    /** The count the build copied into the jar. A build step that rewrote the file, or a
     *  truncated copy, changes it. */
    @Test
    void theWholeListIsLoaded() {
        assertThat(guard.size()).isEqualTo(46_483);
    }

    @ParameterizedTest
    @ValueSource(strings = {"password", "12345678", "qwertyuiop", "password1", "iloveyou", "india123",
            "password@123"})
    void aCommonPasswordIsRefused(String password) {
        ApiException refusal = refused(password);

        assertThat(refusal).as(password).isNotNull();
        assertThat(refusal.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refusal.getMessage()).isEqualTo(WeakPasswordGuard.COMMON_MESSAGE);
    }

    /** Capitals do not make a common password uncommon, and neither does a space typed around it. */
    @ParameterizedTest
    @ValueSource(strings = {"PASSWORD", "Password1", "QwertyUiop", " password ", "ILoveYou"})
    void caseAndSurroundingSpacesDoNotGetACommonPasswordThrough(String password) {
        assertThat(refused(password)).as(password).isNotNull();
    }

    /**
     * None of these is on the list as typed; each is once its symbols and spaces are taken out.
     * A symbol dropped into a common password is how most people meet a "needs a symbol" habit,
     * and it was the largest hole found when the first version of this rule was reviewed.
     */
    @ParameterizedTest
    @ValueSource(strings = {"Hello@123", "Qwerty@123", "Rahul@123", "Test@1234", "Pass@1234", "Sachin@123",
            "Ganesh@123", "Iloveyou@1", "p.a.s.s.w.o.r.d", "pass word 1", "!!password!!"})
    void aCommonPasswordWithSymbolsAddedIsRefused(String password) {
        ApiException refusal = catchThrowableOfType(ApiException.class, () -> guard.check(password, null, null, null));

        assertThat(refusal).as(password).isNotNull();
        assertThat(refusal.getMessage()).isEqualTo(WeakPasswordGuard.COMMON_MESSAGE);
    }

    /** An email name with no letters in it. The letter comparison has nothing to compare. */
    @Test
    void anEmailNameWithNoLettersIsRefusedWhenUsedAsThePassword() {
        assertThatThrownBy(() -> guard.check("58_20.19", "58_20.19@example.com", NAME, PHONE))
                .isInstanceOf(ApiException.class)
                .hasMessage(WeakPasswordGuard.PERSONAL_MESSAGE);
        assertThatCode(() -> guard.check("58_20.19", "someone@example.com", NAME, PHONE))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"correct horse battery staple", "Tm9-vexing-Lantern", "kettle#Orbit42river",
            "नमस्ते दुनिया कैसे", "7391 4628 5507 1183"})
    void anUncommonPasswordIsAccepted(String password) {
        assertAccepted(password);
    }

    /** A common password inside a longer one is not a common password. Only the whole thing is compared. */
    @Test
    void aLongerPasswordThatContainsACommonOneIsAccepted() {
        assertAccepted("password-but-with-a-lot-more-after-it");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "meera.krishnan@example.com",   // the email address itself
            "MEERA.KRISHNAN@example.com",
            "meera.krishnan",               // the part before the @
            "meerakrishnan",
            "Meera Krishnan",               // the full name
            "MeeraKrishnan123",
            "Krishnan@2026",                // one word of the name, with digits and symbols added
            "krishnan1!",
            "meera12345",
    })
    void theirOwnNameOrEmailIsRefused(String password) {
        ApiException refusal = refused(password);

        assertThat(refusal).as(password).isNotNull();
        assertThat(refusal.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refusal.getMessage()).isEqualTo(WeakPasswordGuard.PERSONAL_MESSAGE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Fynora@123", "finora2026", "FYNORA!!!", "f.y.n.o.r.a.1"})
    void theProductsNameIsRefused_withItsOwnSentence(String password) {
        ApiException refusal = refused(password);

        assertThat(refusal).as(password).isNotNull();
        assertThat(refusal.getMessage()).isEqualTo(WeakPasswordGuard.PRODUCT_MESSAGE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"9000047163", "919000047163", "+919000047163", "+91 90000 47163", "00047163",
            "09000047163"})
    void theirOwnPhoneNumberIsRefused(String password) {
        ApiException refusal = refused(password);

        assertThat(refusal).as(password).isNotNull();
        assertThat(refusal.getMessage()).isEqualTo(WeakPasswordGuard.PERSONAL_MESSAGE);
    }

    /** Their name as one part of something longer is their own business. */
    @ParameterizedTest
    @ValueSource(strings = {"meera-likes-green-tea", "krishnan and the lantern", "fynora keeps my ledger",
            "9000047163 plus words", "4417 9000 2231"})
    void aPasswordThatOnlyContainsTheirDetailsIsAccepted(String password) {
        assertAccepted(password);
    }

    /** Two letters match far too much: "Jo" would refuse every password whose only letters are j, o. */
    @Test
    void aVeryShortNameIsNotCompared() {
        assertThatCode(() -> guard.check("jo48291-75306", "jo@example.com", "Jo Li", null))
                .doesNotThrowAnyException();
    }

    /** On no list, and nobody's name here: one word, a symbol and a number. */
    @ParameterizedTest
    @ValueSource(strings = {"Mumbai@123", "Krishna@108", "Summer@2026", "MyDogRex1", "mumbaikar", "2026@Diwali",
            "Zxqvbnk@91", "दीपावली@108"})
    void aSingleShortWordWithNumbersAndSymbolsIsRefused(String password) {
        ApiException refusal = catchThrowableOfType(ApiException.class, () -> guard.check(password, null, null, null));

        assertThat(refusal).as(password).isNotNull();
        assertThat(refusal.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refusal.getMessage()).isEqualTo(WeakPasswordGuard.SINGLE_WORD_MESSAGE);
    }

    /** Twelve characters is where one word is let through, and two words are fine at any length. */
    @ParameterizedTest
    @ValueSource(strings = {"Mumbai@12345", "mumbai-local", "tea-rain", "Rex&Moti2019", "84629173", "58_20.19!"})
    void twelveCharactersOrASecondWordIsAccepted(String password) {
        assertThatCode(() -> guard.check(password, null, null, null)).doesNotThrowAnyException();
    }

    @Test
    void theSingleWordRuleCountsCharactersNotBytes() {
        // Eleven characters, far more than eleven bytes.
        assertThatThrownBy(() -> guard.check("दीपावली@108", null, null, null)).isInstanceOf(ApiException.class);
        assertThat("दीपावली@108".codePointCount(0, "दीपावली@108".length())).isEqualTo(11);
    }

    @Test
    void missingDetailsAreNotAnError() {
        assertThatCode(() -> guard.check("Tm9-vexing-Lantern", null, null, null)).doesNotThrowAnyException();
        assertThatCode(() -> guard.check("84629173", null, null, null)).doesNotThrowAnyException();
        assertThatCode(() -> guard.check("84629173", "", "  ", "")).doesNotThrowAnyException();
        assertThatCode(() -> guard.check(null, EMAIL, NAME, PHONE)).doesNotThrowAnyException();
    }

    /** With no details at all the list still applies. */
    @Test
    void aCommonPasswordIsRefusedWithNoDetails() {
        assertThatThrownBy(() -> guard.check("password", null, null, null)).isInstanceOf(ApiException.class);
    }

    /** A name in another script is compared by its letters too. */
    @Test
    void aNameInHindiIsCompared() {
        assertThatThrownBy(() -> guard.check("मीरा12345", "m@example.com", "मीरा कृष्णन", null))
                .isInstanceOf(ApiException.class)
                .hasMessage(WeakPasswordGuard.PERSONAL_MESSAGE);
    }

    @Test
    void anEmptyListIsAStartupFailure_notASilentlyOpenDoor() {
        assertThatThrownBy(() -> new WeakPasswordGuard(new long[0])).isInstanceOf(IllegalStateException.class);
    }

    /** The file is built by scripts/build-common-password-hashes.py. This pins what that script
     *  and this class must agree on: SHA-256 of the lowercased UTF-8 text, first 8 bytes, big-endian. */
    @Test
    void theHashIsTheFirstEightBytesOfSha256() {
        // sha256("password") begins 5e884898da280471
        assertThat(WeakPasswordGuard.hashOf("password")).isEqualTo(0x5e884898da280471L);
    }

    /** The order of the entries in the file is not relied on. */
    @Test
    void aListInAnyOrderIsSearchedCorrectly() {
        long[] unsorted = {WeakPasswordGuard.hashOf("zzzzzzzz"), WeakPasswordGuard.hashOf("password"),
                WeakPasswordGuard.hashOf("aaaaaaaa")};
        WeakPasswordGuard small = new WeakPasswordGuard(unsorted);

        assertThatThrownBy(() -> small.check("Password", null, null, null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> small.check("aaaaaaaa", null, null, null)).isInstanceOf(ApiException.class);
        assertThatCode(() -> small.check("12345678", null, null, null)).doesNotThrowAnyException();
    }
}
