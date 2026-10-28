package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.RevenueLine;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.RevenueReportResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

// Tạo file .xlsx bằng OOXML thuần của JDK để không thêm dependency vào project hiện tại.
@Service
@RequiredArgsConstructor
public class RevenueExcelExportService {

    private final RevenueReportService revenueReportService;

    public byte[] export(LocalDate from, LocalDate to, String groupBy) throws IOException {
        RevenueReportResponse report = revenueReportService.getRevenue(from, to, groupBy);

        List<List<List<Object>>> sheets = new ArrayList<>();
        sheets.add(summarySheet(report));
        sheets.add(linesSheet("Doanh thu theo nguon", report.getBySource()));
        sheets.add(linesSheet("Doanh thu theo phuong thuc", report.getByMethod()));
        sheets.add(linesSheet("Doanh thu theo ky", report.getByPeriod()));

        String[] sheetNames = {"Tong quan", "Theo nguon", "Phuong thuc", "Theo ky"};
        return buildWorkbook(sheetNames, sheets);
    }

    private List<List<Object>> summarySheet(RevenueReportResponse report) {
        List<List<Object>> rows = new ArrayList<>();
        rows.add(row("BAO CAO DOANH THU SPORT CENTER"));
        rows.add(row("Tu ngay", report.getFrom(), "Den ngay", report.getTo()));
        rows.add(row("Gom theo", report.getGroupBy()));
        rows.add(row("Tong doanh thu", report.getTotalRevenue()));
        rows.add(row("So giao dich", report.getPaymentCount()));
        rows.add(row("Ky truoc tu ngay", report.getPreviousPeriodFrom(), "den", report.getPreviousPeriodTo()));
        rows.add(row("Doanh thu ky truoc", report.getPreviousPeriodRevenue()));
        rows.add(row("Chenh lech doanh thu", report.getChangeAmount()));
        rows.add(row("Thay doi (%)", report.getChangePercent() == null ? "N/A (ky truoc bang 0)" : report.getChangePercent()));
        rows.add(row("Giao dich can doi soat", report.getNeedsReviewCount()));
        return rows;
    }

    private List<List<Object>> linesSheet(String title, List<RevenueLine> lines) {
        List<List<Object>> rows = new ArrayList<>();
        rows.add(row(title));
        rows.add(row("Nhom", "Doanh thu", "So giao dich"));
        for (RevenueLine line : lines) {
            rows.add(row(line.getLabel(), line.getAmount(), line.getPaymentCount()));
        }
        if (lines.isEmpty()) {
            rows.add(row("Khong co du lieu"));
        }
        return rows;
    }

    private List<Object> row(Object... values) {
        return Arrays.asList(values);
    }

    private byte[] buildWorkbook(String[] sheetNames, List<List<List<Object>>> sheets) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            put(zip, "[Content_Types].xml", contentTypes(sheets.size()));
            put(zip, "_rels/.rels", rootRelationships());
            put(zip, "xl/workbook.xml", workbook(sheetNames));
            put(zip, "xl/_rels/workbook.xml.rels", workbookRelationships(sheets.size()));
            for (int i = 0; i < sheets.size(); i++) {
                put(zip, "xl/worksheets/sheet" + (i + 1) + ".xml", worksheet(sheets.get(i)));
            }
        }
        return output.toByteArray();
    }

    private void put(ZipOutputStream zip, String path, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(path));
        zip.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private String contentTypes(int count) {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
                .append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
                .append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
                .append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
                .append("<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>");
        for (int i = 1; i <= count; i++) {
            xml.append("<Override PartName=\"/xl/worksheets/sheet").append(i)
                    .append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
        }
        return xml.append("</Types>").toString();
    }

    private String rootRelationships() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
                + "</Relationships>";
    }

    private String workbook(String[] names) {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
                .append("<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" ")
                .append("xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>");
        for (int i = 0; i < names.length; i++) {
            xml.append("<sheet name=\"").append(escape(names[i])).append("\" sheetId=\"")
                    .append(i + 1).append("\" r:id=\"rId").append(i + 1).append("\"/>");
        }
        return xml.append("</sheets></workbook>").toString();
    }

    private String workbookRelationships(int count) {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
                .append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
        for (int i = 1; i <= count; i++) {
            xml.append("<Relationship Id=\"rId").append(i)
                    .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet")
                    .append(i).append(".xml\"/>");
        }
        return xml.append("</Relationships>").toString();
    }

    private String worksheet(List<List<Object>> rows) {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
                .append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>");
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            int excelRow = rowIndex + 1;
            xml.append("<row r=\"").append(excelRow).append("\">");
            List<Object> values = rows.get(rowIndex);
            for (int columnIndex = 0; columnIndex < values.size(); columnIndex++) {
                Object value = values.get(columnIndex);
                if (value == null) continue;
                String cell = columnName(columnIndex + 1) + excelRow;
                if (value instanceof Number || value instanceof BigDecimal) {
                    xml.append("<c r=\"").append(cell).append("\"><v>")
                            .append(value instanceof BigDecimal decimal ? decimal.toPlainString() : value)
                            .append("</v></c>");
                } else {
                    xml.append("<c r=\"").append(cell).append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                            .append(escape(value.toString())).append("</t></is></c>");
                }
            }
            xml.append("</row>");
        }
        return xml.append("</sheetData></worksheet>").toString();
    }

    private String columnName(int number) {
        StringBuilder name = new StringBuilder();
        int current = number;
        while (current > 0) {
            int remainder = (current - 1) % 26;
            name.insert(0, (char) ('A' + remainder));
            current = (current - 1) / 26;
        }
        return name.toString();
    }

    private String escape(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
