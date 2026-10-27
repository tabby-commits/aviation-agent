package com.kama.jchatmind.report;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reject unsupported text and table values before either renderer receives a report. */
@Component
public class CitationValidator {
    private static final Pattern NUMBERS = Pattern.compile("\\d+(?:[.,]\\d+)*");

    public void validate(ReportDocument report) {
        List<ReportDocument.StatusCard> cards = report.statusCards() == null ? List.of() : report.statusCards();
        List<ReportDocument.GapRow> gaps = report.gapMatrix() == null ? List.of() : report.gapMatrix();
        List<ReportDocument.Claim> overall = report.overallAssessment() == null ? List.of() : report.overallAssessment();
        Map<String, ReportDocument.Source> sources = new HashMap<>();
        for (ReportDocument.Source source : report.sources()) {
            if (sources.putIfAbsent(source.id(), source) != null || source.sha256().isBlank()
                    || source.location() == null || source.location().isBlank())
                throw new IllegalStateException("Invalid or duplicate source: " + source.id());
            if ("external".equals(source.kind()) && (source.url() == null
                    || !source.url().startsWith("https://")))
                throw new IllegalStateException("External source must have a direct HTTPS URL: " + source.id());
        }
        for (ReportDocument.StatusCard card : cards) {
            Set<String> fields = new HashSet<>();
            for (ReportDocument.CardField field : card.fields()) {
                if (!fields.add(field.field()))
                    throw new IllegalStateException("Duplicate card field: " + card.actorId() + "." + field.field());
                checkSources(List.of(field.sourceId()), sources);
                if (field.value() == null || field.value().isBlank())
                    throw new IllegalStateException("Empty card field value: " + card.actorId() + "." + field.field());
                if (("inOrbit".equals(field.field()) || "cumulativeLaunched".equals(field.field()))
                        && (field.asOfDate() == null || !field.asOfDate().matches("\\d{4}-\\d{2}-\\d{2}")))
                    throw new IllegalStateException("Stock field needs an as-of date: "
                            + card.actorId() + "." + field.field());
            }
        }
        Map<String, ReportDocument.Calculation> calculations = new HashMap<>();
        for (ReportDocument.Calculation calc : report.calculations()) {
            if (calculations.putIfAbsent(calc.id(), calc) != null
                    || new HashSet<>(calc.inputIds()).size() != calc.inputIds().size())
                throw new IllegalStateException("Calculation inputs invalid: " + calc.id());
            if (calc.method().startsWith("count distinct")
                    && Integer.parseInt(calc.value()) != calc.inputIds().size())
                throw new IllegalStateException("Calculation cannot be reproduced: " + calc.id());
        }
        for (ReportDocument.Calculation calc : report.calculations()) {
            if ("difference".equals(calc.method())) {
                if (calc.inputIds().size() != 2 || !calculations.containsKey(calc.inputIds().get(0))
                        || !calculations.containsKey(calc.inputIds().get(1)))
                    throw new IllegalStateException("Difference inputs missing: " + calc.id());
                int left = Integer.parseInt(calculations.get(calc.inputIds().get(0)).value());
                int right = Integer.parseInt(calculations.get(calc.inputIds().get(1)).value());
                if (left - right != Integer.parseInt(calc.value()))
                    throw new IllegalStateException("Difference cannot be reproduced: " + calc.id());
            }
            if ("field-difference".equals(calc.method()) || "field-ratio".equals(calc.method()))
                reproduceFieldCalculation(calc, cards);
        }
        for (ReportDocument.Claim claim : report.abstractLines()) checkClaim(claim, sources, calculations);
        Set<String> claimIds = new HashSet<>();
        for (ReportDocument.Claim claim : report.abstractLines()) claimIds.add(claim.id());
        for (ReportDocument.Dimension dimension : report.dimensions()) {
            if (dimension.indicators().isEmpty()) throw new IllegalStateException("Empty dimension");
            for (ReportDocument.Indicator indicator : dimension.indicators()) {
                for (ReportDocument.Claim claim : indicator.paragraphs()) {
                    checkClaim(claim, sources, calculations);
                    claimIds.add(claim.id());
                }
                if (indicator.table() != null) {
                    for (ReportDocument.Row row : indicator.table().rows()) {
                        checkSources(row.sourceIds(), sources);
                        if (row.cells().size() != indicator.table().headers().size())
                            throw new IllegalStateException("Table width mismatch");
                        for (String calcId : row.calculationIds()) {
                            ReportDocument.Calculation calc = calculations.get(calcId);
                            if (calc == null || !row.cells().contains(calc.value()))
                                throw new IllegalStateException("Table value has no calculation: " + calcId);
                        }
                        if (row.calculationIds().size() != row.cells().size() - 1)
                            throw new IllegalStateException("Every numeric table cell needs a calculation");
                    }
                }
            }
        }
        for (ReportDocument.Claim claim : report.summaryLines()) {
            checkClaim(claim, sources, calculations);
            claimIds.add(claim.id());
        }
        for (ReportDocument.Claim claim : overall) {
            checkClaim(claim, sources, calculations);
            claimIds.add(claim.id());
            if (NUMBERS.matcher(claim.text()).find())
                throw new IllegalStateException("Overall assessment must not contain numbers: " + claim.id());
        }
        for (ReportDocument.GapRow gap : gaps) {
            if (gap.aspect() == null || gap.aspect().isBlank() || gap.gapStatement() == null
                    || gap.gapStatement().isBlank())
                throw new IllegalStateException("Gap row incomplete: " + gap.aspect());
            if (gap.basisClaimIds() == null || gap.basisClaimIds().isEmpty()
                    || !claimIds.containsAll(gap.basisClaimIds()))
                throw new IllegalStateException("Gap row basis claims missing: " + gap.aspect());
        }
    }

    /** Field calculations are recomputed from the status cards they cite. */
    private void reproduceFieldCalculation(ReportDocument.Calculation calc,
                                           List<ReportDocument.StatusCard> cards) {
        if (calc.inputIds().size() != 2)
            throw new IllegalStateException("Field calculation needs exactly two inputs: " + calc.id());
        Map<String, ReportDocument.CardField> fields = new HashMap<>();
        for (ReportDocument.StatusCard card : cards)
            for (ReportDocument.CardField field : card.fields())
                fields.put(card.actorId() + "." + field.field(), field);
        ReportDocument.CardField left = fields.get(calc.inputIds().get(0));
        ReportDocument.CardField right = fields.get(calc.inputIds().get(1));
        if (left == null || right == null)
            throw new IllegalStateException("Field calculation inputs missing: " + calc.id());
        double leftValue = Double.parseDouble(left.value().replace(",", "").replace("+", "").trim());
        double rightValue = Double.parseDouble(right.value().replace(",", "").replace("+", "").trim());
        if ("field-difference".equals(calc.method())) {
            long expected = Math.round(leftValue - rightValue);
            if (String.valueOf(expected).equals(calc.value())) return;
        } else {
            double expected = Math.round(leftValue / rightValue * 10.0) / 10.0;
            if (QuestionReportPipeline.ratioText(expected).equals(calc.value())) return;
        }
        throw new IllegalStateException("Field calculation cannot be reproduced: " + calc.id());
    }

    private void checkClaim(ReportDocument.Claim claim, Map<String, ReportDocument.Source> sources,
                            Map<String, ReportDocument.Calculation> calculations) {
        if (claim.text() == null || claim.text().isBlank()) throw new IllegalStateException("Empty claim");
        checkSources(claim.sourceIds(), sources);
        if (claim.value() != null) {
            ReportDocument.Calculation calc = calculations.get(claim.calculationId());
            if (calc == null || !claim.value().equals(calc.value()) || !claim.unit().equals(calc.unit())
                    || !claim.text().contains(claim.value()))
                throw new IllegalStateException("Claim value does not match calculation: " + claim.id());
        } else if (claim.calculationId() != null) {
            throw new IllegalStateException("Claim has calculation without value: " + claim.id());
        }
    }

    private void checkSources(List<String> ids, Map<String, ReportDocument.Source> sources) {
        if (ids == null || ids.isEmpty()) throw new IllegalStateException("Claim has no source");
        for (String id : ids) if (!sources.containsKey(id))
            throw new IllegalStateException("Unknown source: " + id);
    }
}
