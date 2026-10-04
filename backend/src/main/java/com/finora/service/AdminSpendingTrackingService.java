package com.finora.service;

import com.finora.dto.AdminDtos.SpendingTrackingBreakdown;
import com.finora.dto.AdminDtos.SpendingTrackingMethodCount;
import com.finora.onboarding.SpendingTrackingMethod;
import com.finora.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * How users kept track of their spending before Fynora -- the answers to the required setup
 * question (V256), counted across live consumer accounts. Counts only, never who answered what.
 */
@Service
public class AdminSpendingTrackingService {

    private final UserRepository userRepository;

    public AdminSpendingTrackingService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /** Every answer, in SpendingTrackingMethod order, a zero included; the accounts that have not
     *  answered are counted apart. */
    @Transactional(readOnly = true)
    public SpendingTrackingBreakdown breakdown() {
        Map<SpendingTrackingMethod, Long> byMethod = new EnumMap<>(SpendingTrackingMethod.class);
        long notAnswered = 0;
        for (UserRepository.SpendingTrackingCount row : userRepository.countBySpendingTrackingMethod()) {
            if (row.getMethod() == null) {
                notAnswered += row.getCount();
            } else {
                byMethod.merge(SpendingTrackingMethod.valueOf(row.getMethod()), row.getCount(), Long::sum);
            }
        }
        List<SpendingTrackingMethodCount> methods = new ArrayList<>();
        long answered = 0;
        for (SpendingTrackingMethod method : SpendingTrackingMethod.values()) {
            long count = byMethod.getOrDefault(method, 0L);
            methods.add(new SpendingTrackingMethodCount(method.name(), count));
            answered += count;
        }
        return new SpendingTrackingBreakdown(answered, notAnswered, methods);
    }
}
