package com.finora.service.adminusers;

import com.finora.exception.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserListFilterTest {

    private static final LocalDate NOV_1 = LocalDate.of(2026, 11, 1);
    private static final LocalDate NOV_12 = LocalDate.of(2026, 11, 12);

    private static UserListFilter only(String plan) {
        return UserListFilter.of(null, null, plan, null, null, null, null, null, null, null);
    }

    @Test
    void blanks_becomeNoFilter() {
        UserListFilter f = UserListFilter.of("  ", "", " ", "", null, null, null, null, null, null);
        assertThat(f.q()).isNull();
        assertThat(f.status()).isNull();
        assertThat(f.plan()).isNull();
        assertThat(f.bankId()).isNull();
        assertThat(f.isUnfiltered()).isTrue();
    }

    @Test
    void plan_isCaseInsensitive() {
        assertThat(only("plus").plan()).isEqualTo("PLUS");
        assertThat(only(" Premium ").plan()).isEqualTo("PREMIUM");
        assertThat(only("free").plan()).isEqualTo("FREE");
    }

    @Test
    void unknownPlan_is400() {
        assertThatThrownBy(() -> only("GOLD"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("Unknown plan");
    }

    @Test
    void joinedFromAfterTo_is400() {
        assertThatThrownBy(() -> UserListFilter.of(null, null, null, null, NOV_12, NOV_1, null, null, null, null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Joined");
    }

    @Test
    void lastActiveFromAfterTo_is400() {
        assertThatThrownBy(() -> UserListFilter.of(null, null, null, null, null, null, NOV_12, NOV_1, null, null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Last active");
    }

    @Test
    void sameDayRange_isAllowed() {
        UserListFilter f = UserListFilter.of(null, null, null, null, NOV_1, NOV_1, NOV_1, NOV_1, null, null);
        assertThat(f.joinedFrom()).isEqualTo(NOV_1);
        assertThat(f.lastActiveTo()).isEqualTo(NOV_1);
    }

    @Test
    void negativeImports_is400() {
        assertThatThrownBy(() -> UserListFilter.of(null, null, null, null, null, null, null, null, -1, null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Imports");
        assertThatThrownBy(() -> UserListFilter.of(null, null, null, null, null, null, null, null, null, -1))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Imports");
    }

    @Test
    void importsMinAboveMax_is400() {
        assertThatThrownBy(() -> UserListFilter.of(null, null, null, null, null, null, null, null, 5, 2))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Imports");
    }

    @Test
    void importsZeroToZero_isAllowed() {
        UserListFilter f = UserListFilter.of(null, null, null, null, null, null, null, null, 0, 0);
        assertThat(f.importsMin()).isZero();
        assertThat(f.importsMax()).isZero();
        assertThat(f.isUnfiltered()).isFalse();
    }

    @Test
    void searchText_isTrimmedAndLikeEscaped() {
        UserListFilter f = UserListFilter.of(" 50%_x ", null, null, null, null, null, null, null, null, null);
        assertThat(f.q()).isEqualTo("50\\%\\_x");
    }

    @Test
    void bankAndStatus_areTrimmed() {
        UserListFilter f = UserListFilter.of(null, " ACTIVE ", null, " HDFC ", null, null, null, null, null, null);
        assertThat(f.status()).isEqualTo("ACTIVE");
        assertThat(f.bankId()).isEqualTo("HDFC");
    }
}
