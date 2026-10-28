package com.wolfbook.backend.service.assistant;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wolfbook.backend.common.ApiException;
import com.wolfbook.backend.dto.AssistantDtos;
import com.wolfbook.backend.entity.AssistantModelEntity;
import com.wolfbook.backend.mapper.AssistantModelMapper;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * AI 助手的模型管理：可以登记多个 OpenAI 兼容的对话模型（DeepSeek 官方、学校的模型服务……），
 * active = 1 的那一个就是 AI 助手和「按世界观起草」实际调用的模型（见 AssistantConfigService.getRuntimeConfig）。
 * 表是空的时候，把原来「DeepSeek 配置」里的那一份搬进来当第一项。
 */
@Service
public class AssistantModelService {

    private static final String SECRET_MASK = "********";
    private static final int SECRET_SUFFIX_LENGTH = 4;
    private static final int MAX_ERROR_LENGTH = 200;

    private final AssistantModelMapper modelMapper;
    private final AssistantConfigService assistantConfigService;
    private final DeepSeekChatService deepSeekChatService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public AssistantModelService(
            AssistantModelMapper modelMapper,
            AssistantConfigService assistantConfigService,
            DeepSeekChatService deepSeekChatService,
            ObjectMapper objectMapper
    ) {
        this.modelMapper = modelMapper;
        this.assistantConfigService = assistantConfigService;
        this.deepSeekChatService = deepSeekChatService;
        this.objectMapper = objectMapper;
    }

    public List<AssistantDtos.AdminModelView> list() {
        ensureSeeded();
        return modelMapper.selectList(new LambdaQueryWrapper<AssistantModelEntity>().orderByAsc(AssistantModelEntity::getId))
                .stream()
                .map(this::toView)
                .toList();
    }

    public AssistantDtos.AdminModelView create(AssistantDtos.AdminModelRequest request) {
        ensureSeeded();
        AssistantModelEntity entity = new AssistantModelEntity();
        apply(entity, request, null);
        entity.setActive(0);
        entity.setCreateTime(LocalDateTime.now());
        modelMapper.insert(entity);
        return toView(entity);
    }

    public AssistantDtos.AdminModelView update(Integer id, AssistantDtos.AdminModelRequest request) {
        AssistantModelEntity entity = getEntity(id);
        apply(entity, request, entity.getApiKey());
        if (isActive(entity) && !isEnabled(entity)) {
            throw new ApiException(4000, "当前在用的模型不能停用，先切换到别的模型");
        }
        if (isActive(entity) && !StringUtils.hasText(entity.getApiKey())) {
            throw new ApiException(4000, "当前在用的模型不能没有 Key");
        }
        modelMapper.updateById(entity);
        return toView(entity);
    }

    public void delete(Integer id) {
        AssistantModelEntity entity = getEntity(id);
        if (isActive(entity)) {
            throw new ApiException(4000, "当前在用的模型不能删，先切换到别的模型");
        }
        modelMapper.deleteById(id);
    }

    /** 设为当前：AI 助手、起草马上改用它。 */
    @Transactional
    public AssistantDtos.AdminModelView activate(Integer id) {
        AssistantModelEntity entity = getEntity(id);
        if (!isEnabled(entity)) {
            throw new ApiException(4000, "这个模型已停用，先启用再设为当前");
        }
        if (!StringUtils.hasText(entity.getApiKey())) {
            throw new ApiException(4000, "这个模型还没填 Key");
        }
        modelMapper.update(null, new LambdaUpdateWrapper<AssistantModelEntity>()
                .set(AssistantModelEntity::getActive, 0)
                .eq(AssistantModelEntity::getActive, 1));
        entity.setActive(1);
        entity.setUpdateTime(LocalDateTime.now());
        modelMapper.updateById(entity);
        return toView(entity);
    }

    /** 测试连接：真的发一句话过去，看能不能回、要多久。不抛异常，失败写在 error 里。 */
    public AssistantDtos.ModelTestResult test(AssistantDtos.ModelTestRequest request) {
        AssistantModelEntity stored = request.id() == null ? null : getEntity(request.id());
        String baseUrl = normalizeBaseUrl(StringUtils.hasText(request.baseUrl()) ? request.baseUrl() : stored == null ? null : stored.getBaseUrl());
        String model = StringUtils.hasText(request.model()) ? request.model().trim() : stored == null ? null : stored.getModel();
        String apiKey = resolveSecret(request.apiKey(), stored == null ? null : stored.getApiKey());
        if (!StringUtils.hasText(model)) {
            return new AssistantDtos.ModelTestResult(false, 0, null, "没填模型编码");
        }
        if (!StringUtils.hasText(apiKey)) {
            return new AssistantDtos.ModelTestResult(false, 0, null, "没填 Key");
        }
        Prompt prompt = new Prompt(List.of(
                new SystemMessage("你在做连通性测试。"),
                new UserMessage("只回复「在线」两个字。")
        ));
        long start = System.currentTimeMillis();
        try {
            boolean disableThinking = request.disableThinking() != null ? request.disableThinking()
                    : stored != null && Integer.valueOf(1).equals(stored.getDisableThinking());
            String reply = deepSeekChatService.complete(prompt, new AssistantDtos.ProviderSection("openai-compatible", model, baseUrl, apiKey, disableThinking), 0.0);
            long latency = System.currentTimeMillis() - start;
            if (!StringUtils.hasText(reply)) {
                return new AssistantDtos.ModelTestResult(false, latency, null, "接口通了，但没返回内容");
            }
            return new AssistantDtos.ModelTestResult(true, latency, clip(reply.trim(), 60), null);
        } catch (RuntimeException exception) {
            return new AssistantDtos.ModelTestResult(false, System.currentTimeMillis() - start, null, describe(exception));
        }
    }

    /** 读接口的 /models，给后台下拉选模型编码用；读不到返回空列表。 */
    public List<String> remoteModels(AssistantDtos.ModelTestRequest request) {
        AssistantModelEntity stored = request.id() == null ? null : getEntity(request.id());
        String baseUrl = normalizeBaseUrl(StringUtils.hasText(request.baseUrl()) ? request.baseUrl() : stored == null ? null : stored.getBaseUrl());
        String apiKey = resolveSecret(request.apiKey(), stored == null ? null : stored.getApiKey());
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + "/models")).timeout(Duration.ofSeconds(15)).GET();
            if (StringUtils.hasText(apiKey)) {
                builder.header("Authorization", "Bearer " + apiKey);
            }
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                throw new ApiException(4000, "读模型列表失败：HTTP " + response.statusCode());
            }
            List<String> ids = new ArrayList<>();
            for (JsonNode item : objectMapper.readTree(response.body()).path("data")) {
                String id = item.path("id").asText("");
                if (!id.isBlank()) ids.add(id);
            }
            return ids;
        } catch (ApiException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ApiException(4000, "读模型列表被中断");
        } catch (Exception exception) {
            throw new ApiException(4000, "读模型列表失败：" + clip(String.valueOf(exception.getMessage()), MAX_ERROR_LENGTH));
        }
    }

    /** 第一次打开模型管理时，把原来「DeepSeek 配置」那一份搬进来；有 Key 就直接设为当前。 */
    private void ensureSeeded() {
        if (modelMapper.selectCount(null) > 0) {
            return;
        }
        AssistantDtos.ProviderSection legacy = assistantConfigService.getLegacyProvider();
        AssistantModelEntity entity = new AssistantModelEntity();
        entity.setName("DeepSeek 官方");
        entity.setBaseUrl(normalizeBaseUrl(legacy.baseUrl()));
        entity.setModel(StringUtils.hasText(legacy.model()) ? legacy.model() : "deepseek-chat");
        entity.setApiKey(legacy.apiKey());
        entity.setEnabled(1);
        entity.setDisableThinking(0);
        entity.setActive(StringUtils.hasText(legacy.apiKey()) ? 1 : 0);
        entity.setRemark("从原来的 DeepSeek 配置迁移");
        entity.setCreateTime(LocalDateTime.now());
        entity.setUpdateTime(LocalDateTime.now());
        modelMapper.insert(entity);
    }

    private void apply(AssistantModelEntity entity, AssistantDtos.AdminModelRequest request, String existingKey) {
        entity.setName(request.name().trim());
        entity.setBaseUrl(normalizeBaseUrl(request.baseUrl()));
        entity.setModel(request.model().trim());
        entity.setApiKey(resolveSecret(request.apiKey(), existingKey));
        entity.setEnabled(request.enabled() == null || request.enabled() ? 1 : 0);
        entity.setDisableThinking(Boolean.TRUE.equals(request.disableThinking()) ? 1 : 0);
        entity.setRemark(StringUtils.hasText(request.remark()) ? request.remark().trim() : null);
        entity.setUpdateTime(LocalDateTime.now());
    }

    private String normalizeBaseUrl(String value) {
        String url = value == null ? "" : value.trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new ApiException(4000, "接口地址要以 http:// 或 https:// 开头");
        }
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        // 填成完整的 /chat/completions 也认
        if (url.endsWith("/chat/completions")) {
            url = url.substring(0, url.length() - "/chat/completions".length());
        }
        return url;
    }

    /** 传回打码值或 null 表示不改；传空串表示清空。 */
    private String resolveSecret(String submitted, String existing) {
        if (submitted == null) {
            return existing;
        }
        String normalized = submitted.trim();
        if (normalized.isEmpty()) {
            return existing == null ? null : "";
        }
        if (StringUtils.hasText(existing) && normalized.equals(maskSecret(existing))) {
            return existing;
        }
        return normalized;
    }

    private AssistantModelEntity getEntity(Integer id) {
        AssistantModelEntity entity = id == null ? null : modelMapper.selectById(id);
        if (entity == null) {
            throw new ApiException(4004, "模型不存在");
        }
        return entity;
    }

    private AssistantDtos.AdminModelView toView(AssistantModelEntity entity) {
        return new AssistantDtos.AdminModelView(
                entity.getId(),
                entity.getName(),
                entity.getBaseUrl(),
                entity.getModel(),
                maskSecret(entity.getApiKey()),
                isEnabled(entity),
                isActive(entity),
                Integer.valueOf(1).equals(entity.getDisableThinking()),
                entity.getRemark(),
                entity.getUpdateTime()
        );
    }

    private static boolean isActive(AssistantModelEntity entity) {
        return Integer.valueOf(1).equals(entity.getActive());
    }

    private static boolean isEnabled(AssistantModelEntity entity) {
        return Integer.valueOf(1).equals(entity.getEnabled());
    }

    static String maskSecret(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "";
        }
        String normalized = raw.trim();
        return normalized.length() <= SECRET_SUFFIX_LENGTH ? SECRET_MASK : SECRET_MASK + normalized.substring(normalized.length() - SECRET_SUFFIX_LENGTH);
    }

    /** 把调用失败说成人话：余额不足、Key 不对、地址不通……不带出 Key。 */
    private static String describe(RuntimeException exception) {
        Throwable root = exception;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        String message = String.valueOf(root.getMessage());
        if (message.contains("status 401") || message.contains("status 403")) return "Key 不对或没有权限（" + statusOf(message) + "）";
        if (message.contains("status 402")) return "账户余额不足（402）";
        if (message.contains("status 404")) return "地址或模型编码不对（404）";
        if (message.contains("status 429")) return "请求太频繁或额度用完（429）";
        if (root instanceof java.net.ConnectException || message.contains("Connection refused")) return "连不上这个地址";
        if (root instanceof java.net.http.HttpTimeoutException) return "等了太久没有响应";
        if (root instanceof java.nio.channels.UnresolvedAddressException || message.contains("UnresolvedAddress")) return "域名解析不了";
        return clip(message, MAX_ERROR_LENGTH);
    }

    private static String statusOf(String message) {
        int index = message.indexOf("status ");
        return index < 0 ? "" : message.substring(index + 7, Math.min(message.length(), index + 10));
    }

    private static String clip(String value, int max) {
        return value.length() > max ? value.substring(0, max) + "…" : value;
    }
}
