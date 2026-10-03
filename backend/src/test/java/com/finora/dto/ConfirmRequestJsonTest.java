package com.finora.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** ConfirmRequest has several constructors; the JSON a client sends must still reach every field. */
class ConfirmRequestJsonTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void holderIsMineBindsFromJson_andIsNullWhenAbsent() throws Exception {
        ImportDto.ConfirmRequest claimed = mapper.readValue(
                "{\"rows\":[],\"userConfirmedContinue\":true,\"holderIsMine\":true}", ImportDto.ConfirmRequest.class);
        assertThat(claimed.holderIsMine()).isTrue();
        assertThat(claimed.userConfirmedContinue()).isTrue();

        ImportDto.ConfirmRequest olderClient = mapper.readValue("{\"rows\":[]}", ImportDto.ConfirmRequest.class);
        assertThat(olderClient.holderIsMine()).isNull();
    }

    @Test
    void withRowsKeepsHolderIsMine() throws Exception {
        ImportDto.ConfirmRequest claimed = mapper.readValue(
                "{\"rows\":[],\"holderIsMine\":true}", ImportDto.ConfirmRequest.class);
        assertThat(claimed.withRows(java.util.List.of()).holderIsMine()).isTrue();
    }
}
