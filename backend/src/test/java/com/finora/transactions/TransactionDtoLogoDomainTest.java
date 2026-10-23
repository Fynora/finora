package com.finora.transactions;

import com.finora.entity.Transaction;
import com.finora.util.CounterpartyType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Ledger, Dashboard and review rows ask Logo.dev for a logo only by {@code logoDomain}: a web
 * address from MerchantBrands, never the row's merchant name or narration.
 */
class TransactionDtoLogoDomainTest {

    private static Transaction row(String merchant, String description, CounterpartyType type) {
        Transaction t = new Transaction();
        t.setTxnType(Transaction.Type.EXPENSE);
        t.setAmount(new BigDecimal("100"));
        t.setTxnDate(LocalDate.of(2026, 9, 1));
        t.setAccountId(UUID.randomUUID());
        t.setMerchant(merchant);
        t.setDescription(description);
        t.setCounterpartyType(type);
        return t;
    }

    @Test
    void aListedBrandGetsItsWebAddress() {
        assertThat(TransactionDto.from(row("zomato online order", "UPI/DR/600000000001/ZOMATO/YESB",
                CounterpartyType.BUSINESS), "Dining").logoDomain()).isEqualTo("zomato.com");
    }

    @Test
    void aNameThatIsNotAListedBrandGetsNothing_evenTypedAsABusiness() {
        assertThat(TransactionDto.from(row("Sample Vendor Name", "UPI/DR/600000000002/SAMPLE VENDOR/YESB",
                CounterpartyType.BUSINESS), "Food").logoDomain()).isNull();
    }

    @Test
    void aPaymentToAPersonGetsNothing_evenUnderABrandName() {
        assertThat(TransactionDto.from(row("Amazon", "UPI/DR/600000000003/AMAZON/YESB",
                CounterpartyType.PERSON), "Transfers").logoDomain()).isNull();
    }

    @Test
    void aRowWithNoMerchantNameGetsNothing_whateverItsNarrationSays() {
        // The narration is never read for a logo: it is shown in place of a name, not a name.
        assertThat(TransactionDto.from(row(null, "UPI/DR/600000000004/ZOMATO/YESB",
                CounterpartyType.UNKNOWN), "Other").logoDomain()).isNull();
    }
}
