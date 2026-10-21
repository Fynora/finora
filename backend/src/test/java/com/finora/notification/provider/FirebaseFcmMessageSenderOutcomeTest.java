package com.finora.notification.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.firebase.messaging.MessagingErrorCode;
import org.junit.jupiter.api.Test;

/**
 * FCM's error codes, mapped to what happens to the device token. Only two codes ever revoke a
 * token, and they are kept apart because only {@code INVALID_ARGUMENT} can be caused by a malformed
 * message of ours (see {@link FcmSendOutcome#TOKEN_INVALID}).
 */
class FirebaseFcmMessageSenderOutcomeTest {

    @Test
    void unregisteredIsADeadToken() {
        assertThat(FirebaseFcmMessageSender.outcomeFor(MessagingErrorCode.UNREGISTERED))
                .isEqualTo(FcmSendOutcome.TOKEN_DEAD);
    }

    @Test
    void invalidArgumentIsRevokedButKeptApartSoItCanAlert() {
        assertThat(FirebaseFcmMessageSender.outcomeFor(MessagingErrorCode.INVALID_ARGUMENT))
                .isEqualTo(FcmSendOutcome.TOKEN_INVALID);
    }

    @Test
    void everyOtherCodeAndNoCodeAtAllIsTransient_neverARevocation() {
        // Credential and project errors in particular: revoking a live device for those would
        // silently disable it, and they must retry and dead-letter so someone hears about them.
        for (MessagingErrorCode code : MessagingErrorCode.values()) {
            if (code == MessagingErrorCode.UNREGISTERED || code == MessagingErrorCode.INVALID_ARGUMENT) {
                continue;
            }
            assertThat(FirebaseFcmMessageSender.outcomeFor(code)).as(code.name())
                    .isEqualTo(FcmSendOutcome.TRANSIENT_FAILURE);
        }
        assertThat(FirebaseFcmMessageSender.outcomeFor(null)).isEqualTo(FcmSendOutcome.TRANSIENT_FAILURE);
    }
}
