package com.finora.architecture.fixtures;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Deliberately vulnerable fixture -- NOT production code; see {@link UnguardedAdminControllerFixture}
 * for why these exist and why they take no constructor dependencies.
 *
 * <p>A controller with no class-level {@code @RequestMapping} at all, each method carrying its full
 * path. The admin rule once skipped such a class entirely. Do not add the missing annotation: the
 * unguarded method is the test input.
 */
@RestController
public class NoBasePathAdminControllerFixture {

    /** The bug shape: a full admin path on the method, with no authorization annotation. */
    @GetMapping("/api/v1/admin/no-base-path-fixture")
    public String unguardedHandler() {
        return "any authenticated user can reach this";
    }

    /** A correctly-guarded sibling: must not be flagged. */
    @PostMapping(path = "/api/v1/admin/no-base-path-fixture")
    @PreAuthorize("hasAuthority('USER_VIEW')")
    public String guardedHandler() {
        return "properly gated";
    }
}
