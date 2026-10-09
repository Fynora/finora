package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.dto.PushCampaignDtos.PushCampaignAudienceCountDto;
import com.finora.dto.PushCampaignDtos.PushCampaignCancelResultDto;
import com.finora.dto.PushCampaignDtos.PushCampaignDetailDto;
import com.finora.dto.PushCampaignDtos.PushCampaignDto;
import com.finora.dto.PushCampaignDtos.PushCampaignRunDto;
import com.finora.dto.PushCampaignDtos.PushCampaignSaveRequest;
import com.finora.dto.PushCampaignDtos.PushCampaignSettingsDto;
import com.finora.dto.PushCampaignDtos.PushCampaignSettingsRequest;
import com.finora.dto.PushCampaignDtos.PushCampaignTestRequest;
import com.finora.dto.PushCampaignDtos.PushCampaignTestResultDto;
import com.finora.notification.campaign.AudienceType;
import com.finora.notification.campaign.PushCampaignService;
import com.finora.security.CurrentUser;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin push campaigns: write any notification, pick the audience, test it, send now or schedule it
 * (once, or daily at a time in IST), and decide when it stops. The rules live in
 * {@link PushCampaignService}.
 *
 * <p>Gated on its own {@code PUSH_CAMPAIGN_MANAGE} (V261), not borrowed from the read-only
 * notification dashboard's {@code NOTIFICATION_MANAGE}: this one can message users. Class-level, so
 * any endpoint added later is gated by default; the audience dry run is behind it too because the
 * count is itself operational data.
 */
@RestController
@RequestMapping("/api/v1/admin/push-campaigns")
@PreAuthorize("hasAuthority('PUSH_CAMPAIGN_MANAGE')")
public class AdminPushCampaignController {

    private final PushCampaignService service;
    private final CurrentUser currentUser;

    public AdminPushCampaignController(PushCampaignService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @GetMapping
    public ApiResponse<List<PushCampaignDto>> listPushCampaigns() {
        return ApiResponse.ok(service.list());
    }

    /** The daily limit per person, with the bounds the editor should offer. */
    @GetMapping("/settings")
    public ApiResponse<PushCampaignSettingsDto> getPushCampaignSettings() {
        return ApiResponse.ok(service.settings());
    }

    /** Takes effect on the next page any run queues; never takes back a push someone already has. */
    @PutMapping("/settings")
    public ApiResponse<PushCampaignSettingsDto> updatePushCampaignSettings(
            @Valid @RequestBody PushCampaignSettingsRequest request) {
        return ApiResponse.ok(service.updateSettings(currentUser.id(), request));
    }

    @GetMapping("/{id}")
    public ApiResponse<PushCampaignDetailDto> getPushCampaign(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    /** The dry run: how many people the audience reaches right now. An estimate, not a promise. */
    @GetMapping("/audience-count")
    public ApiResponse<PushCampaignAudienceCountDto> countPushCampaignAudience(@RequestParam AudienceType audienceType) {
        return ApiResponse.ok(service.audienceCount(audienceType));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<PushCampaignDto> createPushCampaign(@Valid @RequestBody PushCampaignSaveRequest request) {
        return ApiResponse.ok(service.create(currentUser.id(), request));
    }

    @PutMapping("/{id}")
    public ApiResponse<PushCampaignDto> updatePushCampaign(@PathVariable UUID id, @Valid @RequestBody PushCampaignSaveRequest request) {
        return ApiResponse.ok(service.update(currentUser.id(), id, request));
    }

    @PostMapping("/{id}/clone")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<PushCampaignDto> clonePushCampaign(@PathVariable UUID id) {
        return ApiResponse.ok(service.clone(currentUser.id(), id));
    }

    @PostMapping("/{id}/send-test")
    public ApiResponse<PushCampaignTestResultDto> sendPushCampaignTest(@PathVariable UUID id, @Valid @RequestBody PushCampaignTestRequest request) {
        return ApiResponse.ok(service.sendTest(currentUser.id(), id, request));
    }

    /** Counts as today's run. Returns the run, still RUNNING: it executes after this response. */
    @PostMapping("/{id}/send-now")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<PushCampaignRunDto> sendPushCampaignNow(@PathVariable UUID id) {
        return ApiResponse.ok(service.sendNow(currentUser.id(), id));
    }

    @PostMapping("/{id}/start")
    public ApiResponse<PushCampaignDto> startPushCampaign(@PathVariable UUID id) {
        return ApiResponse.ok(service.start(currentUser.id(), id));
    }

    @PostMapping("/{id}/pause")
    public ApiResponse<PushCampaignDto> pausePushCampaign(@PathVariable UUID id) {
        return ApiResponse.ok(service.pause(currentUser.id(), id));
    }

    @PostMapping("/{id}/resume")
    public ApiResponse<PushCampaignDto> resumePushCampaign(@PathVariable UUID id) {
        return ApiResponse.ok(service.resume(currentUser.id(), id));
    }

    /**
     * The emergency brake: cancels a run still queuing people and withdraws every queued-but-unsent
     * push of this campaign, in any status. Does not end the campaign; Stop does that and does this too.
     */
    @PostMapping("/{id}/cancel-sending")
    public ApiResponse<PushCampaignCancelResultDto> cancelPushCampaignSending(@PathVariable UUID id) {
        return ApiResponse.ok(service.cancelSending(currentUser.id(), id));
    }

    @PostMapping("/{id}/stop")
    public ApiResponse<PushCampaignDto> stopPushCampaign(@PathVariable UUID id) {
        return ApiResponse.ok(service.stop(currentUser.id(), id));
    }
}
