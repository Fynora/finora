package com.finora.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.entity.Permission;
import com.finora.entity.Role;
import com.finora.entity.User;
import com.finora.repository.PermissionRepository;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.RoleRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.testsupport.TestSessions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Audit A-01 (2026-11-20). Managing roles needs ROLE_MANAGE, and nothing checked that the person
 * granting holds what they grant. As seeded only SUPER_ADMIN has ROLE_MANAGE, so this bites the
 * day a Super Admin hands role management to a lesser role: that role's holder could grant
 * SUPER_ADMIN to a colleague, or add every other permission to a colleague's role, or build a new
 * role out of permissions they do not have.
 *
 * <p>The rule now: you cannot grant what you do not hold. A Super Admin holds everything by
 * definition, including a permission created a moment ago that no role carries yet.
 *
 * <p>Every test here acts as a "delegate": an admin-portal account whose only permission is
 * ROLE_MANAGE, through a role made for the test.
 */
class RoleGrantNeedsHeldPermissionsIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PermissionRepository permissionRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private com.finora.service.RoleService roleService;
    private final ObjectMapper mapper = new ObjectMapper();

    private User adminPortalAccount(String legacyRole) {
        User user = new User();
        user.setEmail("grant-rule-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Grant Rule Test User");
        user.setRole(legacyRole);
        user.setAccountScope(User.SCOPE_ADMIN);
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private Permission permission(String name) {
        return permissionRepository.findAll().stream()
                .filter(p -> p.getName().equals(name)).findFirst().orElseThrow();
    }

    private Role roleCarrying(String... permissionNames) {
        Role role = new Role();
        role.setName("GRANT_RULE_" + UUID.randomUUID().toString().replace("-", "").toUpperCase());
        role.setDescription("Made by RoleGrantNeedsHeldPermissionsIT");
        for (String name : permissionNames) role.getPermissions().add(permission(name));
        return roleRepository.save(role);
    }

    /** An admin-portal account that can manage roles and holds nothing else. */
    private User delegate() {
        User user = adminPortalAccount("USER");
        user.getRoles().add(roleCarrying("ROLE_MANAGE"));
        return userRepository.save(user);
    }

    private HttpHeaders bearerFor(User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private ResponseEntity<String> assign(User actor, User target, String roleName) {
        return restTemplate.exchange("/api/v1/admin/users/" + target.getId() + "/roles/" + roleName,
                HttpMethod.POST, new HttpEntity<>(bearerFor(actor)), String.class);
    }

    private ResponseEntity<String> addPermission(User actor, Role role, String permissionName) {
        return restTemplate.exchange(
                "/api/v1/admin/roles/" + role.getId() + "/permissions/" + permission(permissionName).getId(),
                HttpMethod.POST, new HttpEntity<>(bearerFor(actor)), String.class);
    }

    private java.util.List<String> rolesOf(User user) {
        return userRepository.findById(user.getId()).orElseThrow().getRoles().stream().map(Role::getName).toList();
    }

    private java.util.List<String> permissionsOf(Role role) {
        return roleRepository.findById(role.getId()).orElseThrow().getPermissions().stream()
                .map(Permission::getName).toList();
    }

    // ---- granting a role ----

    @Test
    void aDelegate_cannotGrantSuperAdminToAColleague() throws Exception {
        User delegate = delegate();
        User colleague = adminPortalAccount("USER");

        ResponseEntity<String> response = assign(delegate, colleague, "SUPER_ADMIN");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(mapper.readTree(response.getBody()).get("message").asText())
                .contains("permissions your own account does not have");
        assertThat(rolesOf(colleague)).isEmpty();
    }

    @Test
    void aDelegate_cannotGrantARoleCarryingAnyPermissionTheyLack() {
        User delegate = delegate();
        User colleague = adminPortalAccount("USER");
        // ROLE_MANAGE they hold; USER_DELETE they do not. One missing permission is enough.
        Role mixed = roleCarrying("ROLE_MANAGE", "USER_DELETE");

        assertThat(assign(delegate, colleague, "ADMIN").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(assign(delegate, colleague, mixed.getName()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(rolesOf(colleague)).isEmpty();
    }

    @Test
    void aDelegate_canGrantARoleWhosePermissionsTheyAllHold_andOneWithNone() {
        User delegate = delegate();
        User colleague = adminPortalAccount("USER");
        Role held = roleCarrying("ROLE_MANAGE");
        Role empty = roleCarrying();

        assertThat(assign(delegate, colleague, held.getName()).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(assign(delegate, colleague, empty.getName()).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rolesOf(colleague)).containsExactlyInAnyOrder(held.getName(), empty.getName());
    }

    // ---- adding a permission to a role: the same thing by another route ----

    @Test
    void aDelegate_cannotAddAPermissionTheyLackToAColleaguesRole() throws Exception {
        User delegate = delegate();
        Role colleaguesRole = roleCarrying();

        ResponseEntity<String> response = addPermission(delegate, colleaguesRole, "USER_DELETE");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(mapper.readTree(response.getBody()).get("message").asText())
                .contains("your own account does not have");
        assertThat(permissionsOf(colleaguesRole)).isEmpty();
    }

    @Test
    void aDelegate_cannotBuildANewRoleOutOfPermissionsTheyLack() throws Exception {
        User delegate = delegate();
        String name = "GRANT_RULE_BUILT_" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
        ResponseEntity<String> created = restTemplate.exchange("/api/v1/admin/roles", HttpMethod.POST,
                new HttpEntity<>("{\"name\":\"" + name + "\",\"description\":\"built\"}", bearerFor(delegate)), String.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        Role built = roleRepository.findByName(name).orElseThrow();

        assertThat(addPermission(delegate, built, "USER_DELETE").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(addPermission(delegate, built, "AUDIT_VIEW").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(permissionsOf(built)).isEmpty();
    }

    @Test
    void aDelegate_canAddAPermissionTheyHoldToARoleTheyDoNotHold() {
        User delegate = delegate();
        Role other = roleCarrying();

        assertThat(addPermission(delegate, other, "ROLE_MANAGE").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(permissionsOf(other)).containsExactly("ROLE_MANAGE");
    }

    // ---- a Super Admin holds everything ----

    @Test
    void aSuperAdmin_canGrantSuperAdmin_andAttachAPermissionNoRoleCarriesYet() throws Exception {
        User superAdmin = adminPortalAccount("SUPER_ADMIN");
        User colleague = adminPortalAccount("USER");
        String permissionName = "GRANT_RULE_NEW_" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
        ResponseEntity<String> created = restTemplate.exchange("/api/v1/admin/permissions", HttpMethod.POST,
                new HttpEntity<>("{\"name\":\"" + permissionName + "\",\"description\":\"new\"}", bearerFor(superAdmin)),
                String.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        Role role = roleCarrying();

        assertThat(assign(superAdmin, colleague, "SUPER_ADMIN").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(addPermission(superAdmin, role, permissionName).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(permissionsOf(role)).containsExactly(permissionName);
    }

    // ---- first-run setup ----

    /** The bootstrap account holds SYSTEM_INITIALIZE and nothing else, and its one job is to hand
     *  SUPER_ADMIN to the first real administrator. The general rule would refuse it, so setup has
     *  a door of its own -- and the general door stays shut to the same account. */
    @Test
    void theBootstrapAccount_handsOverSuperAdminThroughSetupsOwnDoorOnly() {
        User bootstrap = adminPortalAccount("BOOTSTRAP_ADMIN");
        bootstrap.getRoles().add(roleRepository.findByName("BOOTSTRAP_ADMIN").orElseThrow());
        bootstrap = userRepository.save(bootstrap);
        User firstAdmin = adminPortalAccount("USER");
        User someoneElse = adminPortalAccount("USER");
        UUID bootstrapId = bootstrap.getId();

        roleService.assignFirstSuperAdmin(bootstrapId, firstAdmin.getId());

        assertThat(rolesOf(firstAdmin)).containsExactly("SUPER_ADMIN");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> roleService.assignRole(bootstrapId, someoneElse.getId(), "SUPER_ADMIN"))
                .isInstanceOf(com.finora.exception.ApiException.class)
                .hasMessageContaining("permissions your own account does not have");
        assertThat(rolesOf(someoneElse)).isEmpty();
    }

    /** SUPER_ADMIN held through user_roles alone, with the legacy column left at its default. */
    @Test
    void aSuperAdminByExplicitGrantOnly_isASuperAdminToo() {
        User superAdmin = adminPortalAccount("USER");
        superAdmin.getRoles().add(roleRepository.findByName("SUPER_ADMIN").orElseThrow());
        superAdmin = userRepository.save(superAdmin);
        User colleague = adminPortalAccount("USER");

        assertThat(assign(superAdmin, colleague, "SUPER_ADMIN").getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
