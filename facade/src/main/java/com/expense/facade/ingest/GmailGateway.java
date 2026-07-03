package com.expense.facade.ingest;

import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.MessagePart;
import com.google.api.services.gmail.model.MessagePartBody;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

@Service
public class GmailGateway {

    private static final String USER = "me";
    private static final String QUERY = "has:attachment (filename:pdf OR filename:jpg OR filename:jpeg OR filename:png)";
    private static final long MAX_RESULTS = 50L;

    private static final List<String> RECEIPT_MIME_TYPES = List.of(
            "application/pdf", "image/jpeg", "image/jpg",
            "image/png", "image/gif", "image/webp", "image/tiff");

    private final Gmail gmail;

    public GmailGateway(Gmail gmail) {
        this.gmail = gmail;
    }

    List<Message> listMessages() throws IOException {
        var response = gmail.users().messages().list(USER)
                .setQ(QUERY)
                .setMaxResults(MAX_RESULTS)
                .execute();
        return response.getMessages() != null ? response.getMessages() : List.of();
    }

    Message getMessage(String messageId) throws IOException {
        return gmail.users().messages().get(USER, messageId).execute();
    }

    byte[] getAttachmentBytes(String messageId, MessagePart part) throws IOException {
        String inline = part.getBody().getData();
        if (inline != null && !inline.isEmpty()) {
            return Base64.getUrlDecoder().decode(inline);
        }
        MessagePartBody body = gmail.users().messages().attachments()
                .get(USER, messageId, part.getBody().getAttachmentId())
                .execute();
        return Base64.getUrlDecoder().decode(body.getData());
    }

    /** Recursively collect parts that look like receipt attachments. */
    List<MessagePart> receiptAttachmentParts(Message message) {
        List<MessagePart> result = new ArrayList<>();
        collectParts(message.getPayload(), result);
        return result;
    }

    private void collectParts(MessagePart part, List<MessagePart> out) {
        if (part == null) return;
        String filename = part.getFilename();
        if (filename != null && !filename.isBlank() && isReceiptMime(part.getMimeType(), filename)) {
            out.add(part);
        }
        if (part.getParts() != null) {
            for (MessagePart child : part.getParts()) {
                collectParts(child, out);
            }
        }
    }

    private boolean isReceiptMime(String mimeType, String filename) {
        if (mimeType != null && RECEIPT_MIME_TYPES.contains(mimeType.toLowerCase())) return true;
        String lower = filename.toLowerCase();
        return lower.endsWith(".pdf") || lower.endsWith(".jpg")
                || lower.endsWith(".jpeg") || lower.endsWith(".png");
    }

    String headerValue(Message message, String name) {
        if (message.getPayload() == null || message.getPayload().getHeaders() == null) return null;
        return message.getPayload().getHeaders().stream()
                .filter(h -> name.equalsIgnoreCase(h.getName()))
                .map(com.google.api.services.gmail.model.MessagePartHeader::getValue)
                .findFirst().orElse(null);
    }
}
