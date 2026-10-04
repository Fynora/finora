package com.finora.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultCategoriesTest {

    @Test
    void aDefaultNameIsFoundInItsSeededSpelling_whateverItsCaseOrSpacing() {
        assertThat(DefaultCategories.canonical("Groceries")).contains("Groceries");
        assertThat(DefaultCategories.canonical("  groceries ")).contains("Groceries");
        assertThat(DefaultCategories.canonical("GIFTS & DONATIONS")).contains("Gifts & Donations");
    }

    @Test
    void aNameAUserMadeThemselves_isNotADefault() {
        assertThat(DefaultCategories.canonical("Quick Bites")).isEmpty();
        assertThat(DefaultCategories.canonical("Grocery")).as("a near miss is not a match").isEmpty();
        assertThat(DefaultCategories.canonical("")).isEmpty();
        assertThat(DefaultCategories.canonical(null)).isEmpty();
    }

    @Test
    void everyNameTheEngineCanSuggestOnItsOwn_isADefault() {
        // The fixed answers in CategorizationService's waterfall. A built-in answer outside the
        // default list would create that category in every account it reached.
        assertThat(DefaultCategories.canonical(com.finora.service.CategorizationService.P2P_CATEGORY)).isPresent();
        assertThat(DefaultCategories.canonical("Other")).isPresent();
        assertThat(DefaultCategories.canonical("Investments")).isPresent();
        assertThat(DefaultCategories.iconAndColor()).hasSize(28);
    }
}
