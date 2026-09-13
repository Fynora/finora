package com.finora.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TransactionAccountAggregatorFieldsTest {

    @Test
    void externalTxnIdAndFingerprintDefaultToNull() {
        Transaction txn = new Transaction();
        assertThat(txn.getExternalTxnId()).isNull();
        assertThat(txn.getTransactionFingerprint()).isNull();
    }

    @Test
    void bothCanBeSetIndependently() {
        Transaction txn = new Transaction();

        txn.setExternalTxnId("setu-txn-1");
        txn.setTransactionFingerprint("abc123");

        assertThat(txn.getExternalTxnId()).isEqualTo("setu-txn-1");
        assertThat(txn.getTransactionFingerprint()).isEqualTo("abc123");
    }

    @Test
    void accountAggregatorIsAValidSource() {
        Transaction txn = new Transaction();
        txn.setSource(Transaction.Source.ACCOUNT_AGGREGATOR);
        assertThat(txn.getSource()).isEqualTo(Transaction.Source.ACCOUNT_AGGREGATOR);
    }
}
