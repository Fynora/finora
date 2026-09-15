package com.finora.repository;

import com.finora.entity.SharedMerchantCategory;
import com.finora.entity.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SharedMerchantCategoryRepository extends JpaRepository<SharedMerchantCategory, UUID> {
    Optional<SharedMerchantCategory> findByCounterpartyKeyAndDirection(String counterpartyKey, Transaction.Type direction);
    long countByStatus(SharedMerchantCategory.Status status);
}
