package com.finora.support;

import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.repository.UserRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;

/**
 * Takes the customer's row lock before a support ticket, feedback entry or suspension request is
 * counted and saved, so requests sent at once are handled one after another and the daily ceilings
 * and the receipt cap count every earlier one.
 *
 * <p>With a wait limit. The lock ({@code FOR NO KEY UPDATE}) is not held up by work that only
 * references the user -- an import adding thousands of transactions does not block it -- but it does
 * wait for anything updating the user's own row. Every such write today finishes in milliseconds;
 * the limit is so that a future slow one can never leave a customer's "send" spinning, holding a
 * database connection, for as long as it runs. After {@link #WAIT} the request is refused with a
 * message that keeps the form as it is (the apps show it and leave the text in place), never
 * "refresh", which would lose what the customer wrote.
 *
 * <p>Applies to the rest of the caller's transaction ({@code SET LOCAL}), so the inserts that
 * follow are bounded by the same wait. Must be called inside a transaction.
 */
public final class SubmissionLock {

    /** Postgres interval syntax, for {@code lock_timeout}. */
    static final String WAIT = "5s";

    static final String BUSY_MESSAGE =
            "We couldn't send that just now because your account is busy. Please try again in a moment.";

    private SubmissionLock() {}

    public static Optional<User> acquire(UserRepository users, UUID userId) {
        try {
            users.setLocalLockTimeout(WAIT);
            return users.findByIdForUpdate(userId);
        } catch (PessimisticLockingFailureException e) {
            throw new ApiException(HttpStatus.CONFLICT, BUSY_MESSAGE);
        }
    }
}
