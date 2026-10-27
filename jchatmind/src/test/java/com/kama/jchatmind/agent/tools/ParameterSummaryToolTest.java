package com.kama.jchatmind.agent.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ParameterSummaryToolTest {

    @TempDir
    Path tempDir;

    @Test
    void summarySeparatesPaperAndRecordCountsAndEnforcesZeroPairBoundary() throws Exception {
        Files.writeString(tempDir.resolve("parameter_evidence_details.csv"),
                "snapshot_id,decision_id,doc_id,first_author_country,publish_year,parameter_family\n"
                        + "snap,d1,p1,CN,2024,latency\n"
                        + "snap,d2,p1,CN,2024,cost\n"
                        + "snap,d3,p2,US,2024,latency\n");
        Files.writeString(tempDir.resolve("paper_bibliometrics_by_country_year.csv"),
                "snapshot_id,country,year,first_author_count\n"
                        + "snap,CN,2024,10\n"
                        + "snap,US,2024,5\n");
        Files.writeString(tempDir.resolve("parameter_comparability_summary.csv"),
                "snapshot_id,strict_comparable_pair_count,result_statement,claim_boundary\n"
                        + "snap,0,0 组严格可比对,不得形成中美技术性能排名。\n");

        ParameterSummaryTool tool = new ParameterSummaryTool(tempDir.toString());
        String result = tool.summarizeFrozenEvidence(2020, 2025, 5);

        assertTrue(result.contains("CN: parameter_papers=1 / papers=10"));
        assertTrue(result.contains("parameter_records=2"));
        assertTrue(result.contains("US: parameter_papers=1 / papers=5"));
        assertTrue(result.contains("strict_comparable_pair_count=0"));
        assertTrue(result.contains("不得形成中美技术性能排名"));
        assertTrue(result.contains("不得解释为技术成熟度、透明度、证据质量或性能优势"));
    }
}
