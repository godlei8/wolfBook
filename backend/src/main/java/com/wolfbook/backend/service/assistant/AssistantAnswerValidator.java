package com.wolfbook.backend.service.assistant;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI 助手答案校验器。
 *
 * <p>明确主体问题采用 fail-closed 策略：答案一旦出现未允许的角色/板子名称，
 * 就判为漂移，调用方必须重试或降级成模板直答。</p>
 */
@Service
class AssistantAnswerValidator {
    private static final Pattern EVIDENCE_ID_PATTERN = Pattern.compile("\\[E\\d+]");
    private static final Pattern SECTION_PATTERN = Pattern.compile("(?m)^##\\s+(.+)$");

    private final AssistantSubjectCatalog subjectCatalog;

    AssistantAnswerValidator(AssistantSubjectCatalog subjectCatalog) {
        this.subjectCatalog = subjectCatalog;
    }

    AssistantAnswerValidation validate(
            String answer,
            AssistantQueryPlan queryPlan,
            List<AssistantKnowledgeService.KnowledgeHit> hits
    ) {
        if (answer == null || answer.isBlank()) {
            return AssistantAnswerValidation.invalid("EMPTY_ANSWER");
        }
        if (queryPlan == null || !queryPlan.strictSubject() || queryPlan.subject() == null) {
            return AssistantAnswerValidation.ok();
        }
        if (subjectCatalog.containsOtherSubject(answer, queryPlan.subject(), List.of())) {
            return AssistantAnswerValidation.invalid("SUBJECT_DRIFT");
        }
        ValidationSections sections = splitSections(answer);
        if (!hasEvidenceCoverage(sections.conclusionLines(), sections.evidenceMap())) {
            return AssistantAnswerValidation.invalid("COVERAGE_GAP");
        }
        if (!isEvidenceConsistent(sections.conclusionLines(), sections.evidenceMap())) {
            return AssistantAnswerValidation.invalid("EVIDENCE_CONFLICT");
        }
        if (isLowConfidence(sections.confidenceLines())) {
            return AssistantAnswerValidation.invalid("LOW_CONFIDENCE");
        }
        return AssistantAnswerValidation.ok();
    }

    private ValidationSections splitSections(String answer) {
        Map<String, List<String>> sections = new LinkedHashMap<>();
        Matcher matcher = SECTION_PATTERN.matcher(answer);
        int start = 0;
        String current = null;
        while (matcher.find()) {
            if (current != null) {
                sections.put(current, extractLines(answer.substring(start, matcher.start())));
            }
            current = matcher.group(1).trim();
            start = matcher.end();
        }
        if (current != null) {
            sections.put(current, extractLines(answer.substring(start)));
        }
        Map<String, String> evidenceMap = new LinkedHashMap<>();
        for (String line : sections.getOrDefault("证据引用", List.of())) {
            Matcher evidenceMatcher = EVIDENCE_ID_PATTERN.matcher(line);
            if (evidenceMatcher.find()) {
                evidenceMap.put(evidenceMatcher.group(), line);
            }
        }
        return new ValidationSections(
                sections.getOrDefault("结论", List.of()),
                evidenceMap,
                sections.getOrDefault("置信度", List.of())
        );
    }

    private List<String> extractLines(String content) {
        List<String> lines = new ArrayList<>();
        for (String raw : content.split("\\R")) {
            String line = raw.trim();
            if (!line.isBlank()) {
                lines.add(line);
            }
        }
        return lines;
    }

    private boolean hasEvidenceCoverage(List<String> conclusionLines, Map<String, String> evidenceMap) {
        if (conclusionLines.isEmpty()) {
            return false;
        }
        for (String line : conclusionLines) {
            if (!line.startsWith("-")) {
                continue;
            }
            List<String> refs = extractEvidenceRefs(line);
            if (refs.isEmpty()) {
                return false;
            }
            if (!evidenceMap.keySet().containsAll(refs)) {
                return false;
            }
        }
        return true;
    }

    private boolean isEvidenceConsistent(List<String> conclusionLines, Map<String, String> evidenceMap) {
        for (String line : conclusionLines) {
            if (!line.startsWith("-")) {
                continue;
            }
            List<String> refs = extractEvidenceRefs(line);
            if (refs.isEmpty()) {
                continue;
            }
            String normalizedClaim = normalizeForMatch(EVIDENCE_ID_PATTERN.matcher(line).replaceAll(""));
            if (normalizedClaim.contains("证据不足") || normalizedClaim.contains("资料不足")) {
                continue;
            }
            boolean matched = refs.stream()
                    .map(evidenceMap::get)
                    .filter(text -> text != null && !text.isBlank())
                    .anyMatch(evidence -> sharesToken(normalizedClaim, normalizeForMatch(evidence)));
            if (!matched) {
                return false;
            }
        }
        return true;
    }

    private boolean isLowConfidence(List<String> confidenceLines) {
        return confidenceLines.stream().anyMatch(line -> line.contains("低"));
    }

    private List<String> extractEvidenceRefs(String line) {
        List<String> refs = new ArrayList<>();
        Matcher matcher = EVIDENCE_ID_PATTERN.matcher(line);
        while (matcher.find()) {
            refs.add(matcher.group());
        }
        return refs;
    }

    private String normalizeForMatch(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[\\p{Punct}\\s]+", "");
    }

    private boolean sharesToken(String claim, String evidence) {
        if (claim.isBlank() || evidence.isBlank()) {
            return false;
        }
        if (evidence.contains(claim) || claim.contains(evidence)) {
            return true;
        }
        Set<String> parts = new HashSet<>(List.of(claim.split("(?<=\\G.{2})")));
        return parts.stream().anyMatch(token -> token.length() >= 2 && evidence.contains(token));
    }

    private record ValidationSections(
            List<String> conclusionLines,
            Map<String, String> evidenceMap,
            List<String> confidenceLines
    ) {
    }
}
