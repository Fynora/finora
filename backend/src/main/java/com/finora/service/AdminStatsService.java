package com.finora.service;

import com.finora.dto.AdminDtos.PlatformStatsDto;
import com.finora.dto.AdminDtos.SignupSourceCountDto;
import com.finora.repository.AccountRepository;
import com.finora.repository.SignupAttributionRepository;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Aggregate, platform-wide counts for the admin Dashboard (frontend-admin/) -- deliberately just
 * COUNT queries against existing repositories, not a new reporting/analytics subsystem. Every
 * number here is a point-in-time snapshot computed on request, not cached or pre-aggregated:
 * these are simple indexed counts (user count, status count, date-threshold count), cheap enough
 * to run live at the traffic this platform sees today. Gated by PLATFORM_STATS_VIEW -- see
 * V24__admin_platform_stats_permission.sql.
 */
@Service
public class AdminStatsService {

    private final UserRepository userRepository;
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final StatementImportRepository statementImportRepository;
    private final SignupAttributionRepository signupAttributionRepository;

    public AdminStatsService(UserRepository userRepository, AccountRepository accountRepository,
                              TransactionRepository transactionRepository,
                              StatementImportRepository statementImportRepository,
                              SignupAttributionRepository signupAttributionRepository) {
        this.userRepository = userRepository;
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.statementImportRepository = statementImportRepository;
        this.signupAttributionRepository = signupAttributionRepository;
    }

    /**
     * V286: sign-ups by source, excluding the bootstrap account the same way overview() does
     * (internal, ADMIN-scope and deleted accounts are left out in the query itself). A tag that
     * looks like contact details is shown withheld, as on the activation page, and the withheld
     * lines are added together; the list stays largest first.
     */
    @Transactional(readOnly = true)
    public java.util.List<SignupSourceCountDto> signupSources() {
        java.util.Map<String, Long> merged = new java.util.LinkedHashMap<>();
        for (var row : signupAttributionRepository.countBySource(BootstrapService.BOOTSTRAP_IDENTIFIER)) {
            merged.merge(com.finora.activation.SignupSources.displayableUtm(row.getSource()), row.getUsers(), Long::sum);
        }
        return merged.entrySet().stream()
                .map(e -> new SignupSourceCountDto(e.getKey(), e.getValue()))
                .sorted(java.util.Comparator.comparingLong(SignupSourceCountDto::users).reversed()
                        .thenComparing(SignupSourceCountDto::source))
                .toList();
    }

    @Transactional(readOnly = true)
    public PlatformStatsDto overview() {
        Instant now = Instant.now();
        // Bug fix: totalUsers and suspendedUsers used to filter on countByRoleNot/
        // countByStatusAndRoleNot("BOOTSTRAP_ADMIN") instead of by email. That only excluded the
        // bootstrap account WHILE its legacy User.role column still held "BOOTSTRAP_ADMIN" -- true
        // only during the setup wizard. SetupService.completeSetup() calls RoleService.revokeRole,
        // which resets that column to DEFAULT_ROLE ("USER") and sets status=SUSPENDED, so the
        // role-based filters silently stopped excluding it the instant setup finished: totalUsers
        // overcounted by one forever, and suspendedUsers (status=SUSPENDED matches, role filter no
        // longer excludes it) counted the bootstrap account as a real suspended user forever. See
        // UserRepositoryIT and UserRepository.countByEmailNot's own doc comment.
        long totalUsers = userRepository.countByEmailNot(BootstrapService.BOOTSTRAP_IDENTIFIER);
        long suspendedUsers = userRepository.countByStatusAndEmailNot("SUSPENDED", BootstrapService.BOOTSTRAP_IDENTIFIER);
        // Separate bug fix: this used to derive activeUsers as totalUsers - suspendedUsers, on the
        // reasoning that status was a two-value column (V23's original CHECK constraint) so
        // "active" was always exactly "everyone else." V87 widened status to include DEACTIVATED
        // (and reserves room for more self-service states in a later phase) -- the subtraction
        // silently started counting deactivated accounts as active, with no test catching it
        // (AdminStatsServiceTest only ever exercised the ACTIVE/SUSPENDED world). Counting ACTIVE
        // explicitly is immune to however many more statuses this column grows to hold.
        long activeUsers = userRepository.countByStatusAndEmailNot("ACTIVE", BootstrapService.BOOTSTRAP_IDENTIFIER);

        return new PlatformStatsDto(
                totalUsers,
                activeUsers,
                suspendedUsers,
                userRepository.countByCreatedAtAfter(now.minus(7, ChronoUnit.DAYS)),
                userRepository.countByCreatedAtAfter(now.minus(30, ChronoUnit.DAYS)),
                accountRepository.count(),
                transactionRepository.count(),
                statementImportRepository.count()
        );
    }
}
