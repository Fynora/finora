package com.finora.service;

import com.finora.AbstractIntegrationTest;
import com.finora.config.JwtProperties;
import com.finora.entity.SessionKind;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Audit F-04. The unit tests set the phone session cap themselves, so they would pass with the
 * shipped default still at 0, which means no cap. This reads the value the application actually
 * binds from application.yml when no variable overrides it.
 */
class PhoneSessionCapDefaultIT extends AbstractIntegrationTest {

    @Autowired private JwtProperties jwtProperties;
    @Autowired private RefreshTokenService refreshTokenService;

    @Test
    void aPhoneSessionIsCappedAtAYearByDefault_andStillOutlivesItsIdleLimit() {
        assertThat(jwtProperties.getMobileAbsoluteSessionMs())
                .as("0 would mean a phone session never ends while it is used")
                .isEqualTo(Duration.ofDays(365).toMillis());
        assertThat(refreshTokenService.absoluteSessionMsFor(SessionKind.MOBILE))
                .isEqualTo(Duration.ofDays(365).toMillis());
        assertThat(jwtProperties.getMobileAbsoluteSessionMs())
                .as("a cap shorter than the idle limit would make the idle limit meaningless")
                .isGreaterThan(jwtProperties.getMobileIdleTimeoutMs());
        assertThat(refreshTokenService.absoluteSessionMsFor(SessionKind.WEB))
                .as("the web cap is unchanged")
                .isEqualTo(Duration.ofDays(30).toMillis());
    }
}
