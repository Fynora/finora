package com.finora.service;

import com.finora.dto.SuspensionDtos.SuspensionRequestDto;
import com.finora.dto.SuspensionDtos.SuspensionStatusDto;
import com.finora.entity.Subscription;
import com.finora.entity.SupportTicket;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.repository.SubscriptionRepository;
import com.finora.repository.SupportTicketRepository;
import com.finora.repository.UserRepository;
import com.finora.support.SupportTicketService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The suspended screen's two calls (privacy workstream 10): what the customer is told about their
 * suspension, and raising an appeal or a deletion request.
 *
 * <p>Both requests become support tickets in the admin queue. Deletion is a request, not the
 * self-service flow: {@code UserAccountLifecycleService.requestDeletion} refuses a suspended account
 * on purpose, because a customer suspended for fraud must not be able to wipe the records under
 * review. Staff carry the deletion out once nothing has to be kept.
 *
 * <p>One open request of each kind at a time. "Open" is OPEN or IN_PROGRESS; once a ticket is
 * resolved or closed, the customer may raise another.
 */
@Service
public class AccountSuspensionService {

    /** Long enough to explain an appeal, short enough to read in the admin queue. */
    static final int MAX_REQUEST_TEXT_LENGTH = 4000;

    private static final Set<SupportTicket.Category> REQUEST_CATEGORIES =
            EnumSet.of(SupportTicket.Category.SUSPENSION_APPEAL, SupportTicket.Category.SUSPENSION_DELETION_REQUEST);
    private static final Set<SupportTicket.Status> OPEN_STATUSES =
            EnumSet.of(SupportTicket.Status.OPEN, SupportTicket.Status.IN_PROGRESS);

    private final UserRepository userRepository;
    private final SupportTicketRepository ticketRepository;
    private final SupportTicketService ticketService;
    private final SubscriptionRepository subscriptionRepository;

    public AccountSuspensionService(UserRepository userRepository, SupportTicketRepository ticketRepository,
                                    SupportTicketService ticketService, SubscriptionRepository subscriptionRepository) {
        this.userRepository = userRepository;
        this.ticketRepository = ticketRepository;
        this.ticketService = ticketService;
        this.subscriptionRepository = subscriptionRepository;
    }

    /** What the suspended screen shows. 404 for an account that is not suspended. */
    @Transactional(readOnly = true)
    public SuspensionStatusDto status(UUID userId) {
        User user = requireSuspended(userId);
        String reason = user.getSuspensionReason() == null ? null : user.getSuspensionReason().name();
        String reasonText = user.getSuspensionReason() == null
                ? "Your account was suspended before Fynora recorded reasons. Appeal and we will tell you why."
                : user.getSuspensionReason().customerText();
        List<SuspensionRequestDto> requests = ticketRepository
                .findByUserIdAndCategoryInOrderByCreatedAtDesc(userId, REQUEST_CATEGORIES).stream()
                .map(t -> new SuspensionRequestDto(typeOf(t.getCategory()), t.getTicketNumber(),
                        t.getStatus().name(), t.getCreatedAt()))
                .toList();
        return new SuspensionStatusDto(user.getSuspendedAt(), reason, reasonText, user.getSuspensionMessage(),
                storePlatform(userId), user.getSignInMethod(), requests);
    }

    /** Raises an appeal ({@code APPEAL}, text required) or a deletion request ({@code DELETION},
     *  text optional) as a support ticket, and returns the updated status. */
    @Transactional
    public SuspensionStatusDto request(UUID userId, String rawType, String text) {
        User user = requireSuspended(userId);
        SupportTicket.Category category = categoryOf(rawType);
        String trimmed = text == null ? "" : text.strip();
        if (trimmed.length() > MAX_REQUEST_TEXT_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Keep it under " + MAX_REQUEST_TEXT_LENGTH + " characters.");
        }
        boolean appeal = category == SupportTicket.Category.SUSPENSION_APPEAL;
        if (appeal && trimmed.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Tell us why you think the suspension is a mistake.");
        }
        boolean alreadyOpen = ticketRepository
                .findByUserIdAndCategoryInOrderByCreatedAtDesc(userId, EnumSet.of(category)).stream()
                .anyMatch(t -> OPEN_STATUSES.contains(t.getStatus()));
        if (alreadyOpen) {
            throw new ApiException(HttpStatus.CONFLICT, appeal
                    ? "Your appeal is already with us. We'll email you when we've reviewed it."
                    : "Your deletion request is already with us. We'll email you when it's done.");
        }
        String reason = user.getSuspensionReason() == null ? "not recorded" : user.getSuspensionReason().name();
        String subject = appeal ? "Suspension appeal" : "Delete my suspended account";
        String description = (appeal ? "Appeal against suspension" : "Request to delete a suspended account")
                + " (reason: " + reason + ")."
                + (trimmed.isEmpty() ? "" : "\n\nFrom the customer:\n" + trimmed);
        ticketService.createSuspensionRequest(userId, category, subject, description);
        return status(userId);
    }

    private User requireSuspended(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "User not found"));
        if (!user.isSuspended()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "This account is not suspended.");
        }
        return user;
    }

    /** "IOS" or "ANDROID" when the customer pays for Plus through a store and it is set to renew,
     *  which Fynora cannot stop; null otherwise. The screen names the store and how to cancel. */
    private String storePlatform(UUID userId) {
        Subscription s = subscriptionRepository.findActiveOrTrial(userId).orElse(null);
        if (s == null || !s.isAutoRenew()
                || !AccountSuspensionNotices.PROVIDER_REVENUECAT.equals(s.getPaymentProvider())) {
            return null;
        }
        return "IOS".equals(s.getStorePlatform()) ? "IOS" : "ANDROID";
    }

    private static SupportTicket.Category categoryOf(String rawType) {
        if ("APPEAL".equals(rawType)) return SupportTicket.Category.SUSPENSION_APPEAL;
        if ("DELETION".equals(rawType)) return SupportTicket.Category.SUSPENSION_DELETION_REQUEST;
        throw new ApiException(HttpStatus.BAD_REQUEST, "Choose APPEAL or DELETION.");
    }

    private static String typeOf(SupportTicket.Category category) {
        return category == SupportTicket.Category.SUSPENSION_APPEAL ? "APPEAL" : "DELETION";
    }
}
