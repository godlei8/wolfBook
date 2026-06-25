package com.wolfbook.backend.support;

import com.wolfbook.backend.domain.FaqItem;
import com.wolfbook.backend.entity.*;
import com.wolfbook.backend.mapper.*;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Component
public class DatabaseSeeder {

    private final UserMapper userMapper;
    private final RoleMapper roleMapper;
    private final BoardMapper boardMapper;
    private final BoardRoleMapper boardRoleMapper;
    private final PostMapper postMapper;
    private final CommentMapper commentMapper;
    private final LikeMapper likeMapper;
    private final ReportMapper reportMapper;
    private final AdminUserMapper adminUserMapper;
    private final DomainConverter converter;
    private final Environment environment;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public DatabaseSeeder(
            UserMapper userMapper,
            RoleMapper roleMapper,
            BoardMapper boardMapper,
            BoardRoleMapper boardRoleMapper,
            PostMapper postMapper,
            CommentMapper commentMapper,
            LikeMapper likeMapper,
            ReportMapper reportMapper,
            AdminUserMapper adminUserMapper,
            DomainConverter converter,
            Environment environment
    ) {
        this.userMapper = userMapper;
        this.roleMapper = roleMapper;
        this.boardMapper = boardMapper;
        this.boardRoleMapper = boardRoleMapper;
        this.postMapper = postMapper;
        this.commentMapper = commentMapper;
        this.likeMapper = likeMapper;
        this.reportMapper = reportMapper;
        this.adminUserMapper = adminUserMapper;
        this.converter = converter;
        this.environment = environment;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void seed() {
        if (userMapper.selectCount(null) == 0) {
            seedUsers();
        }
        if (roleMapper.selectCount(null) == 0) {
            seedRoles();
        }
        if (boardMapper.selectCount(null) == 0) {
            seedBoards();
        }
        if (postMapper.selectCount(null) == 0) {
            seedCommunity();
        }
        if (adminUserMapper.selectCount(null) == 0) {
            seedAdminUser();
        }
    }

    private void seedUsers() {
        saveUser("wx_moon", "月下孤狼", "https://picsum.photos/seed/moon/300/300", LocalDateTime.now().minusDays(20));
        saveUser("wx_star", "星轨祭司", "https://picsum.photos/seed/star/300/300", LocalDateTime.now().minusDays(18));
        saveUser("wx_judge", "逻辑判官", "https://picsum.photos/seed/judge/300/300", LocalDateTime.now().minusDays(16));
    }

    private void seedRoles() {
        saveRole(1, "预言家", "先知", "好人", "神职", "每晚可查验一名玩家身份。", "夜幕中最清醒的观察者，负责在混沌中给出可信信息。", faq("预言家首夜查验到金水要跳吗？", "视板子强度和警徽收益决定，不必机械起跳。"), "https://picsum.photos/seed/seer/300/300", "https://picsum.photos/seed/seerfull/700/900");
        saveRole(2, "女巫", null, "好人", "神职", "拥有一瓶解药和一瓶毒药，通常各限用一次。", "兼具拯救与裁决能力的高优先级神职。", faq("女巫首夜是否必须开药？", "不必须，取决于板型、刀口与信息价值。"), "https://picsum.photos/seed/witch/300/300", "https://picsum.photos/seed/witchfull/700/900");
        saveRole(3, "猎人", null, "好人", "神职", "出局时可发动技能带走一名玩家。", "靠硬核威慑改变轮次节奏。", faq("猎人被毒能不能开枪？", "默认毒出不可开枪。"), "https://picsum.photos/seed/hunter/300/300", "https://picsum.photos/seed/hunterfull/700/900");
        saveRole(4, "守卫", null, "好人", "神职", "每晚守护一名玩家，通常不能连续守同一人。", "在关键夜里决定轮次容错。", faq("守卫可以守自己吗？", "默认不可以，具体以板规说明为准。"), "https://picsum.photos/seed/guard/300/300", "https://picsum.photos/seed/guardfull/700/900");
        saveRole(5, "骑士", null, "好人", "神职", "白天可翻牌决斗一名玩家，决斗结果会直接影响轮次。", "强节奏神职，擅长在白天主动改变局势。", faq("骑士决斗失败会怎样？", "通常会立即出局，具体以板规说明为准。"), "https://picsum.photos/seed/knight/300/300", "https://picsum.photos/seed/knightfull/700/900");
        saveRole(6, "狼人", null, "狼人", "狼人", "夜间与同伴协商击杀目标。", "标准狼人阵营角色。", faq("狼人白天可以自爆吗？", "不同板子会在规则详情中单独说明。"), "https://picsum.photos/seed/wolf/300/300", "https://picsum.photos/seed/wolffull/700/900");
        saveRole(7, "狼美人", null, "狼人", "功能狼", "可魅惑目标，部分板型中与出局联动。", "高操作上限的狼人功能位。", faq("狼美人殉情能否触发技能？", "默认按板规优先级处理，详情见对应板子 FAQ。"), "https://picsum.photos/seed/wolfbeauty/300/300", "https://picsum.photos/seed/wolfbeautyfull/700/900");
        saveRole(8, "白痴", null, "好人", "平民", "被票出后可翻牌免死，但失去投票权。", "在公共轮次中提供额外容错。", faq("白痴翻牌后还能发言吗？", "默认可以，无法投票。"), "https://picsum.photos/seed/idiot/300/300", "https://picsum.photos/seed/idiotfull/700/900");
        saveRole(9, "平民", null, "好人", "平民", "无主动技能，依赖发言与站边。", "所有高阶板型的逻辑基础。", faq("平民要怎么提高贡献？", "优先整理发言矛盾、投票路径和夜间信息。"), "https://picsum.photos/seed/villager/300/300", "https://picsum.photos/seed/villagerfull/700/900");
        saveRole(10, "白狼王", null, "狼人", "功能狼", "白天可选择自爆并带走一名玩家。", "经典强攻型狼人角色，擅长在关键轮次强行换人。", faq("白狼王可以在放逐发言阶段开枪吗？", "通常以板规说明为准，核心是白天出局时具备强换人能力。"), "https://picsum.photos/seed/whitewolfking/300/300", "https://picsum.photos/seed/whitewolfkingfull/700/900");
        saveRole(11, "黑狼王", null, "狼人", "功能狼", "出局时可发动技能带走一名玩家，常见于高配板。", "比普通狼更强调白天博弈与轮次置换。", faq("黑狼王和白狼王有什么差异？", "具体发动时机依板规区分，但都属于强换人位。"), "https://picsum.photos/seed/blackwolfking/300/300", "https://picsum.photos/seed/blackwolfkingfull/700/900");
        saveRole(12, "机械狼", null, "狼人", "功能狼", "可在特定时机继承神职能力，是常见的高配狼队角色。", "具备较强的后期成长性与伪装空间。", faq("机械狼何时能学习技能？", "通常在满足对应条件后继承已出局神职技能。"), "https://picsum.photos/seed/mechwolf/300/300", "https://picsum.photos/seed/mechwolffull/700/900");
        saveRole(13, "石像鬼", null, "狼人", "功能狼", "夜间可获取额外视角信息，帮助狼队建立站边优势。", "偏运营和信息压制的狼人角色。", faq("石像鬼是否等同预言家？", "不是，它提供的是狼队视角强化而非好人查验。"), "https://picsum.photos/seed/gargoyle/300/300", "https://picsum.photos/seed/gargoylefull/700/900");
        saveRole(14, "通灵师", null, "好人", "神职", "可通过灵视获取额外身份信息，常见于高配板。", "信息神的一种，擅长补足预言家之外的查验链。", faq("通灵师和预言家定位一样吗？", "都偏信息位，但触发方式和信息结构通常不同。"), "https://picsum.photos/seed/spiritseer/300/300", "https://picsum.photos/seed/spiritseerfull/700/900");
        saveRole(15, "织梦人", null, "好人", "神职", "可在夜间连接玩家关系，改变信息与生死联动。", "极具策略性的高配神职。", faq("织梦人连线失败会怎样？", "通常不会公开失败结果，但会影响后续判断链。"), "https://picsum.photos/seed/dreamweaver/300/300", "https://picsum.photos/seed/dreamweaverfull/700/900");
        saveRole(16, "爱神", null, "第三方", "第三方", "可在开局指定情侣关系，部分局中情侣拥有独立胜利条件。", "常见的第三方机制角色，改变基础阵营博弈。", faq("情侣一定是第三方吗？", "取决于具体板规，有些板型中情侣会形成独立目标。"), "https://picsum.photos/seed/cupid/300/300", "https://picsum.photos/seed/cupidfull/700/900");
        saveRole(17, "老酒鬼", null, "好人", "平民", "拥有较强抗伤或延迟结算能力，常见于功能板。", "属于能扰动刀口节奏的特殊民职。", faq("老酒鬼是否算神职？", "通常不算，会归为好人阵营的平民类。"), "https://picsum.photos/seed/drunk/300/300", "https://picsum.photos/seed/drunkfull/700/900");
    }

    private void seedBoards() {
        saveBoard(1, "狼美人骑士", 12, "进阶", List.of("经典", "高配"), "https://picsum.photos/seed/board1/900/600", List.of("狼美人死亡后会与被魅惑对象发生联动。", "骑士白天可翻牌决斗一名玩家。", "骑士决斗失败会直接出局。"), List.of("警上信息密度很高，优先看发言连贯性。", "骑士不要过早出刀口视角。"), faq("狼美人可以不发动技能吗？", "可以，视具体轮次收益而定。"), "屠边", "功能板", List.of(roleRef(6, 3), roleRef(7, 1), roleRef(1, 1), roleRef(2, 1), roleRef(5, 1), roleRef(4, 1), roleRef(8, 1), roleRef(9, 3)));
        saveBoard(2, "预女猎白", 12, "入门", List.of("经典", "教学"), "https://picsum.photos/seed/board2/900/600", List.of("标准配置，适合入门局和复盘训练。", "白痴翻牌后失去投票权。"), List.of("教学局可优先记录投票链。"), faq("首夜刀口和银水冲突怎么处理？", "优先结合女巫信息和次日发言判断。"), "屠边", "标准板", List.of(roleRef(6, 4), roleRef(1, 1), roleRef(2, 1), roleRef(3, 1), roleRef(8, 1), roleRef(9, 4)));
        saveBoard(3, "守卫九人局", 9, "入门", List.of("娱乐", "快节奏"), "https://picsum.photos/seed/board3/900/600", List.of("九人局发言轮次短，更看票型。", "守卫与女巫不可形成双药强保。"), List.of("适合移动端快速记录与复盘。"), faq("九人局需要警上发言吗？", "建议保留警上发言，信息收益更高。"), "屠边", "短局", List.of(roleRef(6, 3), roleRef(1, 1), roleRef(2, 1), roleRef(4, 1), roleRef(9, 3)));
        saveBoard(4, "白狼王骑士", 12, "进阶", List.of("经典", "高配"), "https://picsum.photos/seed/board4/900/600", List.of("白狼王在关键轮次具备强换人能力。", "骑士的白天决斗会极大放大轮次收益。"), List.of("警上要格外关注冲票和自爆节奏。"), faq("白狼王一定会和骑士形成对冲吗？", "不一定，但这类板型更强调白天强势角色的互博。"), "屠边", "对抗板", List.of(roleRef(6, 3), roleRef(10, 1), roleRef(1, 1), roleRef(2, 1), roleRef(5, 1), roleRef(4, 1), roleRef(9, 4)));
        saveBoard(5, "机械狼通灵师", 12, "烧脑", List.of("高配", "烧脑"), "https://picsum.photos/seed/board5/900/600", List.of("机械狼拥有高上限的学习空间。", "通灵师会补充额外信息链，提升对局复杂度。"), List.of("复盘时建议单独整理技能继承与信息来源。"), faq("机械狼是否总能学到关键技能？", "取决于场上死亡顺序和狼队运营。"), "屠边", "高配板", List.of(roleRef(6, 3), roleRef(12, 1), roleRef(14, 1), roleRef(2, 1), roleRef(3, 1), roleRef(4, 1), roleRef(9, 4)));
        saveBoard(6, "黑狼王织梦人", 12, "烧脑", List.of("高配", "功能"), "https://picsum.photos/seed/board6/900/600", List.of("黑狼王强化白天置换能力。", "织梦人会改变玩家之间的生死与信息联动。"), List.of("这类板型更适合熟悉轮次管理的玩家。"), faq("织梦人的优先级高吗？", "通常很高，因为连结关系会显著影响后续票型判断。"), "屠边", "高配板", List.of(roleRef(6, 3), roleRef(11, 1), roleRef(1, 1), roleRef(2, 1), roleRef(3, 1), roleRef(15, 1), roleRef(9, 4)));
        saveBoard(7, "爱神混情局", 12, "进阶", List.of("娱乐", "第三方"), "https://picsum.photos/seed/board7/900/600", List.of("爱神开局连线会打破基础站边结构。", "情侣关系会让部分回合出现独立胜负目标。"), List.of("发言时要额外关注不合常理的保人与互踩。"), faq("爱神局一定会出现第三方胜利吗？", "不一定，但情侣关系会显著改变局势。"), "屠边", "机制板", List.of(roleRef(6, 4), roleRef(16, 1), roleRef(1, 1), roleRef(2, 1), roleRef(3, 1), roleRef(9, 4)));
    }

    private void seedCommunity() {
        LocalDateTime now = LocalDateTime.now();
        savePost(1, "wx_moon", "昨晚的狼美人骑士局太精彩了，最后一轮悍跳成功把局势全部带偏。", List.of("https://picsum.photos/seed/post1a/600/600", "https://picsum.photos/seed/post1b/600/600", "https://picsum.photos/seed/post1c/600/600"), 2, 2, 1, now.minusHours(3), now.minusHours(3));
        savePost(2, "wx_star", "预女猎白依然是最好用的教学板子，信息闭环清晰，很适合复盘。", List.of("https://picsum.photos/seed/post2a/900/600"), 1, 1, 1, now.minusHours(8), now.minusHours(8));
        saveComment(1, 1, "wx_judge", "这局最妙的是警徽流，基本提前把守卫视角暴露出来了。", 1, 1, now.minusHours(2));
        saveComment(2, 1, "wx_star", "狼美人压状态那段确实很漂亮。", 0, 1, now.minusHours(1));
        saveComment(3, 2, "wx_moon", "教学局也很适合新手练站边。", 0, 1, now.minusHours(6));
        saveLike("post", 1, "wx_star", now.minusHours(2));
        saveLike("post", 1, "wx_judge", now.minusHours(2));
        saveLike("comment", 1, "wx_moon", now.minusHours(1));

        ReportEntity report = new ReportEntity();
        report.setId(1);
        report.setTargetType("post");
        report.setTargetId(2);
        report.setOpenid("wx_judge");
        report.setReason("怀疑带有外站引流倾向，需人工复核。");
        report.setProcessStatus("OPEN");
        report.setCreateTime(now.minusHours(4));
        reportMapper.insert(report);
    }

    private void seedAdminUser() {
        AdminUserEntity admin = new AdminUserEntity();
        admin.setId(1);
        admin.setUsername("admin");
        admin.setPassword(passwordEncoder.encode(resolveAdminInitPassword()));
        admin.setDisplayName("星盘管理员");
        admin.setStatus(1);
        admin.setCreateTime(LocalDateTime.now().minusDays(30));
        adminUserMapper.insert(admin);
    }

    private String resolveAdminInitPassword() {
        String initPassword = environment.getProperty("ADMIN_INIT_PASSWORD", "");
        if (environment.acceptsProfiles(Profiles.of("prod")) && initPassword.isBlank()) {
            throw new IllegalStateException("ADMIN_INIT_PASSWORD must be provided when bootstrapping production admin user");
        }
        return initPassword.isBlank() ? "wolf123" : initPassword;
    }

    private void saveUser(String openid, String nickname, String avatar, LocalDateTime createTime) {
        UserEntity user = new UserEntity();
        user.setOpenid(openid);
        user.setNickname(nickname);
        user.setAvatar(avatar);
        user.setStatus(1);
        user.setCreateTime(createTime);
        userMapper.insert(user);
    }

    private void saveRole(int id, String name, String alias, String faction, String roleType, String skill, String background, List<FaqItem> faqs, String portrait, String fullIllustration) {
        RoleEntity role = new RoleEntity();
        role.setId(id);
        role.setName(name);
        role.setAlias(alias);
        role.setFaction(faction);
        role.setRoleType(roleType);
        role.setCamp(faction + "·" + roleType);
        role.setSkill(skill);
        role.setBackground(background);
        role.setFaqs(converter.writeFaqList(faqs));
        role.setPortrait(portrait);
        role.setFullIllustration(fullIllustration);
        role.setCreateTime(LocalDateTime.now().minusDays(14));
        roleMapper.insert(role);
    }

    private void saveBoard(int id, String name, int playerCount, String difficulty, List<String> tags, String coverImage, List<String> specialRules, List<String> tips, List<FaqItem> faqs, String winCondition, String ruleType, List<BoardRoleEntity> roleRefs) {
        BoardEntity board = new BoardEntity();
        board.setId(id);
        board.setName(name);
        board.setPlayerCount(playerCount);
        board.setDifficulty(difficulty);
        board.setTags(converter.writeStringList(tags));
        board.setCoverImage(coverImage);
        board.setCardDescription(buildSeedCardDescription(tips, specialRules, winCondition));
        board.setBriefConfig(buildBriefConfig(roleRefs));
        board.setSpecialRules(converter.writeStringList(specialRules));
        board.setTips(converter.writeStringList(tips));
        board.setFaqs(converter.writeFaqList(faqs));
        board.setWinCondition(winCondition);
        board.setRuleType(ruleType);
        board.setStatus(1);
        board.setCreateTime(LocalDateTime.now().minusDays(10));
        board.setUpdateTime(LocalDateTime.now().minusDays(1));
        boardMapper.insert(board);

        for (BoardRoleEntity roleRef : roleRefs) {
            roleRef.setBoardId(id);
            boardRoleMapper.insert(roleRef);
        }
    }

    private void savePost(int id, String openid, String content, List<String> images, int likeCount, int commentCount, int status, LocalDateTime createTime, LocalDateTime updateTime) {
        PostEntity post = new PostEntity();
        post.setId(id);
        post.setOpenid(openid);
        post.setContent(content);
        post.setImages(converter.writeStringList(images));
        post.setLikeCount(likeCount);
        post.setCommentCount(commentCount);
        post.setStatus(status);
        post.setCreateTime(createTime);
        post.setUpdateTime(updateTime);
        postMapper.insert(post);
    }

    private void saveComment(int id, int postId, String openid, String content, int likeCount, Object status, LocalDateTime createTime) {
        CommentEntity comment = new CommentEntity();
        comment.setId(id);
        comment.setPostId(postId);
        comment.setOpenid(openid);
        comment.setContent(content);
        comment.setLikeCount(likeCount);
        comment.setStatus(status == null ? null : String.valueOf(status));
        comment.setCreateTime(createTime);
        commentMapper.insert(comment);
    }

    private void saveLike(String targetType, int targetId, String openid, LocalDateTime createTime) {
        LikeEntity like = new LikeEntity();
        like.setTargetType(targetType);
        like.setTargetId(targetId);
        like.setOpenid(openid);
        like.setCreateTime(createTime);
        likeMapper.insert(like);
    }

    private List<FaqItem> faq(String question, String answer) {
        return List.of(new FaqItem(question, answer));
    }

    private BoardRoleEntity roleRef(int roleId, int count) {
        BoardRoleEntity entity = new BoardRoleEntity();
        entity.setRoleId(roleId);
        entity.setCount(count);
        return entity;
    }

    private String buildBriefConfig(List<BoardRoleEntity> roleRefs) {
        return roleRefs.stream()
                .map(item -> {
                    RoleEntity role = roleMapper.selectById(item.getRoleId());
                    return role.getName() + "x" + item.getCount();
                })
                .reduce((left, right) -> left + " " + right)
                .orElse("");
    }

    private String buildSeedCardDescription(List<String> tips, List<String> specialRules, String winCondition) {
        return tips.stream()
                .filter(item -> item != null && !item.isBlank())
                .findFirst()
                .or(() -> specialRules.stream().filter(item -> item != null && !item.isBlank()).findFirst())
                .orElse(winCondition);
    }
}
