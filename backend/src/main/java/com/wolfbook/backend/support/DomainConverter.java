package com.wolfbook.backend.support;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wolfbook.backend.domain.*;
import com.wolfbook.backend.entity.*;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

@Component
public class DomainConverter {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };
    private static final TypeReference<List<FaqItem>> FAQ_LIST = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;

    public DomainConverter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String writeStringList(List<String> values) {
        return write(values == null ? List.of() : values);
    }

    public String writeFaqList(List<FaqItem> values) {
        return write(values == null ? List.of() : values);
    }

    public List<String> readStringList(String json) {
        return read(json, STRING_LIST);
    }

    public List<FaqItem> readFaqList(String json) {
        return read(json, FAQ_LIST);
    }

    public Board toBoard(BoardEntity entity, List<BoardRoleEntity> roleEntities) {
        return new Board(
                entity.getId(),
                entity.getName(),
                entity.getPlayerCount(),
                entity.getDifficulty(),
                readStringList(entity.getTags()),
                entity.getCoverImage(),
                entity.getCardDescription(),
                entity.getBriefConfig(),
                readStringList(entity.getSpecialRules()),
                readStringList(entity.getTips()),
                readFaqList(entity.getFaqs()),
                entity.getWinCondition(),
                entity.getRuleType(),
                entity.getStatus(),
                roleEntities == null ? List.of() : roleEntities.stream()
                        .map(item -> new BoardRoleRef(item.getRoleId(), item.getCount()))
                        .toList()
        );
    }

    public Role toRole(RoleEntity entity) {
        return new Role(
                entity.getId(),
                entity.getName(),
                entity.getAlias(),
                entity.getCamp(),
                entity.getSkill(),
                entity.getBackground(),
                readFaqList(entity.getFaqs()),
                entity.getPortrait(),
                entity.getFullIllustration()
        );
    }

    public Post toPost(PostEntity entity) {
        return new Post(
                entity.getId(),
                entity.getOpenid(),
                entity.getContent(),
                readStringList(entity.getImages()),
                entity.getLikeCount(),
                entity.getCommentCount(),
                entity.getStatus(),
                entity.getCreateTime(),
                entity.getUpdateTime()
        );
    }

    public Comment toComment(CommentEntity entity) {
        return new Comment(
                entity.getId(),
                entity.getPostId(),
                entity.getOpenid(),
                entity.getContent(),
                entity.getLikeCount(),
                entity.getStatus(),
                entity.getCreateTime()
        );
    }

    public Report toReport(ReportEntity entity) {
        return new Report(
                entity.getId(),
                entity.getTargetType(),
                entity.getTargetId(),
                entity.getOpenid(),
                entity.getReason(),
                entity.getProcessStatus(),
                entity.getProcessBy(),
                entity.getProcessTime(),
                entity.getCreateTime()
        );
    }

    public UserProfile toUserProfile(UserEntity entity) {
        return new UserProfile(
                entity.getOpenid(),
                entity.getNickname(),
                entity.getAvatar(),
                entity.getStatus(),
                entity.getCreateTime()
        );
    }

    public AdminUser toAdminUser(AdminUserEntity entity) {
        return new AdminUser(
                entity.getId(),
                entity.getUsername(),
                entity.getPassword(),
                entity.getDisplayName(),
                entity.getStatus(),
                entity.getCreateTime()
        );
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to write json column", exception);
        }
    }

    private <T> T read(String json, TypeReference<T> reference) {
        if (json == null || json.isBlank()) {
            if (reference == STRING_LIST) {
                return (T) Collections.<String>emptyList();
            }
            if (reference == FAQ_LIST) {
                return (T) Collections.<FaqItem>emptyList();
            }
        }
        try {
            return objectMapper.readValue(json, reference);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to read json column", exception);
        }
    }
}
