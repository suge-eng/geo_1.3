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

@Service
public class ExcelParseService {

    private static final Logger log = LoggerFactory.getLogger(ExcelParseService.class);

    private static final String XLSX_EXTENSION = ".xlsx";
    private static final String XLS_EXTENSION = ".xls";

    public List<String> parseQuestionsFromExcel(MultipartFile file) {
        validateFile(file);

        List<String> questions = new ArrayList<>();

        try (InputStream is = file.getInputStream()) {
            Workbook workbook = createWorkbook(file, is);

            Sheet sheet = workbook.getSheetAt(0);
            if (sheet == null) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "Excel文件为空");
            }

            int firstDataRow = 1;
            int lastRowNum = sheet.getLastRowNum();

            for (int i = firstDataRow; i <= lastRowNum; i++) {
                Row row = sheet.getRow(i);
                if (row == null) {
                    continue;
                }

                Cell cell = row.getCell(0);
                if (cell == null) {
                    continue;
                }

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

    private Workbook createWorkbook(MultipartFile file, InputStream is) throws IOException {
        String filename = file.getOriginalFilename();
        if (filename != null && filename.toLowerCase().endsWith(XLSX_EXTENSION)) {
            return new XSSFWorkbook(is);
        } else {
            return new HSSFWorkbook(is);
        }
    }

    private String getCellValueAsString(Cell cell) {
        if (cell == null) {
            return null;
        }

        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell)) {
                    yield cell.getLocalDateTimeCellValue().toString();
                } else {
                    double value = cell.getNumericCellValue();
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
                    yield String.valueOf(cell.getNumericCellValue());
                }
            }
            default -> null;
        };
    }
}
