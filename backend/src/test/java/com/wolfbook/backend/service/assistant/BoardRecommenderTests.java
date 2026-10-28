package com.wolfbook.backend.service.assistant;

import com.wolfbook.backend.domain.Board;
import com.wolfbook.backend.domain.BoardRoleRef;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoardRecommenderTests {

    private static final Map<Integer, String> ROLES = Map.of(1, "预言家", 2, "女巫", 3, "守卫", 4, "丘比特", 5, "狼美人", 6, "骑士");

    private static Board board(int id, String name, int players, String difficulty, List<String> tags, int status, Integer... roleIds) {
        List<BoardRoleRef> roles = java.util.Arrays.stream(roleIds).map(roleId -> new BoardRoleRef(roleId, 1)).toList();
        return new Board(id, name, players, difficulty, tags, null, null, null, null, List.of(), List.of(), List.of(),
                null, null, null, status, roles);
    }

    private static final List<Board> BOARDS = List.of(
            board(1, "狼美人骑士", 12, "进阶", List.of("经典", "高配"), 1, 1, 2, 5, 6),
            board(2, "预女猎白", 12, "入门", List.of("经典", "教学"), 1, 1, 2),
            board(3, "守卫九人局", 9, "入门", List.of("娱乐", "快节奏"), 1, 1, 2, 3),
            board(7, "爱神混情局", 12, "进阶", List.of("娱乐", "第三方"), 1, 1, 2, 4),
            board(8, "下架的板子", 9, "入门", List.of("娱乐"), 0, 1)
    );

    private static String top(String query) {
        return BoardRecommender.recommend(query, BOARDS, ROLES).getFirst().board().name();
    }

    @Test
    void chineseNumbersAndDifficultyWordsShouldBeUnderstood() {
        assertEquals(9, BoardRecommender.parsePlayerCount("九个人新手玩什么"));
        assertEquals(12, BoardRecommender.parsePlayerCount("十二人局"));
        assertEquals(20, BoardRecommender.parseChineseNumber("二十"));
        assertNull(BoardRecommender.parsePlayerCount("推荐个板子"));
        assertEquals("入门", BoardRecommender.parseDifficulty("新手局"));
        assertEquals("烧脑", BoardRecommender.parseDifficulty("来个硬核的"));
    }

    @Test
    void shouldPickTheBoardThatMatchesTheQuestion() {
        assertEquals("守卫九人局", top("9个人新手玩什么好？"));
        assertEquals("爱神混情局", top("想玩有第三方阵营的板子"));
        assertEquals("爱神混情局", top("有丘比特的板子"));
        assertEquals("狼美人骑士", top("12人进阶推荐什么板子"));
    }

    @Test
    void onlyMatchingBoardsShouldBeReturnedWhenSomeMatch() {
        var candidates = BoardRecommender.recommend("想玩有第三方阵营的板子", BOARDS, ROLES);
        assertEquals(1, candidates.size());
        assertEquals("爱神混情局", candidates.getFirst().board().name());
        assertEquals(3, BoardRecommender.recommend("随便来一个", BOARDS, ROLES).size(), "什么条件都没有时给热门的三个");
    }

    @Test
    void offlineBoardsShouldNeverBeRecommended() {
        assertTrue(BoardRecommender.recommend("9人娱乐局", BOARDS, ROLES).stream()
                .noneMatch(candidate -> candidate.board().name().equals("下架的板子")));
    }

    @Test
    void reasonShouldSayWhatMatched() {
        String reason = BoardRecommender.recommend("9个人新手", BOARDS, ROLES).getFirst().reason();
        assertTrue(reason.contains("9 人局"), reason);
        assertTrue(reason.contains("难度入门"), reason);
    }
}
