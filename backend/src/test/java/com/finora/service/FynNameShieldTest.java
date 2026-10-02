package com.finora.service;

import com.finora.entity.Account;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FynNameShieldTest {

    private final UUID userId = UUID.randomUUID();

    @Test
    void theHoldersNameIsHiddenAndRestored() {
        FynNameShield shield = new FynNameShield(List.of("Tanvi Sharma"));

        String out = shield.shield("Did Tanvi Sharma pay rent?");

        assertThat(out).isEqualTo("Did [name-1] [name-2] pay rent?");
        assertThat(shield.unshield("Yes, [name-1] [name-2] paid it.")).isEqualTo("Yes, Tanvi Sharma paid it.");
    }

    @Test
    void aPersonTheUserPaid_isHiddenWholeAndByFirstName_inAnyText() {
        FynNameShield shield = new FynNameShield(List.of(), new java.util.LinkedHashSet<>(List.of("PRIYA SHARMA", "PRIYA")));

        // A category the user named after her, a tool's answer, the user's own question.
        assertThat(shield.shield("Spend in Rent to Priya: 500")).isEqualTo("Spend in Rent to [name-2]: 500");
        assertThat(shield.shield("how much went to priya  sharma?")).isEqualTo("how much went to [name-1]?");
        // Put back as it was last written in text this shield hid.
        assertThat(shield.unshield("[name-1] got 500; [name-2] too.")).isEqualTo("priya  sharma got 500; Priya too.");
    }

    @Test
    void aKnownName_isMatchedOnlyAsWholeWords_andANameWithPunctuationIsMatchedToo() {
        FynNameShield shield = new FynNameShield(List.of(),
                new java.util.LinkedHashSet<>(List.of("ANIL MEHTA", "ANIL", "D'SOUZA RAVI")));

        assertThat(shield.shield("Anil  Mehta, ANIL-2, Anilkumar, vanil, d'souza ravi"))
                .isEqualTo("[name-1], [name-2]-2, Anilkumar, vanil, [name-3]");
        // A token already written is never read as a name, whatever names are known.
        assertThat(new FynNameShield(List.of(), Set.of("NAME")).shield("[name-1] and Name"))
                .isEqualTo("[name-1] and [name-1]");
    }

    @Test
    void aLabelThatIsNotAKnownPerson_staysReadable() {
        FynNameShield shield = new FynNameShield(List.of(), Set.of("PRIYA SHARMA"));

        assertThat(shield.shield("Apple Purchases, Dining Out, Zebra Crossing Tolls"))
                .isEqualTo("Apple Purchases, Dining Out, Zebra Crossing Tolls");
    }

    @Test
    void aHolderWordInsideAKnownName_doesNotSplitIt() {
        FynNameShield shield = new FynNameShield(List.of("Tanvi Sharma"), Set.of("PRIYA SHARMA"));

        String out = shield.shield("Priya Sharma");

        assertThat(out).isEqualTo("[name-1]");
        assertThat(shield.unshield(out)).isEqualTo("Priya Sharma");
    }

    @Test
    void namesInAScreenshotsTextAreHidden_butAQuestionsOwnLinesAreNotReadAsNames() {
        FynNameShield shield = new FynNameShield(List.of());
        String message = "intro " + FynScreenshotOcrService.SCREENSHOT_TEXT_START
                + "Paid to\nRAVI KUMAR\nRs 500" + FynScreenshotOcrService.SCREENSHOT_TEXT_END
                + "Good morning\nwho is RAVI KUMAR?";

        String out = shield.shield(message);

        assertThat(out).contains("Paid to\n[name-1]\nRs 500")
                .contains("Good morning")
                .contains("who is [name-1]?")
                .doesNotContain("RAVI");
        assertThat(shield.unshield("You paid [name-1] Rs 500.")).isEqualTo("You paid RAVI KUMAR Rs 500.");
    }

    @Test
    void aTokenTheModelReshaped_isStillRestored_andAnUnknownOneIsLeftAlone() {
        FynNameShield shield = new FynNameShield(List.of(), Set.of("PRIYA SHARMA"));

        assertThat(shield.unshield("[Name 1] and [name-9]")).isEqualTo("PRIYA SHARMA and [name-9]");
        // A made-up number too long for an int is left as it is, never a failed reply.
        assertThat(shield.unshield("[name-99999999999999] and [name-01]"))
                .isEqualTo("[name-99999999999999] and PRIYA SHARMA");
    }

    @Test
    void shieldingIsIdempotentAndNullSafe() {
        FynNameShield shield = new FynNameShield(List.of("Tanvi Sharma"), Set.of("PRIYA SHARMA"));
        String once = shield.shield("Tanvi Sharma and Priya Sharma");

        assertThat(shield.shield(once)).isEqualTo(once);
        assertThat(shield.shield(null)).isNull();
        assertThat(shield.unshield(null)).isNull();
    }

    @Test
    void theFactory_readsHoldersAndThePeopleInPersonPayments() {
        UserRepository users = mock(UserRepository.class);
        AccountRepository accounts = mock(AccountRepository.class);
        TransactionRepository transactions = mock(TransactionRepository.class);
        User user = new User();
        user.setFullName("Tanvi Sharma");
        when(users.findById(userId)).thenReturn(Optional.of(user));
        Account spouses = new Account();
        spouses.setAccountHolderName("ROHAN VERMA");
        when(accounts.findByUserId(userId)).thenReturn(List.of(spouses));
        when(transactions.findPersonPaymentDescriptions(eq(userId), any()))
                .thenReturn(List.of("UPI-PRIYA SHARMA-priyasharma@okicici-UPI", "UPI-RAVI KUMAR-9876501234@ybl-UPI", // synthetic-ok
                        "UPI-ANIL MEHTA-anil@okaxis-HAPPY BIRTHDAY")); // synthetic-ok

        FynNameShield shield = new FynNameShields(users, accounts, transactions).forUser(userId);
        String out = shield.shield("Priya, Ravi Kumar, Rohan Verma, Tanvi, Dining Out, happy birthday, Mehta");

        assertThat(out).doesNotContain("Priya").doesNotContain("Ravi").doesNotContain("Rohan")
                .doesNotContain("Verma").doesNotContain("Tanvi")
                // A surname alone is hidden once a payment to that person is on record.
                .doesNotContain("Mehta")
                .contains("Dining Out")
                // A remark is never learned as a name.
                .contains("happy birthday");
    }

    @Test
    void everyWordOfAPaidPersonsName_isLearned_butNotAShopTypedAsAPerson() {
        Set<String> names = FynNameShields.namesIn(List.of(
                "UPI-QZXAL DEV KORWATI-qzx@okaxis-UPI", // synthetic-ok
                "UPI-QZX TEA STALL-qzxtea@okaxis-UPI")); // synthetic-ok

        // A surname and a 3-letter first name on their own, never seen elsewhere.
        assertThat(names).contains("QZXAL DEV KORWATI", "QZXAL", "DEV", "KORWATI");
        // An everyday word in the slot marks a shop: nothing from it is learned.
        assertThat(names).noneMatch(n -> n.contains("QZX") && !n.startsWith("QZXAL"));
    }

    @Test
    void aCommonNameTheUserHasNeverPaid_isHidden_andEverydayQuestionsAreLeftAlone() {
        FynNameShield shield = new FynNameShield(List.of());

        assertThat(shield.shield("How much did I pay Rahul Verma? And Raj? And Iyer, and Om?"))
                .isEqualTo("How much did I pay [name-1]? And [name-2]? And [name-3], and [name-4]?");
        assertThat(shield.unshield("You paid [name-1] 500.")).isEqualTo("You paid Rahul Verma 500.");
        // Everyday words and merchants that share a name list's letters are not names.
        for (String q : List.of("How much did I spend on food and Swiggy last month?",
                "Did my EMI go out?", "how much did I give to mummy", "Is my insurance due?")) {
            assertThat(shield.shield(q)).isEqualTo(q);
        }
    }
}
