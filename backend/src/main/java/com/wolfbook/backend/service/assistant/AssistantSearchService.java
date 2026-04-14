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

import java.util.ArrayList;
import java.util.List;

@Service
public class AssistantSearchService {

    private final ObjectProvider<MiniMaxChatModel> miniMaxChatModelProvider;
    private final ObjectMapper objectMapper;

    public AssistantSearchService(ObjectProvider<MiniMaxChatModel> miniMaxChatModelProvider, ObjectMapper objectMapper) {
        this.miniMaxChatModelProvider = miniMaxChatModelProvider;
        this.objectMapper = objectMapper;
    }

    public boolean isAvailable() {
        return miniMaxChatModelProvider.getIfAvailable() != null;
    }

    public SearchResult searchWeb(String query, String modelName, double temperature) {
        MiniMaxChatModel chatModel = miniMaxChatModelProvider.getIfAvailable();
        if (chatModel == null) {
            return SearchResult.empty();
        }

        Prompt prompt = new Prompt(
                List.of(
                        new SystemMessage("Use web search when needed. Return strict JSON with keys answer, citations, suggestedQuestions. citations must be an array of {title,snippet,url}."),
                        new UserMessage("Question: " + query)
                ),
                MiniMaxChatOptions.builder()
                        .model(modelName)
                        .temperature(temperature)
                        .tools(List.of(MiniMaxApi.FunctionTool.webSearchFunctionTool()))
                        .build()
        );

        ChatResponse response = chatModel.call(prompt);
        String content = response.getResult().getOutput().getText();
        return parse(content);
    }

    private SearchResult parse(String content) {
        if (content == null || content.isBlank()) {
            return SearchResult.empty();
        }
        try {
            JsonNode root = objectMapper.readTree(content);
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
            return new SearchResult(root.path("answer").asText(content), citations, suggestedQuestions);
        } catch (Exception exception) {
            return new SearchResult(content, List.of(), List.of());
        }
    }

    public record SearchResult(String answer, List<AssistantDtos.AssistantCitation> citations, List<String> suggestedQuestions) {
        static SearchResult empty() {
            return new SearchResult("", List.of(), List.of());
        }
    }
}
