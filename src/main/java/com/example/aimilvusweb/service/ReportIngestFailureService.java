package com.example.aimilvusweb.service;

import com.example.aimilvusweb.entity.ReportIngestFailure;
import com.example.aimilvusweb.repository.ReportIngestFailureMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;

@Service
public class ReportIngestFailureService {

    private static final int MAX_ERROR_MESSAGE_LENGTH = 2000;

    private final ReportIngestFailureMapper reportIngestFailureMapper;

    public ReportIngestFailureService(ReportIngestFailureMapper reportIngestFailureMapper) {
        this.reportIngestFailureMapper = reportIngestFailureMapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(MultipartFile file,
                              String title,
                              String source,
                              String institution,
                              String stage,
                              Exception exception) {
        ReportIngestFailure failure = new ReportIngestFailure();
        failure.setFilename(file == null ? null : file.getOriginalFilename());
        failure.setTitle(title);
        failure.setSource(source);
        failure.setInstitution(institution);
        failure.setStage(stage == null || stage.isBlank() ? "UNKNOWN" : stage);
        failure.setErrorMessage(limitErrorMessage(exception));
        failure.setCreatedAt(Instant.now());
        reportIngestFailureMapper.insert(failure);
    }

    private String limitErrorMessage(Exception exception) {
        String message = exception == null ? "Unknown ingest failure" : exception.getMessage();
        if (message == null || message.isBlank()) {
            message = exception == null ? "Unknown ingest failure" : exception.getClass().getSimpleName();
        }
        if (message.length() <= MAX_ERROR_MESSAGE_LENGTH) {
            return message;
        }
        return message.substring(0, MAX_ERROR_MESSAGE_LENGTH);
    }
}
