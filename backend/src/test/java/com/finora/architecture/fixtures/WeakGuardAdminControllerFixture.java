package com.finora.architecture.fixtures;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Deliberately vulnerable fixture -- NOT production code; see {@link UnguardedAdminControllerFixture}
 * for why these exist and why they take no constructor dependencies.
 *
 * <p>Guards that exist but that an ordinary customer can meet. A method's own {@code @PreAuthorize}
 * replaces the class's, so a strong class guard does not save a weak method guard. Do not
 * "fix" the weak guards: they are the test input.
 */
@RestController
@RequestMapping("/api/v1/admin/weak-guard-fixture")
@PreAuthorize("hasAuthority('USER_VIEW')")
public class WeakGuardAdminControllerFixture {

    /** Replaces the class guard with one every signed-in account meets. */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public String anySignedInAccount() {
        return "any signed-in customer can reach this";
    }

    /** The customer role itself. */
    @PostMapping
    @PreAuthorize("hasRole('USER')")
    public String customerRole() {
        return "any customer can reach this";
    }

    /** Safe as written, but 'or' cannot be proved safe in general: the rule asks for hasAnyAuthority. */
    @PatchMapping
    @PreAuthorize("hasAuthority('USER_VIEW') or hasAuthority('AUDIT_VIEW')")
    public String eitherPermission() {
        return "should be hasAnyAuthority('USER_VIEW', 'AUDIT_VIEW')";
    }

    /** No guard of its own: the class guard applies, so it must not be flagged. */
    @DeleteMapping
    public String classGuardApplies() {
        return "properly gated by the class";
    }
}
