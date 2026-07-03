package com.expense.facade.ingest.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Service
public class RawStorageService {

    @Value("${app.storage.raw-path:/tmp/raw}")
    private String basePath;

    public String save(String messageId, String filename, byte[] data) throws IOException {
        Path dir = Path.of(basePath, messageId);
        Files.createDirectories(dir);
        Path file = dir.resolve(sanitize(filename));
        Files.write(file, data);
        return file.toString();
    }

    private String sanitize(String filename) {
        return filename.replaceAll("[^a-zA-Z0-9._\\-]", "_");
    }
}
