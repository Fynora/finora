package com.finora.service;

import com.finora.dto.AdminAuditLogRowDto;
import com.finora.entity.AuditLog;
import com.finora.repository.AuditLogRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminAuditLogServiceTest {

    /** A system entry with no account and no admin left the page's name lookup empty, and looking
     *  up a null account in that empty map threw, failing the whole feed. */
    @Test
    void anEntryWithNoAccountAndNoAdmin_isListedWithoutNames() {
        AuditLogRepository audit = mock(AuditLogRepository.class);
        UserRepository users = mock(UserRepository.class);
        AuditLog entry = new AuditLog();
        entry.setAction("SYSTEM_SWEEP");
        when(audit.search(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(entry), PageRequest.of(0, 25), 1));

        List<AdminAuditLogRowDto> rows = new AdminAuditLogService(audit, users)
                .globalFeed(null, null, null, null, null, null, null, PageRequest.of(0, 25), true).content();

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).userId()).isNull();
        assertThat(rows.get(0).userName()).isNull();
        verify(users, never()).findAllById(any());
    }
}
