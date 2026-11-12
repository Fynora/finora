package com.finora.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.entity.Category;
import com.finora.entity.User;
import com.finora.repository.CategoryRepository;
import com.finora.repository.RefreshTokenRepository;
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
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** GET /admin/users/{userId}/categories (2026-11-12): every category of one customer, by name --
 *  the choices on User Detail's merchant panel for setting a category on their behalf. */
class AdminUserCategoriesIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    private final ObjectMapper mapper = new ObjectMapper();

    private User createUser(String role) {
        User user = new User();
        user.setEmail("admin-user-categories-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Admin User Categories IT");
        user.setRole(role);
        user.setAccountScope("USER".equals(role) ? User.SCOPE_USER : User.SCOPE_ADMIN);
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private Category category(User owner, String name) {
        Category c = new Category();
        c.setUserId(owner.getId());
        c.setName(name);
        return categoryRepository.save(c);
    }

    private ResponseEntity<String> list(User caller, User customer) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, caller));
        return restTemplate.exchange("/api/v1/admin/users/" + customer.getId() + "/categories", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
    }

    @Test
    void listsEveryCategoryOfThatCustomerOnly_byName() throws Exception {
        User admin = createUser("ADMIN");
        User customer = createUser("USER");
        User someoneElse = createUser("USER");
        category(customer, "zebra rides");
        category(customer, "Apples");
        category(customer, "mangoes");
        category(someoneElse, "Not theirs");

        ResponseEntity<String> response = list(admin, customer);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = mapper.readTree(response.getBody()).get("data");
        List<String> names = new ArrayList<>();
        data.forEach(c -> names.add(c.get("name").asText()));
        assertThat(names).containsExactly("Apples", "mangoes", "zebra rides");
        assertThat(data.get(0).has("id")).isTrue();
    }

    @Test
    void isForAdminsWithMerchantManage_only() {
        User customer = createUser("USER");

        assertThat(list(customer, customer).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
