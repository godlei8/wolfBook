package com.wolfbook.backend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wolfbook.backend.common.ApiException;
import com.wolfbook.backend.common.PageResponse;
import com.wolfbook.backend.domain.Comment;
import com.wolfbook.backend.domain.Post;
import com.wolfbook.backend.domain.Report;
import com.wolfbook.backend.domain.UserProfile;
import com.wolfbook.backend.dto.WolfbookDtos;
import com.wolfbook.backend.entity.CommentEntity;
import com.wolfbook.backend.entity.LikeEntity;
import com.wolfbook.backend.entity.PostEntity;
import com.wolfbook.backend.entity.ReportEntity;
import com.wolfbook.backend.entity.UserEntity;
import com.wolfbook.backend.entity.UserFavoritePostEntity;
import com.wolfbook.backend.mapper.CommentMapper;
import com.wolfbook.backend.mapper.LikeMapper;
import com.wolfbook.backend.mapper.PostMapper;
import com.wolfbook.backend.mapper.ReportMapper;
import com.wolfbook.backend.mapper.UserFavoritePostMapper;
import com.wolfbook.backend.mapper.UserMapper;
import com.wolfbook.backend.support.CommunityStatuses;
import com.wolfbook.backend.support.ContentSafetyProvider;
import com.wolfbook.backend.support.DomainConverter;
import com.wolfbook.backend.support.TokenService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 社区内容服务。
 *
 * <p>负责帖子、评论、点赞、收藏、举报、审核状态和内容安全检查。
 * 用户侧发布/互动和后台审核最终都会落到这里，因此状态判断和权限校验集中在本类。</p>
 */
@Service
public class CommunityService {

    public static final String POST_STATUS_PUBLISHED = CommunityStatuses.POST_PUBLISHED;
    public static final String POST_STATUS_OFFLINE = CommunityStatuses.POST_OFFLINE;
    public static final String POST_STATUS_PENDING = CommunityStatuses.POST_PENDING_REVIEW;
    public static final String POST_STATUS_REJECTED = CommunityStatuses.POST_REJECTED;

    public static final String COMMENT_STATUS_VISIBLE = CommunityStatuses.COMMENT_VISIBLE;
    public static final String COMMENT_STATUS_HIDDEN = CommunityStatuses.COMMENT_HIDDEN;

    private static final String LEGACY_UNTITLED_POST = "Untitled post";
    private static final String DEFAULT_POST_TITLE = "未命名帖子";

    private static final Set<String> SUPPORTED_POST_TYPES = Set.of(
            "general",
            "review",
            "board_discussion",
            "qa",
            "strategy",
            "help",
            "recruit"
    );

    private final PostMapper postMapper;
    private final CommentMapper commentMapper;
    private final LikeMapper likeMapper;
    private final ReportMapper reportMapper;
    private final UserMapper userMapper;
    private final UserFavoritePostMapper userFavoritePostMapper;
    private final TokenService tokenService;
    private final ContentSafetyProvider contentSafetyProvider;
    private final DomainConverter converter;
    private final BoardService boardService;
    private final CommunityHotService communityHotService;
    private final CommunitySearchService communitySearchService;

    public CommunityService(
            PostMapper postMapper,
            CommentMapper commentMapper,
            LikeMapper likeMapper,
            ReportMapper reportMapper,
            UserMapper userMapper,
            UserFavoritePostMapper userFavoritePostMapper,
            TokenService tokenService,
            ContentSafetyProvider contentSafetyProvider,
            DomainConverter converter,
            BoardService boardService,
            CommunityHotService communityHotService,
            CommunitySearchService communitySearchService
    ) {
        this.postMapper = postMapper;
        this.commentMapper = commentMapper;
        this.likeMapper = likeMapper;
        this.reportMapper = reportMapper;
        this.userMapper = userMapper;
        this.userFavoritePostMapper = userFavoritePostMapper;
        this.tokenService = tokenService;
        this.contentSafetyProvider = contentSafetyProvider;
        this.converter = converter;
        this.boardService = boardService;
        this.communityHotService = communityHotService;
        this.communitySearchService = communitySearchService;
    }

    public WolfbookDtos.CommunityFeedView listPosts(
            String tab,
            String type,
            Integer boardId,
            String query,
            String sort,
            int page,
            int size,
            String authorization
    ) {
        String currentUser = tokenService.resolveUser(authorization);
        String normalizedType = normalizePostType(type);
        int safePage = Math.max(page, 1);
        int safeSize = Math.max(size, 1);

        FeedSelection selection;
        if (StringUtils.hasText(query)) {
            selection = searchPosts(query.trim(), normalizedType, boardId, sort, safePage, safeSize);
        } else {
            selection = feedPosts(tab, normalizedType, boardId, safePage, safeSize);
        }

        return new WolfbookDtos.CommunityFeedView(
                new PageResponse<>(toPostSummaryViews(selection.items(), currentUser), selection.total(), safePage, safeSize),
                buildHotBoardTopics(),
                buildSuggestedTags(selection.sourcePool())
        );
    }

    @Transactional
    public WolfbookDtos.PostDetailView getPostDetail(Integer id, String authorization) {
        String currentUser = tokenService.resolveUser(authorization);
        PostEntity postEntity = getPostEntity(id);
        ensureReadable(postEntity, currentUser);

        postEntity.setViewCount(safe(postEntity.getViewCount()) + 1);
        postEntity.setUpdateTime(LocalDateTime.now());
        communityHotService.refreshPostRanking(postEntity);
        postMapper.updateById(postEntity);
        communitySearchService.syncPost(postEntity);

        List<CommentEntity> commentEntities = commentMapper.selectList(
                new LambdaQueryWrapper<CommentEntity>()
                        .eq(CommentEntity::getPostId, id)
                        .eq(CommentEntity::getStatus, COMMENT_STATUS_VISIBLE)
        ).stream()
                .sorted(Comparator
                        .comparing(CommentEntity::getLikeCount, Comparator.nullsLast(Integer::compareTo)).reversed()
                        .thenComparing(CommentEntity::getCreateTime, Comparator.nullsLast(LocalDateTime::compareTo).reversed()))
                .toList();

        return toPostDetailView(postEntity, commentEntities, currentUser);
    }

    @Transactional
    public WolfbookDtos.PostDetailView createPost(String authorization, WolfbookDtos.CreatePostRequest request) {
        String openid = tokenService.requireUser(authorization);
        String normalizedType = normalizePostType(request.postType());
        String title = normalizeTitle(request.title());
        String content = normalizeContent(request.content());
        String summary = normalizeSummary(request.summary(), content);
        List<String> images = normalizeStringList(request.images(), 9, 500);
        List<String> roleTags = normalizeStringList(request.roleTags(), 8, 30);
        List<String> tagList = normalizeStringList(request.tagList(), 8, 30);

        contentSafetyProvider.ensureSafeText(title);
        contentSafetyProvider.ensureSafeText(content);
        contentSafetyProvider.ensureSafeText(summary);
        contentSafetyProvider.ensureSafeImages(images);

        PostEntity entity = new PostEntity();
        entity.setOpenid(openid);
        entity.setPostType(normalizedType);
        entity.setTitle(title);
        entity.setSummary(summary);
        entity.setContent(content);
        entity.setImages(converter.writeStringList(images));
        entity.setBoardId(resolveBoardId(request.boardId()));
        entity.setBoardName(resolveBoardName(request.boardId(), request.boardName()));
        entity.setRoleTags(converter.writeStringList(roleTags));
        entity.setTagList(converter.writeStringList(tagList));
        entity.setSessionId(normalizeOptionalText(request.sessionId(), 64));
        entity.setQualityScore(estimateQualityScore(title, content, tagList, images));
        entity.setViewCount(0);
        entity.setLikeCount(0);
        entity.setCommentCount(0);
        entity.setFavoriteCount(0);
        entity.setStatus(POST_STATUS_PUBLISHED);
        entity.setFeatured(false);
        entity.setPinned(false);
        entity.setRejectReason(null);
        entity.setCreateTime(LocalDateTime.now());
        entity.setUpdateTime(LocalDateTime.now());
        communityHotService.refreshPostRanking(entity);
        postMapper.insert(entity);
        communitySearchService.syncPost(entity);
        return getPostDetail(entity.getId(), authorization);
    }

    @Transactional
    public WolfbookDtos.ToggleLikeResponse togglePostLike(String authorization, Integer postId) {
        String openid = tokenService.requireUser(authorization);
        PostEntity post = getPostEntity(postId);
        ensureInteractable(post);
        boolean liked = toggleLike("post", postId, openid);
        post.setLikeCount(countLikes("post", postId));
        post.setUpdateTime(LocalDateTime.now());
        communityHotService.refreshPostRanking(post);
        postMapper.updateById(post);
        communitySearchService.syncPost(post);
        return new WolfbookDtos.ToggleLikeResponse(liked, safe(post.getLikeCount()));
    }

    @Transactional
    public WolfbookDtos.ToggleFavoriteResponse setPostFavorite(String authorization, Integer postId, boolean enabled) {
        String openid = tokenService.requireUser(authorization);
        PostEntity post = getPostEntity(postId);
        ensureInteractable(post);

        UserFavoritePostEntity existing = userFavoritePostMapper.selectOne(
                new LambdaQueryWrapper<UserFavoritePostEntity>()
                        .eq(UserFavoritePostEntity::getOpenid, openid)
                        .eq(UserFavoritePostEntity::getPostId, postId)
        );
        if (enabled && existing == null) {
            UserFavoritePostEntity favorite = new UserFavoritePostEntity();
            favorite.setOpenid(openid);
            favorite.setPostId(postId);
            favorite.setCreateTime(LocalDateTime.now());
            userFavoritePostMapper.insert(favorite);
        }
        if (!enabled && existing != null) {
            userFavoritePostMapper.deleteById(existing.getId());
        }

        post.setFavoriteCount(countFavorites(postId));
        post.setUpdateTime(LocalDateTime.now());
        communityHotService.refreshPostRanking(post);
        postMapper.updateById(post);
        communitySearchService.syncPost(post);
        return new WolfbookDtos.ToggleFavoriteResponse(enabled, safe(post.getFavoriteCount()));
    }

    @Transactional
    public WolfbookDtos.CommentView createComment(String authorization, WolfbookDtos.CreateCommentRequest request) {
        String openid = tokenService.requireUser(authorization);
        PostEntity post = getPostEntity(request.postId());
        ensureInteractable(post);
        String content = normalizeComment(request.content());
        contentSafetyProvider.ensureSafeText(content);

        Integer parentCommentId = null;
        String replyToOpenid = null;
        if (request.parentCommentId() != null) {
            CommentEntity parent = getCommentEntity(request.parentCommentId());
            if (!Objects.equals(parent.getPostId(), request.postId())) {
                throw new ApiException(4002, "comment thread does not belong to the post");
            }
            parentCommentId = parent.getId();
            replyToOpenid = StringUtils.hasText(request.replyToOpenid()) ? request.replyToOpenid().trim() : parent.getOpenid();
        }

        CommentEntity entity = new CommentEntity();
        entity.setPostId(request.postId());
        entity.setOpenid(openid);
        entity.setParentCommentId(parentCommentId);
        entity.setReplyToOpenid(replyToOpenid);
        entity.setContent(content);
        entity.setLikeCount(0);
        entity.setStatus(COMMENT_STATUS_VISIBLE);
        entity.setCreateTime(LocalDateTime.now());
        entity.setUpdateTime(LocalDateTime.now());
        commentMapper.insert(entity);

        post.setCommentCount(safe(post.getCommentCount()) + 1);
        post.setUpdateTime(LocalDateTime.now());
        communityHotService.refreshPostRanking(post);
        postMapper.updateById(post);
        communitySearchService.syncPost(post);
        return toCommentView(entity, currentUserState(openid), loadUsersByOpenids(nonBlankSet(openid, replyToOpenid, post.getOpenid())), post.getOpenid());
    }

    @Transactional
    public WolfbookDtos.ToggleLikeResponse toggleCommentLike(String authorization, Integer commentId) {
        String openid = tokenService.requireUser(authorization);
        CommentEntity comment = getCommentEntity(commentId);
        boolean liked = toggleLike("comment", commentId, openid);
        comment.setLikeCount(countLikes("comment", commentId));
        comment.setUpdateTime(LocalDateTime.now());
        commentMapper.updateById(comment);
        return new WolfbookDtos.ToggleLikeResponse(liked, safe(comment.getLikeCount()));
    }

    public void createReport(String authorization, WolfbookDtos.ReportRequest request) {
        String openid = tokenService.requireUser(authorization);
        ReportEntity entity = new ReportEntity();
        entity.setTargetType(request.targetType());
        entity.setTargetId(request.targetId());
        entity.setOpenid(openid);
        entity.setReason(normalizeOptionalText(request.reason(), 200));
        entity.setProcessStatus(CommunityStatuses.REPORT_OPEN);
        entity.setCreateTime(LocalDateTime.now());
        reportMapper.insert(entity);
    }

    @Transactional
    public void deleteOwnPost(String authorization, Integer postId) {
        String openid = tokenService.requireUser(authorization);
        PostEntity post = getPostEntity(postId);
        if (!Objects.equals(post.getOpenid(), openid)) {
            throw new ApiException(4003, "you can only delete your own posts");
        }
        deletePostCascade(postId);
    }

    @Transactional
    public void deleteOwnComment(String authorization, Integer commentId) {
        String openid = tokenService.requireUser(authorization);
        CommentEntity comment = getCommentEntity(commentId);
        if (!Objects.equals(comment.getOpenid(), openid)) {
            throw new ApiException(4003, "you can only delete your own comments");
        }
        deleteCommentCascade(commentId);
    }

    public Post getPost(Integer id) {
        return converter.toPost(getPostEntity(id));
    }

    public Comment getComment(Integer id) {
        return converter.toComment(getCommentEntity(id));
    }

    public UserProfile getUser(String openid) {
        return toUserProfile(loadUser(openid));
    }

    public Report getReport(Integer id) {
        ReportEntity entity = reportMapper.selectById(id);
        if (entity == null) {
            throw new ApiException(4004, "report not found");
        }
        return converter.toReport(entity);
    }

    public PageResponse<WolfbookDtos.CommentView> listAllComments(int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.max(size, 1);
        List<CommentEntity> comments = commentMapper.selectList(
                new LambdaQueryWrapper<CommentEntity>()
                        .orderByDesc(CommentEntity::getCreateTime)
        );
        List<CommentEntity> paged = pageList(comments, safePage, safeSize);
        List<WolfbookDtos.CommentView> list = toCommentViews(paged, null, loadPostAuthorsByCommentIds(paged));
        return new PageResponse<>(list, comments.size(), safePage, safeSize);
    }

    public PageResponse<WolfbookDtos.PostSummaryView> listAllPosts(int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.max(size, 1);
        List<PostEntity> posts = postMapper.selectList(
                new LambdaQueryWrapper<PostEntity>()
                        .orderByDesc(PostEntity::getPinned)
                        .orderByDesc(PostEntity::getFeatured)
                        .orderByDesc(PostEntity::getCreateTime)
        ).stream()
                .peek(post -> post.setHotScore(communityHotService.calculateHotScore(post)))
                .toList();
        List<PostEntity> paged = pageList(posts, safePage, safeSize);
        List<WolfbookDtos.PostSummaryView> list = toPostSummaryViews(paged, null);
        return new PageResponse<>(list, posts.size(), safePage, safeSize);
    }

    public PageResponse<Report> listAllReports(int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.max(size, 1);
        List<Report> list = reportMapper.selectList(
                        new LambdaQueryWrapper<ReportEntity>()
                                .orderByDesc(ReportEntity::getCreateTime)
                ).stream()
                .map(converter::toReport)
                .toList();
        int fromIndex = Math.min((safePage - 1) * safeSize, list.size());
        int toIndex = Math.min(fromIndex + safeSize, list.size());
        return new PageResponse<>(list.subList(fromIndex, toIndex), list.size(), safePage, safeSize);
    }

    @Transactional
    public void updatePostStatus(Integer postId, String status) {
        PostEntity post = getPostEntity(postId);
        post.setStatus(normalizePostStatus(status));
        if (!POST_STATUS_REJECTED.equals(post.getStatus())) {
            post.setRejectReason(null);
        }
        post.setUpdateTime(LocalDateTime.now());
        communityHotService.refreshPostRanking(post);
        postMapper.updateById(post);
        communitySearchService.syncPost(post);
    }

    @Transactional
    public void updatePostFeatured(Integer postId, boolean enabled) {
        PostEntity post = getPostEntity(postId);
        post.setFeatured(enabled);
        post.setUpdateTime(LocalDateTime.now());
        communityHotService.refreshPostRanking(post);
        postMapper.updateById(post);
        communitySearchService.syncPost(post);
    }

    @Transactional
    public void updatePostPinned(Integer postId, boolean enabled) {
        PostEntity post = getPostEntity(postId);
        post.setPinned(enabled);
        post.setUpdateTime(LocalDateTime.now());
        communityHotService.refreshPostRanking(post);
        postMapper.updateById(post);
        communitySearchService.syncPost(post);
    }

    @Transactional
    public void deletePostCascade(Integer postId) {
        getPostEntity(postId);
        postMapper.deleteById(postId);
        commentMapper.delete(new LambdaQueryWrapper<CommentEntity>().eq(CommentEntity::getPostId, postId));
        likeMapper.delete(new LambdaQueryWrapper<LikeEntity>().eq(LikeEntity::getTargetType, "post").eq(LikeEntity::getTargetId, postId));
        userFavoritePostMapper.delete(new LambdaQueryWrapper<UserFavoritePostEntity>().eq(UserFavoritePostEntity::getPostId, postId));
        reportMapper.delete(new LambdaQueryWrapper<ReportEntity>().eq(ReportEntity::getTargetType, "post").eq(ReportEntity::getTargetId, postId));
        communityHotService.removePost(postId);
        communitySearchService.deletePost(postId);
    }

    @Transactional
    public void deleteCommentCascade(Integer commentId) {
        CommentEntity comment = getCommentEntity(commentId);
        commentMapper.deleteById(commentId);
        likeMapper.delete(new LambdaQueryWrapper<LikeEntity>().eq(LikeEntity::getTargetType, "comment").eq(LikeEntity::getTargetId, commentId));
        reportMapper.delete(new LambdaQueryWrapper<ReportEntity>().eq(ReportEntity::getTargetType, "comment").eq(ReportEntity::getTargetId, commentId));

        PostEntity post = getPostEntity(comment.getPostId());
        post.setCommentCount(Math.max(0, safe(post.getCommentCount()) - 1));
        post.setUpdateTime(LocalDateTime.now());
        communityHotService.refreshPostRanking(post);
        postMapper.updateById(post);
        communitySearchService.syncPost(post);
    }

    private FeedSelection searchPosts(String query, String postType, Integer boardId, String sort, int page, int size) {
        CommunitySearchService.SearchResult searchResult = communitySearchService.search(query, postType, boardId, sort, page, size);
        if (!searchResult.ids().isEmpty()) {
            return new FeedSelection(orderByIds(searchResult.ids()), searchResult.total(), loadPublishedPosts(postType, boardId));
        }

        List<PostEntity> filtered = loadPublishedPosts(postType, boardId).stream()
                .filter(post -> matchesQuery(post, query))
                .sorted(buildFeedComparator(sort, "recommend"))
                .toList();
        return pageSelection(filtered, page, size);
    }

    private FeedSelection feedPosts(String tab, String postType, Integer boardId, int page, int size) {
        List<PostEntity> filtered = loadPublishedPosts(postType, boardId);
        String normalizedTab = normalizeTab(tab);
        List<PostEntity> sorted;
        if ("hot".equals(normalizedTab)) {
            sorted = orderHotPosts(filtered);
        } else if ("latest".equals(normalizedTab)) {
            sorted = filtered.stream()
                    .sorted(buildFeedComparator("latest", normalizedTab))
                    .toList();
        } else {
            sorted = filtered.stream()
                    .sorted(buildFeedComparator("hot", normalizedTab))
                    .toList();
        }
        return pageSelection(sorted, page, size);
    }

    private FeedSelection pageSelection(List<PostEntity> source, int page, int size) {
        return new FeedSelection(pageList(source, page, size), source.size(), source);
    }

    private List<PostEntity> orderHotPosts(List<PostEntity> filtered) {
        if (filtered.isEmpty()) {
            return List.of();
        }
        Map<Integer, Integer> hotRank = new HashMap<>();
        List<Integer> hotIds = communityHotService.listHotPostIds(Math.max(filtered.size(), 1));
        for (int index = 0; index < hotIds.size(); index++) {
            hotRank.put(hotIds.get(index), index);
        }
        return filtered.stream()
                .sorted(Comparator
                        .comparingInt((PostEntity post) -> hotRank.getOrDefault(post.getId(), Integer.MAX_VALUE))
                        .thenComparing(this::dynamicHotScore, Comparator.reverseOrder())
                        .thenComparing(PostEntity::getCreateTime, Comparator.nullsLast(LocalDateTime::compareTo).reversed()))
                .toList();
    }

    private Comparator<PostEntity> buildFeedComparator(String sort, String tab) {
        Comparator<PostEntity> latest = Comparator
                .comparing(PostEntity::getPinned, Comparator.nullsLast(Boolean::compareTo)).reversed()
                .thenComparing(PostEntity::getCreateTime, Comparator.nullsLast(LocalDateTime::compareTo).reversed());
        Comparator<PostEntity> recommend = Comparator
                .comparing(PostEntity::getPinned, Comparator.nullsLast(Boolean::compareTo)).reversed()
                .thenComparing(PostEntity::getFeatured, Comparator.nullsLast(Boolean::compareTo)).reversed()
                .thenComparing(this::dynamicHotScore, Comparator.reverseOrder())
                .thenComparing(PostEntity::getCreateTime, Comparator.nullsLast(LocalDateTime::compareTo).reversed());
        if ("latest".equalsIgnoreCase(sort)) {
            return latest;
        }
        if ("latest".equalsIgnoreCase(tab)) {
            return latest;
        }
        return recommend;
    }

    private List<PostEntity> loadPublishedPosts(String postType, Integer boardId) {
        return postMapper.selectList(
                        new LambdaQueryWrapper<PostEntity>()
                                .eq(PostEntity::getStatus, POST_STATUS_PUBLISHED)
                ).stream()
                .filter(post -> !StringUtils.hasText(postType) || Objects.equals(postType, normalizePostType(post.getPostType())))
                .filter(post -> boardId == null || boardId <= 0 || Objects.equals(boardId, post.getBoardId()))
                .peek(post -> post.setHotScore(dynamicHotScore(post)))
                .toList();
    }

    private List<PostEntity> orderByIds(List<Integer> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        Map<Integer, PostEntity> postsById = postMapper.selectBatchIds(ids).stream()
                .peek(post -> post.setHotScore(dynamicHotScore(post)))
                .collect(Collectors.toMap(PostEntity::getId, Function.identity()));
        List<PostEntity> ordered = new ArrayList<>();
        for (Integer id : ids) {
            PostEntity post = postsById.get(id);
            if (post != null && POST_STATUS_PUBLISHED.equals(post.getStatus())) {
                ordered.add(post);
            }
        }
        return ordered;
    }

    private WolfbookDtos.PostDetailView toPostDetailView(PostEntity postEntity, List<CommentEntity> commentEntities, String currentUser) {
        Map<String, UserEntity> users = loadUsersByOpenids(collectOpenids(postEntity, commentEntities));
        Map<String, UserFavoritePostEntity> favoritesByUser = currentUser == null
                ? Map.of()
                : findFavoriteRelations(List.of(postEntity.getId()), currentUser);
        Set<Integer> likedCommentIds = currentUser == null
                ? Set.of()
                : findLikedTargetIds("comment", commentEntities.stream().map(CommentEntity::getId).toList(), currentUser);
        Set<Integer> likedPostIds = currentUser == null
                ? Set.of()
                : findLikedTargetIds("post", List.of(postEntity.getId()), currentUser);
        Set<Integer> ownCommentIds = currentUser == null
                ? Set.of()
                : commentEntities.stream().filter(item -> Objects.equals(item.getOpenid(), currentUser)).map(CommentEntity::getId).collect(Collectors.toSet());

        UserEntity author = users.get(postEntity.getOpenid());
        String displayTitle = displayTitle(postEntity);
        String displaySummary = displaySummary(postEntity);
        List<WolfbookDtos.CommentView> comments = commentEntities.stream()
                .map(comment -> toCommentView(
                        comment,
                        new UserState(
                                likedCommentIds.contains(comment.getId()),
                                false,
                                ownCommentIds.contains(comment.getId())
                        ),
                        users,
                        postEntity.getOpenid()
                ))
                .toList();

        List<PostEntity> relatedEntities = buildRelatedPostEntities(postEntity);
        return new WolfbookDtos.PostDetailView(
                postEntity.getId(),
                postEntity.getOpenid(),
                nicknameOf(author, postEntity.getOpenid()),
                avatarOf(author),
                normalizePostType(postEntity.getPostType()),
                displayTitle,
                displaySummary,
                safeText(postEntity.getContent()),
                converter.readStringList(postEntity.getImages()),
                postEntity.getBoardId(),
                safeText(postEntity.getBoardName()),
                converter.readStringList(postEntity.getRoleTags()),
                converter.readStringList(postEntity.getTagList()),
                postEntity.getSessionId(),
                safe(postEntity.getQualityScore()),
                dynamicHotScore(postEntity),
                safe(postEntity.getViewCount()),
                safe(postEntity.getLikeCount()),
                safe(postEntity.getCommentCount()),
                safe(postEntity.getFavoriteCount()),
                normalizePostStatus(postEntity.getStatus()),
                Boolean.TRUE.equals(postEntity.getFeatured()),
                Boolean.TRUE.equals(postEntity.getPinned()),
                likedPostIds.contains(postEntity.getId()),
                favoritesByUser.containsKey(String.valueOf(postEntity.getId())),
                Objects.equals(postEntity.getOpenid(), currentUser),
                postEntity.getCreateTime(),
                postEntity.getUpdateTime(),
                buildBoardView(postEntity),
                comments,
                toPostSummaryViews(relatedEntities, currentUser)
        );
    }

    private List<WolfbookDtos.PostSummaryView> toPostSummaryViews(List<PostEntity> posts, String currentUser) {
        if (posts == null || posts.isEmpty()) {
            return List.of();
        }
        Map<String, UserEntity> users = loadUsersByOpenids(posts.stream().map(PostEntity::getOpenid).collect(Collectors.toSet()));
        Set<Integer> likedPostIds = currentUser == null
                ? Set.of()
                : findLikedTargetIds("post", posts.stream().map(PostEntity::getId).toList(), currentUser);
        Map<String, UserFavoritePostEntity> favoriteRelations = currentUser == null
                ? Map.of()
                : findFavoriteRelations(posts.stream().map(PostEntity::getId).toList(), currentUser);

        return posts.stream()
                .map(post -> {
                    UserEntity author = users.get(post.getOpenid());
                    String displayTitle = displayTitle(post);
                    String displaySummary = displaySummary(post);
                    return new WolfbookDtos.PostSummaryView(
                            post.getId(),
                            post.getOpenid(),
                            nicknameOf(author, post.getOpenid()),
                            avatarOf(author),
                            normalizePostType(post.getPostType()),
                            displayTitle,
                            displaySummary,
                            safeText(post.getContent()),
                            converter.readStringList(post.getImages()),
                            post.getBoardId(),
                            safeText(post.getBoardName()),
                            converter.readStringList(post.getRoleTags()),
                            converter.readStringList(post.getTagList()),
                            post.getSessionId(),
                            safe(post.getQualityScore()),
                            dynamicHotScore(post),
                            safe(post.getViewCount()),
                            safe(post.getLikeCount()),
                            safe(post.getCommentCount()),
                            safe(post.getFavoriteCount()),
                            normalizePostStatus(post.getStatus()),
                            Boolean.TRUE.equals(post.getFeatured()),
                            Boolean.TRUE.equals(post.getPinned()),
                            likedPostIds.contains(post.getId()),
                            favoriteRelations.containsKey(String.valueOf(post.getId())),
                            Objects.equals(post.getOpenid(), currentUser),
                            post.getCreateTime(),
                            post.getUpdateTime()
                    );
                })
                .toList();
    }

    private List<WolfbookDtos.CommentView> toCommentViews(List<CommentEntity> comments, String currentUser, Map<Integer, String> postAuthorsByPostId) {
        if (comments == null || comments.isEmpty()) {
            return List.of();
        }
        Set<String> openids = comments.stream()
                .map(CommentEntity::getOpenid)
                .collect(Collectors.toCollection(HashSet::new));
        comments.stream()
                .map(CommentEntity::getReplyToOpenid)
                .filter(StringUtils::hasText)
                .forEach(openids::add);
        Map<String, UserEntity> users = loadUsersByOpenids(openids);
        Set<Integer> likedCommentIds = currentUser == null
                ? Set.of()
                : findLikedTargetIds("comment", comments.stream().map(CommentEntity::getId).toList(), currentUser);
        return comments.stream()
                .map(comment -> toCommentView(
                        comment,
                        new UserState(
                                likedCommentIds.contains(comment.getId()),
                                false,
                                Objects.equals(comment.getOpenid(), currentUser)
                        ),
                        users,
                        postAuthorsByPostId.get(comment.getPostId())
                ))
                .toList();
    }

    private WolfbookDtos.CommentView toCommentView(CommentEntity comment, UserState state, Map<String, UserEntity> users, String postAuthorOpenid) {
        UserEntity author = users.get(comment.getOpenid());
        UserEntity replyTo = StringUtils.hasText(comment.getReplyToOpenid()) ? users.get(comment.getReplyToOpenid()) : null;
        return new WolfbookDtos.CommentView(
                comment.getId(),
                comment.getPostId(),
                comment.getOpenid(),
                nicknameOf(author, comment.getOpenid()),
                avatarOf(author),
                comment.getParentCommentId(),
                comment.getReplyToOpenid(),
                replyTo == null ? null : nicknameOf(replyTo, comment.getReplyToOpenid()),
                safeText(comment.getContent()),
                safe(comment.getLikeCount()),
                state.liked(),
                state.owned(),
                Objects.equals(comment.getOpenid(), postAuthorOpenid),
                normalizeCommentStatus(comment.getStatus()),
                comment.getCreateTime(),
                comment.getUpdateTime()
        );
    }

    private List<PostEntity> buildRelatedPostEntities(PostEntity source) {
        List<Integer> relatedIds = communitySearchService.findRelatedPostIds(source, 12);
        List<PostEntity> related = relatedIds.isEmpty() ? List.of() : orderByIds(relatedIds);
        if (related.isEmpty()) {
            related = loadPublishedPosts(normalizePostType(source.getPostType()), source.getBoardId()).stream()
                    .filter(post -> !Objects.equals(post.getId(), source.getId()))
                    .sorted(buildFeedComparator("hot", "recommend"))
                    .toList();
        }
        return related.stream().limit(4).toList();
    }

    private WolfbookDtos.PostBoardView buildBoardView(PostEntity postEntity) {
        if (postEntity.getBoardId() == null || postEntity.getBoardId() <= 0) {
            return null;
        }
        List<WolfbookDtos.BoardCardView> boards = boardService.listBoardCardsByIds(List.of(postEntity.getBoardId()));
        if (!boards.isEmpty()) {
            WolfbookDtos.BoardCardView board = boards.get(0);
            return new WolfbookDtos.PostBoardView(board.id(), board.name(), board.coverImage(), board.playerCount());
        }
        return new WolfbookDtos.PostBoardView(postEntity.getBoardId(), postEntity.getBoardName(), "", null);
    }

    private List<WolfbookDtos.HotBoardTopicView> buildHotBoardTopics() {
        List<PostEntity> published = loadPublishedPosts(null, null);
        if (published.isEmpty()) {
            return List.of();
        }
        Map<Integer, BoardTopicAccumulator> boardStats = new LinkedHashMap<>();
        for (PostEntity post : orderHotPosts(published).stream().limit(24).toList()) {
            if (post.getBoardId() == null || post.getBoardId() <= 0 || !StringUtils.hasText(post.getBoardName())) {
                continue;
            }
            BoardTopicAccumulator current = boardStats.getOrDefault(post.getBoardId(), new BoardTopicAccumulator(post.getBoardName(), 0, 0d));
            boardStats.put(post.getBoardId(), new BoardTopicAccumulator(current.boardName(), current.postCount() + 1, current.hotScore() + dynamicHotScore(post)));
        }
        if (boardStats.isEmpty()) {
            return List.of();
        }
        Map<Integer, WolfbookDtos.BoardCardView> boardCards = boardService.listBoardCardsByIds(new ArrayList<>(boardStats.keySet()))
                .stream()
                .collect(Collectors.toMap(WolfbookDtos.BoardCardView::id, Function.identity()));
        return boardStats.entrySet().stream()
                .sorted(Map.Entry.<Integer, BoardTopicAccumulator>comparingByValue(Comparator.comparing(BoardTopicAccumulator::hotScore)).reversed())
                .limit(6)
                .map(entry -> {
                    WolfbookDtos.BoardCardView board = boardCards.get(entry.getKey());
                    BoardTopicAccumulator stats = entry.getValue();
                    return new WolfbookDtos.HotBoardTopicView(
                            entry.getKey(),
                            stats.boardName(),
                            board == null ? "" : board.coverImage(),
                            board == null ? null : board.playerCount(),
                            stats.postCount(),
                            stats.hotScore()
                    );
                })
                .toList();
    }

    private List<String> buildSuggestedTags(List<PostEntity> sourcePool) {
        if (sourcePool == null || sourcePool.isEmpty()) {
            return List.of();
        }
        Map<String, Integer> counts = new HashMap<>();
        for (PostEntity post : sourcePool) {
            for (String tag : converter.readStringList(post.getTagList())) {
                String normalized = safeText(tag);
                if (!normalized.isBlank()) {
                    counts.merge(normalized, 1, Integer::sum);
                }
            }
        }
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry::getKey))
                .limit(8)
                .map(Map.Entry::getKey)
                .toList();
    }

    private Map<String, UserFavoritePostEntity> findFavoriteRelations(List<Integer> postIds, String openid) {
        if (!StringUtils.hasText(openid) || postIds == null || postIds.isEmpty()) {
            return Map.of();
        }
        return userFavoritePostMapper.selectList(
                        new LambdaQueryWrapper<UserFavoritePostEntity>()
                                .eq(UserFavoritePostEntity::getOpenid, openid)
                                .in(UserFavoritePostEntity::getPostId, postIds)
                ).stream()
                .collect(Collectors.toMap(item -> String.valueOf(item.getPostId()), Function.identity()));
    }

    private Set<Integer> findLikedTargetIds(String targetType, List<Integer> targetIds, String openid) {
        if (!StringUtils.hasText(openid) || targetIds == null || targetIds.isEmpty()) {
            return Set.of();
        }
        return likeMapper.selectList(
                        new LambdaQueryWrapper<LikeEntity>()
                                .eq(LikeEntity::getTargetType, targetType)
                                .eq(LikeEntity::getOpenid, openid)
                                .in(LikeEntity::getTargetId, targetIds)
                ).stream()
                .map(LikeEntity::getTargetId)
                .collect(Collectors.toSet());
    }

    private Map<String, UserEntity> loadUsersByOpenids(Collection<String> openids) {
        if (openids == null) {
            return Map.of();
        }
        List<String> ids = openids.stream()
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return userMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(UserEntity::getOpenid, Function.identity()));
    }

    private Map<Integer, String> loadPostAuthorsByCommentIds(List<CommentEntity> comments) {
        if (comments == null || comments.isEmpty()) {
            return Map.of();
        }
        Set<Integer> postIds = comments.stream().map(CommentEntity::getPostId).collect(Collectors.toSet());
        return postMapper.selectBatchIds(postIds).stream()
                .collect(Collectors.toMap(PostEntity::getId, PostEntity::getOpenid));
    }

    private Set<String> collectOpenids(PostEntity postEntity, List<CommentEntity> comments) {
        Set<String> openids = new HashSet<>();
        openids.add(postEntity.getOpenid());
        for (CommentEntity comment : comments) {
            openids.add(comment.getOpenid());
            if (StringUtils.hasText(comment.getReplyToOpenid())) {
                openids.add(comment.getReplyToOpenid());
            }
        }
        return openids;
    }

    private UserEntity loadUser(String openid) {
        UserEntity entity = userMapper.selectById(openid);
        if (entity == null) {
            throw new ApiException(4004, "user not found");
        }
        return entity;
    }

    private UserProfile toUserProfile(UserEntity entity) {
        return converter.toUserProfile(entity);
    }

    private UserState currentUserState(String openid) {
        return new UserState(false, false, StringUtils.hasText(openid));
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

    private int countFavorites(Integer postId) {
        return Math.toIntExact(userFavoritePostMapper.selectCount(
                new LambdaQueryWrapper<UserFavoritePostEntity>()
                        .eq(UserFavoritePostEntity::getPostId, postId)
        ));
    }

    private void ensureReadable(PostEntity post, String currentUser) {
        if (POST_STATUS_PUBLISHED.equals(post.getStatus())) {
            return;
        }
        if (Objects.equals(post.getOpenid(), currentUser)) {
            return;
        }
        throw new ApiException(4004, "post not found");
    }

    private void ensureInteractable(PostEntity post) {
        if (!POST_STATUS_PUBLISHED.equals(post.getStatus())) {
            throw new ApiException(4003, "post is not available");
        }
    }

    private PostEntity getPostEntity(Integer id) {
        PostEntity entity = postMapper.selectById(id);
        if (entity == null) {
            throw new ApiException(4004, "post not found");
        }
        return entity;
    }

    private CommentEntity getCommentEntity(Integer id) {
        CommentEntity entity = commentMapper.selectById(id);
        if (entity == null) {
            throw new ApiException(4004, "comment not found");
        }
        return entity;
    }

    private String normalizePostType(String postType) {
        if (!StringUtils.hasText(postType)) {
            return "general";
        }
        String normalized = postType.trim().toLowerCase(Locale.ROOT);
        return SUPPORTED_POST_TYPES.contains(normalized) ? normalized : "general";
    }

    private String normalizeTab(String tab) {
        if (!StringUtils.hasText(tab)) {
            return "recommend";
        }
        String normalized = tab.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "latest", "hot", "recommend" -> normalized;
            default -> "recommend";
        };
    }

    private String normalizePostStatus(String status) {
        if (!StringUtils.hasText(status)) {
            return POST_STATUS_PUBLISHED;
        }
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case POST_STATUS_OFFLINE, POST_STATUS_PENDING, POST_STATUS_REJECTED -> normalized;
            default -> POST_STATUS_PUBLISHED;
        };
    }

    private String normalizeCommentStatus(String status) {
        if (!StringUtils.hasText(status)) {
            return COMMENT_STATUS_VISIBLE;
        }
        return COMMENT_STATUS_HIDDEN.equalsIgnoreCase(status) ? COMMENT_STATUS_HIDDEN : COMMENT_STATUS_VISIBLE;
    }

    private String normalizeTitle(String title) {
        String normalized = safeText(title);
        if (normalized.isBlank()) {
            throw new ApiException(4002, "title is required");
        }
        if (normalized.length() < 4) {
            throw new ApiException(4002, "title is too short");
        }
        return normalized.length() > 60 ? normalized.substring(0, 60) : normalized;
    }

    private String normalizeContent(String content) {
        String normalized = safeText(content);
        if (normalized.isBlank()) {
            throw new ApiException(4002, "content is required");
        }
        if (normalized.length() < 10) {
            throw new ApiException(4002, "content is too short");
        }
        return normalized.length() > 5000 ? normalized.substring(0, 5000) : normalized;
    }

    private String normalizeSummary(String summary, String content) {
        String normalized = safeText(summary);
        if (normalized.isBlank()) {
            normalized = content.replaceAll("\\s+", " ");
            normalized = normalized.length() > 90 ? normalized.substring(0, 90) : normalized;
        }
        return normalized.length() > 200 ? normalized.substring(0, 200) : normalized;
    }

    private String displayTitle(PostEntity post) {
        return displayTitle(post == null ? null : post.getTitle(), post == null ? null : post.getSummary(), post == null ? null : post.getContent());
    }

    private String displayTitle(String title, String summary, String content) {
        String normalizedTitle = safeText(title);
        if (!isPlaceholderTitle(normalizedTitle)) {
            return normalizedTitle;
        }
        String fallback = excerpt(summary, 40);
        if (fallback.isBlank()) {
            fallback = excerpt(content, 40);
        }
        return fallback.isBlank() ? DEFAULT_POST_TITLE : fallback;
    }

    private String displaySummary(PostEntity post) {
        return displaySummary(post == null ? null : post.getSummary(), post == null ? null : post.getContent(), displayTitle(post));
    }

    private String displaySummary(String summary, String content, String fallbackTitle) {
        String normalizedSummary = excerpt(summary, 120);
        if (!normalizedSummary.isBlank()) {
            return normalizedSummary;
        }
        String fallback = excerpt(content, 120);
        if (!fallback.isBlank()) {
            return fallback;
        }
        return safeText(fallbackTitle);
    }

    private String normalizeComment(String content) {
        String normalized = safeText(content);
        if (normalized.isBlank()) {
            throw new ApiException(4002, "comment content is required");
        }
        return normalized.length() > 600 ? normalized.substring(0, 600) : normalized;
    }

    private List<String> normalizeStringList(List<String> source, int limit, int itemMaxLength) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        return source.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .map(item -> item.length() > itemMaxLength ? item.substring(0, itemMaxLength) : item)
                .limit(limit)
                .toList();
    }

    private Integer resolveBoardId(Integer boardId) {
        if (boardId == null || boardId <= 0) {
            return null;
        }
        boardService.getBoard(boardId);
        return boardId;
    }

    private String resolveBoardName(Integer boardId, String boardName) {
        if (boardId != null && boardId > 0) {
            return boardService.getBoard(boardId).name();
        }
        return normalizeOptionalText(boardName, 100);
    }

    private String normalizeOptionalText(String value, int maxLength) {
        String normalized = safeText(value);
        if (normalized.isBlank()) {
            return null;
        }
        return normalized.length() > maxLength ? normalized.substring(0, maxLength) : normalized;
    }

    private int estimateQualityScore(String title, String content, List<String> tags, List<String> images) {
        int score = 30;
        score += Math.min(title.length(), 30);
        score += Math.min(content.length() / 20, 30);
        score += Math.min(tags.size() * 4, 16);
        score += Math.min(images.size() * 3, 12);
        return Math.min(score, 100);
    }

    private boolean matchesQuery(PostEntity post, String query) {
        String normalized = query.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return true;
        }
        String searchable = String.join(" ",
                displayTitle(post),
                displaySummary(post),
                safeText(post.getContent()),
                safeText(post.getBoardName()),
                String.join(" ", converter.readStringList(post.getTagList())),
                String.join(" ", converter.readStringList(post.getRoleTags()))
        ).toLowerCase(Locale.ROOT);
        return searchable.contains(normalized);
    }

    private double dynamicHotScore(PostEntity post) {
        return communityHotService.calculateHotScore(post);
    }

    private String nicknameOf(UserEntity user, String openid) {
        if (user != null && StringUtils.hasText(user.getNickname())) {
            return user.getNickname();
        }
        if (!StringUtils.hasText(openid)) {
            return "狼友";
        }
        String suffix = openid.substring(Math.max(0, openid.length() - 6)).toUpperCase(Locale.ROOT);
        return "玩家" + suffix;
    }

    private String avatarOf(UserEntity user) {
        return user == null || !StringUtils.hasText(user.getAvatar()) ? "" : user.getAvatar();
    }

    private Set<String> nonBlankSet(String... values) {
        Set<String> set = new HashSet<>();
        if (values == null) {
            return set;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                set.add(value);
            }
        }
        return set;
    }

    private String safeText(String value) {
        return value == null ? "" : value.trim();
    }

    private boolean isPlaceholderTitle(String title) {
        return !StringUtils.hasText(title) || LEGACY_UNTITLED_POST.equalsIgnoreCase(title.trim());
    }

    private String excerpt(String value, int maxLength) {
        String normalized = safeText(value).replaceAll("\\s+", " ");
        if (normalized.isBlank()) {
            return "";
        }
        return normalized.length() > maxLength ? normalized.substring(0, maxLength) : normalized;
    }

    private int safe(Integer value) {
        return value == null ? 0 : value;
    }

    private <T> List<T> pageList(List<T> source, int page, int size) {
        int fromIndex = Math.min((page - 1) * size, source.size());
        int toIndex = Math.min(fromIndex + size, source.size());
        return source.subList(fromIndex, toIndex);
    }

    private record FeedSelection(List<PostEntity> items, long total, List<PostEntity> sourcePool) {
    }

    private record UserState(boolean liked, boolean favorited, boolean owned) {
    }

    private record BoardTopicAccumulator(String boardName, int postCount, double hotScore) {
    }
}
