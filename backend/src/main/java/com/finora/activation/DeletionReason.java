package com.finora.activation;

/**
 * The fixed answers to "Before you go: what's the main reason?" (Gate 2a spec §5), in the order the
 * clients show them. A new reason needs a change here and in V278's CHECK constraint, never a
 * client-only change.
 */
public enum DeletionReason {
    /** "I couldn't get my statements in" */
    COULDNT_GET_STATEMENTS_IN,
    /** "I'm not comfortable with my data here" */
    NOT_COMFORTABLE_WITH_DATA,
    /** "It didn't show me anything useful" */
    NOTHING_USEFUL,
    /** "I use another app" */
    USE_ANOTHER_APP,
    /** "I don't need it anymore" */
    DONT_NEED_IT,
    /** "Something else" */
    SOMETHING_ELSE
}
