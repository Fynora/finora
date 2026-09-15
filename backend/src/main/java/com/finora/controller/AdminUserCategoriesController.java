package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.dto.CategoryDto;
import com.finora.service.CategoryService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Support-assisted visibility into what Fynora's AI has created on a user's behalf -- same
 * thin-proxy, same MERCHANT_MANAGE-reuse pattern as AdminUserLearningController. Read-only.
 * Goes through CategoryService rather than CategoryRepository directly -- this repo's
 * LayerDependencyDirectionTest forbids a controller reaching past its service into a repository.
 */
@RestController
@RequestMapping("/api/v1/admin/users/{userId}/categories")
@PreAuthorize("hasAuthority('MERCHANT_MANAGE')")
public class AdminUserCategoriesController {

    private final CategoryService categoryService;

    public AdminUserCategoriesController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @GetMapping("/ai-created")
    public ApiResponse<List<CategoryDto>> aiCreated(@PathVariable UUID userId) {
        var categories = categoryService.findAiCreated(userId).stream()
                .map(c -> new CategoryDto(c.getId(), c.getName(), c.isSystem(), c.getIcon(), c.getColor(), c.getAiCreationReason()))
                .toList();
        return ApiResponse.ok(categories);
    }
}
