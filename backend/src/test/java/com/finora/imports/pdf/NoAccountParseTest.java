package com.finora.imports.pdf;

import com.finora.dto.ImportDto.StagedAccountSection;
import com.finora.dto.ImportDto.StagedRow;
import com.finora.imports.BalanceChainValidator;
import com.finora.imports.ColumnAmbiguityValidator;
import com.finora.imports.CreditCardFlowReconciliationValidator;
import com.finora.imports.CreditCardStatementTotalsValidator;
import com.finora.imports.DescriptionCorruptionValidator;
import com.finora.imports.DuplicateDetector;
import com.finora.imports.ImportVerifier;
import com.finora.imports.RowAccountingValidator;
import com.finora.imports.StatementTotalsValidator;
import com.finora.imports.SummaryTotalsValidator;
import com.finora.imports.TransactionNormalizer;
import com.finora.imports.pdf.fixtures.PdfFixtureBuilder;
import com.finora.imports.product.ProductAttributeExtractor;
import com.finora.imports.product.ProductDiscovery;
import com.finora.service.CategorizationService;
import com.finora.service.RuleEngineService;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * A statement read with no account (the free converter): the parser looks nothing up, and only the
 * normalizer built for it may be used that way.
 */
class NoAccountParseTest {

    private static ImportVerifier verifier() {
        return new ImportVerifier(new BalanceChainValidator(), new StatementTotalsValidator(),
                new SummaryTotalsValidator(), new ColumnAmbiguityValidator(), new RowAccountingValidator(),
                new CreditCardStatementTotalsValidator(), new CreditCardFlowReconciliationValidator(),
                new DescriptionCorruptionValidator());
    }

    private static PdfPreviewGenerator generator(TransactionNormalizer normalizer, RuleEngineService rules) {
        return new PdfPreviewGenerator(new PdfTextExtractor(), new PdfTableLocator(), new PdfMetadataExtractor(),
                normalizer, ProductDiscovery.standard(), new ProductAttributeExtractor(), verifier(), rules);
    }

    @Test
    void withNoAccountNothingIsLookedUp_noCategoryAndNoDuplicateFlag() throws Exception {
        RuleEngineService rules = mock(RuleEngineService.class);

        var result = generator(TransactionNormalizer.withoutAccount(), rules).generateSectionsWithContext(
                null, "statement.pdf", PdfFixtureBuilder.buildChainedOldestFirstRunningBalanceSample(), null);

        verifyNoInteractions(rules);
        StagedAccountSection section = result.sections().get(0);
        assertThat(section.rows()).hasSize(6);
        for (StagedRow row : section.rows()) {
            assertThat(row.suggestedCategory()).isNull();
            assertThat(row.categorySource()).isEqualTo("none");
            assertThat(row.likelyDuplicate()).isFalse();
            assertThat(row.amount()).isNotNull();
        }
    }

    @Test
    void withNoAccountTheAmountsAndChecksMatchAnAccountsReading() throws Exception {
        byte[] pdf = PdfFixtureBuilder.buildChainedOldestFirstRunningBalanceSample();
        var anonymous = generator(TransactionNormalizer.withoutAccount(), null)
                .generateSectionsWithContext(null, "statement.pdf", pdf, null).sections().get(0);

        // The same file read for an account, with categorisation and duplicates stood in for.
        CategorizationService categorization = mock(CategorizationService.class, org.mockito.Answers.RETURNS_MOCKS);
        DuplicateDetector duplicates = mock(DuplicateDetector.class, org.mockito.Answers.RETURNS_MOCKS);
        org.mockito.Mockito.when(duplicates.findMatch(org.mockito.ArgumentMatchers.<com.finora.imports.DuplicateIndex>any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(java.util.Optional.empty());
        RuleEngineService rules = mock(RuleEngineService.class);
        var forAccount = generator(new TransactionNormalizer(categorization, duplicates, rules), rules)
                .generateSectionsWithContext(UUID.randomUUID(), "statement.pdf", pdf, null).sections().get(0);

        assertThat(anonymous.rows()).hasSameSizeAs(forAccount.rows());
        for (int i = 0; i < anonymous.rows().size(); i++) {
            StagedRow a = anonymous.rows().get(i);
            StagedRow b = forAccount.rows().get(i);
            assertThat(a.date()).isEqualTo(b.date());
            assertThat(a.amount()).isEqualByComparingTo(b.amount());
            assertThat(a.type()).isEqualTo(b.type());
            assertThat(a.description()).isEqualTo(b.description());
            assertThat(a.balanceAfter()).isEqualByComparingTo(b.balanceAfter());
        }
        assertThat(anonymous.verification().findings()).isEqualTo(forAccount.verification().findings());
    }

    @Test
    void theRegularNormalizerRefusesToReadWithNoAccount() {
        TransactionNormalizer regular = new TransactionNormalizer(mock(CategorizationService.class),
                mock(DuplicateDetector.class), mock(RuleEngineService.class));

        assertThat(regular.isWithoutAccount()).isFalse();
        assertThat(TransactionNormalizer.withoutAccount().isWithoutAccount()).isTrue();
        assertThatThrownBy(() -> generator(regular, mock(RuleEngineService.class)).generateSectionsWithContext(
                null, "statement.pdf", PdfFixtureBuilder.buildChainedOldestFirstRunningBalanceSample(), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("withoutAccount");
    }
}
