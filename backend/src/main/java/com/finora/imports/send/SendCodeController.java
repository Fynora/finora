package com.finora.imports.send;

import com.finora.dto.ApiResponse;
import com.finora.security.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Gate 2b spec §7, the phone's side: ask for a code to send a statement from a computer, then watch
 * for the file to arrive. Signed in. See {@link StatementSendService}.
 */
@RestController
@RequestMapping("/api/v1/imports/send-codes")
public class SendCodeController {

    private final StatementSendService service;
    private final CurrentUser currentUser;

    public SendCodeController(StatementSendService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    /** A new code, valid for 10 minutes. Cancels the user's open ones. */
    @PostMapping
    public ApiResponse<SendDto.CodeIssued> issue() {
        return ApiResponse.ok(service.issue(currentUser.id()));
    }

    /** The latest code's state, polled by the phone while it shows the code. */
    @GetMapping("/current")
    public ApiResponse<SendDto.CodeStatus> current() {
        return ApiResponse.ok(service.current(currentUser.id()));
    }
}
