package com.expense.facade.document;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "document")
public class Document {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 32)
    private String source;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private DocumentType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private DocumentStatus status = DocumentStatus.RAW;

    @Column(unique = true, length = 255)
    private String gmailMessageId;

    @Column(length = 255)
    private String gmailAttachmentId;

    @Column(columnDefinition = "TEXT")
    private String subject;

    @Column(length = 255)
    private String sender;

    @Column(nullable = false)
    private OffsetDateTime receivedAt;

    @Column(columnDefinition = "TEXT")
    private String rawStoragePath;

    @Column(columnDefinition = "TEXT")
    private String rawText;

    // JSON-serialized ExtractionResult; read by ExpensePersistenceService in Step 4.
    @Column(columnDefinition = "TEXT")
    private String extractedData;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    // --- getters / setters ---

    public UUID getId() { return id; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public DocumentType getType() { return type; }
    public void setType(DocumentType type) { this.type = type; }

    public DocumentStatus getStatus() { return status; }
    public void setStatus(DocumentStatus status) { this.status = status; }

    public String getGmailMessageId() { return gmailMessageId; }
    public void setGmailMessageId(String gmailMessageId) { this.gmailMessageId = gmailMessageId; }

    public String getGmailAttachmentId() { return gmailAttachmentId; }
    public void setGmailAttachmentId(String gmailAttachmentId) { this.gmailAttachmentId = gmailAttachmentId; }

    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }

    public String getSender() { return sender; }
    public void setSender(String sender) { this.sender = sender; }

    public OffsetDateTime getReceivedAt() { return receivedAt; }
    public void setReceivedAt(OffsetDateTime receivedAt) { this.receivedAt = receivedAt; }

    public String getRawStoragePath() { return rawStoragePath; }
    public void setRawStoragePath(String rawStoragePath) { this.rawStoragePath = rawStoragePath; }

    public String getRawText() { return rawText; }
    public void setRawText(String rawText) { this.rawText = rawText; }

    public String getExtractedData() { return extractedData; }
    public void setExtractedData(String extractedData) { this.extractedData = extractedData; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
}
