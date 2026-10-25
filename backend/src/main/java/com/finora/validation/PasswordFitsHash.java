package com.finora.validation;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.charset.StandardCharsets;

/**
 * A password BCrypt can hash: at most {@value #MAX_BYTES} bytes as UTF-8. Null passes (pair it with
 * {@code @NotBlank}).
 *
 * <p>{@code @Size(max = 72)} counts characters, and BCrypt counts bytes. A Hindi letter is 3 bytes
 * and an emoji 4, so 25 Hindi letters (75 bytes) passed the size check and then failed inside the
 * encoder ("password cannot be more than 72 bytes"), which reached the user as "One of the
 * request's parameters is not valid" on sign-up, reset and change alike. Checked here, before the
 * encoder, with a message that says what to do.
 */
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = PasswordFitsHash.Validator.class)
public @interface PasswordFitsHash {

    int MAX_BYTES = 72;

    String message() default "That password is too long. Passwords can be up to 72 bytes, and each Hindi letter "
            + "or emoji counts as 3 or 4, so use fewer of them or mix in English letters and numbers.";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<PasswordFitsHash, String> {
        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            return value == null || value.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES;
        }
    }
}
