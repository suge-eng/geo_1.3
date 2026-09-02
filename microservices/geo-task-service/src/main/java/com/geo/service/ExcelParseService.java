package com.geo.service;

import com.geo.common.BusinessException;
import com.geo.common.ResultCode;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 【Excel解析服务】
 *
 * 设计思路：
 * 系统有两处需要导入Excel：
 * 1. 批量导入问题（用户上传一个Excel，里面每一行是一个要问AI的问题）
 * 2. 批量导入白名单来源URL（给AI引用来源做信用评级时，哪些域名算"权威来源"）
 *
 * 这两个导入逻辑很像，所以抽取成两个独立方法，复用底层工具方法：
 * - validateFile：校验文件格式和扩展名（防止传错文件）
 * - createWorkbook：根据扩展名选xlsx还是xls的解析器（两个格式API不同）
 * - getCellValueAsString：各种单元格类型（字符串/数字/日期/公式）都转成字符串
 *
 * 约定：Excel第0行是表头（"问题"或"网址"），从第1行开始读数据，只看第0列。
 */
@Service
public class ExcelParseService {

    private static final Logger log = LoggerFactory.getLogger(ExcelParseService.class);

    private static final String XLSX_EXTENSION = ".xlsx";
    private static final String XLS_EXTENSION = ".xls";

    /**
     * 【批量导入问题】
     * 约定格式：第一列 = 问题文本；第0行是表头，从第1行开始读。
     */
    public List<String> parseQuestionsFromExcel(MultipartFile file) {
        validateFile(file);

        List<String> questions = new ArrayList<>();

        try (InputStream is = file.getInputStream()) {
            Workbook workbook = createWorkbook(file, is);

            Sheet sheet = workbook.getSheetAt(0);
            if (sheet == null) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "Excel文件为空");
            }

            // firstDataRow=1 → 跳过第0行表头
            int firstDataRow = 1;
            int lastRowNum = sheet.getLastRowNum();

            for (int i = firstDataRow; i <= lastRowNum; i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;

                Cell cell = row.getCell(0); // 只看第0列
                if (cell == null) continue;

                String question = getCellValueAsString(cell);
                if (question != null && !question.trim().isEmpty()) {
                    questions.add(question.trim());
                }
            }

            workbook.close();

            if (questions.isEmpty()) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "Excel文件中没有找到有效问题");
            }

            log.info("从Excel文件解析出 {} 个问题", questions.size());
            return questions;

        } catch (IOException e) {
            log.error("解析Excel文件失败", e);
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "解析Excel文件失败");
        }
    }

    /**
     * 基础文件校验：非空 + 扩展名必须是xlsx/xls
     */
    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "请上传Excel文件");
        }

        String filename = file.getOriginalFilename();
        if (filename == null ||
            (!filename.toLowerCase().endsWith(XLSX_EXTENSION) && !filename.toLowerCase().endsWith(XLS_EXTENSION))) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "请上传有效的Excel文件(.xlsx或.xls)");
        }
    }

    /**
     * 根据文件后缀名决定用哪个POI实现类打开。
     * xlsx = XSSFWorkbook（Office 2007+），xls = HSSFWorkbook（老格式）。
     */
    private Workbook createWorkbook(MultipartFile file, InputStream is) throws IOException {
        String filename = file.getOriginalFilename();
        if (filename != null && filename.toLowerCase().endsWith(XLSX_EXTENSION)) {
            return new XSSFWorkbook(is);
        } else {
            return new HSSFWorkbook(is);
        }
    }

    /**
     * 【单元格万能转字符串】
     * 最容易踩坑的地方：Excel单元格有很多类型（数字、日期、公式...），
     * 直接调cell.toString()会出现各种怪格式（如数字变成科学计数法、日期变成数字）。
     * 所以按类型分别处理：
     * - 数字：整数就去掉小数点（1234而不是1234.0）
     * - 日期：转成LocalDateTime字符串
     * - 公式：先用getString尝试，不行就拿数值
     */
    private String getCellValueAsString(Cell cell) {
        if (cell == null) return null;

        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell)) {
                    yield cell.getLocalDateTimeCellValue().toString();
                } else {
                    double value = cell.getNumericCellValue();
                    // 整数值就不要显示.0了（用户体验细节）
                    if (value == Math.floor(value)) {
                        yield String.valueOf((long) value);
                    } else {
                        yield String.valueOf(value);
                    }
                }
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> {
                try {
                    yield cell.getStringCellValue();
                } catch (Exception e) {
                    // 公式计算后可能是数值型，fallback到取数值
                    yield String.valueOf(cell.getNumericCellValue());
                }
            }
            default -> null;
        };
    }

    /**
     * 【批量导入白名单来源】
     * 格式同问题导入，但多了一步URL标准化（normalizeWhitelistUrl）和去重。
     */
    public List<String> parseWhitelistFromExcel(MultipartFile file) {
        validateFile(file);

        List<String> urls = new ArrayList<>();

        try (InputStream is = file.getInputStream()) {
            Workbook workbook = createWorkbook(file, is);

            Sheet sheet = workbook.getSheetAt(0);
            if (sheet == null) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "白名单Excel文件为空");
            }

            int firstDataRow = 1;
            int lastRowNum = sheet.getLastRowNum();

            for (int i = firstDataRow; i <= lastRowNum; i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;

                Cell cell = row.getCell(0);
                if (cell == null) continue;

                String url = getCellValueAsString(cell);
                if (url != null && !url.trim().isEmpty()) {
                    // 关键：URL必须标准化才能匹配（去掉http://www.等前缀、去掉路径和参数）
                    String cleaned = normalizeWhitelistUrl(url.trim());
                    if (cleaned != null && !cleaned.isEmpty()) {
                        // 去重：同一个域名只存一次（List.contains虽然是O(n)，但通常白名单不大）
                        if (!urls.contains(cleaned)) {
                            urls.add(cleaned);
                        }
                    }
                }
            }

            workbook.close();

            log.info("从白名单Excel文件解析出 {} 个网址", urls.size());
            return urls;

        } catch (IOException e) {
            log.error("解析白名单Excel文件失败", e);
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "解析白名单Excel文件失败");
        }
    }

    /**
     * 【URL标准化/归一化】
     * 设计思路：用户上传的URL格式五花八门（有http/有https/有www/有带路径...），
     * 但AI引用来源URL也格式不一，直接匹配根本匹配不上。
     * 所以两边都用同一套规则"浓缩"成纯域名：
     *   例：https://www.zhihu.com/question/123?a=1 → zhihu.com
     * 这样匹配时就能忽略协议、子域名、路径、参数等差异。
     */
    private String normalizeWhitelistUrl(String url) {
        if (url == null || url.isEmpty()) return null;
        String result = url.toLowerCase().trim();
        // 去掉 http:// 或 https://
        if (result.startsWith("http://")) result = result.substring(7);
        else if (result.startsWith("https://")) result = result.substring(8);
        // 去掉 www.
        if (result.startsWith("www.")) result = result.substring(4);
        // 去掉第一个 / 后的路径部分（如 /question/123）
        int slashIdx = result.indexOf('/');
        if (slashIdx > 0) result = result.substring(0, slashIdx);
        // 去掉 ? 后的查询参数
        int queryIdx = result.indexOf('?');
        if (queryIdx > 0) result = result.substring(0, queryIdx);
        return result.trim();
    }
}