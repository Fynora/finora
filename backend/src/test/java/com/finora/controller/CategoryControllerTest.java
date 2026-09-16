package com.finora.controller;

import com.finora.entity.Category;
import com.finora.repository.CategoryRepository;
import com.finora.security.CurrentUser;
import com.finora.service.CategoryService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CategoryControllerTest {

    @Test
    void list_includesAiCreationReasonWhenPresent() {
        CategoryRepository categoryRepository = mock(CategoryRepository.class);
        CategoryService categoryService = mock(CategoryService.class);
        CurrentUser currentUser = mock(CurrentUser.class);
        UUID userId = UUID.randomUUID();
        when(currentUser.id()).thenReturn(userId);

        Category aiCreated = new Category();
        ReflectionTestUtils.setField(aiCreated, "id", UUID.randomUUID());
        aiCreated.setUserId(userId);
        aiCreated.setName("Pet Care");
        aiCreated.setAiCreationReason("Pet supplies retailer, no existing match");
        when(categoryRepository.findByUserId(userId)).thenReturn(List.of(aiCreated));

        var controller = new CategoryController(categoryRepository, categoryService, currentUser);

        var response = controller.list();

        assertThat(response.data().get(0).aiCreationReason()).isEqualTo("Pet supplies retailer, no existing match");
    }
}
