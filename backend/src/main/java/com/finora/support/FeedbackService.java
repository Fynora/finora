package com.finora.support;

import com.finora.dto.PagedResponse;
import com.finora.entity.FeedbackEntry;
import com.finora.entity.User;
import com.finora.repository.FeedbackEntryRepository;
import com.finora.repository.UserRepository;
import com.finora.util.PageBounds;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Orchestration for feedback: submission and the admin list. Smaller than
 * {@link SupportTicketService} because feedback has no ownership check to enforce on read — the
 * only read path is the admin list, gated entirely by the controller's class-level
 * {@code @PreAuthorize}, so unlike tickets there is no dual-audience route here and nothing for a
 * service-level ownership predicate to do.
 */
@Service
public class FeedbackService {

    private final FeedbackEntryRepository repository;
    private final ClientIdentity clientIdentity;
    private final UserRepository userRepository;

    public FeedbackService(FeedbackEntryRepository repository, ClientIdentity clientIdentity,
                           UserRepository userRepository) {
        this.repository = repository;
        this.clientIdentity = clientIdentity;
        this.userRepository = userRepository;
    }

    @Transactional
    public FeedbackDto.Summary submit(UUID userId, FeedbackEntry.Type type, FeedbackEntry.Context context, String message) {
        FeedbackEntry entry = new FeedbackEntry();
        entry.setUserId(userId);
        entry.setType(type);
        entry.setContext(context);
        entry.setMessage(message.trim());
        entry.setSource(clientIdentity.platform());
        entry.setAppVersion(clientIdentity.appVersion());
        return FeedbackDto.Summary.from(repository.save(entry));
    }

    /**
     * One page of feedback with who sent each entry. Senders are fetched in one batch for the
     * page rather than per row; the page is at most {@code PageBounds}' ceiling, so this is a
     * single {@code IN} query either way.
     */
    @Transactional(readOnly = true)
    public PagedResponse<FeedbackDto.AdminRow> adminList(FeedbackEntry.Type type, FeedbackEntry.Context context,
                                                          int page, int size) {
        Pageable pageable = PageRequest.of(PageBounds.safePage(page), PageBounds.safeSize(size));
        Page<FeedbackEntry> entries = repository.findForAdmin(type, context, pageable);

        List<UUID> senderIds = entries.getContent().stream()
                .map(FeedbackEntry::getUserId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<UUID, User> senders = senderIds.isEmpty()
                ? Map.of()
                : userRepository.findAllById(senderIds).stream()
                        .collect(Collectors.toMap(User::getId, Function.identity(), (a, b) -> a));

        return PagedResponse.of(entries.map(e ->
                FeedbackDto.AdminRow.from(e, e.getUserId() == null ? null : senders.get(e.getUserId()))));
    }

    /** Phase 9's counts panel — unfiltered by design, same as {@code countGrouped()}'s own doc
     *  comment scopes it: a breakdown across everything, not a filtered slice matching whatever the
     *  list view's type/context filter happens to be set to. */
    @Transactional(readOnly = true)
    public FeedbackDto.Breakdown breakdown() {
        return FeedbackDto.Breakdown.from(repository.countGrouped());
    }
}
