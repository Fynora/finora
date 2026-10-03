package com.finora.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Words that name people, and words that never do -- for hiding names from text sent to the AI
 * model ({@code FynNameShield}).
 *
 * <p>{@link #isCommonName} answers from a bundled list of common given names and surnames
 * ({@code person-names/given-names.txt}, {@code surnames.txt}), so "Rahul" typed into a question
 * is hidden even when the user has never paid a Rahul. {@link #isEverydayWord} answers from
 * {@code common-words.txt} plus every merchant and category keyword the app knows: a word a
 * payee slot happened to hold ("STATE", "AVENUE", "SWIGGY") is never learned as a name. A name
 * that is also a merchant keyword or an everyday word counts as the word, not the name.
 */
public final class PersonNameLexicon {

    private PersonNameLexicon() {}

    private static final Set<String> NAMES;
    private static final Set<String> EVERYDAY;

    static {
        Set<String> everyday = new HashSet<>(read("person-names/common-words.txt"));
        for (String term : MerchantIdentityLookup.knownEntityTerms()) everyday.addAll(words(term));
        for (String keyword : CategoryRules.allKeywords()) everyday.addAll(words(keyword));
        EVERYDAY = Collections.unmodifiableSet(everyday);

        Set<String> names = new HashSet<>(read("person-names/given-names.txt"));
        names.addAll(read("person-names/surnames.txt"));
        names.removeAll(everyday);
        NAMES = Collections.unmodifiableSet(names);
    }

    /** Whether {@code word} is a common given name or surname and not also an everyday word. */
    public static boolean isCommonName(String word) {
        return word != null && NAMES.contains(word.toLowerCase(Locale.ROOT));
    }

    /** Whether {@code word} is an everyday word, or a merchant or category keyword -- never a name. */
    public static boolean isEverydayWord(String word) {
        return word != null && EVERYDAY.contains(word.toLowerCase(Locale.ROOT));
    }

    private static Set<String> words(String text) {
        Set<String> out = new HashSet<>();
        for (String w : text.toLowerCase(Locale.ROOT).split("[^a-z]+")) {
            if (!w.isEmpty()) out.add(w);
        }
        return out;
    }

    private static Set<String> read(String resource) {
        InputStream in = PersonNameLexicon.class.getClassLoader().getResourceAsStream(resource);
        if (in == null) throw new IllegalStateException("missing resource " + resource);
        Set<String> out = new HashSet<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank() || line.stripLeading().startsWith("#")) continue;
                out.addAll(words(line));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }
}
