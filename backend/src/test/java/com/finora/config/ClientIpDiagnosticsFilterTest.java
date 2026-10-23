package com.finora.config;

import com.finora.observability.ClientIpDiagnostics;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ClientIpDiagnosticsFilterTest {

    private final ClientIpDiagnostics diagnostics = mock(ClientIpDiagnostics.class);
    private final FilterChain chain = mock(FilterChain.class);
    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/health");
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @Test
    void enabledFilterRecordsTheRequestAndContinuesTheChain() throws Exception {
        new ClientIpDiagnosticsFilter(diagnostics, true).doFilter(request, response, chain);

        verify(diagnostics).record(request);
        verify(chain).doFilter(request, response);
    }

    @Test
    void disabledFilterRecordsNothingAndStillContinuesTheChain() throws Exception {
        new ClientIpDiagnosticsFilter(diagnostics, false).doFilter(request, response, chain);

        verify(diagnostics, never()).record(any());
        verify(chain).doFilter(request, response);
    }

    /** ClientIpDiagnostics.record swallows its own failures; this guards the filter's side too, in
     *  case a future change lets one escape -- the request must not depend on diagnostics. */
    @Test
    void aFailingRecordDoesNotStopTheRequest() throws Exception {
        doThrow(new IllegalStateException("boom")).when(diagnostics).record(any());

        try {
            new ClientIpDiagnosticsFilter(diagnostics, true).doFilter(request, response, chain);
        } catch (IllegalStateException ignored) {
            // fall through to the assertion so the failure message names the real problem
        }

        verify(chain).doFilter(request, response);
    }
}
