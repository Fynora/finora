package com.finora.dto;

/** Opens a protected PDF for this refresh only; never stored or logged. Null when the statement is not protected. */
public record StatementRefreshRequest(String password) {}
