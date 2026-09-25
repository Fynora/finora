package com.finora.security;

import com.finora.AbstractIntegrationTest;
import com.finora.dto.AdminDtos.CreatePermissionRequest;
import com.finora.dto.AdminDtos.CreateRoleRequest;
import com.finora.dto.PermissionDto;
import com.finora.dto.RoleDto;
import com.finora.entity.User;
import com.finora.repository.UserRepository;
import com.finora.service.RoleService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Audit F-12 (2026-09-24): the role/permission half of every authenticated request's principal
 * resolution is cached per user in Redis. A cache is only as safe as its invalidation, so the
 * tests here are about what makes an entry go away, against the real Redis container -- a mocked
 * {@code CacheManager} would prove nothing about {@code @Cacheable} itself.
 */
class UserAuthorityCacheIT extends AbstractIntegrationTest {

    @Autowired private CurrentUserDetailsService userDetailsService;
    @Autowired private UserAuthorityCache userAuthorityCache;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleService roleService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private User adminScopeUser(String role) {
        User user = new User();
        user.setEmail("authority-cache-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant");
        user.setFullName("Authority Cache Test");
        user.setRole(role);
        user.setAccountScope(User.SCOPE_ADMIN);
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private Set<String> authoritiesOf(UUID userId) {
        UserDetails details = userDetailsService.loadUserByUsername(userId.toString());
        return details.getAuthorities().stream().map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
    }

    @Test
    void authoritiesAreServedFromTheCacheUntilTheEntryIsEvicted() {
        User user = adminScopeUser("USER");
        assertThat(authoritiesOf(user.getId())).contains("ROLE_USER", "PORTAL_ADMIN").doesNotContain("ROLE_ADMIN");

        // A grant written BEHIND the service layer -- no eviction. The user row (and its EAGER
        // roles collection) is reloaded fresh on every call, so the only way the next read can
        // still lack ROLE_ADMIN is if the authority set genuinely came from the cache.
        jdbcTemplate.update("INSERT INTO user_roles (user_id, role_id) "
                + "SELECT ?, id FROM roles WHERE name = 'ADMIN'", user.getId());
        assertThat(authoritiesOf(user.getId()))
                .as("without an eviction the cached set is what every request sees")
                .doesNotContain("ROLE_ADMIN");

        userAuthorityCache.evict(user.getId());
        assertThat(authoritiesOf(user.getId()))
                .as("an eviction makes the next request recompute from the database")
                .contains("ROLE_ADMIN");
    }

    @Test
    void grantsAndRevocationsThroughRoleServiceAreVisibleOnTheVeryNextRequest() {
        User actingAdmin = adminScopeUser("SUPER_ADMIN");
        User user = adminScopeUser("USER");
        assertThat(authoritiesOf(user.getId())).doesNotContain("ROLE_ADMIN");

        roleService.assignRole(actingAdmin.getId(), user.getId(), "ADMIN");
        assertThat(authoritiesOf(user.getId()))
                .as("assignRole evicts after commit; no request should wait out the TTL for a grant")
                .contains("ROLE_ADMIN");

        roleService.revokeRole(actingAdmin.getId(), user.getId(), "ADMIN");
        assertThat(authoritiesOf(user.getId()))
                .as("a revocation must stop working immediately -- this is the case that matters")
                .doesNotContain("ROLE_ADMIN");
    }

    @Test
    void changingARolesPermissionsRefreshesEveryHolderOfThatRole() {
        User actingAdmin = adminScopeUser("SUPER_ADMIN");
        User holder = adminScopeUser("USER");
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        RoleDto role = roleService.createRole(actingAdmin.getId(),
                new CreateRoleRequest("CACHE_IT_ROLE_" + suffix, "authority cache test"));
        PermissionDto permission = roleService.createPermission(actingAdmin.getId(),
                new CreatePermissionRequest("CACHE_IT_PERMISSION_" + suffix, "authority cache test"));
        try {
            roleService.assignRole(actingAdmin.getId(), holder.getId(), role.name());
            assertThat(authoritiesOf(holder.getId())).contains("ROLE_" + role.name()).doesNotContain(permission.name());

            // Keyed by user, but the change is to the role: the whole cache is dropped so the
            // holder's next request carries the new permission.
            roleService.addPermissionToRole(actingAdmin.getId(), role.id(), permission.id());
            assertThat(authoritiesOf(holder.getId())).contains(permission.name());

            roleService.removePermissionFromRole(actingAdmin.getId(), role.id(), permission.id());
            assertThat(authoritiesOf(holder.getId())).doesNotContain(permission.name());
        } finally {
            // roles and permissions are global tables shared by every IT in this JVM.
            roleService.revokeRole(actingAdmin.getId(), holder.getId(), role.name());
            roleService.deleteRole(actingAdmin.getId(), role.id());
            roleService.deletePermission(actingAdmin.getId(), permission.id());
        }
    }

    @Test
    void aRedisOutageFallsBackToComputingAuthoritiesFromTheDatabase() {
        User user = adminScopeUser("ADMIN");
        REDIS_PROXY.setConnectionCut(true);
        try {
            assertThat(authoritiesOf(user.getId()))
                    .as("a broken cache must read as a miss, never as a failed request")
                    .contains("ROLE_ADMIN", "PORTAL_ADMIN");
        } finally {
            REDIS_PROXY.setConnectionCut(false);
        }
    }
}
