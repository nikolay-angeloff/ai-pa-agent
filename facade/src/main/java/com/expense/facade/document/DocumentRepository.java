package com.expense.facade.document;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DocumentRepository extends JpaRepository<Document, UUID> {

    boolean existsByGmailMessageId(String gmailMessageId);

    List<Document> findByStatus(DocumentStatus status);
}
