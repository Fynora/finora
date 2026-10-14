package com.finora.imports.blanked;

/** A held statement's bytes, already unlocked, handed from a queue's service to the blanked-copy
 *  service and nowhere else. {@code reference} is the held item's id, never its file name. */
public record StaffStatementContent(String reference, String sourceFormat, byte[] content) {}
