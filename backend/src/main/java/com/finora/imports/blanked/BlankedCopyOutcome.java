package com.finora.imports.blanked;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the second audit entry for a blanked copy records once the copy is made or refused: the
 * check's result, how many differences it found, and the kinds of data a refusal named. Never any
 * text: the differences themselves, with their masked tokens, stay in the response.
 *
 * @param check           MATCHES, DIFFERS or NOT_CHECKED for a copy made; REFUSED when the
 *                        privacy scan refused it
 * @param differenceCount how many differences the check found (0 unless DIFFERS)
 * @param refusedKinds    the kind names a refusal gave, such as "email address"; empty otherwise
 */
public record BlankedCopyOutcome(String check, int differenceCount, List<String> refusedKinds) {

    public static final String REFUSED = "REFUSED";

    public BlankedCopyOutcome {
        refusedKinds = List.copyOf(refusedKinds);
    }

    public static BlankedCopyOutcome of(BlankedCopy copy) {
        return new BlankedCopyOutcome(copy.check().name(), copy.differences().size(), List.of());
    }

    public static BlankedCopyOutcome refused(List<String> kinds) {
        return new BlankedCopyOutcome(REFUSED, 0, kinds);
    }

    /** The audit details this outcome adds to the entry's ids. */
    public Map<String, Object> auditDetails() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("check", check);
        details.put("differenceCount", differenceCount);
        details.put("refusedKinds", refusedKinds);
        return details;
    }
}
