package com.example.aimilvusweb.service.ingest;

import com.example.aimilvusweb.entity.ReportIngestFailure;
import com.example.aimilvusweb.repository.ReportIngestFailureMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;

@Service
/**
 * @Description: ReportIngestFailureService类，负责相关业务能力的组织与实现。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
 * @author: cx
 * @Date: 2026-05-17 10:24:01
 */
public class ReportIngestFailureService {

    private static final int MAX_ERROR_MESSAGE_LENGTH = 2000;

    private final ReportIngestFailureMapper reportIngestFailureMapper;

    /**
     * @Description: 初始化ReportIngestFailureService依赖与运行所需组件。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    public ReportIngestFailureService(ReportIngestFailureMapper reportIngestFailureMapper) {
        this.reportIngestFailureMapper = reportIngestFailureMapper;
    }

    /**
     * @Description: 执行recordFailure相关业务处理。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
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

    /**
     * @Description: 按阈值限制输出内容范围。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
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
