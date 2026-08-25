package com.kama.jchatmind.service.paper;

import com.kama.jchatmind.model.entity.Paper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WoS CSV 解析器单元测试（纯函数，无 Spring/DB 依赖）
 */
public class WosCsvParserTest {

    private final WosCsvParser parser = new WosCsvParser();

    /**
     * 样例覆盖：BOM 表头、引号内逗号、分号多值（AU/C1）、空 TC、缺 DI
     */
    private static final String SAMPLE_CSV =
            "﻿PT,AU,GP,TI,SO,SE,DT,PD,PY,AB,CT,CY,CL,SP,RI,ZB,ZS,ZR,Z8,TC,ZA,Z9,C1,SN,BN,DA,UT,BE,VL,AR,DI\n"
            + "C,\"Sahin, Selami; Ozbilgin, Tugba\",IEEE,\"On the Performance of Downlink, Optical Communication\","
            + "TEST JOURNAL,Test,Proceedings Paper,2016,2016,\"Abstract with, comma\",CT,CY,CL,SP,RI,0,0,0,0,,0,0,"
            + "\"TUBITAK, Kocaeli, Turkey; Univ, Ankara, Turkey\",1525-3511,,2016-01-01,WOS:TEST-0001,,,,\n"
            + "C,\"Doe, John\",,Single Author Paper,TEST JOURNAL 2,,Article,2020,2020,Abstract2,CT,CY,CL,SP,RI,0,0,0,0,3,0,0,"
            + "\"MIT, Cambridge, USA\",1111-2222,,2020-01-01,WOS:TEST-0002,,,,10.1000/x\n";

    @Test
    public void shouldParseAllFieldsWithBomAndQuotedCommas() throws IOException {
        List<Paper> papers = parser.parse(new ByteArrayInputStream(SAMPLE_CSV.getBytes(StandardCharsets.UTF_8)));

        assertEquals(2, papers.size());

        Paper first = papers.get(0);
        assertEquals("WOS:TEST-0001", first.getDocId());
        assertEquals("WOS", first.getSourceDb());
        // 引号内的逗号不能破坏字段
        assertEquals("On the Performance of Downlink, Optical Communication", first.getTitle());
        // AU 按 "; " 分割为 JSON 数组，firstAuthor 取第一个
        assertEquals("[\"Sahin, Selami\",\"Ozbilgin, Tugba\"]", first.getAuthors());
        assertEquals("Sahin, Selami", first.getFirstAuthor());
        // C1 按 "; " 分割
        assertEquals("[\"TUBITAK, Kocaeli, Turkey\",\"Univ, Ankara, Turkey\"]", first.getAffiliations());
        assertEquals("TUBITAK, Kocaeli, Turkey", first.getFirstAuthorAffiliation());
        assertEquals(2016, first.getPublishYear());
        assertEquals("TEST JOURNAL", first.getJournal());
        assertEquals("Proceedings Paper", first.getDocType());
        // 空 TC 解析为 null
        assertNull(first.getCitedCount());
        assertNull(first.getDoi());
        // 元数据导入时国别未判定
        assertNull(first.getFirstAuthorCountry());
        assertEquals("pending", first.getScreeningStatus());

        Paper second = papers.get(1);
        assertEquals("WOS:TEST-0002", second.getDocId());
        assertEquals(3, second.getCitedCount());
        assertEquals("10.1000/x", second.getDoi());
        assertEquals("Doe, John", second.getFirstAuthor());
    }

    @Test
    public void shouldRejectRowMissingUt() {
        String csv = "PT,AU,TI,PY,UT\n" + "C,\"A, B\",Title,2020,\n";
        PaperParseException ex = assertThrows(PaperParseException.class,
                () -> parser.parse(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8))));
        assertTrue(ex.getMessage().contains("UT"));
        assertEquals(2, ex.getLineNumber());
    }

    @Test
    public void shouldTolerateUnparsableYear() throws IOException {
        String csv = "PT,AU,TI,PY,UT\n" + "C,\"A, B\",Title,not-a-year,WOS:TEST-0003\n";
        List<Paper> papers = parser.parse(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));
        assertEquals(1, papers.size());
        assertNull(papers.get(0).getPublishYear());
    }

    @Test
    public void paperJsonShouldRoundTripLists() {
        assertNull(PaperJson.toJson(null));
        assertEquals("[\"a\",\"b\"]", PaperJson.toJson(List.of("a", "b")));
    }
}
