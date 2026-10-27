package com.kama.jchatmind.report;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFFootnote;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.apache.poi.xwpf.usermodel.XWPFTable.XWPFBorderType;
import org.apache.poi.xwpf.usermodel.LineSpacingRule;
import org.springframework.stereotype.Component;
import org.apache.poi.util.Units;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Word content is rendered from the same validated claims as the web page. */
@Component
public class ReportDocxExporter {
    private final CitationValidator validator;

    public ReportDocxExporter(CitationValidator validator) { this.validator = validator; }

    public byte[] export(ReportDocument report) {
        validator.validate(report);
        Map<String, ReportDocument.Source> sources = new HashMap<>();
        Map<String, Integer> numbers = new HashMap<>();
        for (int i = 0; i < report.sources().size(); i++) {
            ReportDocument.Source source = report.sources().get(i);
            sources.put(source.id(), source);
            numbers.put(source.id(), i + 1);
        }
        List<ReportDocument.StatusCard> cards = report.statusCards() == null ? List.of() : report.statusCards();
        List<ReportDocument.GapRow> gaps = report.gapMatrix() == null ? List.of() : report.gapMatrix();
        List<ReportDocument.Claim> overall = report.overallAssessment() == null ? List.of() : report.overallAssessment();
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            heading(doc, report.title() == null || report.title().isBlank()
                    ? "航天竞争情报分析报告" : report.title(), 16);
            heading(doc, "摘要", 14);
            for (ReportDocument.Claim claim : report.abstractLines()) claim(doc, claim, sources, numbers);
            for (ReportDocument.StatusCard card : cards) renderCard(doc, card, sources, numbers);
            for (ReportDocument.Dimension dimension : report.dimensions()) {
                heading(doc, dimension.name(), 14);
                for (ReportDocument.Indicator indicator : dimension.indicators()) {
                    heading(doc, indicator.code() + "  " + indicator.name(), 12);
                    for (ReportDocument.Claim claim : indicator.paragraphs()) claim(doc, claim, sources, numbers);
                    if (indicator.table() != null) {
                        if ("11".equals(indicator.code())) chart(doc, indicator.table());
                        table(doc, indicator.table(), sources, numbers);
                    }
                }
            }
            if (!gaps.isEmpty()) {
                heading(doc, "中美差距矩阵", 14);
                renderMatrix(doc, gaps, sources, numbers);
            }
            if (!overall.isEmpty()) {
                heading(doc, "整体研判", 14);
                for (ReportDocument.Claim claim : overall) claim(doc, claim, sources, numbers);
            }
            heading(doc, "总结", 14);
            for (ReportDocument.Claim claim : report.summaryLines()) claim(doc, claim, sources, numbers);
            doc.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("DOCX export failed", e);
        }
    }

    private void renderCard(XWPFDocument doc, ReportDocument.StatusCard card,
                            Map<String, ReportDocument.Source> sources, Map<String, Integer> numbers) {
        heading(doc, "主体现状：" + (card.actorName() == null ? card.actorId() : card.actorName())
                + (card.country() == null ? "" : "（" + card.country() + "）"), 12);
        XWPFTable table = doc.createTable(card.fields().size() + 1, 4);
        styleTable(table);
        headerCell(table, 0, 0, "字段");
        headerCell(table, 0, 1, "数值");
        headerCell(table, 0, 2, "基准日");
        headerCell(table, 0, 3, "层级");
        for (int r = 0; r < card.fields().size(); r++) {
            ReportDocument.CardField field = card.fields().get(r);
            bodyCell(table, r + 1, 0, FIELD_NAMES.getOrDefault(field.field(), field.field()));
            XWPFParagraph value = table.getRow(r + 1).getCell(1).getParagraphs().get(0);
            value.setSpacingBetween(1.5, LineSpacingRule.AUTO);
            XWPFRun run = value.createRun();
            font(run, 12);
            run.setText(field.value());
            footnotes(doc, value, List.of(field.sourceId()), sources, numbers);
            bodyCell(table, r + 1, 2, field.asOfDate() == null ? "—" : field.asOfDate());
            bodyCell(table, r + 1, 3, TIER_NAMES.getOrDefault(field.tier(), field.tier()));
        }
    }

    private void renderMatrix(XWPFDocument doc, List<ReportDocument.GapRow> gaps,
                              Map<String, ReportDocument.Source> sources, Map<String, Integer> numbers) {
        XWPFTable table = doc.createTable(gaps.size() + 1, 5);
        styleTable(table);
        headerCell(table, 0, 0, "比较维度");
        headerCell(table, 0, 1, "美国侧（已核验）");
        headerCell(table, 0, 2, "中国侧（已核验）");
        headerCell(table, 0, 3, "差距表述");
        headerCell(table, 0, 4, "证据强度");
        for (int r = 0; r < gaps.size(); r++) {
            ReportDocument.GapRow gap = gaps.get(r);
            bodyCell(table, r + 1, 0, gap.aspect());
            bodyCell(table, r + 1, 1, gap.usStatus() == null ? "—" : gap.usStatus());
            bodyCell(table, r + 1, 2, gap.cnStatus() == null ? "—" : gap.cnStatus());
            bodyCell(table, r + 1, 3, gap.gapStatement());
            bodyCell(table, r + 1, 4, gap.evidenceStrength() == null ? "—" : gap.evidenceStrength());
        }
    }

    private void styleTable(XWPFTable table) {
        table.setTopBorder(XWPFBorderType.SINGLE, 8, 0, "000000");
        table.setBottomBorder(XWPFBorderType.SINGLE, 8, 0, "000000");
        table.setLeftBorder(XWPFBorderType.NIL, 0, 0, "FFFFFF");
        table.setRightBorder(XWPFBorderType.NIL, 0, 0, "FFFFFF");
        table.setInsideHBorder(XWPFBorderType.NIL, 0, 0, "FFFFFF");
        table.setInsideVBorder(XWPFBorderType.NIL, 0, 0, "FFFFFF");
    }

    private void headerCell(XWPFTable table, int row, int column, String text) {
        XWPFTableCell cell = table.getRow(row).getCell(column);
        cell.setText("");
        XWPFParagraph paragraph = cell.getParagraphs().get(0);
        paragraph.setSpacingBetween(1.5, LineSpacingRule.AUTO);
        XWPFRun run = paragraph.createRun();
        font(run, 12);
        run.setBold(true);
        run.setText(text);
        var tcPr = cell.getCTTc().getTcPr();
        if (tcPr == null) tcPr = cell.getCTTc().addNewTcPr();
        var borders = tcPr.isSetTcBorders() ? tcPr.getTcBorders() : tcPr.addNewTcBorders();
        var bottom = borders.isSetBottom() ? borders.getBottom() : borders.addNewBottom();
        bottom.setVal(org.openxmlformats.schemas.wordprocessingml.x2006.main.STBorder.SINGLE);
        bottom.setSz(java.math.BigInteger.valueOf(8));
    }

    private void bodyCell(XWPFTable table, int row, int column, String text) {
        XWPFTableCell cell = table.getRow(row).getCell(column);
        cell.setText("");
        XWPFParagraph paragraph = cell.getParagraphs().get(0);
        paragraph.setSpacingBetween(1.5, LineSpacingRule.AUTO);
        XWPFRun run = paragraph.createRun();
        font(run, 12);
        run.setText(text);
    }

    private static final Map<String, String> FIELD_NAMES = Map.of(
            "plannedConstellation", "规划星座规模", "inOrbit", "在轨卫星数",
            "cumulativeLaunched", "累计发射卫星数", "launchCadence", "发射节奏",
            "latestVersion", "最新版本", "commercial", "商业化/组网节点");

    private static final Map<String, String> TIER_NAMES = Map.of(
            "official", "官方", "third-party", "第三方汇编", "media", "媒体转述");

    private void chart(XWPFDocument doc, ReportDocument.Table data) throws Exception {
        int width = 640, height = 210;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int max = data.rows().stream().flatMap(row -> row.cells().stream().skip(1))
                .mapToInt(Integer::parseInt).max().orElse(1);
        max = Math.max(max, 1);
        g.setColor(Color.GRAY);
        g.drawLine(45, 165, 600, 165);
        Color[] colors = {new Color(37, 99, 235), new Color(233, 115, 22)};
        for (int series = 1; series <= 2; series++) {
            g.setColor(colors[series - 1]);
            g.setStroke(new BasicStroke(3));
            for (int i = 1; i < data.rows().size(); i++) {
                int x1 = 45 + (i - 1) * 555 / Math.max(1, data.rows().size() - 1);
                int x2 = 45 + i * 555 / Math.max(1, data.rows().size() - 1);
                int y1 = 165 - Integer.parseInt(data.rows().get(i - 1).cells().get(series)) * 130 / max;
                int y2 = 165 - Integer.parseInt(data.rows().get(i).cells().get(series)) * 130 / max;
                g.drawLine(x1, y1, x2, y2);
            }
        }
        g.setColor(Color.DARK_GRAY);
        for (int i = 0; i < data.rows().size(); i++) {
            int x = 45 + i * 555 / Math.max(1, data.rows().size() - 1);
            g.drawString(data.rows().get(i).cells().get(0), x - 16, 188);
        }
        g.setColor(colors[0]); g.drawString("CN", 48, 18);
        g.setColor(colors[1]); g.drawString("US", 90, 18);
        g.dispose();
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(image, "png", png);
        XWPFParagraph paragraph = doc.createParagraph();
        paragraph.createRun().addPicture(new ByteArrayInputStream(png.toByteArray()),
                XWPFDocument.PICTURE_TYPE_PNG, "paper-trend.png", Units.toEMU(480), Units.toEMU(158));
    }

    private void heading(XWPFDocument doc, String text, int size) {
        XWPFParagraph paragraph = doc.createParagraph();
        paragraph.setSpacingBetween(1.5, LineSpacingRule.AUTO);
        XWPFRun run = paragraph.createRun();
        font(run, size);
        run.setBold(true);
        run.setText(text);
    }

    private void claim(XWPFDocument doc, ReportDocument.Claim claim,
                       Map<String, ReportDocument.Source> sources, Map<String, Integer> numbers) {
        XWPFParagraph paragraph = doc.createParagraph();
        paragraph.setSpacingBetween(1.5, LineSpacingRule.AUTO);
        paragraph.setIndentationFirstLine(480);
        XWPFRun run = paragraph.createRun();
        font(run, 12);
        run.setText(claim.text());
        footnotes(doc, paragraph, claim.sourceIds(), sources, numbers);
    }

    private void table(XWPFDocument doc, ReportDocument.Table data,
                       Map<String, ReportDocument.Source> sources, Map<String, Integer> numbers) {
        XWPFParagraph caption = doc.createParagraph();
        font(caption.createRun(), 12);
        caption.getRuns().get(0).setText(data.title());
        XWPFTable table = doc.createTable(data.rows().size() + 1, data.headers().size());
        table.setTopBorder(XWPFBorderType.SINGLE, 8, 0, "000000");
        table.setBottomBorder(XWPFBorderType.SINGLE, 8, 0, "000000");
        table.setLeftBorder(XWPFBorderType.NIL, 0, 0, "FFFFFF");
        table.setRightBorder(XWPFBorderType.NIL, 0, 0, "FFFFFF");
        table.setInsideHBorder(XWPFBorderType.NIL, 0, 0, "FFFFFF");
        table.setInsideVBorder(XWPFBorderType.NIL, 0, 0, "FFFFFF");
        for (int c = 0; c < data.headers().size(); c++) {
            XWPFTableCell cell = table.getRow(0).getCell(c);
            cell.setText("");
            XWPFParagraph paragraph = cell.getParagraphs().get(0);
            paragraph.setSpacingBetween(1.5, LineSpacingRule.AUTO);
            XWPFRun run = paragraph.createRun();
            font(run, 12);
            run.setBold(true);
            run.setText(data.headers().get(c));
            var tcPr = cell.getCTTc().getTcPr();
            if (tcPr == null) tcPr = cell.getCTTc().addNewTcPr();
            var borders = tcPr.isSetTcBorders() ? tcPr.getTcBorders() : tcPr.addNewTcBorders();
            var bottom = borders.isSetBottom() ? borders.getBottom() : borders.addNewBottom();
            bottom.setVal(org.openxmlformats.schemas.wordprocessingml.x2006.main.STBorder.SINGLE);
            bottom.setSz(java.math.BigInteger.valueOf(8));
        }
        for (int r = 0; r < data.rows().size(); r++) {
            ReportDocument.Row sourceRow = data.rows().get(r);
            XWPFTableRow row = table.getRow(r + 1);
            for (int c = 0; c < sourceRow.cells().size(); c++) {
                XWPFParagraph paragraph = row.getCell(c).getParagraphs().get(0);
                paragraph.setSpacingBetween(1.5, LineSpacingRule.AUTO);
                XWPFRun run = paragraph.createRun();
                font(run, 12);
                run.setText(sourceRow.cells().get(c));
                if (c == sourceRow.cells().size() - 1)
                    footnotes(doc, paragraph, sourceRow.sourceIds(), sources, numbers);
            }
        }
    }

    private void footnotes(XWPFDocument doc, XWPFParagraph paragraph, List<String> sourceIds,
                           Map<String, ReportDocument.Source> sources, Map<String, Integer> numbers) {
        for (String id : sourceIds) {
            ReportDocument.Source source = sources.get(id);
            XWPFFootnote footnote = doc.createFootnote();
            XWPFParagraph note = footnote.createParagraph();
            XWPFRun text = note.createRun();
            font(text, 10);
            text.setText("[" + numbers.get(id) + "] " + source.title() + "；" + source.location()
                    + "；SHA-256: " + source.sha256() + "；" + source.verificationStatus());
            if (source.url() != null && !source.url().isBlank()) {
                XWPFRun link = note.createHyperlinkRun(source.url());
                font(link, 10);
                link.setText(" " + source.url());
            }
            paragraph.addFootnoteReference(footnote);
        }
    }

    private void font(XWPFRun run, int size) {
        run.setFontFamily("Times New Roman");
        run.setFontSize(size);
        var rpr = run.getCTR().isSetRPr() ? run.getCTR().getRPr() : run.getCTR().addNewRPr();
        var fonts = rpr.sizeOfRFontsArray() > 0 ? rpr.getRFontsArray(0) : rpr.addNewRFonts();
        fonts.setEastAsia("仿宋");
        fonts.setAscii("Times New Roman");
        fonts.setHAnsi("Times New Roman");
    }
}
