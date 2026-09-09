package com.finora.service;

import com.finora.entity.FeatureViewCount;
import com.finora.entity.TrackedFeature;
import com.finora.repository.FeatureViewCountRepository;
import com.finora.util.EnumParsing;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Backs the Billing page's "Smart Insights" usage tile (and any future feature-level view count)
 *  with a real per-user counter -- see V171__feature_view_counts.sql. */
@Service
public class FeatureUsageService {

    private final FeatureViewCountRepository repository;

    public FeatureUsageService(FeatureViewCountRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void recordView(UUID userId, String feature) {
        TrackedFeature key = EnumParsing.parse(TrackedFeature.class, feature, "feature");
        repository.recordView(userId, key.name());
    }

    public int viewCount(UUID userId, String feature) {
        TrackedFeature key = EnumParsing.parse(TrackedFeature.class, feature, "feature");
        return repository.findByUserIdAndFeature(userId, key.name())
                .map(FeatureViewCount::getViewCount)
                .orElse(0);
    }
}
