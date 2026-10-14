package com.finora.imports.original;

/** Published once a view row is saved; {@code OriginalViewNotices} sends the emails after commit. */
public record OriginalViewOpened(StatementOriginalView view) {}
