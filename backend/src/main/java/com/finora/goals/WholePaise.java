package com.finora.goals;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.math.BigDecimal;

/**
 * An amount with no part smaller than one paisa: at most two decimal places once trailing zeros
 * are ignored, so 100.500 passes and 100.505 does not. Null passes; pair with @NotNull when the
 * field is required.
 *
 * <p>Not {@code @Digits(fraction = 2)}: Hibernate Validator counts the scale as written, so it
 * rejects 100.500 -- an amount that is exactly representable in paise.
 */
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = WholePaise.Validator.class)
public @interface WholePaise {
    String message() default "can have at most 2 decimal places";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<WholePaise, BigDecimal> {
        @Override
        public boolean isValid(BigDecimal value, ConstraintValidatorContext context) {
            // stripTrailingZeros never expands the number, so a huge exponent is cheap here.
            return value == null || value.stripTrailingZeros().scale() <= 2;
        }
    }
}
