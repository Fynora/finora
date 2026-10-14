package com.finora.imports.blanked;

import java.util.List;

public record BlankedCopy(String fileName, String trace, Check check, String notCheckedReason,
                          List<BlankedCopyComparison.Difference> differences) {
    public enum Check { MATCHES, DIFFERS, NOT_CHECKED }
}
