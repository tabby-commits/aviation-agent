package com.kama.jchatmind.report;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class NewsEventVerifierTest {
    @TempDir Path folder;
    private final NewsEventVerifier verifier = new NewsEventVerifier();

    @Test
    void onlyOriginalInstitutionalEventWithSourceDateHashAndDedupCanPass() throws Exception {
        Path raw = folder.resolve("original.txt");
        Files.writeString(raw, "Example agency institutional release: deployed 12 satellites on 2025-05-01.");
        FrozenSnapshot.Snapshot snapshot = new FrozenSnapshot.Snapshot("synthetic-test", Instant.now(),
                List.of(), List.of(), List.of(), "paper", "parameter", "manifest", folder.toString(),
                Map.of(), List.of("example.gov.cn"));
        FrozenSnapshot.NewsEvent valid = event("e1", "https://example.gov.cn/mission",
                FrozenSnapshot.sha256(raw), "institutional", "2025-05-01", "g1");
        assertNull(verifier.failure(snapshot, valid, Set.of(), Set.of()));
        assertTrue(verifier.failure(snapshot, valid, Set.of("e1"), Set.of()).contains("重复"));
        assertTrue(verifier.failure(snapshot, event("e2", "https://spacenews.com/story",
                FrozenSnapshot.sha256(raw), "media", "2025-05-01", "g2"), Set.of(), Set.of())
                .contains("原始机构"));
        assertTrue(verifier.failure(snapshot, event("e3", "https://example.gov.cn/mission",
                "wrong-hash", "institutional", "2025-05-01", "g3"), Set.of(), Set.of())
                .contains("哈希"));
        assertTrue(verifier.failure(snapshot, event("e4", "https://example.gov.cn/mission",
                FrozenSnapshot.sha256(raw), "institutional", "", "g4"), Set.of(), Set.of())
                .contains("日期"));
        assertTrue(verifier.failure(snapshot, event("e5", "https://example.gov.cn/mission",
                FrozenSnapshot.sha256(raw), "institutional", "2025-05-01", "g1"), Set.of(), Set.of("g1"))
                .contains("重复"));
    }

    private FrozenSnapshot.NewsEvent event(String id, String url, String hash, String kind,
                                           String date, String group) {
        return new FrozenSnapshot.NewsEvent(id, date, "2025-05-02", "CN", "Example agency",
                "deployment", "satellites", "12", "satellites", "", url,
                "original.txt", hash, "2025-05-03T00:00:00Z", "deployed 12 satellites",
                group, kind);
    }
}
