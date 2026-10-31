package com.finora.imports.convert;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.config.ClientIpResolver;
import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The body is read by hand with a hard cap (a JSON body has no size limit of its own), and an
 * unreadable body becomes one fixed message rather than the JSON library's, which can quote it.
 */
class StatementConversionControllerTest {

    private final StatementConversionService service = mock(StatementConversionService.class);
    private final ClientIpResolver ips = mock(ClientIpResolver.class);
    private final StatementConversionController controller =
            new StatementConversionController(service, ips, new ObjectMapper());

    private static MockHttpServletRequest request(byte[] body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/convert");
        request.setContentType("application/json");
        request.setContent(body);
        return request;
    }

    private static ErrorCode refusal(Runnable call) {
        try {
            call.run();
        } catch (ApiException e) {
            return e.getCode();
        }
        throw new AssertionError("expected a refusal");
    }

    @Test
    void aReadableBodyReachesTheServiceWithTheResolvedAddress() {
        when(ips.resolve(any())).thenReturn("198.51.100.4");
        when(service.convert(any(), anyString())).thenReturn(new ConvertDto.Result(1, List.of()));
        byte[] body = "{\"file\":\"JVBERi0=\",\"password\":\"pw\",\"turnstileToken\":\"t\",\"consent\":true}"
                .getBytes(StandardCharsets.UTF_8);

        controller.convert(request(body));

        ArgumentCaptor<ConvertDto.Request> captured = ArgumentCaptor.forClass(ConvertDto.Request.class);
        verify(service).convert(captured.capture(), org.mockito.ArgumentMatchers.eq("198.51.100.4"));
        assertThat(captured.getValue().file()).isEqualTo("JVBERi0=");
        assertThat(captured.getValue().password()).isEqualTo("pw");
        assertThat(captured.getValue().consent()).isTrue();
        // The record's own toString never prints the file or the password.
        assertThat(captured.getValue().toString()).doesNotContain("JVBERi0=").doesNotContain("pw\"").contains("given");
    }

    @Test
    void aBodyDeclaredOverTheCapIsRefusedBeforeReading() {
        MockHttpServletRequest tooBig = new MockHttpServletRequest("POST", "/api/v1/convert") {
            @Override
            public long getContentLengthLong() {
                return StatementConversionController.MAX_BODY_BYTES + 1L;
            }
        };

        assertThat(refusal(() -> controller.convert(tooBig))).isEqualTo(ErrorCode.CONVERT_TOO_LARGE);
        verify(service, never()).convert(any(), anyString());
    }

    @Test
    void aBodyOverTheCapWithNoDeclaredLengthIsRefusedAtTheCap() {
        MockHttpServletRequest chunked = request(new byte[StatementConversionController.MAX_BODY_BYTES + 10]);
        chunked.removeHeader("Content-Length");

        assertThat(refusal(() -> controller.convert(chunked))).isEqualTo(ErrorCode.CONVERT_TOO_LARGE);
        verify(service, never()).convert(any(), anyString());
    }

    @Test
    void anUnreadableBodyIsOneFixedMessage_neverTheParserQuotingIt() {
        ApiException refused = (ApiException) org.assertj.core.api.Assertions.catchThrowable(() ->
                controller.convert(request("{\"file\": \"QUOTEDSENTINEL\", \"consent\": tru".getBytes(StandardCharsets.UTF_8))));

        assertThat(refused.getCode()).isEqualTo(ErrorCode.CONVERT_BAD_REQUEST);
        assertThat(refused.getMessage()).doesNotContain("QUOTEDSENTINEL");
        assertThat(refused.getCause()).isNull();
    }

    @Test
    void anEmptyBodyIsUnreadable() {
        assertThatThrownBy(() -> controller.convert(request(new byte[0])))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.CONVERT_BAD_REQUEST));
    }
}
