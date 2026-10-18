package com.finora.activation;

/** Classifies a signup at their furthest {@link FunnelStep} from what the records already say. */
public final class FunnelSteps {

    private FunnelSteps() {}

    /** One user's facts, each read straight from a table (see ActivationFunnelService). */
    public record UserFacts(boolean openedImport, boolean choseFile, boolean refused, boolean readFailed,
                            boolean held, boolean read, boolean reviewOpened, boolean imported) {}

    /** The highest step whose fact is true, or {@link FunnelStep#SIGNED_UP} when none is. */
    public static FunnelStep furthest(UserFacts f) {
        if (f.imported()) return FunnelStep.GOT_DATA_IN;
        if (f.reviewOpened()) return FunnelStep.REVIEWED_NOT_CONFIRMED;
        if (f.read()) return FunnelStep.READ_NOT_REVIEWED;
        if (f.held()) return FunnelStep.HELD;
        if (f.readFailed()) return FunnelStep.READ_FAILED;
        if (f.refused()) return FunnelStep.REFUSED;
        if (f.choseFile()) return FunnelStep.CHOSE_FILE;
        if (f.openedImport()) return FunnelStep.OPENED_IMPORT;
        return FunnelStep.SIGNED_UP;
    }
}
