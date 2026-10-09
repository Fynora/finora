package com.finora.notification.campaign;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Everyone who can be reached by push: the base rule and nothing more. */
@Component
public class AllWithDeviceAudience implements AudienceResolver {

    private final JdbcTemplate jdbc;

    public AllWithDeviceAudience(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public AudienceType type() {
        return AudienceType.ALL_WITH_DEVICE;
    }

    @Override
    public long count() {
        return AudienceSql.count(jdbc, "");
    }

    @Override
    public List<UUID> page(UUID afterUserId, int limit) {
        return AudienceSql.page(jdbc, "", afterUserId, limit);
    }
}
