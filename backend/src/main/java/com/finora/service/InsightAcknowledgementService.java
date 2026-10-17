package com.finora.service;

import com.finora.dto.InsightAcknowledgementDto;
import com.finora.repository.InsightAcknowledgementRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** The Insights rows a user marked "Got it" (V277). The app hides them; nothing else reads them. */
@Service
public class InsightAcknowledgementService {

    private final InsightAcknowledgementRepository repository;

    public InsightAcknowledgementService(InsightAcknowledgementRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<InsightAcknowledgementDto> list(UUID userId) {
        return repository.findByUserId(userId).stream()
                .map(a -> new InsightAcknowledgementDto(a.getKind(), a.getItemKey()))
                .toList();
    }

    @Transactional
    public void acknowledge(UUID userId, InsightAcknowledgementDto request) {
        repository.insertIfAbsent(userId, request.kind(), request.itemKey());
    }
}
