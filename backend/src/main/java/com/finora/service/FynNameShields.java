package com.finora.service;

import com.finora.entity.Account;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import com.finora.util.PersonToPersonTransferDetector;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Builds a {@link FynNameShield} for one user and one request from what Fynora already knows about
 * the people in that user's money: the account holders, and the people the user has paid or been
 * paid by.
 */
@Component
public class FynNameShields {

    /** How many of the newest person payments are read for names -- enough for the people a user
     *  deals with regularly, bounded so a request does not read the whole ledger. */
    static final int PERSON_PAYMENTS_READ = 500;

    private final UserRepository userRepository;
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;

    public FynNameShields(UserRepository userRepository, AccountRepository accountRepository,
                          TransactionRepository transactionRepository) {
        this.userRepository = userRepository;
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
    }

    public FynNameShield forUser(UUID userId) {
        return new FynNameShield(holderNames(userId), peopleNames(userId));
    }

    /** The profile name and the holder printed on each of the user's accounts -- a spouse's or a
     *  joint account the user imported names someone else. */
    List<String> holderNames(UUID userId) {
        List<String> names = new ArrayList<>();
        userRepository.findById(userId).map(User::getFullName)
                .filter(n -> !n.isBlank()).ifPresent(names::add);
        for (Account account : accountRepository.findByUserId(userId)) {
            String holder = account.getAccountHolderName();
            if (holder != null && !holder.isBlank() && !names.contains(holder)) names.add(holder);
        }
        return names;
    }

    /**
     * The names in the user's person payments: each payee slot's full name, and its first word as a
     * first name on its own when the name has two words or more and that word has four letters or
     * more. Only the payee slot is read, never a remark: measured on the real corpus, reading every
     * name-shaped segment learned "HAPPY BIRTHDAY", "HELLO" and words like PAY, NEW, FIRST, CHECK and
     * TRANSACTION as names, and every later question using one would have had it hidden. Every word
     * of a name on its own (a surname, a middle name) was measured the same way and kept STATE, HAD
     * and NEW; a first name of four letters or more kept almost only real names.
     */
    Set<String> peopleNames(UUID userId) {
        Set<String> names = new LinkedHashSet<>();
        for (String description : transactionRepository.findPersonPaymentDescriptions(
                userId, PageRequest.of(0, PERSON_PAYMENTS_READ))) {
            for (String name : PersonToPersonTransferDetector.payeeSlotNames(description)) {
                names.add(name);
                String[] words = name.split("[^A-Za-z]+");
                if (words.length >= 2 && words[0].length() >= 4) names.add(words[0]);
            }
        }
        return names;
    }
}
