package com.wolfbook.backend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 用种子板子 3（守卫九人局：3 狼 + 预女守 + 3 平民）跑完整法官流程。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JudgeFlowIntegrationTests {

    private static final int BOARD_ID = 3;
    private static final int PLAYER_COUNT = 9;
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void playerSnapshotHidesOtherRolesUserIdsAndJudgeOnlyEvents() throws Exception {
        Game game = startedGame();
        game.submitNightAction(game.players.get(0), 2);

        JsonNode view = game.snapshot(game.players.get(1));
        for (JsonNode player : view.path("players")) {
            Assertions.assertFalse(player.has("userId"), "openid 不应下发给其他玩家");
            if (player.path("seatNo").asInt() != 2) {
                Assertions.assertTrue(player.path("roleName").isNull(), "其他玩家的身份应隐藏");
            } else {
                Assertions.assertTrue(player.path("self").asBoolean(), "自己的座位应标记为 self");
            }
        }
        for (JsonNode event : view.path("timeline")) {
            Assertions.assertNotEquals("night_action_submit", event.path("actionType").asText());
        }
        Assertions.assertEquals(0, view.path("nightActions").size());

        JsonNode judgeView = game.snapshot(game.host);
        Assertions.assertEquals(1, judgeView.path("nightActions").size());
        Assertions.assertFalse(judgeView.path("players").get(0).has("userId"));
    }

    @Test
    void onlyJudgeCanStartAndAdvance() throws Exception {
        Game game = lobbyGame();
        game.readyAll();
        Assertions.assertEquals(4003, game.call(game.players.get(0), "/start", null).path("code").asInt());
        game.start();
        Assertions.assertEquals(4003, game.advance(game.players.get(0), "{\"action\":\"finish\",\"winnerCamp\":\"狼人\"}").path("code").asInt());
    }

    @Test
    void startingTwiceIsRejected() throws Exception {
        Game game = startedGame();
        Assertions.assertEquals(4002, game.call(game.host, "/start", null).path("code").asInt());
    }

    @Test
    void nightActionTargetMustBeAnAliveSeat() throws Exception {
        Game game = startedGame();
        Assertions.assertEquals(4000, game.call(game.players.get(0), "/night-action", "{\"actionType\":\"查验\",\"targetSeatNo\":99}").path("code").asInt());
        Assertions.assertEquals(4000, game.call(game.players.get(0), "/night-action", "{\"actionType\":\"查验\",\"targetSeatNo\":-1}").path("code").asInt());
        Assertions.assertEquals(0, game.call(game.players.get(0), "/night-action", "{\"actionType\":\"查验\",\"targetSeatNo\":3}").path("code").asInt());
    }

    @Test
    void resolveNightRejectsUnknownOrDeadSeats() throws Exception {
        Game game = startedGame();
        Assertions.assertEquals(4000, game.advance(game.host, "{\"action\":\"resolve_night\",\"deadSeatNos\":[99]}").path("code").asInt());

        JsonNode resolved = game.advance(game.host, "{\"action\":\"resolve_night\",\"deadSeatNos\":[3]}");
        Assertions.assertEquals(0, resolved.path("code").asInt());
        Assertions.assertEquals("day_speech", resolved.path("data").path("currentPhase").asText());

        Assertions.assertEquals(0, game.advance(game.host, "{\"action\":\"start_vote\"}").path("code").asInt());
        Assertions.assertEquals(4000, game.advance(game.host, "{\"action\":\"resolve_vote\",\"eliminatedSeatNo\":3}").path("code").asInt(),
                "已出局的座位不能再次被放逐");
    }

    @Test
    void deadPlayerCannotVoteAndVoteTargetMustBeAlive() throws Exception {
        Game game = startedGame();
        game.advance(game.host, "{\"action\":\"resolve_night\",\"deadSeatNos\":[3]}");
        game.advance(game.host, "{\"action\":\"start_vote\"}");

        Assertions.assertEquals(4002, game.call(game.players.get(2), "/vote", "{\"targetSeatNo\":1}").path("code").asInt());
        Assertions.assertEquals(4002, game.call(game.players.get(0), "/vote", "{\"targetSeatNo\":3}").path("code").asInt());
        Assertions.assertEquals(0, game.call(game.players.get(0), "/vote", "{\"targetSeatNo\":2}").path("code").asInt());
    }

    @Test
    void finishRequiresAPlayingRoomAndCannotRunTwice() throws Exception {
        Game lobby = lobbyGame();
        Assertions.assertEquals(4002, lobby.advance(lobby.host, "{\"action\":\"finish\",\"winnerCamp\":\"好人\"}").path("code").asInt());

        Game game = startedGame();
        JsonNode finished = game.advance(game.host, "{\"action\":\"finish\",\"winnerCamp\":\"狼人\"}");
        Assertions.assertEquals(0, finished.path("code").asInt());
        Assertions.assertEquals("finished", finished.path("data").path("roomStatus").asText());
        Assertions.assertEquals(4002, game.advance(game.host, "{\"action\":\"finish\",\"winnerCamp\":\"好人\"}").path("code").asInt());
        Assertions.assertEquals("狼人", game.snapshot(game.host).path("winnerCamp").asText(), "结果不应被第二次 finish 覆盖");
    }

    @Test
    void automaticWinnerIsOnlyASuggestionUntilJudgeConfirms() throws Exception {
        Game game = startedGame();
        JsonNode judgeView = game.snapshot(game.host);
        List<Integer> goodSeats = new ArrayList<>();
        for (JsonNode player : judgeView.path("players")) {
            if (player.path("playing").asBoolean() && "好人".equals(player.path("faction").asText())) {
                goodSeats.add(player.path("seatNo").asInt());
            }
        }
        Assertions.assertEquals(6, goodSeats.size());

        // 杀掉 3 个好人后 3 狼 vs 3 好人，按人数规则会判狼人胜，但板子是屠边，不应自动结束
        String body = "{\"action\":\"resolve_night\",\"deadSeatNos\":[" + goodSeats.get(0) + "," + goodSeats.get(1) + "," + goodSeats.get(2) + "]}";
        JsonNode resolved = game.advance(game.host, body);
        Assertions.assertEquals(0, resolved.path("code").asInt());
        Assertions.assertEquals("playing", resolved.path("data").path("roomStatus").asText());
        Assertions.assertEquals("", resolved.path("data").path("winnerCamp").asText());
        Assertions.assertTrue(resolved.path("data").path("winnerSuggestion").asText().contains("狼人"));
    }

    @Test
    void lobbyDoesNotShowAWinnerSuggestion() throws Exception {
        Game game = lobbyGame();
        Assertions.assertEquals("", game.snapshot(game.host).path("winnerSuggestion").asText());
    }

    @Test
    void finishedRoomRevealsAllRolesToPlayers() throws Exception {
        Game game = startedGame();
        game.advance(game.host, "{\"action\":\"finish\",\"winnerCamp\":\"狼人\"}");
        JsonNode view = game.snapshot(game.players.get(0));
        for (JsonNode player : view.path("players")) {
            if (player.path("playing").asBoolean()) {
                Assertions.assertFalse(player.path("roleName").isNull());
            }
        }
    }

    @Test
    void judgeCanRecordANightActionForASeatAndSeesTheHistory() throws Exception {
        Game game = startedGame();
        JsonNode recorded = game.call(game.host, "/night-action", "{\"actionType\":\"刀人\",\"targetSeatNo\":2,\"actorSeatNo\":1}");
        Assertions.assertEquals(0, recorded.path("code").asInt(), recorded.toString());
        JsonNode action = recorded.path("data").path("nightActions").path(0);
        Assertions.assertEquals(1, action.path("actorSeatNo").asInt());
        Assertions.assertEquals(2, action.path("targetSeatNo").asInt());
        Assertions.assertEquals(1, action.path("dayNo").asInt());
        Assertions.assertEquals(1, recorded.path("data").path("nightHistory").size());

        // 玩家不能替别的座位记录，也看不到夜间历史
        Assertions.assertEquals(4003, game.call(game.players.get(1), "/night-action", "{\"actionType\":\"查验\",\"targetSeatNo\":3,\"actorSeatNo\":1}").path("code").asInt());
        Assertions.assertEquals(0, game.snapshot(game.players.get(1)).path("nightHistory").size());
        // 替不存在的座位记录同样被拒
        Assertions.assertEquals(4000, game.call(game.host, "/night-action", "{\"actionType\":\"查验\",\"targetSeatNo\":3,\"actorSeatNo\":99}").path("code").asInt());
    }

    @Test
    void shortRoomCodeResolvesToTheRoomForViewingAndJoining() throws Exception {
        int run = SEQUENCE.incrementAndGet();
        String host = login("judge-code-host-" + run);
        String created = mockMvc.perform(post("/api/judge/rooms")
                        .header("Authorization", "Bearer " + host)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"boardId\":" + BOARD_ID + ",\"judgeMode\":\"observer\"}"))
                .andReturn().getResponse().getContentAsString();
        JsonNode data = objectMapper.readTree(created).path("data");
        String roomId = data.path("roomId").asText();
        String roomCode = data.path("roomCode").asText();
        Assertions.assertTrue(roomCode.matches("\\d{4}"), roomCode);

        // 玩家手输 4 位房号：查房和入座都按短房号找到同一间
        String player = login("judge-code-player-" + run);
        JsonNode viewed = objectMapper.readTree(mockMvc.perform(get("/api/judge/rooms/{id}", roomCode)
                        .header("Authorization", "Bearer " + player))
                .andReturn().getResponse().getContentAsString());
        Assertions.assertEquals(0, viewed.path("code").asInt(), viewed.toString());
        Assertions.assertEquals(roomId, viewed.path("data").path("roomId").asText());
        JsonNode joined = objectMapper.readTree(mockMvc.perform(post("/api/judge/rooms/{id}/join", roomCode)
                        .header("Authorization", "Bearer " + player))
                .andReturn().getResponse().getContentAsString());
        Assertions.assertEquals(0, joined.path("code").asInt(), joined.toString());
        Assertions.assertEquals(1, joined.path("data").path("joinedCount").asInt());

        JsonNode missing = objectMapper.readTree(mockMvc.perform(get("/api/judge/rooms/{id}", "000000")
                        .header("Authorization", "Bearer " + player))
                .andReturn().getResponse().getContentAsString());
        Assertions.assertEquals(4004, missing.path("code").asInt());
    }

    // ---- helpers ----

    private Game lobbyGame() throws Exception {
        int run = SEQUENCE.incrementAndGet();
        String host = login("judge-host-" + run);
        String created = mockMvc.perform(post("/api/judge/rooms")
                        .header("Authorization", "Bearer " + host)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"boardId\":" + BOARD_ID + ",\"judgeMode\":\"observer\"}"))
                .andReturn().getResponse().getContentAsString();
        JsonNode createdRoot = objectMapper.readTree(created);
        Assertions.assertEquals(0, createdRoot.path("code").asInt(), created);
        Game game = new Game(host, createdRoot.path("data").path("roomId").asText());
        for (int i = 1; i <= PLAYER_COUNT; i++) {
            String token = login("judge-player-" + run + "-" + i);
            Assertions.assertEquals(0, game.call(token, "/join", null).path("code").asInt());
            game.players.add(token);
        }
        return game;
    }

    private Game startedGame() throws Exception {
        Game game = lobbyGame();
        game.readyAll();
        game.start();
        return game;
    }

    private String login(String code) throws Exception {
        String body = mockMvc.perform(post("/api/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\"}"))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data").path("token").asText();
    }

    private class Game {
        final String host;
        final String roomId;
        final List<String> players = new ArrayList<>();

        Game(String host, String roomId) {
            this.host = host;
            this.roomId = roomId;
        }

        void readyAll() throws Exception {
            for (String token : players) {
                Assertions.assertEquals(0, call(token, "/ready", null).path("code").asInt());
            }
        }

        void start() throws Exception {
            JsonNode response = call(host, "/start", null);
            Assertions.assertEquals(0, response.path("code").asInt(), response.toString());
        }

        void submitNightAction(String token, int targetSeatNo) throws Exception {
            Assertions.assertEquals(0, call(token, "/night-action", "{\"actionType\":\"查验\",\"targetSeatNo\":" + targetSeatNo + "}").path("code").asInt());
        }

        JsonNode advance(String token, String body) throws Exception {
            return call(token, "/advance", body);
        }

        JsonNode snapshot(String token) throws Exception {
            String body = mockMvc.perform(get("/api/judge/rooms/" + roomId).header("Authorization", "Bearer " + token))
                    .andReturn().getResponse().getContentAsString();
            JsonNode root = objectMapper.readTree(body);
            Assertions.assertEquals(0, root.path("code").asInt(), body);
            return root.path("data");
        }

        JsonNode call(String token, String path, String body) throws Exception {
            var request = post("/api/judge/rooms/" + roomId + path)
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON);
            if (body != null) {
                request = request.content(body);
            }
            String response = mockMvc.perform(request).andReturn().getResponse().getContentAsString();
            return objectMapper.readTree(response);
        }
    }
}
