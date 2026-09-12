package com.finora.integrations.setu;

import com.finora.accounts.AccountDto;
import com.finora.accounts.AccountService;
import com.finora.entity.Account;
import com.finora.imports.product.FinancialProductType;
import com.finora.imports.product.ProductIdentity;
import com.finora.imports.product.ProductIdentityResolver;
import com.finora.repository.AccountRepository;
import com.finora.util.BankRegistry;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Decides which Account (if any) a newly-approved AccountAggregatorLink belongs to. Reuses
 * ProductIdentityResolver -- the same NEW/MATCHED/PROBABLE model already governing manual
 * re-import -- rather than a separate, less-safe matching system. See the design spec's "Account
 * identity resolution" section for why silent attachment on anything less than an exact match was
 * rejected.
 */
@Service
public class AccountAggregatorIdentityResolutionService {

    private final SetuConsentGateway gateway;
    private final AccountRepository accountRepository;
    private final AccountService accountService;
    private final ProductIdentityResolver productIdentityResolver;
    private final AccountAggregatorLinkRepository links;

    public AccountAggregatorIdentityResolutionService(SetuConsentGateway gateway, AccountRepository accountRepository,
                                                        AccountService accountService,
                                                        ProductIdentityResolver productIdentityResolver,
                                                        AccountAggregatorLinkRepository links) {
        this.gateway = gateway;
        this.accountRepository = accountRepository;
        this.accountService = accountService;
        this.productIdentityResolver = productIdentityResolver;
        this.links = links;
    }

    public void resolveAndAttach(AccountAggregatorLink link) {
        SetuConsentDetail detail = gateway.fetchConsentDetail(link.getConsentHandleId());

        // IFSC is passed as a labelled hint, reusing BankRegistry's own "Signal 1: the account's
        // own, labelled IFSC" detection path (see BankRegistry.detect) rather than adding a second,
        // Setu-specific bank-id mapping table.
        String bankId = BankRegistry.detect("account-aggregator",
                List.of("IFSC " + detail.ifscCode())).id();

        FinancialProductType type = link.getFiType() == FiType.CREDIT_CARD
                ? FinancialProductType.CREDIT_CARD : FinancialProductType.SAVINGS;

        ProductIdentity discovered = (detail.fullAccountNumber() != null
                ? ProductIdentity.of(bankId, type, detail.fullAccountNumber(), detail.maskedAccountNumber())
                : ProductIdentity.stored(bankId, type, null, detail.maskedAccountNumber()))
                .withWeakSignals(detail.ifscCode(), detail.accountHolderName());

        ProductIdentityResolver.ProductMatch match =
                productIdentityResolver.resolve(link.getUserId(), discovered);

        switch (match.resolution()) {
            case MATCHED -> attach(link, match.account());
            case NEW -> attach(link, createAccount(link, detail, bankId));
            case PROBABLE -> {
                // Never auto-attached -- see this class's own doc comment. The candidate(s) stay
                // available via match.candidates() for the confirmation endpoint (Task 9) to offer;
                // this method's job ends at surfacing that a decision is needed.
                link.setStatus(AccountAggregatorLinkStatus.PENDING_ACCOUNT_CONFIRMATION);
                links.save(link);
            }
        }
    }

    /** Shared by the MATCHED and NEW branches above, and by Task 9's confirm-existing-account
     *  endpoint (a user-confirmed PROBABLE match is handled identically to an automatic MATCHED
     *  one once the account is settled). */
    void attach(AccountAggregatorLink link, Account account) {
        account.setPrimarySource(Account.PrimarySource.ACCOUNT_AGGREGATOR);
        accountRepository.save(account);
        link.setAccountId(account.getId());
        link.setStatus(AccountAggregatorLinkStatus.ACTIVE);
        links.save(link);
    }

    private Account createAccount(AccountAggregatorLink link, SetuConsentDetail detail, String bankId) {
        String accountType = link.getFiType() == FiType.CREDIT_CARD ? "CREDIT_CARD" : "SAVINGS";
        AccountDto created = accountService.create(link.getUserId(), new AccountDto.CreateRequest(
                bankNameOr(bankId, "Bank"), accountType, java.math.BigDecimal.ZERO, null, null,
                null, detail.accountHolderName(), detail.maskedAccountNumber(), bankId,
                null, detail.ifscCode(),
                null, null, null, null, null, null, null), link.getUserId());
        return accountRepository.findById(created.id())
                .orElseThrow(() -> new IllegalStateException("Just-created account not found: " + created.id()));
    }

    /** Task 9. The user, shown match.candidates() from a PROBABLE resolution, picked one. Handled
     *  identically to an automatic MATCHED attach once the account is settled. */
    public void confirmExistingAccount(AccountAggregatorLink link, java.util.UUID accountId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new com.finora.exception.ApiException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Account not found."));
        attach(link, account);
    }

    /** The user, shown a PROBABLE match, said "no, this is a different/new account." */
    public void confirmNewAccount(AccountAggregatorLink link, SetuConsentDetail detail, String bankId) {
        attach(link, createAccount(link, detail, bankId));
    }

    private static String bankNameOr(String bankId, String fallback) {
        BankRegistry.BankInfo info = BankRegistry.get(bankId);
        return info != null ? info.shortName() : fallback;
    }
}
