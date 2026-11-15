package com.finora.service.adminusers;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The column's plan follows EntitlementService: a referral grant wins only when it ranks higher. */
class AdminUserListQueryTest {

    @Test
    void noPlanAndNoGrant_isNull() {
        assertThat(AdminUserListQuery.effectivePlan(null, null)).isNull();
    }

    @Test
    void grantAlone_isTheGrant() {
        assertThat(AdminUserListQuery.effectivePlan(null, "PLUS")).isEqualTo("PLUS");
    }

    @Test
    void higherGrant_beatsSubscription() {
        assertThat(AdminUserListQuery.effectivePlan("FREE", "PLUS")).isEqualTo("PLUS");
        assertThat(AdminUserListQuery.effectivePlan("PLUS", "PREMIUM")).isEqualTo("PREMIUM");
    }

    @Test
    void equalOrLowerGrant_keepsSubscription() {
        assertThat(AdminUserListQuery.effectivePlan("PLUS", "PLUS")).isEqualTo("PLUS");
        assertThat(AdminUserListQuery.effectivePlan("PREMIUM", "PLUS")).isEqualTo("PREMIUM");
    }
}
