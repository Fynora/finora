package com.finora.integrations.setu;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SetuDataFetchGatewayImplTest {

    @Test
    void isConfiguredDelegatesToProperties() {
        SetuProperties properties = new SetuProperties();
        SetuDataFetchGatewayImpl gateway = new SetuDataFetchGatewayImpl(properties);

        assertThat(gateway.isConfigured()).isFalse();

        properties.setClientId("id");
        properties.setClientSecret("secret");
        properties.setWebhookSecret("whsecret");
        assertThat(gateway.isConfigured()).isTrue();
    }

    @Test
    void fetchTransactionsIsNotYetImplemented() {
        SetuDataFetchGatewayImpl gateway = new SetuDataFetchGatewayImpl(new SetuProperties());

        assertThatThrownBy(() -> gateway.fetchTransactions("consent-handle-1",
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 9, 1)))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
