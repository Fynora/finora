package com.finora.imports.blanked;

import com.finora.exception.ApiException;
import org.springframework.http.HttpStatus;

import java.util.List;

/** The privacy scan found customer data in the copy. Carries the kinds only, never the data. */
public class BlankedCopyRefusedException extends ApiException {
    private final List<String> kinds;

    public BlankedCopyRefusedException(List<String> kinds) {
        super(HttpStatus.UNPROCESSABLE_ENTITY,
                "The blanked copy was refused: it still contained " + String.join(", ", kinds) + ".");
        this.kinds = List.copyOf(kinds);
    }

    public List<String> kinds() { return kinds; }
}
