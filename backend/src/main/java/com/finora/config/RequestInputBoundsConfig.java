package com.finora.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.std.NumberDeserializers;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.finora.validation.MoneyLimits;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Outer bounds on numbers and dates that arrive in an HTTP request, checked while the request is
 * read, so no out-of-range value reaches the database driver or BigDecimal arithmetic.
 *
 * <p>Numbers: see {@link MoneyLimits} for what the driver did with them (a huge value saved as 0,
 * a filter silently ignored, 500s). Dates: LocalDate allows years far past 9999; year 999999999
 * failed in the database as a 409, and year 10000 saved as "+10000-01-01", which clients misread.
 *
 * <p>Only on the MVC request side. The application's shared ObjectMapper also reads JSON this app
 * stored itself (staged import rows can hold whatever a statement printed), so it is copied for
 * the HTTP converter rather than changed.
 *
 * <p>Not query parameters: a Converter cannot refuse one. Measured -- Spring's TypeConverterDelegate
 * treats a failed conversion as a cue to try the default PropertyEditor, whose CustomNumberEditor
 * parses the number anyway and the refusal is swallowed. The only BigDecimal query parameters
 * (the ledger's amountMin/amountMax) are checked in TransactionService.search instead.
 */
@Configuration
public class RequestInputBoundsConfig implements WebMvcConfigurer {

    /** Thrown while reading a request body; GlobalExceptionHandler turns it into a 400 naming the field. */
    public static class RequestValueOutOfBoundsException extends InvalidFormatException {
        RequestValueOutOfBoundsException(JsonParser p, String msg, Object value, Class<?> targetType) {
            super(p, msg, value, targetType);
        }
    }

    public static final String NUMBER_OUT_OF_BOUNDS_MESSAGE = "is too large, or has too many decimal places, to store";
    static final String DATE_OUT_OF_BOUNDS = "must be a date between 0001-01-01 and 9999-12-31";

    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        for (int i = 0; i < converters.size(); i++) {
            // Exact class only: a subclass is someone's deliberate choice, and replacing (not
            // mutating) keeps the shared converter instance other clients may hold unchanged.
            if (converters.get(i).getClass() == MappingJackson2HttpMessageConverter.class) {
                ObjectMapper shared = ((MappingJackson2HttpMessageConverter) converters.get(i)).getObjectMapper();
                converters.set(i, new MappingJackson2HttpMessageConverter(withRequestBounds(shared)));
            }
        }
    }

    /** A copy of {@code mapper} that refuses out-of-bounds numbers and dates while reading. */
    static ObjectMapper withRequestBounds(ObjectMapper mapper) {
        SimpleModule bounds = new SimpleModule("request-input-bounds");
        bounds.addDeserializer(BigDecimal.class, new BoundedBigDecimalDeserializer());
        bounds.addDeserializer(LocalDate.class, new BoundedLocalDateDeserializer());
        return mapper.copy().registerModule(bounds);
    }

    static final class BoundedBigDecimalDeserializer extends NumberDeserializers.BigDecimalDeserializer {
        @Override
        public BigDecimal deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            BigDecimal value = super.deserialize(p, ctxt);
            if (!MoneyLimits.withinRequestBounds(value)) {
                throw new RequestValueOutOfBoundsException(p, NUMBER_OUT_OF_BOUNDS_MESSAGE, null, BigDecimal.class);
            }
            return value;
        }
    }

    static final class BoundedLocalDateDeserializer extends StdDeserializer<LocalDate> {
        BoundedLocalDateDeserializer() { super(LocalDate.class); }

        @Override
        public LocalDate deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            LocalDate value = LocalDateDeserializer.INSTANCE.deserialize(p, ctxt);
            if (value != null && (value.getYear() < 1 || value.getYear() > 9999)) {
                throw new RequestValueOutOfBoundsException(p, DATE_OUT_OF_BOUNDS, null, LocalDate.class);
            }
            return value;
        }
    }
}
