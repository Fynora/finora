package com.finora.controller;

import com.finora.dto.CategoryDto;
import com.finora.entity.Category;
import com.finora.service.CategoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminUserCategoriesControllerTest {

    private CategoryService categoryService;
    private AdminUserCategoriesController controller;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        categoryService = mock(CategoryService.class);
        controller = new AdminUserCategoriesController(categoryService);
    }

    @Test
    void aiCreated_returnsWhatCategoryServiceFindAiCreatedReturns() {
        Category aiCreated = new Category();
        aiCreated.setName("Pet Care");
        aiCreated.setAiCreationReason("Pet supplies retailer, no existing match");
        when(categoryService.findAiCreated(userId)).thenReturn(List.of(aiCreated));

        List<CategoryDto> result = controller.aiCreated(userId).data();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).name()).isEqualTo("Pet Care");
    }
}
