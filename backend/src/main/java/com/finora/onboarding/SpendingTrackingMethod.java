package com.finora.onboarding;

/**
 * The answers to the required setup question "How do you keep track of your spending today?" --
 * a closed set, one answer per user, never free text. Display copy lives in the clients; this name
 * is a stable identifier only, so the wording can change without a migration. V256's CHECK lists
 * the same names: add one there too.
 */
public enum SpendingTrackingMethod {
    /** "I don't really track it" */
    NOT_TRACKED,
    /** "Roughly, in my head" */
    IN_MY_HEAD,
    /** "A notebook or on paper" */
    PAPER,
    /** "A spreadsheet (Excel, Google Sheets)" */
    SPREADSHEET,
    /** "An expense or budgeting app" */
    EXPENSE_APP,
    /** "My bank's app or statements" */
    BANK_APP,
    /** "Something else" */
    OTHER
}
