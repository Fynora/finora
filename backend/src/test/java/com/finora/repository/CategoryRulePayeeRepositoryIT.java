package com.finora.repository;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.CategoryRule;
import com.finora.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The saved answers of the recurring-payment question: one PAYEE rule per user and payee (V248). */
class CategoryRulePayeeRepositoryIT extends AbstractIntegrationTest {

    @Autowired private CategoryRuleRepository ruleRepository;
    @Autowired private UserRepository userRepository;

    private UUID newUser() {
        User user = new User();
        user.setEmail("payee-rule-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Payee Rule IT User");
        user.setPhoneVerified(true);
        return userRepository.save(user).getId();
    }

    @Test
    @Transactional
    void insertingTheSamePayeeTwice_ignoringCase_leavesOneRule() {
        UUID userId = newUser();

        int first = ruleRepository.insertPayeeRuleIfAbsent(UUID.randomUUID(), userId, "sample landlord", "Rent",
                new BigDecimal("8000.00"), new BigDecimal("12000.00"));
        int second = ruleRepository.insertPayeeRuleIfAbsent(UUID.randomUUID(), userId, "SAMPLE LANDLORD", "Loan EMI",
                new BigDecimal("1.00"), new BigDecimal("2.00"));

        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        CategoryRule rule = ruleRepository.findUserPayeeRule(userId, "Sample Landlord").orElseThrow();
        assertThat(rule.getActionValue()).isEqualTo("Rent");
        assertThat(rule.getAmountMin()).isEqualByComparingTo("8000.00");
        assertThat(rule.getAmountMax()).isEqualByComparingTo("12000.00");
        assertThat(rule.getField()).isEqualTo(CategoryRule.Field.PAYEE);
        assertThat(rule.getOperator()).isEqualTo(CategoryRule.Operator.EQUALS);
        assertThat(rule.getActionType()).isEqualTo(CategoryRule.ActionType.ASSIGN_CATEGORY);
        assertThat(rule.isEnabled()).isTrue();
    }

    @Test
    @Transactional
    void payeeRulesAreListedPerUser_andOtherRulesAreNot() {
        UUID userId = newUser();
        UUID otherUser = newUser();
        ruleRepository.insertPayeeRuleIfAbsent(UUID.randomUUID(), userId, "sample landlord", "Rent", null, null);
        ruleRepository.insertPayeeRuleIfAbsent(UUID.randomUUID(), otherUser, "sample landlord", "Rent", null, null);
        CategoryRule description = new CategoryRule();
        description.setUserId(userId);
        description.setScope(CategoryRule.Scope.USER);
        description.setField(CategoryRule.Field.DESCRIPTION);
        description.setOperator(CategoryRule.Operator.CONTAINS);
        description.setComparisonValue("sample landlord");
        description.setActionType(CategoryRule.ActionType.ASSIGN_CATEGORY);
        description.setActionValue("Rent");
        ruleRepository.save(description);

        assertThat(ruleRepository.findUserPayeeRules(userId)).hasSize(1)
                .allSatisfy(r -> assertThat(r.getUserId()).isEqualTo(userId));
        assertThat(ruleRepository.findUserPayeeRule(userId, "another payee")).isEmpty();
    }
}
