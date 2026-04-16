package com.wolfbook.backend.service.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wolfbook.backend.dto.AssistantDtos;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.minimax.MiniMaxChatModel;
import org.springframework.ai.minimax.MiniMaxChatOptions;
import org.springframework.ai.minimax.api.MiniMaxApi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * AI 助手联网搜索补充服务。
 *
 * <p>当前通过 MiniMax 模型能力获取联网补充，并要求模型返回结构化 JSON。
 * 站内知识仍是主答案来源，联网结果只在问题具备时效性或站内无资料时参与。</p>
 */
@Service
public class AssistantSearchService {

    private final ObjectProvider<MiniMaxChatModel> miniMaxChatModelProvider;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
    private static final Set<String> HIGH_TRUST_DOMAINS = Set.of(".gov", ".edu", "wikipedia.org", "mozilla.org", "openjdk.org");

    public AssistantSearchService(ObjectProvider<MiniMaxChatModel> miniMaxChatModelProvider, ObjectMapper objectMapper) {
        this.miniMaxChatModelProvider = miniMaxChatModelProvider;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(java.time.Duration.ofSeconds(2))
                .build();
    }

    public boolean isAvailable() {
        return miniMaxChatModelProvider.getIfAvailable() != null;
    }

    public SearchResult searchWeb(String query, String modelName, double temperature, Integer timeoutSeconds) {
        MiniMaxChatModel chatModel = miniMaxChatModelProvider.getIfAvailable();
        if (chatModel == null) {
            return SearchResult.empty("WEB_MODEL_UNAVAILABLE");
        }
        List<String> rewrittenQueries = rewriteQueries(query);

        Prompt prompt = new Prompt(
                List.of(
                        new SystemMessage("""
                                You are a web research engine.
                                Pipeline:
                                1) Rewrite user query into 2-3 search intents.
                                2) Search multiple sources and merge.
                                3) Deduplicate by URL/title.
                                4) Extract evidence snippets from pages.
                                5) Prefer trustworthy sources.
                                Return strict JSON with keys:
                                answer, citations, suggestedQuestions.
                                citations is array of {title,snippet,url}.
                                """),
                        new UserMessage("Question: " + query + "\nRewritten queries: " + rewrittenQueries)
                ),
                MiniMaxChatOptions.builder()
                        .model(modelName)
                        .temperature(temperature)
                        .tools(List.of(MiniMaxApi.FunctionTool.webSearchFunctionTool()))
                        .build()
        );

        ChatResponse response = chatModel.call(prompt);
        String content = response.getResult().getOutput().getText();
        SearchResult parsed = parse(content);
        return enrichPipeline(parsed, query, rewrittenQueries, timeoutSeconds == null ? 4 : timeoutSeconds);
    }

    private SearchResult parse(String content) {
        if (content == null || content.isBlank()) {
            return SearchResult.empty("WEB_EMPTY_RESPONSE");
        }
        try {
            JsonNode root = objectMapper.readTree(extractJsonObject(content));
            List<AssistantDtos.AssistantCitation> citations = new ArrayList<>();
            for (JsonNode item : root.path("citations")) {
                citations.add(new AssistantDtos.AssistantCitation(
                        AssistantConstants.SOURCE_WEB,
                        item.path("title").asText(),
                        item.path("snippet").asText(),
                        item.path("url").asText(null),
                        null
                ));
            }
            List<String> suggestedQuestions = new ArrayList<>();
            for (JsonNode item : root.path("suggestedQuestions")) {
                suggestedQuestions.add(item.asText());
            }
            return new SearchResult(root.path("answer").asText(content), citations, suggestedQuestions, Map.of(), null);
        } catch (Exception exception) {
            return new SearchResult(content, List.of(), List.of(), Map.of(), "WEB_JSON_PARSE_FAILED");
        }
    }

    private String extractJsonObject(String content) {
        String normalized = content == null ? "" : content.trim();
        if (normalized.startsWith("```")) {
            normalized = normalized.replaceFirst("^```[a-zA-Z]*\\s*", "")
                    .replaceFirst("\\s*```$", "")
                    .trim();
        }
        int start = normalized.indexOf('{');
        int end = normalized.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return normalized.substring(start, end + 1);
        }
        return normalized;
    }

    private List<String> rewriteQueries(String query) {
        String normalized = query == null ? "" : query.trim();
        if (normalized.isBlank()) {
            return List.of();
        }
        List<String> rewritten = new ArrayList<>();
        rewritten.add(normalized);
        rewritten.add(normalized + " 官方公告");
        rewritten.add(normalized + " 最新进展");
        return rewritten.stream().distinct().limit(3).toList();
    }

    private SearchResult enrichPipeline(SearchResult parsed, String query, List<String> rewrittenQueries, int timeoutSeconds) {
        List<AssistantDtos.AssistantCitation> deduped = deduplicateCitations(parsed.citations());
        List<PageExtract> extracts = extractPages(deduped, timeoutSeconds);
        List<AssistantDtos.AssistantCitation> withEvidence = backfillEvidence(deduped, extracts);
        Map<String, Object> observable = buildObservable(query, rewrittenQueries, withEvidence, extracts);
        String failureReason = parsed.failureReason();
        if (withEvidence.isEmpty() && (failureReason == null || failureReason.isBlank())) {
            failureReason = "WEB_NO_USABLE_SOURCE";
        }
        return new SearchResult(parsed.answer(), withEvidence, parsed.suggestedQuestions(), observable, failureReason);
    }

    private List<AssistantDtos.AssistantCitation> deduplicateCitations(List<AssistantDtos.AssistantCitation> citations) {
        Map<String, AssistantDtos.AssistantCitation> merged = new LinkedHashMap<>();
        for (AssistantDtos.AssistantCitation citation : citations) {
            if (citation == null) {
                continue;
            }
            String key = (citation.url() == null || citation.url().isBlank())
                    ? (citation.title() == null ? "" : citation.title().trim().toLowerCase(Locale.ROOT))
                    : citation.url().trim().toLowerCase(Locale.ROOT);
            merged.putIfAbsent(key, citation);
        }
        return merged.values().stream()
                .sorted(Comparator.comparingInt((AssistantDtos.AssistantCitation citation) -> trustScore(citation.url())).reversed())
                .limit(6)
                .toList();
    }

    private List<PageExtract> extractPages(List<AssistantDtos.AssistantCitation> citations, int timeoutSeconds) {
        List<PageExtract> extracts = new ArrayList<>();
        for (AssistantDtos.AssistantCitation citation : citations) {
            String url = citation == null ? null : citation.url();
            if (url == null || url.isBlank()) {
                extracts.add(new PageExtract(url, null, "NO_URL", ""));
                continue;
            }
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .GET()
                        .timeout(java.time.Duration.ofSeconds(Math.max(1, timeoutSeconds)))
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                String plainText = HTML_TAG.matcher(response.body() == null ? "" : response.body()).replaceAll(" ")
                        .replaceAll("\\s+", " ")
                        .trim();
                extracts.add(new PageExtract(url, response.statusCode(), null, limit(plainText, 420)));
            } catch (java.net.http.HttpTimeoutException timeoutException) {
                extracts.add(new PageExtract(url, null, "TIMEOUT", ""));
            } catch (Exception exception) {
                extracts.add(new PageExtract(url, null, exception.getClass().getSimpleName(), ""));
            }
        }
        return extracts;
    }

    private List<AssistantDtos.AssistantCitation> backfillEvidence(
            List<AssistantDtos.AssistantCitation> citations,
            List<PageExtract> extracts
    ) {
        Map<String, PageExtract> extractByUrl = new ConcurrentHashMap<>();
        for (PageExtract extract : extracts) {
            if (extract.url() != null) {
                extractByUrl.put(extract.url(), extract);
            }
        }
        List<AssistantDtos.AssistantCitation> enriched = new ArrayList<>();
        for (AssistantDtos.AssistantCitation citation : citations) {
            String url = citation.url();
            PageExtract extract = url == null ? null : extractByUrl.get(url);
            String snippet = citation.snippet();
            if ((snippet == null || snippet.isBlank()) && extract != null && extract.extractText() != null && !extract.extractText().isBlank()) {
                snippet = limit(extract.extractText(), 180);
            }
            enriched.add(new AssistantDtos.AssistantCitation(
                    citation.sourceType(),
                    citation.title(),
                    snippet,
                    citation.url(),
                    citation.sourceId()
            ));
        }
        return enriched;
    }

    private Map<String, Object> buildObservable(
            String query,
            List<String> rewrittenQueries,
            List<AssistantDtos.AssistantCitation> citations,
            List<PageExtract> extracts
    ) {
        List<String> sources = citations.stream()
                .map(citation -> citation.url() == null || citation.url().isBlank() ? citation.title() : citation.url())
                .toList();
        Map<String, Integer> httpStatuses = new LinkedHashMap<>();
        Map<String, String> timeoutReasons = new LinkedHashMap<>();
        Map<String, Integer> extractLengths = new LinkedHashMap<>();
        Map<String, Integer> trustScores = new LinkedHashMap<>();
        int injectedSnippetCount = 0;
        for (PageExtract extract : extracts) {
            String key = extract.url() == null ? "NO_URL_" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) : extract.url();
            if (extract.statusCode() != null) {
                httpStatuses.put(key, extract.statusCode());
            }
            if (extract.failureReason() != null) {
                timeoutReasons.put(key, extract.failureReason());
            }
            int length = extract.extractText() == null ? 0 : extract.extractText().length();
            extractLengths.put(key, length);
            trustScores.put(key, trustScore(extract.url()));
        }
        for (AssistantDtos.AssistantCitation citation : citations) {
            if (citation.snippet() != null && !citation.snippet().isBlank()) {
                injectedSnippetCount++;
            }
        }
        Map<String, Object> observable = new LinkedHashMap<>();
        observable.put("query", query);
        observable.put("queryTerms", rewrittenQueries);
        observable.put("hitSources", sources);
        observable.put("httpStatus", httpStatuses);
        observable.put("timeoutReason", timeoutReasons);
        observable.put("extractLength", extractLengths);
        observable.put("trustedSourceScore", trustScores);
        observable.put("injectedSnippetCount", injectedSnippetCount);
        return observable;
    }

    private int trustScore(String url) {
        if (url == null || url.isBlank()) {
            return 20;
        }
        String normalized = url.toLowerCase(Locale.ROOT);
        if (HIGH_TRUST_DOMAINS.stream().anyMatch(normalized::contains)) {
            return 95;
        }
        if (normalized.contains(".org")) {
            return 80;
        }
        if (normalized.contains(".com")) {
            return 60;
        }
        return 40;
    }

    private String limit(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private record PageExtract(String url, Integer statusCode, String failureReason, String extractText) {
    }

    public record SearchResult(
            String answer,
            List<AssistantDtos.AssistantCitation> citations,
            List<String> suggestedQuestions,
            Map<String, Object> observable,
            String failureReason
    ) {
        static SearchResult empty(String failureReason) {
            return new SearchResult("", List.of(), List.of(), Map.of(), failureReason);
        }
    }
}
