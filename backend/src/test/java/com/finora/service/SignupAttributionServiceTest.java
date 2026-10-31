package com.finora.service;

import com.finora.dto.AuthDtos.SignupAttributionRequest;
import com.finora.entity.SignupAttribution;
import com.finora.repository.SignupAttributionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * What of the client's attribution actually reaches the row (V286): the referrer cut down to a
 * host name, UTM values trimmed and capped, and no row at all when nothing usable was sent.
 */
class SignupAttributionServiceTest {

    private SignupAttributionRepository repository;
    private SignupAttributionService service;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repository = mock(SignupAttributionRepository.class);
        service = new SignupAttributionService(repository);
    }

    @Test
    void record_withNothingSent_writesNoRow() {
        service.record(userId, null);
        service.record(userId, new SignupAttributionRequest(null, null, null, null, null));
        service.record(userId, new SignupAttributionRequest("", "  ", "\t", "", null));
        verify(repository, never()).save(any());
    }

    @Test
    void record_keepsEveryFieldOnTheRow_underTheNewUser() {
        service.record(userId, new SignupAttributionRequest(
                "www.example.org", "instagram", "reel", "launch-oct", "video-a"));

        SignupAttribution saved = savedRow();
        assertThat(saved.getUserId()).isEqualTo(userId);
        assertThat(saved.getReferrerHost()).isEqualTo("www.example.org");
        assertThat(saved.getUtmSource()).isEqualTo("instagram");
        assertThat(saved.getUtmMedium()).isEqualTo("reel");
        assertThat(saved.getUtmCampaign()).isEqualTo("launch-oct");
        assertThat(saved.getUtmContent()).isEqualTo("video-a");
    }

    @Test
    void record_withOnlyAReferrer_writesARowWithNullUtmFields() {
        service.record(userId, new SignupAttributionRequest("news.example.org", null, null, null, null));

        SignupAttribution saved = savedRow();
        assertThat(saved.getReferrerHost()).isEqualTo("news.example.org");
        assertThat(saved.getUtmSource()).isNull();
        assertThat(saved.getUtmMedium()).isNull();
        assertThat(saved.getUtmCampaign()).isNull();
        assertThat(saved.getUtmContent()).isNull();
    }

    /** A client may send a full referrer URL despite the field's name; the path and query must
     *  not be stored, since they can carry another site's tokens. */
    @ParameterizedTest
    @CsvSource({
            "https://www.Example.com/path/page?token=abc#frag, www.example.com",
            "http://example.com:8080/, example.com",
            "Example.com/some/path?x=1, example.com",
            "example.com:443, example.com",
            "  sub.example.co.in  , sub.example.co.in",
            "localhost, localhost",
    })
    void normalizeReferrerHost_keepsOnlyTheLowerCasedHost(String raw, String expected) {
        assertThat(SignupAttributionService.normalizeReferrerHost(raw)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", "   ", "not a host", "host_with_underscore.com", "-leading.com", "trailing-.com",
            "a..b", "https://", "https://[::1]/", "a b.com", ".example.com",
    })
    void normalizeReferrerHost_dropsAnythingThatIsNotAHostName(String raw) {
        assertThat(SignupAttributionService.normalizeReferrerHost(raw)).isNull();
    }

    @Test
    void normalizeReferrerHost_dropsAHostLongerThanAHostNameCanBe() {
        String label = "a".repeat(60);
        String tooLong = String.join(".", label, label, label, label, label, "com"); // 308 characters
        assertThat(tooLong.length()).isGreaterThan(SignupAttributionService.HOST_MAX_LENGTH);
        assertThat(SignupAttributionService.normalizeReferrerHost(tooLong)).isNull();
        assertThat(SignupAttributionService.normalizeReferrerHost("https://" + tooLong + "/x")).isNull();
    }

    @Test
    void normalizeUtm_trimsStripsControlCharactersAndCapsTheLength() {
        assertThat(SignupAttributionService.normalizeUtm("  reel  ")).isEqualTo("reel");
        assertThat(SignupAttributionService.normalizeUtm("re\u0000el\n")).isEqualTo("reel");
        assertThat(SignupAttributionService.normalizeUtm("")).isNull();
        assertThat(SignupAttributionService.normalizeUtm("\u0001\u0002")).isNull();
        assertThat(SignupAttributionService.normalizeUtm(null)).isNull();

        String exact = "x".repeat(SignupAttributionService.UTM_MAX_LENGTH);
        assertThat(SignupAttributionService.normalizeUtm(exact)).isEqualTo(exact);
        assertThat(SignupAttributionService.normalizeUtm(exact + "y")).isEqualTo(exact);
    }

    /** One source spelt two ways must count once in the admin list. */
    @Test
    void normalizeUtm_lowerCases() {
        assertThat(SignupAttributionService.normalizeUtm("Instagram")).isEqualTo("instagram");
        assertThat(SignupAttributionService.normalizeUtm("LAUNCH-Oct")).isEqualTo("launch-oct");
    }

    /** Postgres counts characters; a cut inside a surrogate pair would not be valid text. */
    @Test
    void normalizeUtm_cutsOnACodePointBoundary() {
        String emoji = "😀"; // one character, two UTF-16 units
        String value = emoji.repeat(SignupAttributionService.UTM_MAX_LENGTH + 1);
        String cut = SignupAttributionService.normalizeUtm(value);
        assertThat(cut.codePointCount(0, cut.length())).isEqualTo(SignupAttributionService.UTM_MAX_LENGTH);
        assertThat(cut).endsWith(emoji);
        // Exactly at the cap, measured in characters, nothing is cut even though the UTF-16
        // length is twice the cap.
        String exact = emoji.repeat(SignupAttributionService.UTM_MAX_LENGTH);
        assertThat(SignupAttributionService.normalizeUtm(exact)).isEqualTo(exact);
    }

    @Test
    void record_withAnOverlongCampaign_storesTheCappedValue() {
        String longCampaign = "c".repeat(SignupAttributionService.UTM_MAX_LENGTH + 40);
        service.record(userId, new SignupAttributionRequest(null, null, null, longCampaign, null));
        assertThat(savedRow().getUtmCampaign()).hasSize(SignupAttributionService.UTM_MAX_LENGTH);
    }

    @Test
    void record_withOnlyAnUnusableReferrer_writesNoRow() {
        service.record(userId, new SignupAttributionRequest("not a host at all", null, null, null, null));
        verify(repository, never()).save(any());
    }

    private SignupAttribution savedRow() {
        ArgumentCaptor<SignupAttribution> captor = ArgumentCaptor.forClass(SignupAttribution.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    // ---- landing path (V300)

    @ParameterizedTest
    @CsvSource({
            "/, /",
            "/help, /help",
            "/help/, /help",
            "  /banks/example-bank  , /banks/example-bank",
            "/help/how-to-read_a.statement~2, /help/how-to-read_a.statement~2",
    })
    void normalizeLandingPath_keepsAPlainPublicPagePath(String raw, String expected) {
        assertThat(SignupAttributionService.normalizeLandingPath(raw)).isEqualTo(expected);
    }

    @Test
    void normalizeLandingPath_trimsSurroundingWhitespaceButNotWhitespaceInsideThePath() {
        assertThat(SignupAttributionService.normalizeLandingPath(" /help\n")).isEqualTo("/help");
        assertThat(SignupAttributionService.normalizeLandingPath("/he lp")).isNull();
    }

    /** Nothing but the path of a page: a query, a fragment, a host, a scheme, a token-shaped or
     *  made-up value, or anything that is not lower-case path segments is dropped, not repaired. */
    @ParameterizedTest
    @ValueSource(strings = {
            "", "   ", "help", "//help", "/help//x", "/help?token=abc", "/help#top", "/help?", "/help#",
            "https://app.fynora.net/help", "app.fynora.net/help", "/Help", "/help page", "/help/%2e%2e",
            "/.", "/..", "/help/..", "/help/./x", "/.hidden", "/someone@example.com", "/help;jsessionid=1",
            "/he\nlp", "//", "/help//", "/hel\u0000p",
    })
    void normalizeLandingPath_dropsAnythingThatIsNotAPublicPagePath(String raw) {
        assertThat(SignupAttributionService.normalizeLandingPath(raw)).isNull();
        assertThat(SignupAttributionService.normalizeLandingPath(null)).isNull();
    }

    @Test
    void normalizeLandingPath_dropsAPathOverTheCap_andKeepsOneExactlyAtIt() {
        String atCap = "/" + "a".repeat(SignupAttributionService.LANDING_PATH_MAX_LENGTH - 1);
        assertThat(atCap).hasSize(SignupAttributionService.LANDING_PATH_MAX_LENGTH);
        assertThat(SignupAttributionService.normalizeLandingPath(atCap)).isEqualTo(atCap);
        assertThat(SignupAttributionService.normalizeLandingPath(atCap + "a")).as("dropped, never cut").isNull();
    }

    @Test
    void record_withOnlyALandingPath_writesARow() {
        service.record(userId, new SignupAttributionRequest(null, null, null, null, null, "/help"));

        SignupAttribution saved = savedRow();
        assertThat(saved.getLandingPath()).isEqualTo("/help");
        assertThat(saved.getReferrerHost()).isNull();
        assertThat(saved.getUtmSource()).isNull();
    }

    @Test
    void record_keepsALandingPathBesideTheRestOfTheAttribution() {
        service.record(userId, new SignupAttributionRequest(
                "www.example.org", "instagram", "reel", "launch-oct", "video-a", "/banks/example-bank/"));

        SignupAttribution saved = savedRow();
        assertThat(saved.getLandingPath()).isEqualTo("/banks/example-bank");
        assertThat(saved.getReferrerHost()).isEqualTo("www.example.org");
        assertThat(saved.getUtmSource()).isEqualTo("instagram");
    }

    @Test
    void record_withAnUnusableLandingPathAlone_writesNoRow() {
        service.record(userId, new SignupAttributionRequest(null, null, null, null, null, "/help?token=abc"));
        verify(repository, never()).save(any());
    }

    @Test
    void record_withAnUnusableLandingPathBesideOtherFields_keepsTheOthersAndDropsThePath() {
        service.record(userId, new SignupAttributionRequest(null, "instagram", null, null, null, "https://x.example/help"));

        SignupAttribution saved = savedRow();
        assertThat(saved.getUtmSource()).isEqualTo("instagram");
        assertThat(saved.getLandingPath()).isNull();
    }
}
