package com.finora.service;

import com.finora.repository.UserRepository;
import com.finora.util.PhoneNumbers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Removes the phone provider's records (Firebase users) for numbers no Fynora account holds.
 *
 * <p>Every number verified through Fynora's phone sign-in leaves a Firebase user behind. Account
 * deletion removes the account's own number ({@code AccountPurgeSweepService}), but other paths
 * leave one too, with nothing in Fynora still pointing at it: the old number after a phone change,
 * a number verified for a change that was never completed, a number verified and then refused for
 * not matching the account. This daily pass is what makes sure those go as well.
 *
 * <p>A record is kept when its number is on any account that is not deleted (deactivated,
 * suspended and pending-deletion accounts included), or when it was created or signed in with in
 * the last {@link #GRACE}, so a verification still in progress is never touched. Deleting a record
 * by mistake costs nothing lasting: every client signs out of Firebase straight after verifying,
 * and the next verification of that number creates the record again.
 *
 * <p>If no account at all has a number, the pass does nothing: that is far likelier to be a broken
 * read than the truth, and acting on it would empty the provider.
 *
 * <p>Logs counts only, never a number.
 */
@Component
public class PhoneIdentitySweepService {

    private static final Logger log = LoggerFactory.getLogger(PhoneIdentitySweepService.class);

    static final Duration GRACE = Duration.ofHours(24);

    private final PhoneVerificationProvider phoneVerificationProvider;
    private final UserRepository userRepository;

    @Value("${app.phone-identity-sweep.enabled:true}")
    private boolean enabled;

    public PhoneIdentitySweepService(PhoneVerificationProvider phoneVerificationProvider, UserRepository userRepository) {
        this.phoneVerificationProvider = phoneVerificationProvider;
        this.userRepository = userRepository;
    }

    @Scheduled(cron = "0 45 3 * * *")
    public void scheduledSweep() {
        if (!enabled) {
            return;
        }
        try {
            Result result = sweep(Instant.now());
            if (result.deleted() > 0 || result.failed() > 0) {
                log.info("Phone identity sweep: {} record(s) deleted, {} failed, {} kept.",
                        result.deleted(), result.failed(), result.kept());
            }
        } catch (RuntimeException e) {
            log.error("Phone identity sweep could not run: {}", e.getMessage(), e);
        }
    }

    /** One pass. Public so a test can drive it with a fixed clock; the scheduler goes through
     *  {@link #scheduledSweep}, which alone consults the enabled flag. */
    public Result sweep(Instant now) {
        if (!phoneVerificationProvider.isConfigured()) {
            return new Result(0, 0, 0);
        }
        List<String> stored = userRepository.listContactNumbersInBothPortals();
        if (stored.isEmpty()) {
            log.warn("Phone identity sweep skipped: no account has a phone number, which is more likely a failed read.");
            return new Result(0, 0, 0);
        }
        Set<String> live = new HashSet<>();
        for (String number : stored) {
            live.add(PhoneNumbers.normalize(number));
        }

        Instant cutoff = now.minus(GRACE);
        int deleted = 0;
        int failed = 0;
        int kept = 0;
        for (PhoneVerificationProvider.PhoneIdentity identity : phoneVerificationProvider.listPhoneIdentities()) {
            if (identity.lastActiveAt().isAfter(cutoff) || belongsToAnAccount(identity.e164PhoneNumber(), live, stored)) {
                kept++;
                continue;
            }
            try {
                phoneVerificationProvider.deleteIdentityForPhoneNumber(identity.e164PhoneNumber());
                deleted++;
            } catch (RuntimeException e) {
                // One failure does not stop the rest; the record is found again tomorrow.
                failed++;
                log.error("Phone identity sweep could not delete one record: {}", e.getMessage(), e);
            }
        }
        return new Result(deleted, failed, kept);
    }

    /** Exact E.164 first; then the digit-level comparison PhoneNumbers.sameNumber does, for a
     *  stored number in a legacy form normalize() does not bring to the same string. */
    private static boolean belongsToAnAccount(String e164, Set<String> live, List<String> stored) {
        if (live.contains(PhoneNumbers.normalize(e164))) {
            return true;
        }
        for (String number : stored) {
            if (PhoneNumbers.sameNumber(e164, number)) {
                return true;
            }
        }
        return false;
    }

    public record Result(int deleted, int failed, int kept) {
    }
}
