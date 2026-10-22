package com.finora.notification.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.client.json.gson.GsonFactory;
import com.google.firebase.messaging.Message;
import org.junit.jupiter.api.Test;

/**
 * What the phone is told to do when a push arrives. A push built with only a title and body is
 * delivered silently on iOS (no {@code aps.sound}) and is not marked high priority on Android, so
 * it can sit unannounced while the phone dozes. The Message class has no getters, so the payload is
 * serialised with the same JSON factory the SDK sends with and read back as a tree.
 */
class FirebaseFcmMessageSenderPayloadTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode payload() throws Exception {
        Message message = FirebaseFcmMessageSender.buildMessage("token-1", "Title", "Body", "TRANSACTION_ALERT");
        return MAPPER.readTree(GsonFactory.getDefaultInstance().toString(message));
    }

    @Test
    void iosPushPlaysTheDefaultSound() throws Exception {
        assertThat(payload().at("/apns/payload/aps/sound").asText()).isEqualTo("default");
    }

    @Test
    void iosPushIsDeliveredImmediately() throws Exception {
        assertThat(payload().at("/apns/headers/apns-priority").asText()).isEqualTo("10");
    }

    @Test
    void androidPushIsHighPriority() throws Exception {
        assertThat(payload().at("/android/priority").asText()).isEqualToIgnoringCase("high");
    }

    @Test
    void androidPushAsksForTheDefaultSound() throws Exception {
        assertThat(payload().at("/android/notification/sound").asText()).isEqualTo("default");
    }

    /**
     * On Android 8+ the channel on the phone decides sound and vibration, not the message. This id
     * must equal PUSH_CHANNEL_ID in mobile/src/lib/systemNotification.ts, where the channel is
     * created with sound and vibration on. Pinned as a literal here so changing it on one side
     * only fails this test instead of silently putting pushes on a channel that does not exist.
     */
    @Test
    void androidPushNamesTheChannelTheAppCreatesWithSoundAndVibration() throws Exception {
        assertThat(payload().at("/android/notification/channel_id").asText()).isEqualTo("pushes");
    }

    @Test
    void titleBodyAndTypeStillTravelWithThePush() throws Exception {
        JsonNode json = payload();
        assertThat(json.at("/token").asText()).isEqualTo("token-1");
        assertThat(json.at("/notification/title").asText()).isEqualTo("Title");
        assertThat(json.at("/notification/body").asText()).isEqualTo("Body");
        assertThat(json.at("/data/type").asText()).isEqualTo("TRANSACTION_ALERT");
    }
}
