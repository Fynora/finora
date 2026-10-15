package com.finora.imports.original;

/**
 * Published once a view row is saved; {@code OriginalViewNotices} emails the Super Admins after
 * commit. The reason travels here, in memory, because the view row does not keep it (see V271).
 */
public record OriginalViewOpened(StatementOriginalView view, String reason) {}
