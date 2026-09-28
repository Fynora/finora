package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.dto.SavedStatementPasswordDtos.SavedStatementPassword;
import com.finora.dto.SavedStatementPasswordDtos.SavedStatementPasswordList;
import com.finora.dto.SavedStatementPasswordDtos.SavedStatementPasswordsRemoved;
import com.finora.imports.passwords.StatementPasswordService;
import com.finora.security.CurrentUser;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Settings -> Saved statement passwords (statement refresh, step 4): which statements have a
 * password the user agreed to let Fynora keep, and removing one or all of them. Removing always
 * works, even with saving switched off; the password itself is never returned.
 */
@RestController
@RequestMapping("/api/v1/statement-passwords")
public class StatementPasswordController {

    private final StatementPasswordService statementPasswordService;
    private final CurrentUser currentUser;

    public StatementPasswordController(StatementPasswordService statementPasswordService, CurrentUser currentUser) {
        this.statementPasswordService = statementPasswordService;
        this.currentUser = currentUser;
    }

    @GetMapping
    public ApiResponse<SavedStatementPasswordList> listSavedStatementPasswords() {
        var items = statementPasswordService.list(currentUser.id()).stream()
                .map(r -> new SavedStatementPassword(r.getStatementImportId(), r.getFileName(), r.getAccountName(),
                        r.getPeriodStart(), r.getPeriodEnd(), r.getConsentedAt()))
                .toList();
        return ApiResponse.ok(new SavedStatementPasswordList(statementPasswordService.enabled(), items));
    }

    @DeleteMapping("/{statementImportId}")
    public ApiResponse<Void> removeSavedStatementPassword(@PathVariable UUID statementImportId) {
        statementPasswordService.remove(currentUser.id(), statementImportId);
        return ApiResponse.ok(null, "Saved password removed");
    }

    @DeleteMapping
    public ApiResponse<SavedStatementPasswordsRemoved> removeAllSavedStatementPasswords() {
        int removed = statementPasswordService.removeAll(currentUser.id());
        return ApiResponse.ok(new SavedStatementPasswordsRemoved(removed), "Saved passwords removed");
    }
}
