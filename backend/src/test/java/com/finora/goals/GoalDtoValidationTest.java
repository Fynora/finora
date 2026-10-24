package com.finora.goals;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the validation constraints added to GoalDto.CreateRequest/ContributionRequest --
 * following the same direct-Validator pattern as RegisterRequestValidationTest, no Spring
 * context needed. Covers the gap found during review: the frontend (Goals.tsx) already blocks
 * non-positive amounts and blank names client-side, but nothing enforced this server-side.
 */
class GoalDtoValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    private boolean violatesField(Set<? extends ConstraintViolation<?>> violations, String field) {
        return violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals(field));
    }

    @Test
    void contributionRequest_zeroAmount_isRejected() {
        var req = new GoalDto.ContributionRequest(BigDecimal.ZERO);
        Set<ConstraintViolation<GoalDto.ContributionRequest>> violations = validator.validate(req);
        assertThat(violatesField(violations, "amount")).isTrue();
    }

    @Test
    void contributionRequest_negativeAmount_isRejected() {
        var req = new GoalDto.ContributionRequest(new BigDecimal("-50.00"));
        Set<ConstraintViolation<GoalDto.ContributionRequest>> violations = validator.validate(req);
        assertThat(violatesField(violations, "amount")).isTrue();
    }

    @Test
    void contributionRequest_nullAmount_isRejected() {
        var req = new GoalDto.ContributionRequest(null);
        Set<ConstraintViolation<GoalDto.ContributionRequest>> violations = validator.validate(req);
        assertThat(violatesField(violations, "amount")).isTrue();
    }

    @Test
    void contributionRequest_positiveAmount_passesValidation() {
        var req = new GoalDto.ContributionRequest(new BigDecimal("500.00"));
        Set<ConstraintViolation<GoalDto.ContributionRequest>> violations = validator.validate(req);
        assertThat(violations).isEmpty();
    }

    @Test
    void createRequest_zeroTargetAmount_isRejected() {
        var req = new GoalDto.CreateRequest("Emergency Fund", BigDecimal.ZERO, null, null);
        Set<ConstraintViolation<GoalDto.CreateRequest>> violations = validator.validate(req);
        assertThat(violatesField(violations, "targetAmount")).isTrue();
    }

    @Test
    void createRequest_negativeTargetAmount_isRejected() {
        var req = new GoalDto.CreateRequest("Emergency Fund", new BigDecimal("-1000"), null, null);
        Set<ConstraintViolation<GoalDto.CreateRequest>> violations = validator.validate(req);
        assertThat(violatesField(violations, "targetAmount")).isTrue();
    }

    @Test
    void createRequest_blankName_isRejected() {
        var req = new GoalDto.CreateRequest("   ", new BigDecimal("1000"), null, null);
        Set<ConstraintViolation<GoalDto.CreateRequest>> violations = validator.validate(req);
        assertThat(violatesField(violations, "name")).isTrue();
    }

    @Test
    void createRequest_nullStartingAmount_isAllowed() {
        // A brand-new goal legitimately starts with nothing saved yet.
        var req = new GoalDto.CreateRequest("Emergency Fund", new BigDecimal("1000"), null, null);
        Set<ConstraintViolation<GoalDto.CreateRequest>> violations = validator.validate(req);
        assertThat(violatesField(violations, "currentAmount")).isFalse();
    }

    @Test
    void createRequest_negativeStartingAmount_isRejected() {
        var req = new GoalDto.CreateRequest("Emergency Fund", new BigDecimal("1000"), new BigDecimal("-1"), null);
        Set<ConstraintViolation<GoalDto.CreateRequest>> violations = validator.validate(req);
        assertThat(violatesField(violations, "currentAmount")).isTrue();
    }

    @Test
    void createRequest_wellFormed_passesEveryConstraint() {
        var req = new GoalDto.CreateRequest("Emergency Fund", new BigDecimal("1000"), new BigDecimal("200"), null);
        Set<ConstraintViolation<GoalDto.CreateRequest>> violations = validator.validate(req);
        assertThat(violations).isEmpty();
    }

    // The goals table stores name as VARCHAR(120) and every amount as NUMERIC(14,2). Anything the
    // columns cannot hold used to reach the database, fail there, and come back as a 409 telling
    // the user to "refresh and try again" -- advice that can never work. These pin the limits at
    // the request boundary instead.

    @Test
    void createRequest_nameAtTheColumnLimit_isAccepted() {
        var req = new GoalDto.CreateRequest("a".repeat(120), new BigDecimal("1000"), null, null);
        assertThat(violatesField(validator.validate(req), "name")).isFalse();
    }

    @Test
    void createRequest_nameOneCharacterPastTheColumnLimit_isRejected() {
        var req = new GoalDto.CreateRequest("a".repeat(121), new BigDecimal("1000"), null, null);
        assertThat(violatesField(validator.validate(req), "name")).isTrue();
    }

    @Test
    void createRequest_targetAtTheLargestStorableAmount_isAccepted() {
        var req = new GoalDto.CreateRequest("Big", GoalDto.MAX_AMOUNT, GoalDto.MAX_AMOUNT, null);
        assertThat(validator.validate(req)).isEmpty();
    }

    @Test
    void createRequest_targetOnePaisaPastTheLargestStorableAmount_isRejected() {
        var req = new GoalDto.CreateRequest("Big", new BigDecimal("1000000000000.00"), null, null);
        assertThat(violatesField(validator.validate(req), "targetAmount")).isTrue();
    }

    @Test
    void createRequest_startingAmountPastTheLargestStorableAmount_isRejected() {
        var req = new GoalDto.CreateRequest("Big", new BigDecimal("1000"), new BigDecimal("1000000000000"), null);
        assertThat(violatesField(validator.validate(req), "currentAmount")).isTrue();
    }

    @Test
    void createRequest_hugeExponentTarget_isRejected() {
        var req = new GoalDto.CreateRequest("Huge", new BigDecimal("1E+999999999"), null, null);
        assertThat(violatesField(validator.validate(req), "targetAmount")).isTrue();
    }

    @Test
    void createRequest_amountsFinerThanOnePaisa_areRejected() {
        // The column rounds these to two places, so the goal the user saw in the response
        // (10.004 / 10.001: not complete) differed from the goal stored (10.00 / 10.00: complete).
        var req = new GoalDto.CreateRequest("Round", new BigDecimal("10.004"), new BigDecimal("10.001"), null);
        var violations = validator.validate(req);
        assertThat(violatesField(violations, "targetAmount")).isTrue();
        assertThat(violatesField(violations, "currentAmount")).isTrue();
    }

    @Test
    void createRequest_trailingZerosPastTwoPlaces_areNotFinerThanOnePaisa() {
        var req = new GoalDto.CreateRequest("Zeros", new BigDecimal("100.500"), new BigDecimal("1.000"), null);
        assertThat(validator.validate(req)).isEmpty();
    }

    @Test
    void createRequest_nameWithANulCharacter_isRejected() {
        // Postgres cannot store U+0000 in text at all; it failed in the database as a 409.
        var req = new GoalDto.CreateRequest("Trip\u0000x", new BigDecimal("1000"), null, null);
        assertThat(violatesField(validator.validate(req), "name")).isTrue();
    }

    @Test
    void createRequest_nameWithALineBreakOrTab_isRejected() {
        assertThat(violatesField(validator.validate(
                new GoalDto.CreateRequest("Trip\nGoa", new BigDecimal("1000"), null, null)), "name")).isTrue();
        assertThat(violatesField(validator.validate(
                new GoalDto.CreateRequest("Trip\tGoa", new BigDecimal("1000"), null, null)), "name")).isTrue();
    }

    @Test
    void createRequest_nameWithAccentsAndEmoji_isAccepted() {
        var req = new GoalDto.CreateRequest("Café trip 🏖️ — Goa", new BigDecimal("1000"), null, null);
        assertThat(validator.validate(req)).isEmpty();
    }

    @Test
    void createRequest_targetDateWithAFiveDigitYear_isRejected() {
        // Year 999999999 failed in the database (409); year 10000 saved as "+10000-01-01", which
        // the web's ISO string comparison sorts before every real date ("Past due").
        var far = new GoalDto.CreateRequest("Far", new BigDecimal("1000"), null, LocalDate.of(10000, 1, 1));
        var farther = new GoalDto.CreateRequest("Far", new BigDecimal("1000"), null, LocalDate.of(999_999_999, 12, 31));
        assertThat(violatesField(validator.validate(far), "targetDate")).isTrue();
        assertThat(violatesField(validator.validate(farther), "targetDate")).isTrue();
    }

    @Test
    void createRequest_targetDateAtYearZeroOrBefore_isRejected() {
        var yearZero = new GoalDto.CreateRequest("Old", new BigDecimal("1000"), null, LocalDate.of(0, 6, 1));
        var bc = new GoalDto.CreateRequest("Old", new BigDecimal("1000"), null, LocalDate.of(-5, 6, 1));
        assertThat(violatesField(validator.validate(yearZero), "targetDate")).isTrue();
        assertThat(violatesField(validator.validate(bc), "targetDate")).isTrue();
    }

    @Test
    void createRequest_targetDateAtEitherEndOfFourDigitYears_isAccepted() {
        assertThat(validator.validate(new GoalDto.CreateRequest(
                "Edge", new BigDecimal("1000"), null, LocalDate.of(9999, 12, 31)))).isEmpty();
        assertThat(validator.validate(new GoalDto.CreateRequest(
                "Edge", new BigDecimal("1000"), null, LocalDate.of(1, 1, 1)))).isEmpty();
    }

    @Test
    void contributionRequest_atTheLargestStorableAmount_isAccepted() {
        assertThat(validator.validate(new GoalDto.ContributionRequest(GoalDto.MAX_AMOUNT))).isEmpty();
    }

    @Test
    void contributionRequest_pastTheLargestStorableAmount_isRejected() {
        var req = new GoalDto.ContributionRequest(new BigDecimal("1000000000000"));
        assertThat(violatesField(validator.validate(req), "amount")).isTrue();
    }

    @Test
    void contributionRequest_hugeExponent_isRejected() {
        // Used to pass validation and then throw ArithmeticException inside BigDecimal.add: a 500.
        var req = new GoalDto.ContributionRequest(new BigDecimal("1E+999999999"));
        assertThat(violatesField(validator.validate(req), "amount")).isTrue();
    }

    @Test
    void contributionRequest_finerThanOnePaisa_isRejected() {
        var req = new GoalDto.ContributionRequest(new BigDecimal("0.015"));
        assertThat(violatesField(validator.validate(req), "amount")).isTrue();
    }
}
