package com.wolfbook.backend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wolfbook.backend.common.ApiException;
import com.wolfbook.backend.common.PageResponse;
import com.wolfbook.backend.domain.Comment;
import com.wolfbook.backend.domain.Post;
import com.wolfbook.backend.domain.Report;
import com.wolfbook.backend.domain.UserProfile;
import com.wolfbook.backend.dto.WolfbookDtos;
import com.wolfbook.backend.entity.*;
import com.wolfbook.backend.mapper.BoardMapper;
import com.wolfbook.backend.mapper.CommentMapper;
import com.wolfbook.backend.mapper.LikeMapper;
import com.wolfbook.backend.mapper.PostMapper;
import com.wolfbook.backend.mapper.ReportMapper;
import com.wolfbook.backend.mapper.UserMapper;
import com.wolfbook.backend.support.ContentSafetyProvider;
import com.wolfbook.backend.support.DomainConverter;
import com.wolfbook.backend.support.TokenService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class CommunityService {

    private final PostMapper postMapper;
    private final CommentMapper commentMapper;
    private final LikeMapper likeMapper;
    private final ReportMapper reportMapper;
    private final UserMapper userMapper;
    private final TokenService tokenService;
    private final ContentSafetyProvider contentSafetyProvider;
    private final DomainConverter converter;
    private final BoardMapper boardMapper;

    public CommunityService(
            PostMapper postMapper,
            CommentMapper commentMapper,
            LikeMapper likeMapper,
            ReportMapper reportMapper,
            UserMapper userMapper,
            TokenService tokenService,
            ContentSafetyProvider contentSafetyProvider,
            DomainConverter converter,
            BoardMapper boardMapper
    ) {
        this.postMapper = postMapper;
        this.commentMapper = commentMapper;
        this.likeMapper = likeMapper;
        this.reportMapper = reportMapper;
        this.userMapper = userMapper;
        this.tokenService = tokenService;
        this.contentSafetyProvider = contentSafetyProvider;
        this.converter = converter;
        this.boardMapper = boardMapper;
    }

    public static final int MAX_PAGE_SIZE = 50;
    public static final int ADMIN_MAX_PAGE_SIZE = 500;

    public PageResponse<WolfbookDtos.PostSummaryView> listPosts(int page, int size, String authorization) {
        return listPosts(page, size, authorization, null, null, null, false);
    }

    /**
     * 酒馆列表：sort = hot 按点赞数（同票按时间），其余按时间倒序；category 按分类筛选；boardId 只看某个板子的帖子；mine 只看自己的。
     */
    public PageResponse<WolfbookDtos.PostSummaryView> listPosts(int page, int size, String authorization, String sort, String category, Integer boardId, boolean mine) {
        // mine = 只看自己发的（「我的」页的入口），需要登录
        String currentUser = mine ? tokenService.requireUser(authorization) : tokenService.resolveUser(authorization);
        LambdaQueryWrapper<PostEntity> wrapper = new LambdaQueryWrapper<PostEntity>().eq(PostEntity::getStatus, 1);
        if (mine) {
            wrapper.eq(PostEntity::getOpenid, currentUser);
        }
        if (category != null && !category.isBlank()) {
            wrapper.eq(PostEntity::getCategory, category.trim());
        }
        if (boardId != null && boardId > 0) {
            wrapper.eq(PostEntity::getBoardId, boardId);
        }
        if ("hot".equalsIgnoreCase(sort)) {
            wrapper.orderByDesc(PostEntity::getLikeCount).orderByDesc(PostEntity::getCreateTime);
        } else {
            wrapper.orderByDesc(PostEntity::getCreateTime);
        }
        return pagePosts(wrapper, page, size, currentUser, false);
    }

    private PageResponse<WolfbookDtos.PostSummaryView> pagePosts(
            LambdaQueryWrapper<PostEntity> wrapper,
            int page,
            int size,
            String currentUser,
            boolean includeAuthorOpenid
    ) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), includeAuthorOpenid ? ADMIN_MAX_PAGE_SIZE : MAX_PAGE_SIZE);
        Page<PostEntity> result = postMapper.selectPage(new Page<>(safePage, safeSize), wrapper);
        List<Post> posts = result.getRecords().stream().map(converter::toPost).toList();
        Map<String, UserProfile> authors = loadUsers(posts.stream().map(Post::openid).collect(Collectors.toSet()));
        List<WolfbookDtos.PostSummaryView> items = posts.stream()
                .map(post -> toPostSummary(post, currentUser, authors.get(post.openid()), includeAuthorOpenid))
                .toList();
        return new PageResponse<>(items, (int) result.getTotal(), safePage, safeSize);
    }

    public WolfbookDtos.PostDetailView getPostDetail(Integer id, String authorization) {
        String currentUser = tokenService.resolveUser(authorization);
        Post post = converter.toPost(getVisiblePostEntity(id));
        UserProfile author = getUser(post.openid());
        List<WolfbookDtos.CommentView> comments = commentMapper.selectList(
                        new LambdaQueryWrapper<CommentEntity>()
                                .eq(CommentEntity::getPostId, id)
                ).stream()
                .map(converter::toComment)
                .filter(comment -> isVisibleComment(comment.status()))
                .sorted(Comparator.comparing(Comment::likeCount).reversed().thenComparing(Comment::createTime).reversed())
                .map(comment -> toCommentView(comment, currentUser))
                .toList();
        return new WolfbookDtos.PostDetailView(
                post.id(),
                null,
                author.nickname(),
                author.avatar(),
                post.content(),
                post.title(),
                post.category(),
                post.boardId(),
                post.boardName(),
                post.images(),
                post.likeCount(),
                post.commentCount(),
                post.status(),
                isLiked("post", post.id(), currentUser),
                post.createTime(),
                comments
        );
    }

    public WolfbookDtos.PostDetailView createPost(String authorization, WolfbookDtos.CreatePostRequest request) {
        String openid = tokenService.requireUser(authorization);
        String title = request.title() == null || request.title().isBlank() ? null : request.title().trim();
        contentSafetyProvider.ensureSafeText(title == null ? request.content() : title + "\n" + request.content());
        contentSafetyProvider.ensureSafeImages(request.images());

        PostEntity entity = new PostEntity();
        entity.setOpenid(openid);
        entity.setContent(request.content());
        entity.setTitle(title);
        entity.setCategory(request.category() == null || request.category().isBlank() ? "讨论" : request.category());
        if (request.boardId() != null && request.boardId() > 0) {
            // 板子名以库里为准，不信任前端传来的名字
            BoardEntity board = boardMapper.selectById(request.boardId());
            if (board == null) {
                throw new ApiException(4000, "关联的板子不存在");
            }
            entity.setBoardId(board.getId());
            entity.setBoardName(board.getName());
        }
        entity.setImages(converter.writeStringList(request.images()));
        entity.setLikeCount(0);
        entity.setCommentCount(0);
        entity.setStatus(1);
        entity.setCreateTime(LocalDateTime.now());
        entity.setUpdateTime(LocalDateTime.now());
        postMapper.insert(entity);
        return getPostDetail(entity.getId(), authorization);
    }

    public WolfbookDtos.ToggleLikeResponse togglePostLike(String authorization, Integer postId) {
        String openid = tokenService.requireUser(authorization);
        PostEntity post = getPostEntity(postId);
        boolean liked = toggleLike("post", postId, openid);
        int likeCount = countLikes("post", postId);
        postMapper.update(null, new LambdaUpdateWrapper<PostEntity>()
                .eq(PostEntity::getId, post.getId())
                .set(PostEntity::getLikeCount, likeCount)
                .set(PostEntity::getUpdateTime, LocalDateTime.now()));
        return new WolfbookDtos.ToggleLikeResponse(liked, likeCount);
    }

    public WolfbookDtos.CommentView createComment(String authorization, WolfbookDtos.CreateCommentRequest request) {
        String openid = tokenService.requireUser(authorization);
        PostEntity post = getVisiblePostEntity(request.postId());
        contentSafetyProvider.ensureSafeText(request.content());

        CommentEntity entity = new CommentEntity();
        entity.setPostId(request.postId());
        entity.setOpenid(openid);
        entity.setContent(request.content());
        entity.setLikeCount(0);
        entity.setStatus(CommentEntity.STATUS_VISIBLE);
        entity.setCreateTime(LocalDateTime.now());
        commentMapper.insert(entity);

        adjustCommentCount(post.getId(), 1);
        return toCommentView(converter.toComment(entity), openid);
    }

    public WolfbookDtos.ToggleLikeResponse toggleCommentLike(String authorization, Integer commentId) {
        String openid = tokenService.requireUser(authorization);
        CommentEntity comment = getCommentEntity(commentId);
        boolean liked = toggleLike("comment", commentId, openid);
        int likeCount = countLikes("comment", commentId);
        commentMapper.update(null, new LambdaUpdateWrapper<CommentEntity>()
                .eq(CommentEntity::getId, comment.getId())
                .set(CommentEntity::getLikeCount, likeCount));
        return new WolfbookDtos.ToggleLikeResponse(liked, likeCount);
    }

    public void createReport(String authorization, WolfbookDtos.ReportRequest request) {
        String openid = tokenService.requireUser(authorization);
        ReportEntity entity = new ReportEntity();
        entity.setTargetType(request.targetType());
        entity.setTargetId(request.targetId());
        entity.setOpenid(openid);
        entity.setReason(request.reason());
        entity.setProcessStatus("OPEN");
        entity.setCreateTime(LocalDateTime.now());
        reportMapper.insert(entity);
    }

    public Post getPost(Integer id) {
        return converter.toPost(getPostEntity(id));
    }

    public Comment getComment(Integer id) {
        return converter.toComment(getCommentEntity(id));
    }

    public UserProfile getUser(String openid) {
        UserEntity entity = userMapper.selectById(openid);
        if (entity == null) {
            throw new ApiException(4004, "用户不存在");
        }
        return converter.toUserProfile(entity);
    }

    public Report getReport(Integer id) {
        ReportEntity entity = reportMapper.selectById(id);
        if (entity == null) {
            throw new ApiException(4004, "举报不存在");
        }
        return converter.toReport(entity);
    }

    public WolfbookDtos.PostSummaryView toPostSummary(Post post, String currentUser) {
        return toPostSummary(post, currentUser, null, true);
    }

    private WolfbookDtos.PostSummaryView toPostSummary(Post post, String currentUser, UserProfile loadedAuthor, boolean includeAuthorOpenid) {
        UserProfile author = loadedAuthor != null ? loadedAuthor : getUser(post.openid());
        return new WolfbookDtos.PostSummaryView(
                post.id(),
                includeAuthorOpenid ? author.openid() : null,
                author.nickname(),
                author.avatar(),
                post.content(),
                post.title(),
                post.category(),
                post.boardId(),
                post.boardName(),
                post.images(),
                post.likeCount(),
                post.commentCount(),
                post.status(),
                isLiked("post", post.id(), currentUser),
                post.createTime()
        );
    }

    public WolfbookDtos.CommentView toCommentView(Comment comment, String currentUser) {
        return toCommentView(comment, currentUser, false);
    }

    private WolfbookDtos.CommentView toCommentView(Comment comment, String currentUser, boolean includeAuthorOpenid) {
        UserProfile author = getUser(comment.openid());
        return new WolfbookDtos.CommentView(
                comment.id(),
                comment.postId(),
                includeAuthorOpenid ? author.openid() : null,
                author.nickname(),
                author.avatar(),
                comment.content(),
                comment.likeCount(),
                isLiked("comment", comment.id(), currentUser),
                comment.status(),
                comment.createTime()
        );
    }

    public PageResponse<WolfbookDtos.CommentView> listAllComments(int page, int size) {
        List<WolfbookDtos.CommentView> list = commentMapper.selectList(
                        new LambdaQueryWrapper<CommentEntity>()
                                .orderByDesc(CommentEntity::getCreateTime)
                ).stream()
                .map(converter::toComment)
                .map(comment -> toCommentView(comment, null, true))
                .toList();
        return page(list, page, size);
    }

    private boolean isVisibleComment(String status) {
        if (status == null || status.isBlank()) {
            return true;
        }
        return CommentEntity.STATUS_VISIBLE.equalsIgnoreCase(status) || "1".equals(status);
    }

    public PageResponse<WolfbookDtos.PostSummaryView> listAllPosts(int page, int size) {
        return pagePosts(new LambdaQueryWrapper<PostEntity>().orderByDesc(PostEntity::getCreateTime), page, size, null, true);
    }

    public PageResponse<Report> listAllReports(int page, int size) {
        List<Report> list = reportMapper.selectList(
                        new LambdaQueryWrapper<ReportEntity>()
                                .orderByDesc(ReportEntity::getCreateTime)
                ).stream()
                .map(converter::toReport)
                .toList();
        return page(list, page, size);
    }

    public void deletePostCascade(Integer postId) {
        getPostEntity(postId);
        postMapper.deleteById(postId);
        commentMapper.delete(new LambdaQueryWrapper<CommentEntity>().eq(CommentEntity::getPostId, postId));
        likeMapper.delete(new LambdaQueryWrapper<LikeEntity>().eq(LikeEntity::getTargetType, "post").eq(LikeEntity::getTargetId, postId));
    }

    public void deleteCommentCascade(Integer commentId) {
        CommentEntity comment = getCommentEntity(commentId);
        commentMapper.deleteById(commentId);
        likeMapper.delete(new LambdaQueryWrapper<LikeEntity>().eq(LikeEntity::getTargetType, "comment").eq(LikeEntity::getTargetId, commentId));

        adjustCommentCount(comment.getPostId(), -1);
    }

    private void adjustCommentCount(Integer postId, int delta) {
        postMapper.update(null, new LambdaUpdateWrapper<PostEntity>()
                .eq(PostEntity::getId, postId)
                .setSql(delta > 0
                        ? "comment_count = comment_count + " + delta
                        : "comment_count = GREATEST(comment_count - " + (-delta) + ", 0)")
                .set(PostEntity::getUpdateTime, LocalDateTime.now()));
    }

    private boolean toggleLike(String targetType, Integer targetId, String openid) {
        LikeEntity existing = likeMapper.selectOne(
                new LambdaQueryWrapper<LikeEntity>()
                        .eq(LikeEntity::getTargetType, targetType)
                        .eq(LikeEntity::getTargetId, targetId)
                        .eq(LikeEntity::getOpenid, openid)
        );
        if (existing != null) {
            likeMapper.deleteById(existing.getId());
            return false;
        }
        LikeEntity like = new LikeEntity();
        like.setTargetType(targetType);
        like.setTargetId(targetId);
        like.setOpenid(openid);
        like.setCreateTime(LocalDateTime.now());
        likeMapper.insert(like);
        return true;
    }

    private int countLikes(String targetType, Integer targetId) {
        return Math.toIntExact(likeMapper.selectCount(
                new LambdaQueryWrapper<LikeEntity>()
                        .eq(LikeEntity::getTargetType, targetType)
                        .eq(LikeEntity::getTargetId, targetId)
        ));
    }

    private boolean isLiked(String targetType, Integer targetId, String openid) {
        if (openid == null || openid.isBlank()) {
            return false;
        }
        return likeMapper.selectCount(
                new LambdaQueryWrapper<LikeEntity>()
                        .eq(LikeEntity::getTargetType, targetType)
                        .eq(LikeEntity::getTargetId, targetId)
                        .eq(LikeEntity::getOpenid, openid)
        ) > 0;
    }

    private PostEntity getPostEntity(Integer id) {
        PostEntity entity = postMapper.selectById(id);
        if (entity == null) {
            throw new ApiException(4004, "帖子不存在");
        }
        return entity;
    }

    /** 小程序端只允许访问在线（status = 1）的帖子；下线帖子对用户等同于不存在。 */
    private PostEntity getVisiblePostEntity(Integer id) {
        PostEntity entity = getPostEntity(id);
        if (!Integer.valueOf(1).equals(entity.getStatus())) {
            throw new ApiException(4004, "帖子不存在或已下线");
        }
        return entity;
    }

    private Map<String, UserProfile> loadUsers(Set<String> openids) {
        if (openids.isEmpty()) {
            return Map.of();
        }
        Map<String, UserProfile> result = new HashMap<>();
        for (UserEntity entity : userMapper.selectBatchIds(openids)) {
            result.put(entity.getOpenid(), converter.toUserProfile(entity));
        }
        for (String openid : openids) {
            result.computeIfAbsent(openid, this::getUser);
        }
        return result;
    }

    private CommentEntity getCommentEntity(Integer id) {
        CommentEntity entity = commentMapper.selectById(id);
        if (entity == null) {
            throw new ApiException(4004, "评论不存在");
        }
        return entity;
    }

    private <T> PageResponse<T> page(List<T> list, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.max(size, 1);
        int fromIndex = Math.min((safePage - 1) * safeSize, list.size());
        int toIndex = Math.min(fromIndex + safeSize, list.size());
        return new PageResponse<>(list.subList(fromIndex, toIndex), list.size(), safePage, safeSize);
    }
}
