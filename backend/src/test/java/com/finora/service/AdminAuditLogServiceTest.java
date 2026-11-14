package com.finora.service;

import com.finora.dto.AdminAuditLogRowDto;
import com.finora.entity.AuditLog;
import com.finora.repository.AuditLogRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import com.finora.entity.User;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminAuditLogServiceTest {

    /** A system entry with no account and no admin left the page's name lookup empty, and looking
     *  up a null account in that empty map threw, failing the whole feed. */
    @Test
    void anEntryWithNoAccountAndNoAdmin_isListedWithoutNames() {
        AuditLogRepository audit = mock(AuditLogRepository.class);
        UserRepository users = mock(UserRepository.class);
        AuditLog entry = new AuditLog();
        entry.setAction("SYSTEM_SWEEP");
        when(audit.search(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(entry), PageRequest.of(0, 25), 1));

        List<AdminAuditLogRowDto> rows = new AdminAuditLogService(audit, users)
                .globalFeed(null, null, null, null, null, null, null, PageRequest.of(0, 25), true).content();

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).userId()).isNull();
        assertThat(rows.get(0).userName()).isNull();
        verify(users, never()).findAllById(any());
    }

    // ---- who did it, when the writing service put the admin in the account slot (break-test 2026-11-14)

    private final AuditLogRepository audit = mock(AuditLogRepository.class);
    private final UserRepository users = mock(UserRepository.class);

    private static User person(String scope, String name) {
        User u = new User();
        ReflectionTestUtils.setField(u, "id", UUID.randomUUID());
        u.setAccountScope(scope);
        u.setFullName(name);
        u.setEmail(name.toLowerCase().replace(' ', '.') + "@example.test");
        return u;
    }

    private static AuditLog entry(UUID account, String action, UUID entityId, Map<String, Object> metadata) {
        AuditLog l = new AuditLog();
        ReflectionTestUtils.setField(l, "id", UUID.randomUUID());
        l.setUserId(account);
        l.setAction(action);
        l.setEntityType("Thing");
        l.setEntityId(entityId);
        l.setMetadata(metadata);
        return l;
    }

    private AdminAuditLogRowDto row(AuditLog l, boolean userView, User... people) {
        when(audit.search(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(l), PageRequest.of(0, 25), 1));
        when(users.findAllById(any())).thenReturn(List.of(people));
        return new AdminAuditLogService(audit, users)
                .globalFeed(null, null, null, null, null, null, null, PageRequest.of(0, 25), userView).content().get(0);
    }

    @Test
    void aStaffAccountOnARowNamingNoActor_isWhoActed_andTheRowIsAboutNoAccount() {
        User admin = person("ADMIN", "Ops Admin");
        AdminAuditLogRowDto r = row(entry(admin.getId(), "TRUST_REVIEW_APPROVED", UUID.randomUUID(), Map.of("note", "ok")),
                true, admin);
        assertThat(r.actorId()).isEqualTo(admin.getId());
        assertThat(r.actorName()).isEqualTo("Ops Admin");
        assertThat(r.userId()).isNull();
        assertThat(r.userName()).isNull();
    }

    @Test
    void aReadAuditRow_isTheAdminOpeningTheCustomersData() {
        User admin = person("ADMIN", "Ops Admin");
        User customer = person("USER", "Asha Rao");
        AdminAuditLogRowDto r = row(entry(admin.getId(), "ADMIN_USER_DATA_VIEWED", customer.getId(),
                Map.of("endpoint", "/api/v1/admin/users/{id}", "status", 200)), true, admin, customer);
        assertThat(r.actorId()).isEqualTo(admin.getId());
        assertThat(r.userId()).isEqualTo(customer.getId());
        assertThat(r.userName()).isEqualTo("Asha Rao");
    }

    @Test
    void aCustomersOwnAction_staysTheirs() {
        User customer = person("USER", "Asha Rao");
        AdminAuditLogRowDto r = row(entry(customer.getId(), "USER_LOGIN", customer.getId(), Map.of()), true, customer);
        assertThat(r.userId()).isEqualTo(customer.getId());
        assertThat(r.actorId()).isNull();
    }

    @Test
    void aStaffAccountActedOnByAnotherAdmin_orByAJob_isNotReAttributed() {
        User admin = person("ADMIN", "Ops Admin");
        User other = person("ADMIN", "Second Admin");
        AdminAuditLogRowDto byOther = row(entry(admin.getId(), "ACCOUNT_SUSPENDED", admin.getId(),
                Map.of("suspendedBy", other.getId().toString())), true, admin, other);
        assertThat(byOther.actorId()).isEqualTo(other.getId());
        assertThat(byOther.userId()).isEqualTo(admin.getId());

        AdminAuditLogRowDto byJob = row(entry(admin.getId(), "EMAIL_SENT", admin.getId(),
                Map.of("actor", "account-purge-sweep")), true, admin);
        assertThat(byJob.actorId()).isNull();
        assertThat(byJob.systemActor()).isEqualTo("account-purge-sweep");
        assertThat(byJob.userId()).isEqualTo(admin.getId());
    }

    /** A row whose entity is the staff account itself happened TO them -- an email sent to them, a
     *  failed sign-in on their account -- and is not their act (re-probe of 2026-11-14). */
    @Test
    void aRowAboutTheStaffAccountItself_staysAboutThem() {
        User admin = person("ADMIN", "Ops Admin");
        AdminAuditLogRowDto email = row(entry(admin.getId(), "EMAIL_SENT", admin.getId(), Map.of("type", "verify")),
                true, admin);
        assertThat(email.actorId()).isNull();
        assertThat(email.userId()).isEqualTo(admin.getId());
        AdminAuditLogRowDto failed = row(entry(admin.getId(), "LOGIN_FAILED", admin.getId(), Map.of()), true, admin);
        assertThat(failed.actorId()).isNull();
        assertThat(failed.userName()).isEqualTo("Ops Admin");
    }

    /** An unreadable first key no longer hides the admin a later key names (the search already
     *  listed the row under that admin). */
    @Test
    void anUnreadableActorKey_fallsThroughToTheNextOne() {
        User customer = person("USER", "Asha Rao");
        User admin = person("ADMIN", "Ops Admin");
        Map<String, Object> md = new HashMap<>();
        md.put("actorId", "not-a-uuid");
        md.put("changedBy", admin.getId().toString());
        AdminAuditLogRowDto r = row(entry(customer.getId(), "PLAN_CHANGED", customer.getId(), md), true, customer, admin);
        assertThat(r.actorId()).isEqualTo(admin.getId());
        assertThat(r.userId()).isEqualTo(customer.getId());
    }

    @Test
    void aCustomersGoogleEmail_isHiddenFromACallerWhoMayNotKnowWhoTheyAre() {
        User customer = person("USER", "Asha Rao");
        AuditLog l = entry(customer.getId(), "GMAIL_CONNECTED", customer.getId(),
                Map.of("googleEmail", "asha.personal@example.test", "scopes", "gmail.readonly"));
        AdminAuditLogRowDto hidden = row(l, false, customer);
        assertThat(hidden.userName()).isNull();
        assertThat(hidden.metadata()).containsEntry("googleEmail", "hidden (needs USER_VIEW)")
                .containsEntry("scopes", "gmail.readonly");
        assertThat(hidden.metadata().toString()).doesNotContain("asha.personal@example.test");

        AdminAuditLogRowDto shown = row(l, true, customer);
        assertThat(shown.metadata()).containsEntry("googleEmail", "asha.personal@example.test");
    }
}
