package com.finora.service;

import com.finora.entity.Account;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import com.finora.util.PersonNameLexicon;
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

    /** The names in the user's person payments -- see {@link #namesIn}. */
    Set<String> peopleNames(UUID userId) {
        return namesIn(transactionRepository.findPersonPaymentDescriptions(
                userId, PageRequest.of(0, PERSON_PAYMENTS_READ)));
    }

    /**
     * The names in these person-payment narrations: each payee slot's full name, and every word of
     * it on its own -- a first name, a surname, a middle name -- so "Verma" or "Raj" alone in a
     * question is hidden too. A word is learned only when it has three letters or more (or is a
     * common name, "Om"), and nothing is learned from a slot holding an everyday word, a merchant or
     * a category keyword ({@link PersonNameLexicon#isEverydayWord}). Only the payee slot is read,
     * never a remark: measured on the real corpus, reading every name-shaped segment learned "HAPPY
     * BIRTHDAY" and "HELLO", and the payee slots of rows typed PERSON still held shops ("BURGER",
     * "TRADER", "VEG") and words like STATE, NEW and AVENUE -- every later question using one would
     * have had it hidden.
     */
    static Set<String> namesIn(Iterable<String> descriptions) {
        Set<String> names = new LinkedHashSet<>();
        for (String description : descriptions) {
            for (String name : PersonToPersonTransferDetector.payeeSlotNames(description)) {
                String[] words = name.trim().split("[^A-Za-z]+");
                // A shop the classifier typed PERSON ("<NAME> TEA STALL", "BURGER KING") is not a
                // person: one everyday word in the slot and none of its words is learned.
                if (java.util.Arrays.stream(words).anyMatch(PersonNameLexicon::isEverydayWord)) continue;
                List<String> nameWords = java.util.Arrays.stream(words)
                        .filter(w -> !w.isEmpty() && (w.length() >= 3 || PersonNameLexicon.isCommonName(w)))
                        .toList();
                if (nameWords.isEmpty()) continue;
                // The full name first, so it gets the lowest token number of the three.
                if (words.length >= 2) names.add(name.trim());
                names.addAll(nameWords);
            }
        }
        return names;
    }
}
