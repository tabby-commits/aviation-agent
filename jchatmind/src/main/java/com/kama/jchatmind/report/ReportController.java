package com.kama.jchatmind.report;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.util.List;

@RestController
@RequestMapping("/api")
public class ReportController {
    private final ReportStore store;
    private final ReportDocxExporter exporter;

    public ReportController(ReportStore store, ReportDocxExporter exporter) {
        this.store = store;
        this.exporter = exporter;
    }

    @GetMapping("/reports/{id}")
    public ReportDocument get(@PathVariable String id) {
        return store.get(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @GetMapping("/reports/session/{sessionId}")
    public List<ReportStore.ReportSummary> versions(@PathVariable String sessionId) {
        return store.versions(sessionId);
    }

    @GetMapping("/evidence/{id}")
    public ReportDocument.Source source(@PathVariable String id) {
        return store.source(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @GetMapping("/report-runs/{id}")
    public ReportStore.RunArchive run(@PathVariable String id) {
        return store.run(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @GetMapping("/reports/{id}/docx")
    public ResponseEntity<byte[]> docx(@PathVariable String id) {
        ReportDocument report = get(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=report-" + report.version() + ".docx")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                .body(exporter.export(report));
    }
}
