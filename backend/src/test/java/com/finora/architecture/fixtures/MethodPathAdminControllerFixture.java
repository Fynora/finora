package com.finora.architecture.fixtures;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Deliberately vulnerable fixture -- NOT production code; see {@link UnguardedAdminControllerFixture}
 * for why these exist and why they take no constructor dependencies.
 *
 * <p>The shape {@code CapabilityCoverageController} uses: the class maps a consumer prefix
 * ({@code /api/v1}) and one method adds {@code /admin/...}. The admin rule once looked only at the
 * class path, so an unguarded handler written this way passed the build. Do not add the missing
 * annotation: the unguarded admin method is the test input.
 */
@RestController
@RequestMapping("/api/v1")
public class MethodPathAdminControllerFixture {

    /** The bug shape: an admin route declared on the method, with no authorization annotation. */
    @GetMapping("/admin/method-path-fixture")
    public String unguardedAdminHandler() {
        return "any authenticated user can reach this";
    }

    /** A correctly-guarded admin sibling: must not be flagged. */
    @PostMapping("/admin/method-path-fixture")
    @PreAuthorize("hasAuthority('USER_VIEW')")
    public String guardedAdminHandler() {
        return "properly gated";
    }

    /** A consumer route on the same class: user-scoped, so no authority is required and it must not be flagged. */
    @GetMapping("/method-path-fixture")
    public String consumerHandler() {
        return "a user's own data";
    }
}
