package com.finora.activation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The committed API document (backend/openapi/openapi.json), which the three apps' generated types
 * come from. springdoc names a schema by its simple class name, so a request record named like one
 * elsewhere (QuickSortDto.AnswerRequest) silently shares that schema and the apps get the wrong type.
 */
class ActivationOpenApiContractTest {

    private static JsonNode spec() throws Exception {
        return new ObjectMapper().readTree(Files.readString(Path.of("openapi/openapi.json")));
    }

    @Test
    void theAnswerEndpointHasItsOwnSchemaWithMomentAndAnswer() throws Exception {
        JsonNode spec = spec();
        String ref = spec.at("/paths/~1api~1v1~1activation~1answers/post/requestBody/content/application~1json/schema/$ref")
                .asText();
        assertThat(ref).isEqualTo("#/components/schemas/ActivationAnswerRequest");

        JsonNode properties = spec.at("/components/schemas/ActivationAnswerRequest/properties");
        assertThat(properties.fieldNames()).toIterable().containsExactlyInAnyOrder("moment", "answer");
    }

    @Test
    void quickSortKeepsItsOwnAnswerSchema() throws Exception {
        JsonNode spec = spec();
        String ref = spec.at("/paths/~1api~1v1~1transactions~1quick-sort~1answer/post/requestBody/content/application~1json/schema/$ref")
                .asText();
        assertThat(ref).isEqualTo("#/components/schemas/AnswerRequest");
        assertThat(spec.at("/components/schemas/AnswerRequest/properties").has("anchorTransactionId")).isTrue();
    }
}
