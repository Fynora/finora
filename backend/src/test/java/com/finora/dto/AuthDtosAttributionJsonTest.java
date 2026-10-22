package com.finora.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.dto.AuthDtos.AppleAuthRequest;
import com.finora.dto.AuthDtos.GoogleAuthRequest;
import com.finora.dto.AuthDtos.RegisterRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V286: the three sign-up request records gained an optional {@code attribution} component and,
 * with it, a second (shorter) constructor for callers that have none. Jackson must still bind the
 * JSON through the canonical constructor, so {@code attribution} arrives rather than being
 * silently dropped -- and a body without it, as the mobile apps send, must still bind.
 */
class AuthDtosAttributionJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();
    /** Shape only: Jackson does not validate it, and no account is created here. */
    private static final String PHONE = "+919000000001"; // synthetic-ok

    @Test
    void registerRequest_bindsAttribution_andToleratesItsAbsence() throws Exception {
        RegisterRequest with = mapper.readValue("""
                {"email": "a@example.com", "password": "Password123", "fullName": "Jane Doe",
                 "phoneNumber": "%s",
                 "attribution": {"referrerHost": "news.example.org", "utmSource": "news", "utmMedium": null}}
                """.formatted(PHONE), RegisterRequest.class);
        assertThat(with.attribution()).isNotNull();
        assertThat(with.attribution().referrerHost()).isEqualTo("news.example.org");
        assertThat(with.attribution().utmSource()).isEqualTo("news");
        assertThat(with.attribution().utmMedium()).isNull();
        assertThat(with.referralCode()).isNull();

        RegisterRequest without = mapper.readValue("""
                {"email": "a@example.com", "password": "Password123", "fullName": "Jane Doe",
                 "phoneNumber": "%s", "referralCode": "ABCD1234"}
                """.formatted(PHONE), RegisterRequest.class);
        assertThat(without.attribution()).isNull();
        assertThat(without.referralCode()).isEqualTo("ABCD1234");
    }

    @Test
    void googleAuthRequest_bindsAttribution_andToleratesItsAbsence() throws Exception {
        GoogleAuthRequest with = mapper.readValue("""
                {"idToken": "t", "attribution": {"utmCampaign": "launch-oct"}}
                """, GoogleAuthRequest.class);
        assertThat(with.attribution()).isNotNull();
        assertThat(with.attribution().utmCampaign()).isEqualTo("launch-oct");

        GoogleAuthRequest without = mapper.readValue("""
                {"idToken": "t", "referralCode": "ABCD1234"}
                """, GoogleAuthRequest.class);
        assertThat(without.attribution()).isNull();
        assertThat(without.referralCode()).isEqualTo("ABCD1234");
    }

    @Test
    void appleAuthRequest_bindsAttribution_andToleratesItsAbsence() throws Exception {
        AppleAuthRequest with = mapper.readValue("""
                {"idToken": "t", "fullName": null, "attribution": {"utmContent": "video-a"}}
                """, AppleAuthRequest.class);
        assertThat(with.attribution()).isNotNull();
        assertThat(with.attribution().utmContent()).isEqualTo("video-a");

        AppleAuthRequest without = mapper.readValue("""
                {"idToken": "t", "fullName": "Amy Santiago"}
                """, AppleAuthRequest.class);
        assertThat(without.attribution()).isNull();
        assertThat(without.fullName()).isEqualTo("Amy Santiago");
    }
}
