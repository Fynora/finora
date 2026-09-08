package com.finora.entity;

/** Allow-list for {@link FeatureViewCount#getFeature()} -- same role as onboarding's
 *  {@code ChecklistItemKey}: keeps arbitrary caller-supplied strings out of the view-count table. */
public enum TrackedFeature {
    INSIGHTS
}
