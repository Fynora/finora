package com.finora.integrations.setu;

import com.finora.entity.Account;
import com.finora.exception.ApiException;
import com.finora.repository.AccountRepository;
import com.finora.service.AuditService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccountAggregatorLinkManagementServiceTest {

    @Test
    void listForUserReturnsOnlyThatUsersLinks() {
        AccountAggregatorLinkRepository links = mock(AccountAggregatorLinkRepository.class);
        AccountRepository accountRepository = mock(AccountRepository.class);
        AuditService auditService = mock(AuditService.class);
        UUID userId = UUID.randomUUID();
        AccountAggregatorLink link = new AccountAggregatorLink();
        when(links.findByUserId(userId)).thenReturn(List.of(link));

        AccountAggregatorLinkManagementService service =
                new AccountAggregatorLinkManagementService(links, accountRepository, auditService);

        assertThat(service.listForUser(userId)).containsExactly(link);
    }

    @Test
    void disconnectRevokesTheLinkAndRevertsTheAccountAndAuditsWithADistinctAction() {
        AccountAggregatorLinkRepository links = mock(AccountAggregatorLinkRepository.class);
        AccountRepository accountRepository = mock(AccountRepository.class);
        AuditService auditService = mock(AuditService.class);
        UUID userId = UUID.randomUUID();
        UUID linkId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        AccountAggregatorLink link = new AccountAggregatorLink();
        link.setUserId(userId);
        link.setAccountId(accountId);
        link.setStatus(AccountAggregatorLinkStatus.ACTIVE);
        // save() is an unstubbed mock (returns null) -- the id a real repository would assign on
        // insert has to be set here instead, the same way every other mocked-repository test in
        // this package fakes JPA's @GeneratedValue.
        ReflectionTestUtils.setField(link, "id", linkId);
        when(links.findById(linkId)).thenReturn(Optional.of(link));
        Account account = new Account();
        account.setPrimarySource(Account.PrimarySource.ACCOUNT_AGGREGATOR);
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));

        AccountAggregatorLinkManagementService service =
                new AccountAggregatorLinkManagementService(links, accountRepository, auditService);
        service.disconnect(userId, linkId);

        assertThat(link.getStatus()).isEqualTo(AccountAggregatorLinkStatus.REVOKED);
        assertThat(account.getPrimarySource()).isEqualTo(Account.PrimarySource.MANUAL);
        // Distinct action from the webhook path's ACCOUNT_AGGREGATOR_CONSENT_REVOKED -- see this
        // service's own doc comment for why the who/why distinction lives here, not in the status.
        verify(auditService).record(eq(userId), eq("ACCOUNT_AGGREGATOR_USER_DISCONNECTED"),
                eq("AccountAggregatorLink"), eq(linkId));
    }

    @Test
    void disconnectRefusesALinkBelongingToAnotherUser() {
        AccountAggregatorLinkRepository links = mock(AccountAggregatorLinkRepository.class);
        AccountRepository accountRepository = mock(AccountRepository.class);
        AuditService auditService = mock(AuditService.class);
        UUID linkId = UUID.randomUUID();
        AccountAggregatorLink link = new AccountAggregatorLink();
        link.setUserId(UUID.randomUUID()); // a different user
        when(links.findById(linkId)).thenReturn(Optional.of(link));

        AccountAggregatorLinkManagementService service =
                new AccountAggregatorLinkManagementService(links, accountRepository, auditService);

        assertThatThrownBy(() -> service.disconnect(UUID.randomUUID(), linkId))
                .isInstanceOf(ApiException.class);
    }
}
