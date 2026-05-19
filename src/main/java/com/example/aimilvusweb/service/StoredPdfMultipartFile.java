package com.example.aimilvusweb.service;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * @Description: StoredPdfMultipartFile类，负责将本地落盘文件适配为 MultipartFile 供既有链路复用。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
 * @author: cx
 * @Date: 2026-05-19 23:15:00
 */
public class StoredPdfMultipartFile implements MultipartFile {

    private final String originalFilename;
    private final Path path;

    /**
     * @Description: 初始化本地文件 Multipart 适配器。
     * @Logic: 保存文件名与路径，文件名为空时回退默认值 report.pdf。
     * @Param: originalFilename 原始文件名；path 本地文件路径。
     * @Return: 无（仅初始化对象状态）。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-19 23:40:00
     */
    public StoredPdfMultipartFile(String originalFilename, Path path) {
        this.originalFilename = originalFilename == null ? "report.pdf" : originalFilename;
        this.path = path;
    }

    @Override
    /**
     * @Description: 返回 multipart 字段名。
     * @Logic: 固定返回 file，与 OCR 上传字段约定保持一致。
     * @Param: 无。
     * @Return: 字段名字符串。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-19 23:40:00
     */
    public String getName() {
        return "file";
    }

    @Override
    /**
     * @Description: 返回原始文件名。
     * @Logic: 直接返回构造时保存的文件名。
     * @Param: 无。
     * @Return: 原始文件名。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-19 23:40:00
     */
    public String getOriginalFilename() {
        return originalFilename;
    }

    @Override
    /**
     * @Description: 返回文件 MIME 类型。
     * @Logic: 固定返回 application/pdf。
     * @Param: 无。
     * @Return: MIME 类型字符串。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-19 23:40:00
     */
    public String getContentType() {
        return "application/pdf";
    }

    @Override
    /**
     * @Description: 判断文件是否为空。
     * @Logic: 读取文件大小并判断是否为 0；读取异常时按空文件处理。
     * @Param: 无。
     * @Return: true 表示空文件。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-19 23:40:00
     */
    public boolean isEmpty() {
        try {
            return Files.size(path) == 0L;
        } catch (IOException e) {
            return true;
        }
    }

    @Override
    /**
     * @Description: 获取文件大小。
     * @Logic: 读取本地文件字节大小，异常时抛出状态异常。
     * @Param: 无。
     * @Return: 文件字节大小。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-19 23:40:00
     */
    public long getSize() {
        try {
            return Files.size(path);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read stored file size", e);
        }
    }

    @Override
    /**
     * @Description: 读取全部文件字节。
     * @Logic: 直接从本地路径读取全部内容。
     * @Param: 无。
     * @Return: 文件字节数组。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-19 23:40:00
     */
    public byte[] getBytes() throws IOException {
        return Files.readAllBytes(path);
    }

    @Override
    /**
     * @Description: 获取文件输入流。
     * @Logic: 基于本地路径创建并返回输入流。
     * @Param: 无。
     * @Return: 文件输入流。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-19 23:40:00
     */
    public InputStream getInputStream() throws IOException {
        return Files.newInputStream(path);
    }

    @Override
    /**
     * @Description: 将本地文件复制到目标文件。
     * @Logic: 使用覆盖策略写入目标路径。
     * @Param: dest 目标文件对象。
     * @Return: 无（仅文件复制副作用）。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-19 23:40:00
     */
    public void transferTo(java.io.File dest) throws IOException, IllegalStateException {
        Files.copy(path, dest.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
}
