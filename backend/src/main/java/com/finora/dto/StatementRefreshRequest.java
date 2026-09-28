package com.finora.dto;

/**
 * Opens a protected PDF for this refresh. Null when the statement is not protected or its password
 * was saved. {@code savePassword} is the user's consent to keep {@code password}, encrypted, for
 * this statement (statement refresh, step 4); without it the password is used once and never stored.
 * Never logged either way.
 */
public record StatementRefreshRequest(String password, Boolean savePassword) {}
