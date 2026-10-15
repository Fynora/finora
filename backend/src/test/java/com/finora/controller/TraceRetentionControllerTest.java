package com.finora.controller;

import com.finora.imports.blanked.TraceRetentionService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The endpoint has no rate limit; its key is the whole control. So a key short enough to guess
 *  never switches it on. */
class TraceRetentionControllerTest {

    private static final String LONG_KEY = "k".repeat(32);

    @Test
    void aKeyShorterThan32CharactersLeavesTheEndpointOff() {
        TraceRetentionService service = mock(TraceRetentionService.class);
        String shortKey = "k".repeat(31);
        TraceRetentionController controller = new TraceRetentionController(service, shortKey);

        var response = controller.gone(shortKey, new TraceRetentionController.TraceRetentionQuestion(List.of("x")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(service, never()).gone(any());
    }

    @Test
    void a32CharacterKeySwitchesItOn() {
        TraceRetentionService service = mock(TraceRetentionService.class);
        when(service.gone(any())).thenReturn(List.of());
        TraceRetentionController controller = new TraceRetentionController(service, LONG_KEY);

        var response = controller.gone(LONG_KEY, new TraceRetentionController.TraceRetentionQuestion(List.of("x")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
