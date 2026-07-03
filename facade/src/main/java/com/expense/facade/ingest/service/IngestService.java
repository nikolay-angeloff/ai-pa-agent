package com.expense.facade.ingest.service;

import com.expense.facade.document.entity.Document;
import com.expense.facade.document.entity.DocumentStatus;
import com.expense.facade.document.entity.DocumentType;
import com.expense.facade.document.repository.DocumentRepository;
import com.expense.facade.ingest.dto.IngestResult;
import com.expense.facade.ingest.gateway.GmailGateway;
import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.MessagePart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);

    private final GmailGateway gmailGateway;
    private final RawStorageService rawStorage;
    private final DocumentRepository documentRepository;

    public IngestService(GmailGateway gmailGateway,
                         RawStorageService rawStorage,
                         DocumentRepository documentRepository) {
        this.gmailGateway = gmailGateway;
        this.rawStorage = rawStorage;
        this.documentRepository = documentRepository;
    }

    public IngestResult run() {
        int downloaded = 0, skipped = 0, failed = 0;

        List<Message> stubs;
        try {
            stubs = gmailGateway.listMessages();
        } catch (Exception e) {
            log.error("Failed to list Gmail messages: {}", e.getMessage());
            return new IngestResult(0, 0, 1);
        }

        for (Message stub : stubs) {
            String msgId = stub.getId();
            try {
                if (documentRepository.existsByGmailMessageId(msgId)) {
                    skipped++;
                    continue;
                }

                Message full = gmailGateway.getMessage(msgId);
                List<MessagePart> parts = gmailGateway.receiptAttachmentParts(full);
                if (parts.isEmpty()) {
                    skipped++;
                    continue;
                }

                MessagePart part = parts.get(0);
                byte[] data = gmailGateway.getAttachmentBytes(msgId, part);
                String path = rawStorage.save(msgId, part.getFilename(), data);

                save(full, path, part.getFilename(), part.getBody().getAttachmentId());
                downloaded++;
                log.info("Ingested message {} → {}", msgId, path);

            } catch (Exception e) {
                log.error("Failed to ingest message {}: {}", msgId, e.getMessage());
                failed++;
            }
        }

        log.info("Ingest complete — downloaded={} skipped={} failed={}", downloaded, skipped, failed);
        return new IngestResult(downloaded, skipped, failed);
    }

    @Transactional
    void save(Message full, String storagePath, String filename, String attachmentId) {
        OffsetDateTime receivedAt = full.getInternalDate() != null
                ? OffsetDateTime.ofInstant(Instant.ofEpochMilli(full.getInternalDate()), ZoneOffset.UTC)
                : OffsetDateTime.now(ZoneOffset.UTC);

        Document doc = new Document();
        doc.setSource("gmail");
        doc.setType(DocumentType.UNKNOWN);
        doc.setStatus(DocumentStatus.RAW);
        doc.setGmailMessageId(full.getId());
        doc.setGmailAttachmentId(attachmentId);
        doc.setSubject(gmailGateway.headerValue(full, "Subject"));
        doc.setSender(gmailGateway.headerValue(full, "From"));
        doc.setReceivedAt(receivedAt);
        doc.setRawStoragePath(storagePath);

        documentRepository.save(doc);
    }
}
