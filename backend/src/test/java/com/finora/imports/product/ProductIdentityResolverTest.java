package com.finora.imports.product;

import com.finora.entity.Account;
import com.finora.repository.AccountRepository;
import com.finora.service.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ProductIdentityResolverTest {

    private final AccountRepository accountRepository = mock(AccountRepository.class);
    private final AuditService auditService = mock(AuditService.class);
    private final ProductIdentityResolver resolver = new ProductIdentityResolver(accountRepository, auditService);
    private final UUID userId = UUID.randomUUID();

    private Account account(String bankId, FinancialProductType type, String fullNumber, String masked) {
        Account a = new Account();
        a.setUserId(userId);
        a.setBankId(bankId);
        a.setProductType(type.name());
        a.setAccountType(type.accountType() == null ? Account.Type.SAVINGS : type.accountType());
        a.setAccountNumberMasked(masked);
        a.setProductIdentityHash(ProductIdentity.of(bankId, type, fullNumber, masked).strongKey());
        return a;
    }

    /** An account with no account number at all (neither full nor masked) -- only the weak
     *  fallback signals -- for exercising the IFSC+holder-name PROBABLE path. */
    private Account accountWithWeakSignalsOnly(String bankId, FinancialProductType type,
                                               String ifscCode, String accountHolderName) {
        Account a = account(bankId, type, null, null);
        a.setIfscCode(ifscCode);
        a.setAccountHolderName(accountHolderName);
        return a;
    }

    @BeforeEach
    void noAccountsByDefault() {
        when(accountRepository.findByUserId(any())).thenReturn(List.of());
    }

    @Test
    void anUnseenProductIsNew() {
        var found = resolver.resolve(userId,
                ProductIdentity.of("HDFC", FinancialProductType.FIXED_DEPOSIT, "40000000000004", "1234"));

        assertThat(found.resolution()).isEqualTo(ProductIdentityResolver.Resolution.NEW);
        assertThat(found.account()).isNull();
    }

    @Test
    void reimportingTheSameDepositMatchesTheExistingOne() {
        Account existing = account("HDFC", FinancialProductType.FIXED_DEPOSIT, "40000000000004", "1234");
        when(accountRepository.findByUserId(userId)).thenReturn(List.of(existing));

        var found = resolver.resolve(userId,
                ProductIdentity.of("HDFC", FinancialProductType.FIXED_DEPOSIT, "40000000000004", "1234"));

        assertThat(found.resolution()).isEqualTo(ProductIdentityResolver.Resolution.MATCHED);
        assertThat(found.account()).isSameAs(existing);
        assertThat(found.mayImportWithoutAsking()).isTrue();
    }

    @Test
    void aMaskedOnlyMatchIsProbableAndNeverImportedIntoSilently() {
        // "Probably the same FD" means ask. This is the case that corrupts data if guessed.
        Account existing = account("HDFC", FinancialProductType.FIXED_DEPOSIT, null, "4521");
        when(accountRepository.findByUserId(userId)).thenReturn(List.of(existing));

        var found = resolver.resolve(userId,
                ProductIdentity.of("HDFC", FinancialProductType.FIXED_DEPOSIT, null, "4521"));

        assertThat(found.resolution()).isEqualTo(ProductIdentityResolver.Resolution.PROBABLE);
        assertThat(found.mayImportWithoutAsking()).isFalse();
        assertThat(found.reason()).contains("no full number");
    }

    @Test
    void aCardAccountStoredWhileDetectionSaidUnknownIsStillRecognisedOnceItDetectsAsACard() {
        // Accounts created while 7 of 11 real card layouts detected UNKNOWN stored that type. Once
        // those cards detect CREDIT_CARD, the masked match must still find them -- by the account
        // type the user confirmed -- and still only as PROBABLE, never silently.
        Account existing = account("HDFC", FinancialProductType.UNKNOWN, null, "400000XXXXXX1111");
        existing.setAccountType(Account.Type.CREDIT_CARD);
        when(accountRepository.findByUserId(userId)).thenReturn(List.of(existing));

        var found = resolver.resolve(userId,
                ProductIdentity.of("HDFC", FinancialProductType.CREDIT_CARD, null, "400000XXXXXX1111"));

        assertThat(found.resolution()).isEqualTo(ProductIdentityResolver.Resolution.PROBABLE);
        assertThat(found.account()).isSameAs(existing);
        assertThat(found.mayImportWithoutAsking()).isFalse();
    }

    @Test
    void anUnknownInvestmentAccountStaysUnknownAndDoesNotMatchADifferentProduct() {
        Account existing = account("HDFC", FinancialProductType.UNKNOWN, null, "4521");
        existing.setAccountType(Account.Type.INVESTMENT);
        when(accountRepository.findByUserId(userId)).thenReturn(List.of(existing));

        var found = resolver.resolve(userId,
                ProductIdentity.of("HDFC", FinancialProductType.FIXED_DEPOSIT, null, "4521"));

        assertThat(found.resolution()).isEqualTo(ProductIdentityResolver.Resolution.NEW);
    }

    @Test
    void severalCandidatesAreNeverDisambiguatedByGuessing() {
        when(accountRepository.findByUserId(userId)).thenReturn(List.of(
                account("HDFC", FinancialProductType.FIXED_DEPOSIT, null, "4521"),
                account("HDFC", FinancialProductType.FIXED_DEPOSIT, null, "4521")));

        var found = resolver.resolve(userId,
                ProductIdentity.of("HDFC", FinancialProductType.FIXED_DEPOSIT, null, "4521"));

        assertThat(found.resolution()).isEqualTo(ProductIdentityResolver.Resolution.PROBABLE);
        assertThat(found.account()).as("no single answer, so none is offered").isNull();
        assertThat(found.candidates()).hasSize(2);
    }

    @Test
    void preExistingDuplicatesAreSurfacedRatherThanPickedBetween() {
        // Duplicates can already exist from before identity was recorded -- exactly the mess this
        // feature stops growing. Importing into an arbitrary one of them would deepen it.
        when(accountRepository.findByUserId(userId)).thenReturn(List.of(
                account("HDFC", FinancialProductType.FIXED_DEPOSIT, "40000000000004", "1234"),
                account("HDFC", FinancialProductType.FIXED_DEPOSIT, "40000000000004", "1234")));

        var found = resolver.resolve(userId,
                ProductIdentity.of("HDFC", FinancialProductType.FIXED_DEPOSIT, "40000000000004", "1234"));

        assertThat(found.resolution()).isEqualTo(ProductIdentityResolver.Resolution.PROBABLE);
        assertThat(found.reason()).contains("duplicates");
    }

    @Test
    void aDifferentProductAtTheSameBankIsNotAMatch() {
        Account savings = account("HDFC", FinancialProductType.SAVINGS, "50000000000005", "6000");
        when(accountRepository.findByUserId(userId)).thenReturn(List.of(savings));

        var found = resolver.resolve(userId,
                ProductIdentity.of("HDFC", FinancialProductType.FIXED_DEPOSIT, "40000000000004", "1234"));

        assertThat(found.resolution()).isEqualTo(ProductIdentityResolver.Resolution.NEW);
    }

    // IFSC + account holder name fallback -- the actual regression the PNB incident exposed: when
    // extraction finds no account number at all (no full number, not even masked), "we have some
    // evidence" (same bank, same IFSC, same holder) and "we have none" both used to resolve to NEW.

    @Test
    void noAccountNumberAtAll_butMatchingIfscAndHolder_isProbableNotNew() {
        Account existing = accountWithWeakSignalsOnly("PNB", FinancialProductType.SAVINGS,
                "PUNB0XXXXXX", "JOHN DOE"); // synthetic-ok
        when(accountRepository.findByUserId(userId)).thenReturn(List.of(existing));

        var discovered = ProductIdentity.of("PNB", FinancialProductType.SAVINGS, null, null)
                .withWeakSignals("PUNB0XXXXXX", "JOHN DOE"); // synthetic-ok

        var found = resolver.resolve(userId, discovered);

        assertThat(found.resolution()).isEqualTo(ProductIdentityResolver.Resolution.PROBABLE);
        assertThat(found.account()).isSameAs(existing);
        assertThat(found.mayImportWithoutAsking()).isFalse();
        assertThat(found.reason()).contains("IFSC").contains("account holder name");
    }

    /**
     * Regression test for audit finding F-08 (2026-09-18): the IFSC+holder fallback used to write
     * the raw IFSC code and account holder name straight into the application log. This asserts
     * the raw evidence now goes only to {@code audit_logs} (via {@code AuditService}, the purpose-
     * built access-controlled record with its own retention policy), not that it stopped being
     * recorded anywhere -- support still needs a real answer to "why did the resolver say
     * PROBABLE", just not from a log line every log-shipping tool can read.
     */
    @Test
    void weakSignalMatch_writesRawEvidenceToAuditLog_notJustTheApplicationLog() {
        UUID existingId = UUID.randomUUID();
        Account existing = accountWithWeakSignalsOnly("PNB", FinancialProductType.SAVINGS,
                "PUNB0XXXXXX", "JOHN DOE"); // synthetic-ok
        ReflectionTestUtils.setField(existing, "id", existingId);
        when(accountRepository.findByUserId(userId)).thenReturn(List.of(existing));

        var discovered = ProductIdentity.of("PNB", FinancialProductType.SAVINGS, null, null)
                .withWeakSignals("PUNB0XXXXXX", "JOHN DOE"); // synthetic-ok

        resolver.resolve(userId, discovered);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditService).recordEvenOnRollback(eq(userId), eq("PRODUCT_IDENTITY_WEAK_SIGNAL_MATCH"),
                eq("Account"), eq(existingId), metadataCaptor.capture());

        Map<String, Object> metadata = metadataCaptor.getValue();
        assertThat(metadata).containsEntry("bank", "PNB");
        assertThat(metadata).containsEntry("ifsc", "PUNB0XXXXXX");
        assertThat(metadata).containsEntry("holder", "JOHN DOE");
        assertThat(metadata).containsEntry("reason", "account_number_missing");
        assertThat(metadata).containsEntry("accountIds", List.of(existingId.toString()));
        // A SHA-256 hex digest is exactly 64 hex characters -- proves a real hash was computed,
        // not a placeholder or the raw value itself.
        assertThat((String) metadata.get("fingerprint")).hasSize(64).matches("[0-9a-f]{64}");
    }

    /** Same regression as above, for the "more than one candidate" branch -- a separate call site
     *  in {@code resolve}, not exercised by the single-candidate test. */
    @Test
    void weakSignalMatch_withMultipleCandidates_alsoWritesToAuditLog() {
        Account first = accountWithWeakSignalsOnly("PNB", FinancialProductType.SAVINGS,
                "PUNB0XXXXXX", "JOHN DOE"); // synthetic-ok
        ReflectionTestUtils.setField(first, "id", UUID.randomUUID());
        Account second = accountWithWeakSignalsOnly("PNB", FinancialProductType.SAVINGS,
                "PUNB0XXXXXX", "JOHN DOE"); // synthetic-ok
        ReflectionTestUtils.setField(second, "id", UUID.randomUUID());
        when(accountRepository.findByUserId(userId)).thenReturn(List.of(first, second));

        var discovered = ProductIdentity.of("PNB", FinancialProductType.SAVINGS, null, null)
                .withWeakSignals("PUNB0XXXXXX", "JOHN DOE"); // synthetic-ok

        var found = resolver.resolve(userId, discovered);

        assertThat(found.resolution()).isEqualTo(ProductIdentityResolver.Resolution.PROBABLE);
        verify(auditService).recordEvenOnRollback(eq(userId), eq("PRODUCT_IDENTITY_WEAK_SIGNAL_MATCH"),
                eq("Account"), eq(first.getId()), any());
    }

    /** The audit write is specific to the IFSC+holder fallback -- an ordinary masked-number
     *  PROBABLE match (a full account number was available, just not confirmable) carries no such
     *  raw evidence to protect and must not write an unrelated audit row. */
    @Test
    void nonWeakSignalMatch_neverWritesToAuditLog() {
        Account existing = account("HDFC", FinancialProductType.SAVINGS, "40000000009999", "9999");
        when(accountRepository.findByUserId(userId)).thenReturn(List.of(existing));

        var discovered = ProductIdentity.of("HDFC", FinancialProductType.SAVINGS, null, "9999");

        var found = resolver.resolve(userId, discovered);

        assertThat(found.resolution()).isEqualTo(ProductIdentityResolver.Resolution.PROBABLE);
        verifyNoInteractions(auditService);
    }

    @Test
    void sameBankOnly_withNoIfscOrHolderOverlap_isNewNotProbable() {
        Account existing = accountWithWeakSignalsOnly("PNB", FinancialProductType.SAVINGS,
                "PUNB0XXXXXX", "JOHN DOE"); // synthetic-ok
        when(accountRepository.findByUserId(userId)).thenReturn(List.of(existing));

        var discovered = ProductIdentity.of("PNB", FinancialProductType.SAVINGS, null, null)
                .withWeakSignals("PUNB0YYYYYY", "SOMEONE ELSE"); // synthetic-ok

        var found = resolver.resolve(userId, discovered);

        assertThat(found.resolution()).isEqualTo(ProductIdentityResolver.Resolution.NEW);
    }

    @Test
    void sameIfscOnly_withNoHolderNameMatch_isNewNotProbable() {
        // A branch's IFSC is shared by every customer at that branch -- far too weak on its own.
        Account existing = accountWithWeakSignalsOnly("PNB", FinancialProductType.SAVINGS,
                "PUNB0XXXXXX", "JOHN DOE"); // synthetic-ok
        when(accountRepository.findByUserId(userId)).thenReturn(List.of(existing));

        var discovered = ProductIdentity.of("PNB", FinancialProductType.SAVINGS, null, null)
                .withWeakSignals("PUNB0XXXXXX", "SOMEONE ELSE"); // synthetic-ok

        var found = resolver.resolve(userId, discovered);

        assertThat(found.resolution()).isEqualTo(ProductIdentityResolver.Resolution.NEW);
    }

    @Test
    void sameHolderNameOnly_withNoIfscMatch_isNewNotProbable() {
        // A holder name alone is shared by every account that person holds -- too weak on its own.
        Account existing = accountWithWeakSignalsOnly("PNB", FinancialProductType.SAVINGS,
                "PUNB0XXXXXX", "JOHN DOE"); // synthetic-ok
        when(accountRepository.findByUserId(userId)).thenReturn(List.of(existing));

        var discovered = ProductIdentity.of("PNB", FinancialProductType.SAVINGS, null, null)
                .withWeakSignals("PUNB0YYYYYY", "JOHN DOE"); // synthetic-ok

        var found = resolver.resolve(userId, discovered);

        assertThat(found.resolution()).isEqualTo(ProductIdentityResolver.Resolution.NEW);
    }

    /**
     * Closes the gap PdfMetadataExtractorTest's own PNB tests leave open: those prove extraction
     * succeeds in isolation, not that two INDEPENDENT extractions of PNB's "Statement of Account"
     * line -- one at account-creation time, one from a later statement -- actually resolve to the
     * SAME account rather than a second one. This is the real regression the production incident
     * exposed: PDF -> PdfMetadataExtractor -> ProductIdentity -> ProductIdentityResolver -> match,
     * exercised end-to-end rather than asserting on hand-typed identity values.
     */
    @Test
    void aSecondPnbStatementImport_extractsTheSameAccountNumberAndMatchesTheExistingAccount() {
        var extractor = new com.finora.imports.pdf.PdfMetadataExtractor();

        // First import: a January PNB statement is what created the account originally.
        var firstStatement = extractor.extract(List.of(
                "Statement of Account:98765432101234 For Period: 01/01/2026 to 31/01/2026")); // synthetic-ok
        Account existing = account("PNB", FinancialProductType.SAVINGS,
                firstStatement.accountNumberFullForHashingOnly(), firstStatement.accountNumberMasked());
        when(accountRepository.findByUserId(userId)).thenReturn(List.of(existing));

        // Second import: a later PNB statement for the same real account, extracted independently
        // (a fresh extractor run against different statement text, not a reused value).
        var secondStatement = extractor.extract(List.of(
                "Statement of Account:98765432101234 For Period: 01/02/2026 to 28/02/2026")); // synthetic-ok
        var discovered = ProductIdentity.of("PNB", FinancialProductType.SAVINGS,
                secondStatement.accountNumberFullForHashingOnly(), secondStatement.accountNumberMasked());

        var found = resolver.resolve(userId, discovered);

        assertThat(found.resolution()).isEqualTo(ProductIdentityResolver.Resolution.MATCHED);
        assertThat(found.account()).isSameAs(existing);
        assertThat(found.mayImportWithoutAsking()).isTrue();
    }

    @Test
    void anAccountPredatingTheProductTypeColumnStillMatchesOnItsAccountType() {
        // V49 backfills product_type, but a hand-created account can still have it null. Falling
        // back to the coarse account type keeps identity working rather than treating every legacy
        // row as unmatched and duplicating it on the next import.
        Account legacy = account("HDFC", FinancialProductType.SAVINGS, "50000000000005", "6000");
        legacy.setProductType(null);
        when(accountRepository.findByUserId(userId)).thenReturn(List.of(legacy));

        var found = resolver.resolve(userId,
                ProductIdentity.of("HDFC", FinancialProductType.SAVINGS, "50000000000005", "6000"));

        assertThat(found.resolution()).isEqualTo(ProductIdentityResolver.Resolution.MATCHED);
    }
}
