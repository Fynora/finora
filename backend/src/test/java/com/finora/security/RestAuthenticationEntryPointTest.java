package com.finora.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.InsufficientAuthenticationException;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The 401 body is written straight to the servlet response, outside Spring MVC's message
 * converters, so nothing picks its charset unless this class does. Left to the servlet default
 * (ISO-8859-1) the em dash in the message reached the apps as "?".
 *
 * The mock response encodes bare application/json as UTF-8 on its own, which Tomcat does not, so
 * the header's charset is the check that bites here. ServletWrittenJsonBodiesIT reads the
 * bytes off the real embedded server.
 */
class RestAuthenticationEntryPointTest {

    private final RestAuthenticationEntryPoint entryPoint =
            new RestAuthenticationEntryPoint(new ObjectMapper().registerModule(new JavaTimeModule()));

    @Test
    void writesTheBodyAsUtf8_soTheEmDashSurvives() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        entryPoint.commence(new MockHttpServletRequest(), response,
                new InsufficientAuthenticationException("no token"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(MediaType.parseMediaType(response.getContentType()).getCharset())
                .isEqualTo(StandardCharsets.UTF_8);
        assertThat(new String(response.getContentAsByteArray(), StandardCharsets.UTF_8))
                .contains("Authentication required — please sign in again.")
                .contains("UNAUTHORIZED");
    }
}
