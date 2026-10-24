package com.finora.goals;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.LocalDate;

/**
 * A date whose year is 1 through 9999, so it serialises as a plain "YYYY-MM-DD". Null passes.
 *
 * <p>LocalDate allows years far outside that. Year 999999999 failed in the database and came back
 * as a 409 "refresh and try again"; year 10000 saved, as "+10000-01-01", which the web's ISO
 * string comparison sorts before every real date. Year 0 and earlier serialise with a sign too.
 */
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = FourDigitYear.Validator.class)
public @interface FourDigitYear {
    String message() default "must be a date between 0001-01-01 and 9999-12-31";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<FourDigitYear, LocalDate> {
        @Override
        public boolean isValid(LocalDate value, ConstraintValidatorContext context) {
            return value == null || (value.getYear() >= 1 && value.getYear() <= 9999);
        }
    }
}
