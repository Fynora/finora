package com.finora.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.dto.PushCampaignDtos.PushCampaignSaveRequest;
import com.finora.notification.campaign.AudienceType;
import com.finora.notification.campaign.ScheduleKind;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * The exact JSON shapes the admin portal's campaign editor sends (admin-portal
 * {@code pages/push-campaigns/CampaignEditor.tsx}): a one-time instant from {@code Date.toISOString()}
 * (milliseconds, trailing Z), a time of day as "HH:mm" with no seconds, and an IST calendar date.
 * The HTTP tests only send NOW_ONLY and "HH:mm" daily campaigns, so without this the one-time
 * schedule and the end date -- both of which the screen offers -- would reach production never having
 * been parsed by anything but the browser's own idea of the format.
 */
class PushCampaignSaveRequestJsonTest {

    private final ObjectMapper mapper = Jackson2ObjectMapperBuilder.json().build();

    @Test
    void aOneTimeCampaignWithAnInstantFromToIsoString() throws Exception {
        PushCampaignSaveRequest request = mapper.readValue("""
                {"name":"Once","title":"Hello","message":"World","audienceType":"ALL_WITH_DEVICE",
                 "scheduleKind":"ONCE_AT","runAt":"2099-01-02T14:00:00.000Z","sendTimeIst":null,
                 "endsOn":null,"expectedVersion":null}
                """, PushCampaignSaveRequest.class);

        assertThat(request.scheduleKind()).isEqualTo(ScheduleKind.ONCE_AT);
        assertThat(request.runAt()).isEqualTo(Instant.parse("2099-01-02T14:00:00Z"));
        assertThat(request.sendTimeIst()).isNull();
        assertThat(request.endsOn()).isNull();
        assertThat(request.expectedVersion()).isNull();
    }

    @Test
    void aDailyCampaignWithAnEndDateAndAVersion() throws Exception {
        PushCampaignSaveRequest request = mapper.readValue("""
                {"name":"Daily","title":"Hello","message":"World","audienceType":"NO_STATEMENT_UPLOADED",
                 "scheduleKind":"DAILY_AT","runAt":null,"sendTimeIst":"09:30","endsOn":"2026-12-31",
                 "expectedVersion":7}
                """, PushCampaignSaveRequest.class);

        assertThat(request.audienceType()).isEqualTo(AudienceType.NO_STATEMENT_UPLOADED);
        assertThat(request.sendTimeIst()).isEqualTo(LocalTime.of(9, 30));
        assertThat(request.endsOn()).isEqualTo(LocalDate.of(2026, 12, 31));
        assertThat(request.expectedVersion()).isEqualTo(7L);
    }

    @Test
    void aSendNowOnlyCampaignWithEveryScheduleFieldNull() throws Exception {
        PushCampaignSaveRequest request = mapper.readValue("""
                {"name":"Now","title":"Hello","message":"World","audienceType":"ALL_WITH_DEVICE",
                 "scheduleKind":"NOW_ONLY","runAt":null,"sendTimeIst":null,"endsOn":null,
                 "expectedVersion":null}
                """, PushCampaignSaveRequest.class);

        assertThat(request.scheduleKind()).isEqualTo(ScheduleKind.NOW_ONLY);
        assertThat(request.runAt()).isNull();
    }
}
