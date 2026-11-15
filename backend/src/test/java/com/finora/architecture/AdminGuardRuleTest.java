package com.finora.architecture;

import com.finora.architecture.registry.GuardianSelfTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** The guard-strength rule on its own, one expression at a time. */
class AdminGuardRuleTest {

    @GuardianSelfTest(rule = "FG-024")
    @ParameterizedTest
    @ValueSource(strings = {
            "hasAuthority('USER_VIEW')",
            "hasAnyAuthority('IMPORT_TRIAGE_MANAGE', 'TRUST_REVIEW_MANAGE')",
            "hasAnyRole('ADMIN', 'SUPER_ADMIN')",
            "hasAuthority('TRUST_REVIEW_MANAGE') and hasAnyRole('ADMIN', 'SUPER_ADMIN')",
            "hasAuthority('PLATFORM_ANALYTICS_VIEW') and hasAuthority('SYSTEM_SETTINGS')",
            "hasAuthority('PORTAL_ADMIN')",
            "  hasAuthority( 'AUDIT_VIEW' )  ",
    })
    void acceptsGuardsOnlyAnAdminCanMeet(String guard) {
        assertThat(AdminGuardRule.weakness(guard)).isEmpty();
    }

    @GuardianSelfTest(rule = "FG-024")
    @ParameterizedTest
    @ValueSource(strings = {
            "isAuthenticated()",
            "permitAll()",
            "true",
            "isFullyAuthenticated()",
            "isAnonymous()",
            "",
            "   ",
            "hasAuthority('USER_VIEW') or isAuthenticated()",
            "hasAuthority('USER_VIEW') or hasAuthority('AUDIT_VIEW')",
            "hasAuthority('USER_VIEW') || isAuthenticated()",
            "!hasAuthority('USER_VIEW')",
            "not hasAuthority('USER_VIEW')",
            "hasAuthority('USER_VIEW') and #userId == authentication.principal.id",
            "@ownershipGuard.owns(#id)",
            "hasAuthority(\"USER_VIEW\")",
            "hasAuthority('USER_VIEW') and isAuthenticated()",
    })
    void rejectsAnyOtherShape(String guard) {
        assertThat(AdminGuardRule.weakness(guard)).isPresent();
    }

    @GuardianSelfTest(rule = "FG-024")
    @ParameterizedTest
    @ValueSource(strings = {
            "hasRole('USER')",
            "hasAnyRole('ADMIN', 'USER')",
            "hasAuthority('ROLE_USER')",
            "hasAuthority('PORTAL_USER')",
            "hasAuthority('ACCOUNT_STATUS_SUSPENDED')",
            "hasAuthority('USER_VIEW') and hasRole('USER')",
    })
    void rejectsGuardsNamingWhatACustomerHolds(String guard) {
        assertThat(AdminGuardRule.weakness(guard)).hasValueSatisfying(why -> assertThat(why).contains("ordinary customer"));
    }

    /** The customer names come from the code that grants them; this pins what that code says today. */
    @GuardianSelfTest(rule = "FG-024")
    @Test
    void customerAuthoritiesAreTheOnesTheAppGrants() {
        assertThat(AdminGuardRule.CUSTOMER_AUTHORITIES)
                .containsExactlyInAnyOrder("USER", "ROLE_USER", "PORTAL_USER", "ACCOUNT_STATUS_SUSPENDED");
    }

    @GuardianSelfTest(rule = "FG-024")
    @Test
    void aMissingGuardIsReportedAsSuch() {
        assertThat(AdminGuardRule.check("X.m()", null))
                .hasValueSatisfying(v -> assertThat(v.problem()).startsWith("no @PreAuthorize"));
    }
}
