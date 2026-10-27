package com.kama.jchatmind.agent.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NewsEventToolTest {

    @TempDir
    Path tempDir;

    @Test
    void pendingCandidatesMustNotBeReturnedAsQuantitativeEvidence() throws Exception {
        writeEngineeringCsv("pending");
        NewsEventTool tool = new NewsEventTool(tempDir.toString());

        String result = tool.queryConfirmedEvents("engineering", "CN", 2020, 2025);

        assertTrue(result.contains("AWAITING_HUMAN_REVIEW"));
        assertTrue(result.contains("confirmed=0"));
        assertFalse(result.contains("136 satellites"));
    }

    @Test
    void confirmedEventsRetainStableIdAndSource() throws Exception {
        writeEngineeringCsv("confirmed");
        NewsEventTool tool = new NewsEventTool(tempDir.toString());

        String result = tool.queryConfirmedEvents("engineering", "CN", 2020, 2025);

        assertTrue(result.contains("eng-test"));
        assertTrue(result.contains("https://spacenews.com/test"));
        assertTrue(result.contains("136 satellites"));
    }

    private void writeEngineeringCsv(String reviewStatus) throws Exception {
        String csv = "snapshot_id,event_id,country_attribution,operator,event_type,event_date,report_date,metric_name,value,unit,stock_or_flow,status_definition,source_url,source_title,evidence_text,duplicate_group_id,review_status,review_note\n"
                + "snapshot-test,eng-test,CN,China SatNet,in_orbit_snapshot,,2025-12-29,in_orbit_satellites,136,satellites,stock,in-orbit,https://spacenews.com/test,Test title,136 satellites,dup-test,"
                + reviewStatus + ",checked\n";
        Files.writeString(tempDir.resolve("news_engineering_events.csv"), csv);
    }
}
