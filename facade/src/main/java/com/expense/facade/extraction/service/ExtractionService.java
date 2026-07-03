package com.expense.facade.extraction.service;

import com.expense.facade.document.entity.Document;
import com.expense.facade.extraction.dto.ExtractionResult;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Service
public class ExtractionService {

    private static final String SYSTEM_PROMPT = """
            You are an expense extraction assistant. Analyze the document and extract structured data.

            Return ONLY valid JSON with these exact fields — no markdown, no explanation:
            {
              "documentType": "receipt" | "invoice" | "unknown",
              "merchant":     "<business name or null>",
              "amount":       <total amount as number or null>,
              "currency":     "<ISO 4217 code or null>",
              "date":         "<YYYY-MM-DD or null>",
              "confidence":   <0.0 to 1.0>
            }

            Rules:
            - documentType: "receipt" for retail receipts, "invoice" for business invoices, "unknown" if neither.
            - amount: FINAL total paid, numeric only (no currency symbol).
            - currency: infer from context if not printed; use null only if truly indeterminate.
            - confidence: 0.9+ = very clear, 0.5–0.9 = some uncertainty, <0.5 = poor quality.
            - If not a receipt or invoice, set documentType to "unknown" and confidence to 0.1.
            """;

    private final ChatClient chatClient;

    public ExtractionService(ChatClient.Builder builder) {
        this.chatClient = builder.defaultSystem(SYSTEM_PROMPT).build();
    }

    public ExtractionResult extract(Document document) throws IOException {
        Path filePath = Path.of(document.getRawStoragePath());
        byte[] bytes = Files.readAllBytes(filePath);
        String filename = filePath.getFileName().toString().toLowerCase();

        if (filename.endsWith(".pdf")) {
            return extractFromPdf(bytes);
        }
        return extractFromImage(bytes, filename);
    }

    private ExtractionResult extractFromImage(byte[] bytes, String filename) {
        var mimeType = filename.endsWith(".png") ? MimeTypeUtils.IMAGE_PNG : MimeTypeUtils.IMAGE_JPEG;
        var resource = new ByteArrayResource(bytes);

        return chatClient.prompt()
                .user(u -> u.text("Extract expense data from this document.")
                            .media(mimeType, resource))
                .call()
                .entity(ExtractionResult.class);
    }

    private ExtractionResult extractFromPdf(byte[] bytes) throws IOException {
        String text;
        try (var pdf = Loader.loadPDF(bytes)) {
            text = new PDFTextStripper().getText(pdf);
        }

        return chatClient.prompt()
                .user("Extract expense data from this document text:\n\n" + text)
                .call()
                .entity(ExtractionResult.class);
    }
}
