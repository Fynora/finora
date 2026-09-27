package com.finora.imports.refresh;

import com.finora.config.BuildVersionResolver;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.StatementRefreshPreviewRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StatementRefreshSummaryServiceTest {

    private final StatementRefreshPreviewRepository previews = mock(StatementRefreshPreviewRepository.class);
    private final StatementImportRepository statements = mock(StatementImportRepository.class);
    private final BuildVersionResolver build = mock(BuildVersionResolver.class);
    private final StatementRefreshSummaryService service = new StatementRefreshSummaryService(previews, statements, build);

    private static StatementRefreshPreviewRepository.StatusTotals totals(String status, long statements, long users,
                                                                          long changed, long removed) {
        StatementRefreshPreviewRepository.StatusTotals t = mock(StatementRefreshPreviewRepository.StatusTotals.class);
        when(t.getStatus()).thenReturn(status);
        when(t.getStatements()).thenReturn(statements);
        when(t.getUsers()).thenReturn(users);
        when(t.getRowsChanged()).thenReturn(changed);
        when(t.getRowsRemoved()).thenReturn(removed);
        return t;
    }

    @Test
    void summarisesTheRunningBuild_byDefault() {
        when(build.currentCommit()).thenReturn("abc1234");
        List<StatementRefreshPreviewRepository.StatusTotals> rows = List.of(
                totals("CHANGES", 5, 2, 7, 1), totals("NO_CHANGES", 20, 9, 0, 0));
        when(previews.totalsFor("abc1234")).thenReturn(rows);
        when(statements.countAwaitingRefreshCheck("abc1234")).thenReturn(3L);

        var summary = service.summary(null);

        assertThat(summary.parserVersion()).isEqualTo("abc1234");
        assertThat(summary.statementsAwaitingCheck()).isEqualTo(3);
        assertThat(summary.byStatus()).extracting(StatementRefreshSummaryService.StatusTotals::status,
                        StatementRefreshSummaryService.StatusTotals::statements,
                        StatementRefreshSummaryService.StatusTotals::rowsChanged)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("CHANGES", 5L, 7L),
                        org.assertj.core.groups.Tuple.tuple("NO_CHANGES", 20L, 0L));
    }

    @Test
    void anExplicitBuildIsSummarisedInstead() {
        when(build.currentCommit()).thenReturn("abc1234");
        when(previews.totalsFor("old5678")).thenReturn(List.of());

        assertThat(service.summary("old5678").parserVersion()).isEqualTo("old5678");
    }

    @Test
    void withNoBuildVersion_thereIsNothingToSummarise() {
        when(build.currentCommit()).thenReturn(null);

        var summary = service.summary(null);

        assertThat(summary.parserVersion()).isNull();
        assertThat(summary.byStatus()).isEmpty();
    }
}
