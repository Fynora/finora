package com.finora.integrations.setu;

import com.finora.accounts.AccountDto;
import com.finora.accounts.AccountService;
import com.finora.entity.Account;
import com.finora.imports.product.ProductIdentityResolver;
import com.finora.repository.AccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AccountAggregatorIdentityResolutionServiceTest {

    private SetuConsentGateway gateway;
    private AccountRepository accountRepository;
    private AccountService accountService;
    private ProductIdentityResolver productIdentityResolver;
    private AccountAggregatorLinkRepository links;
    private AccountAggregatorIdentityResolutionService service;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        gateway = mock(SetuConsentGateway.class);
        accountRepository = mock(AccountRepository.class);
        accountService = mock(AccountService.class);
        productIdentityResolver = mock(ProductIdentityResolver.class);
        links = mock(AccountAggregatorLinkRepository.class);
        service = new AccountAggregatorIdentityResolutionService(
                gateway, accountRepository, accountService, productIdentityResolver, links);

        when(links.save(any(AccountAggregatorLink.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private AccountAggregatorLink pendingLink() {
        AccountAggregatorLink link = new AccountAggregatorLink();
        link.setUserId(userId);
        link.setFiType(FiType.DEPOSIT);
        link.setConsentHandleId("consent-handle-1");
        link.setStatus(AccountAggregatorLinkStatus.CONSENT_PENDING);
        return link;
    }

    @Test
    void exactMatchAttachesSilentlyAndActivatesTheLink() {
        AccountAggregatorLink link = pendingLink();
        when(gateway.fetchConsentDetail("consent-handle-1")).thenReturn(
                new SetuConsentDetail("HDFC", "HDFC0XXXXXX", "XXXX1234", "ACCTNUM0001234", "JOHN DOE"));

        Account existingAccount = new Account();
        existingAccount.setUserId(userId);
        ReflectionTestUtils.setField(existingAccount, "id", UUID.randomUUID());
        when(accountRepository.findByUserId(userId)).thenReturn(List.of(existingAccount));

        ProductIdentityResolver.ProductMatch matched = new ProductIdentityResolver.ProductMatch(
                ProductIdentityResolver.Resolution.MATCHED, existingAccount, List.of(existingAccount), "exact match");
        when(productIdentityResolver.resolve(eq(userId), any())).thenReturn(matched);

        service.resolveAndAttach(link);

        assertThat(link.getStatus()).isEqualTo(AccountAggregatorLinkStatus.ACTIVE);
        assertThat(link.getAccountId()).isEqualTo(existingAccount.getId());
        assertThat(existingAccount.getPrimarySource()).isEqualTo(Account.PrimarySource.ACCOUNT_AGGREGATOR);
        verify(accountService, never()).create(any(), any(), any());
    }

    @Test
    void probableMatchNeverAutoAttachesAndWaitsForConfirmation() {
        AccountAggregatorLink link = pendingLink();
        when(gateway.fetchConsentDetail("consent-handle-1")).thenReturn(
                new SetuConsentDetail("HDFC", "HDFC0XXXXXX", "XXXX1234", null, null));

        Account candidate = new Account();
        candidate.setUserId(userId);
        when(accountRepository.findByUserId(userId)).thenReturn(List.of(candidate));

        ProductIdentityResolver.ProductMatch probable = new ProductIdentityResolver.ProductMatch(
                ProductIdentityResolver.Resolution.PROBABLE, candidate, List.of(candidate), "probable match");
        when(productIdentityResolver.resolve(eq(userId), any())).thenReturn(probable);

        service.resolveAndAttach(link);

        assertThat(link.getStatus()).isEqualTo(AccountAggregatorLinkStatus.PENDING_ACCOUNT_CONFIRMATION);
        assertThat(link.getAccountId()).isNull();
        assertThat(candidate.getPrimarySource()).isEqualTo(Account.PrimarySource.MANUAL);
        verify(accountService, never()).create(any(), any(), any());
    }

    @Test
    void newProductCreatesAnAccountAndActivatesTheLink() {
        AccountAggregatorLink link = pendingLink();
        when(gateway.fetchConsentDetail("consent-handle-1")).thenReturn(
                new SetuConsentDetail("HDFC", "HDFC0XXXXXX", "XXXX9999", "ACCTNUM0009999", "JANE ROE"));
        when(accountRepository.findByUserId(userId)).thenReturn(List.of());

        ProductIdentityResolver.ProductMatch none = new ProductIdentityResolver.ProductMatch(
                ProductIdentityResolver.Resolution.NEW, null, List.of(), "no existing product matches");
        when(productIdentityResolver.resolve(eq(userId), any())).thenReturn(none);

        UUID newAccountId = UUID.randomUUID();
        AccountDto created = mock(AccountDto.class);
        when(created.id()).thenReturn(newAccountId);
        when(accountService.create(eq(userId), any(AccountDto.CreateRequest.class), eq(userId))).thenReturn(created);

        Account persisted = new Account();
        persisted.setUserId(userId);
        ReflectionTestUtils.setField(persisted, "id", newAccountId);
        when(accountRepository.findById(newAccountId)).thenReturn(java.util.Optional.of(persisted));

        service.resolveAndAttach(link);

        assertThat(link.getStatus()).isEqualTo(AccountAggregatorLinkStatus.ACTIVE);
        assertThat(link.getAccountId()).isEqualTo(newAccountId);
        assertThat(persisted.getPrimarySource()).isEqualTo(Account.PrimarySource.ACCOUNT_AGGREGATOR);
    }
}
