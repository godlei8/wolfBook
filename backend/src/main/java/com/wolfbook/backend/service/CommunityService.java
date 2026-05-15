package com.wolfbook.backend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wolfbook.backend.common.ApiException;
import com.wolfbook.backend.common.PageResponse;
import com.wolfbook.backend.domain.Comment;
import com.wolfbook.backend.domain.Post;
import com.wolfbook.backend.domain.Report;
import com.wolfbook.backend.domain.UserProfile;
import com.wolfbook.backend.dto.WolfbookDtos;
import com.wolfbook.backend.entity.*;
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
import java.util.List;
import java.util.Objects;

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

    public CommunityService(
            PostMapper postMapper,
            CommentMapper commentMapper,
            LikeMapper likeMapper,
            ReportMapper reportMapper,
            UserMapper userMapper,
            TokenService tokenService,
            ContentSafetyProvider contentSafetyProvider,
            DomainConverter converter
    ) {
        this.postMapper = postMapper;
        this.commentMapper = commentMapper;
        this.likeMapper = likeMapper;
        this.reportMapper = reportMapper;
        this.userMapper = userMapper;
        this.tokenService = tokenService;
        this.contentSafetyProvider = contentSafetyProvider;
        this.converter = converter;
    }

    public PageResponse<WolfbookDtos.PostSummaryView> listPosts(int page, int size, String authorization) {
        String currentUser = tokenService.resolveUser(authorization);
        List<WolfbookDtos.PostSummaryView> items = postMapper.selectList(
                        new LambdaQueryWrapper<PostEntity>()
                                .eq(PostEntity::getStatus, 1)
                                .orderByDesc(PostEntity::getCreateTime)
                ).stream()
                .map(converter::toPost)
                .map(post -> toPostSummary(post, currentUser))
                .toList();
        return page(items, page, size);
    }

    public WolfbookDtos.PostDetailView getPostDetail(Integer id, String authorization) {
        String currentUser = tokenService.resolveUser(authorization);
        Post post = getPost(id);
        UserProfile author = getUser(post.openid());
        List<WolfbookDtos.CommentView> comments = commentMapper.selectList(
                        new LambdaQueryWrapper<CommentEntity>()
                                .eq(CommentEntity::getPostId, id)
                                .eq(CommentEntity::getStatus, 1)
                ).stream()
                .map(converter::toComment)
                .sorted(Comparator.comparing(Comment::likeCount).reversed().thenComparing(Comment::createTime).reversed())
                .map(comment -> toCommentView(comment, currentUser))
                .toList();
        return new WolfbookDtos.PostDetailView(
                post.id(),
                author.openid(),
                author.nickname(),
                author.avatar(),
                post.content(),
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
        contentSafetyProvider.ensureSafeText(request.content());
        contentSafetyProvider.ensureSafeImages(request.images());

        PostEntity entity = new PostEntity();
        entity.setOpenid(openid);
        entity.setContent(request.content());
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
        post.setLikeCount(likeCount);
        post.setUpdateTime(LocalDateTime.now());
        postMapper.updateById(post);
        return new WolfbookDtos.ToggleLikeResponse(liked, likeCount);
    }

    public WolfbookDtos.CommentView createComment(String authorization, WolfbookDtos.CreateCommentRequest request) {
        String openid = tokenService.requireUser(authorization);
        PostEntity post = getPostEntity(request.postId());
        contentSafetyProvider.ensureSafeText(request.content());

        CommentEntity entity = new CommentEntity();
        entity.setPostId(request.postId());
        entity.setOpenid(openid);
        entity.setContent(request.content());
        entity.setLikeCount(0);
        entity.setStatus(1);
        entity.setCreateTime(LocalDateTime.now());
        commentMapper.insert(entity);

        post.setCommentCount(post.getCommentCount() + 1);
        post.setUpdateTime(LocalDateTime.now());
        postMapper.updateById(post);
        return toCommentView(converter.toComment(entity), openid);
    }

    public WolfbookDtos.ToggleLikeResponse toggleCommentLike(String authorization, Integer commentId) {
        String openid = tokenService.requireUser(authorization);
        CommentEntity comment = getCommentEntity(commentId);
        boolean liked = toggleLike("comment", commentId, openid);
        int likeCount = countLikes("comment", commentId);
        comment.setLikeCount(likeCount);
        commentMapper.updateById(comment);
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
        UserProfile author = getUser(post.openid());
        return new WolfbookDtos.PostSummaryView(
                post.id(),
                author.openid(),
                author.nickname(),
                author.avatar(),
                post.content(),
                post.images(),
                post.likeCount(),
                post.commentCount(),
                post.status(),
                isLiked("post", post.id(), currentUser),
                post.createTime()
        );
    }

    public WolfbookDtos.CommentView toCommentView(Comment comment, String currentUser) {
        UserProfile author = getUser(comment.openid());
        return new WolfbookDtos.CommentView(
                comment.id(),
                comment.postId(),
                author.openid(),
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
                .map(comment -> toCommentView(comment, null))
                .toList();
        return page(list, page, size);
    }

    public PageResponse<WolfbookDtos.PostSummaryView> listAllPosts(int page, int size) {
        List<WolfbookDtos.PostSummaryView> list = postMapper.selectList(
                        new LambdaQueryWrapper<PostEntity>()
                                .orderByDesc(PostEntity::getCreateTime)
                ).stream()
                .map(converter::toPost)
                .map(post -> toPostSummary(post, null))
                .toList();
        return page(list, page, size);
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

        PostEntity post = getPostEntity(comment.getPostId());
        post.setCommentCount(Math.max(0, post.getCommentCount() - 1));
        post.setUpdateTime(LocalDateTime.now());
        postMapper.updateById(post);
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
