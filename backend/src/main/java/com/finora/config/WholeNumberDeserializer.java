package com.finora.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import java.io.IOException;

/**
 * An {@code Integer} field that accepts only a JSON whole number, or {@code null}.
 *
 * <p>Jackson's default reads {@code 50.9} as 50 and {@code "60"} as 60 without a word, so a request
 * means something other than what it said. For a setting that changes what Fynora does with the
 * user's data, that silent rewrite is a bug; this refuses it with a 400 naming the field, through
 * {@link RequestInputBoundsConfig.RequestValueOutOfBoundsException}. Null passes through, so the
 * field's own {@code @NotNull} (or the service) says it is required. A value outside {@code int}
 * is refused the same way.
 */
public class WholeNumberDeserializer extends StdDeserializer<Integer> {

    static final String MESSAGE = "must be a whole number";

    public WholeNumberDeserializer() {
        super(Integer.class);
    }

    @Override
    public Integer deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        if (p.currentToken() == JsonToken.VALUE_NUMBER_INT && p.getNumberType() == JsonParser.NumberType.INT) {
            return p.getIntValue();
        }
        throw new RequestInputBoundsConfig.RequestValueOutOfBoundsException(p, MESSAGE, p.getText(), Integer.class);
    }

    @Override
    public Integer getNullValue(DeserializationContext ctxt) {
        return null;
    }
}
