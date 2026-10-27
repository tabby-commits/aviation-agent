package com.kama.jchatmind.report;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Machine gate for turning a candidate mention into one countable original-source event. */
@Component
public class NewsEventVerifier {
    public String failure(FrozenSnapshot.Snapshot snapshot, FrozenSnapshot.NewsEvent event,
                          Set<String> acceptedIds, Set<String> acceptedDuplicateGroups) {
        if (event.id().isBlank() || acceptedIds.contains(event.id())) return "事件 ID 缺失或重复";
        if (event.duplicateGroup().isBlank() || acceptedDuplicateGroups.contains(event.duplicateGroup()))
            return "重复组缺失或重复事件";
        if (!"institutional".equals(event.sourceKind()) || !event.originalUrl().startsWith("https://")
                || !allowedInstitutionalHost(event.originalUrl(), snapshot.institutionalDomains()))
            return "不属于冻结清单登记的原始机构网页";
        if ((!"CN".equals(event.country()) && !"US".equals(event.country()))
                || event.actor().isBlank() || event.action().isBlank() || event.object().isBlank()
                || event.value().isBlank() || event.unit().isBlank()
                || event.originalExcerpt().isBlank() || !event.originalExcerpt().contains(event.value()))
            return "主体、数值、单位或原文摘录不完整";
        try {
            LocalDate.parse(event.eventDate());
            LocalDate.parse(event.reportDate());
            Instant.parse(event.originalFetchedAt());
            if (event.action().contains("in_orbit") && event.asOfDate().isBlank()) return "存量缺少基准日";
            Path folder = Path.of(snapshot.folder());
            Path original = folder.resolve(event.originalFile()).normalize();
            if (!original.startsWith(folder) || !FrozenSnapshot.sha256(original).equals(event.originalSha256()))
                return "原网页文件缺失或哈希不匹配";
            String raw = Files.readString(original);
            String normalized = raw.toLowerCase(Locale.ROOT);
            if (!raw.contains(event.originalExcerpt())
                    || !normalized.contains(event.actor().toLowerCase(Locale.ROOT))
                    || !normalized.contains(event.object().toLowerCase(Locale.ROOT))
                    || !normalized.contains(event.unit().toLowerCase(Locale.ROOT))
                    || !raw.contains(event.eventDate()))
                return "原网页中找不到所引片段";
            return null;
        } catch (Exception e) {
            return "日期或原网页文件不可核验";
        }
    }

    private boolean allowedInstitutionalHost(String url, List<String> domains) {
        try {
            String host = URI.create(url).getHost();
            if (host == null) return false;
            String normalized = host.toLowerCase(Locale.ROOT);
            return domains.stream().anyMatch(domain -> normalized.equals(domain.toLowerCase(Locale.ROOT))
                    || normalized.endsWith("." + domain.toLowerCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
