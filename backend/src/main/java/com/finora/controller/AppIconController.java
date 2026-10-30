package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.dto.AppIconDtos.AppIconDto;
import com.finora.service.AppIconService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * Which home-screen icon the mobile app should show right now: {@code {"icon":"diwali","until":...}}
 * during a scheduled window, {@code {"icon":"default"}} otherwise.
 *
 * <p>Public, no login (SecurityConfig permits GET on this path): the answer is the same for every
 * caller and reveals nothing about anyone. The app asks on launch and resume, including before
 * sign-in, so requiring a session would leave signed-out phones on a stale icon.
 *
 * <p>Cheap by construction: one cached Redis read ({@code AppIconWindowSource}), no database query
 * on a hit. It sits under no per-IP rate limiter on purpose -- a limiter is for endpoints with a
 * real per-call cost (see RateLimitFilter's class comment), and many phones on one mobile carrier
 * share an IP, so a limit here would mostly fail real users. {@code max-age} lets the app's HTTP
 * client and any shared cache reuse the answer for a minute.
 */
@RestController
@RequestMapping("/api/v1/app")
public class AppIconController {

    /** How long a client may reuse an answer. Matches the server cache's TTL. */
    static final Duration CLIENT_MAX_AGE = Duration.ofSeconds(60);

    private final AppIconService service;

    public AppIconController(AppIconService service) {
        this.service = service;
    }

    @GetMapping("/icon")
    public ResponseEntity<ApiResponse<AppIconDto>> currentAppIcon() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(CLIENT_MAX_AGE).cachePublic())
                .body(ApiResponse.ok(service.current()));
    }
}
