package com.wolfbook.backend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
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

    @Autowired
    private JdbcTemplate jdbcTemplate;

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
    void deathsThatDecideNothingKeepTheGameGoing() throws Exception {
        Game game = startedGame();
        Map<String, List<Integer>> seats = game.seatsByRole();
        // 杀掉 3 个平民后 3 狼对 3 神：旧版按人数会判狼胜，屠边规则下还没结束
        List<Integer> villagers = seats.get("平民");
        String body = "{\"action\":\"resolve_night\",\"deadSeatNos\":[" + villagers.get(0) + "," + villagers.get(1) + "]}";
        JsonNode resolved = game.advance(game.host, body);
        Assertions.assertEquals(0, resolved.path("code").asInt());
        Assertions.assertEquals("playing", resolved.path("data").path("roomStatus").asText());
        Assertions.assertEquals("", resolved.path("data").path("winnerCamp").asText());
        Assertions.assertEquals("", resolved.path("data").path("winnerSuggestion").asText());
    }

    @Test
    void engineResolvesTheNightWhenJudgeOmitsDeadSeats() throws Exception {
        Game game = startedGame();
        Map<String, List<Integer>> seats = game.seatsByRole();
        int target = seats.get("平民").get(0);
        int poisoned = seats.get("平民").get(1);
        game.record(seats.get("狼人").get(0), "kill", target);
        game.record(seats.get("女巫").get(0), "poison", poisoned);

        JsonNode resolved = game.advance(game.host, "{\"action\":\"resolve_night\"}");
        Assertions.assertEquals(0, resolved.path("code").asInt(), resolved.toString());
        Assertions.assertEquals("day_speech", resolved.path("data").path("currentPhase").asText());
        Assertions.assertFalse(game.player(resolved.path("data"), target).path("alive").asBoolean(), "刀口没人救");
        Assertions.assertFalse(game.player(resolved.path("data"), poisoned).path("alive").asBoolean(), "毒药");
        Assertions.assertTrue(resolved.path("data").path("latestAnnouncement").asText().contains(Math.min(target, poisoned) + "号"));
    }

    @Test
    void judgeOverrideStillWins() throws Exception {
        Game game = startedGame();
        Map<String, List<Integer>> seats = game.seatsByRole();
        int knifed = seats.get("平民").get(0);
        int overridden = seats.get("平民").get(1);
        game.record(seats.get("狼人").get(0), "kill", knifed);

        JsonNode resolved = game.advance(game.host, "{\"action\":\"resolve_night\",\"deadSeatNos\":[" + overridden + "]}");
        Assertions.assertEquals(0, resolved.path("code").asInt());
        Assertions.assertTrue(game.player(resolved.path("data"), knifed).path("alive").asBoolean());
        Assertions.assertFalse(game.player(resolved.path("data"), overridden).path("alive").asBoolean());
        boolean overrideLogged = false;
        for (JsonNode event : resolved.path("data").path("timeline")) {
            overrideLogged |= "night_override".equals(event.path("actionType").asText());
        }
        Assertions.assertTrue(overrideLogged, "法官改判要留记录");
        for (JsonNode event : game.snapshot(game.players.get(0)).path("timeline")) {
            Assertions.assertNotEquals("night_override", event.path("actionType").asText(), "改判记录只给法官看");
        }
    }

    @Test
    void guardAndSaveTogetherKillInIntegration() throws Exception {
        Game game = startedGame();
        Map<String, List<Integer>> seats = game.seatsByRole();
        int target = seats.get("平民").get(0);
        game.record(seats.get("守卫").get(0), "guard", target);
        game.record(seats.get("狼人").get(0), "kill", target);
        game.record(seats.get("女巫").get(0), "save", target);

        JsonNode resolved = game.advance(game.host, "{\"action\":\"resolve_night\"}");
        Assertions.assertFalse(game.player(resolved.path("data"), target).path("alive").asBoolean(), "同守同救照样死");
    }

    @Test
    void witchSelfSaveIsRejectedByRule() throws Exception {
        Game game = startedGame();
        Map<String, List<Integer>> seats = game.seatsByRole();
        // 第一夜随便死一个平民，白天没人投票，进入第二夜
        game.advance(game.host, "{\"action\":\"resolve_night\",\"deadSeatNos\":[" + seats.get("平民").get(0) + "]}");
        game.advance(game.host, "{\"action\":\"start_vote\"}");
        JsonNode night2 = game.advance(game.host, "{\"action\":\"resolve_vote\"}");
        Assertions.assertEquals(2, night2.path("data").path("currentDay").asInt());

        int witch = seats.get("女巫").get(0);
        game.record(seats.get("狼人").get(0), "kill", witch);
        JsonNode selfSave = game.call(game.host, "/night-action", "{\"actionType\":\"save\",\"targetSeatNo\":" + witch + ",\"actorSeatNo\":" + witch + "}");
        Assertions.assertEquals(4000, selfSave.path("code").asInt(), selfSave.toString());
    }

    @Test
    void sideRuleFinishesTheGameAutomatically() throws Exception {
        Game game = startedGame();
        Map<String, List<Integer>> seats = game.seatsByRole();
        String body = "{\"action\":\"resolve_night\",\"deadSeatNos\":[" + seats.get("预言家").get(0) + ","
                + seats.get("女巫").get(0) + "," + seats.get("守卫").get(0) + "]}";
        JsonNode resolved = game.advance(game.host, body);
        Assertions.assertEquals(0, resolved.path("code").asInt());
        JsonNode data = resolved.path("data");
        Assertions.assertEquals("finished", data.path("roomStatus").asText());
        Assertions.assertEquals("狼人", data.path("winnerCamp").asText());
        Assertions.assertTrue(data.path("winVerdict").path("reason").asText().contains("神职"));
    }

    @Test
    void finishWithoutAWinnerIsADraw() throws Exception {
        Game game = startedGame();
        JsonNode finished = game.advance(game.host, "{\"action\":\"finish\"}");
        Assertions.assertEquals("流局", finished.path("data").path("winnerCamp").asText());
    }

    @Test
    void advancingTheSameStepTwiceIsRejected() throws Exception {
        Game game = startedGame();
        JsonNode first = game.advance(game.host, "{\"action\":\"next_step\",\"stepIndex\":0}");
        Assertions.assertEquals(0, first.path("code").asInt(), first.toString());
        Assertions.assertEquals(1, first.path("data").path("currentStep").path("index").asInt());
        Assertions.assertEquals(4002, game.advance(game.host, "{\"action\":\"next_step\",\"stepIndex\":0}").path("code").asInt());
    }

    @Test
    void lastStepResolvesTheNight() throws Exception {
        Game game = startedGame();
        Map<String, List<Integer>> seats = game.seatsByRole();
        int target = seats.get("平民").get(0);
        game.record(seats.get("狼人").get(0), "kill", target);
        int total = game.snapshot(game.host).path("currentStep").path("total").asInt();
        JsonNode last = null;
        for (int step = 0; step < total; step++) {
            last = game.advance(game.host, "{\"action\":\"next_step\",\"stepIndex\":" + step + "}");
            Assertions.assertEquals(0, last.path("code").asInt(), last.toString());
        }
        Assertions.assertEquals("day_speech", last.path("data").path("currentPhase").asText());
        Assertions.assertFalse(game.player(last.path("data"), target).path("alive").asBoolean());
    }

    @Test
    void snapshotShowsTheStepLineOnlyToItsHolder() throws Exception {
        Game game = startedGame();
        JsonNode judgeView = game.snapshot(game.host);
        Assertions.assertTrue(judgeView.path("host").asBoolean());
        Assertions.assertEquals("guard", judgeView.path("currentStep").path("kind").asText());
        Assertions.assertTrue(judgeView.path("currentStep").path("line").asText().startsWith("守卫请睁眼"));

        Map<String, List<Integer>> seats = game.seatsByRole();
        JsonNode guardView = game.snapshot(game.players.get(seats.get("守卫").get(0) - 1));
        Assertions.assertFalse(guardView.path("host").asBoolean());
        Assertions.assertTrue(guardView.path("currentStep").path("line").asText().startsWith("守卫请睁眼"));
        JsonNode otherView = game.snapshot(game.players.get(seats.get("平民").get(0) - 1));
        Assertions.assertEquals("", otherView.path("currentStep").path("line").asText());
        Assertions.assertEquals(0, otherView.path("currentStep").path("index").asInt());
    }

    @Test
    void legacyChineseActionTypesStillWork() throws Exception {
        Game game = startedGame();
        Map<String, List<Integer>> seats = game.seatsByRole();
        JsonNode recorded = game.call(game.host, "/night-action",
                "{\"actionType\":\"查验\",\"targetSeatNo\":" + seats.get("狼人").get(0) + ",\"actorSeatNo\":" + seats.get("预言家").get(0) + "}");
        Assertions.assertEquals(0, recorded.path("code").asInt(), recorded.toString());
        Assertions.assertEquals("查验", recorded.path("data").path("nightActions").path(0).path("actionType").asText(),
                "旧版小程序按中文动作名识别刀口和用药");
    }

    @Test
    void sheriffVoteCountsOnePointFive() throws Exception {
        Game game = startedGame();
        Map<String, List<Integer>> seats = game.seatsByRole();
        int sheriff = seats.get("预言家").get(0);
        jdbcTemplate.update("UPDATE judge_room_players SET is_sheriff = 1 WHERE room_id = ? AND seat_no = ?", game.roomId, sheriff);
        game.advance(game.host, "{\"action\":\"resolve_night\",\"deadSeatNos\":[]}");
        game.advance(game.host, "{\"action\":\"start_vote\"}");

        List<Integer> villagers = seats.get("平民");
        int a = villagers.get(0);
        int b = villagers.get(1);
        // 两张普通票投 a；警长 + 一张普通票投 b（2.5 票）
        game.vote(seats.get("狼人").get(0), a);
        game.vote(seats.get("狼人").get(1), a);
        game.vote(sheriff, b);
        game.vote(seats.get("女巫").get(0), b);

        JsonNode resolved = game.advance(game.host, "{\"action\":\"resolve_vote\"}");
        Assertions.assertEquals(0, resolved.path("code").asInt(), resolved.toString());
        Assertions.assertFalse(game.player(resolved.path("data"), b).path("alive").asBoolean());
        Assertions.assertTrue(game.player(resolved.path("data"), a).path("alive").asBoolean());
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

    // ---- 新阶段机（autoJudge）----

    @Test
    void autoJudgeRoomGoesToDayAnnounceThenSpeech() throws Exception {
        Game game = autoGame(false);
        Map<String, List<Integer>> seats = game.seatsByRole();
        int dead = seats.get("平民").get(0);
        JsonNode announced = game.advance(game.host, "{\"action\":\"resolve_night\",\"deadSeatNos\":[" + dead + "]}");
        Assertions.assertEquals("skill_pending", announced.path("data").path("currentPhase").asText(), "公布死讯前先问出局者的技能");
        Assertions.assertTrue(announced.path("data").path("latestAnnouncement").asText().contains(dead + "号"));
        Assertions.assertEquals("day_announce", game.skill(dead, "{\"decision\":\"skip\"}").path("currentPhase").asText());

        JsonNode speech = game.advance(game.host, "{\"action\":\"start_speech\"}");
        Assertions.assertEquals("day_speech", speech.path("data").path("currentPhase").asText(), speech.toString());
        JsonNode order = speech.path("data").path("speech").path("order");
        Assertions.assertEquals(8, order.size(), "出局的人不发言");
        Assertions.assertEquals(dead % PLAYER_COUNT + 1, order.get(0).asInt(), "有死者时从死者左边开始");
        Assertions.assertEquals(0, speech.path("data").path("speech").path("index").asInt());
    }

    @Test
    void sheriffElectionEndsWithOneAndAHalfVotes() throws Exception {
        Game game = autoGame(true);
        int dead = game.seatsByRole().get("平民").get(0);
        JsonNode signup = game.advance(game.host, "{\"action\":\"resolve_night\",\"deadSeatNos\":[" + dead + "]}");
        Assertions.assertEquals("sheriff_signup", signup.path("data").path("currentPhase").asText(), signup.toString());
        Assertions.assertTrue(game.player(signup.path("data"), dead).path("alive").asBoolean(), "上警时还没公布昨夜死讯");

        int a = dead == 1 ? 2 : 1;
        int b = dead == 3 ? 4 : 3;
        game.sheriff(a, "{\"candidate\":true}");
        game.sheriff(b, "{\"candidate\":true}");
        Assertions.assertEquals("sheriff_speech", game.advance(game.host, "{\"action\":\"close_sheriff_signup\"}").path("data").path("currentPhase").asText());
        Assertions.assertEquals("sheriff_vote", game.advance(game.host, "{\"action\":\"start_sheriff_vote\"}").path("data").path("currentPhase").asText());

        List<Integer> voters = new ArrayList<>();
        for (int seat = 1; seat <= PLAYER_COUNT; seat++) {
            if (seat != a && seat != b) {
                voters.add(seat);
            }
        }
        game.sheriff(voters.get(0), "{\"targetSeatNo\":" + a + "}");
        game.sheriff(voters.get(1), "{\"targetSeatNo\":" + a + "}");
        game.sheriff(voters.get(2), "{\"targetSeatNo\":" + b + "}");
        JsonNode elected = game.advance(game.host, "{\"action\":\"resolve_sheriff_vote\"}");
        Assertions.assertEquals(0, elected.path("code").asInt(), elected.toString());
        Assertions.assertEquals("skill_pending", elected.path("data").path("currentPhase").asText(), "公布死讯后先问出局者的技能");
        Assertions.assertEquals(a, elected.path("data").path("sheriffSeatNo").asInt());
        Assertions.assertFalse(game.player(elected.path("data"), dead).path("alive").asBoolean(), "警长选完才公布死讯");
        Integer flag = jdbcTemplate.queryForObject("SELECT is_sheriff FROM judge_room_players WHERE room_id = ? AND seat_no = ?",
                Integer.class, game.roomId, a);
        Assertions.assertEquals(1, flag);
    }

    @Test
    void nobodyOrEveryoneOnStageLosesTheBadge() throws Exception {
        Game nobody = autoGame(true);
        nobody.advance(nobody.host, "{\"action\":\"resolve_night\",\"deadSeatNos\":[]}");
        JsonNode lost = nobody.advance(nobody.host, "{\"action\":\"close_sheriff_signup\"}");
        Assertions.assertEquals("day_announce", lost.path("data").path("currentPhase").asText(), lost.toString());
        Assertions.assertTrue(lost.path("data").path("sheriffSeatNo").isNull());

        Game everyone = autoGame(true);
        everyone.advance(everyone.host, "{\"action\":\"resolve_night\",\"deadSeatNos\":[]}");
        everyone.advance(everyone.host, "{\"action\":\"close_sheriff_signup\",\"sheriffCandidates\":[1,2,3,4,5,6,7,8,9]}");
        JsonNode all = everyone.snapshot(everyone.host);
        Assertions.assertEquals("day_announce", all.path("currentPhase").asText());
        Assertions.assertTrue(all.path("sheriffSeatNo").isNull());
    }

    @Test
    void candidatesCannotVoteForSheriff() throws Exception {
        Game game = autoGame(true);
        game.advance(game.host, "{\"action\":\"resolve_night\",\"deadSeatNos\":[]}");
        game.sheriff(1, "{\"candidate\":true}");
        game.sheriff(2, "{\"candidate\":true}");
        game.advance(game.host, "{\"action\":\"close_sheriff_signup\"}");
        game.advance(game.host, "{\"action\":\"start_sheriff_vote\"}");
        JsonNode response = game.call(game.players.get(0), "/sheriff", "{\"targetSeatNo\":2}");
        Assertions.assertEquals(4003, response.path("code").asInt(), response.toString());
    }

    @Test
    void exileTieGoesToPkAndOnlyOthersVote() throws Exception {
        Game game = autoGame(false);
        game.advance(game.host, "{\"action\":\"resolve_night\",\"deadSeatNos\":[]}");
        game.advance(game.host, "{\"action\":\"start_speech\"}");
        game.advance(game.host, "{\"action\":\"start_vote\"}");
        game.vote(1, 5);
        game.vote(2, 5);
        game.vote(3, 6);
        game.vote(4, 6);
        JsonNode tied = game.advance(game.host, "{\"action\":\"resolve_vote\"}");
        Assertions.assertEquals("vote_result", tied.path("data").path("currentPhase").asText(), tied.toString());
        Assertions.assertEquals("[5,6]", tied.path("data").path("pkSeats").toString());
        JsonNode playerView = game.snapshot(game.players.get(8));
        Assertions.assertTrue(playerView.path("voteTallies").size() > 0, "票型公布对全场可见");

        Assertions.assertEquals("vote_pk", game.advance(game.host, "{\"action\":\"continue\"}").path("data").path("currentPhase").asText());
        Assertions.assertEquals(4003, game.call(game.players.get(4), "/vote", "{\"targetSeatNo\":6}").path("code").asInt(), "平票者不能投");
        Assertions.assertEquals(4000, game.call(game.players.get(0), "/vote", "{\"targetSeatNo\":7}").path("code").asInt(), "只能投平票者");
        game.vote(1, 5);
        game.vote(2, 6);
        JsonNode again = game.advance(game.host, "{\"action\":\"resolve_vote\"}");
        Assertions.assertEquals("vote_result", again.path("data").path("currentPhase").asText());
        Assertions.assertEquals(0, again.path("data").path("pkSeats").size());
        JsonNode night = game.advance(game.host, "{\"action\":\"continue\"}");
        Assertions.assertEquals("night_action", night.path("data").path("currentPhase").asText());
        Assertions.assertEquals(2, night.path("data").path("currentDay").asInt());
        Assertions.assertTrue(game.player(night.path("data"), 5).path("alive").asBoolean());
        Assertions.assertTrue(game.player(night.path("data"), 6).path("alive").asBoolean());
    }

    @Test
    void legacyRoomsNeverSeeTheNewPhases() throws Exception {
        Game game = startedGame();
        JsonNode day = game.advance(game.host, "{\"action\":\"resolve_night\",\"deadSeatNos\":[]}");
        Assertions.assertEquals("day_speech", day.path("data").path("currentPhase").asText());
        game.advance(game.host, "{\"action\":\"start_vote\"}");
        game.vote(1, 5);
        game.vote(2, 6);
        JsonNode night = game.advance(game.host, "{\"action\":\"resolve_vote\"}");
        Assertions.assertEquals("night_action", night.path("data").path("currentPhase").asText(), "旧房间平票直接无人出局");
    }

    @Test
    void roundsRecordDeathsVotesAndSheriffForEveryPlayer() throws Exception {
        Game game = autoGame(true);
        Map<String, List<Integer>> seats = game.seatsByRole();
        int dead = seats.get("平民").get(0);
        // 第 0 步是守卫：记一条守护，最后检查它没出现在公开记录里
        game.record(seats.get("守卫").get(0), "guard", dead);
        game.advance(game.host, "{\"action\":\"resolve_night\",\"deadSeatNos\":[" + dead + "]}");
        int a = dead == 1 ? 2 : 1;
        game.sheriff(a, "{\"candidate\":true}");
        game.advance(game.host, "{\"action\":\"close_sheriff_signup\"}");
        game.skill(dead, "{\"decision\":\"skip\"}");
        game.advance(game.host, "{\"action\":\"start_speech\"}");
        game.advance(game.host, "{\"action\":\"start_vote\"}");
        int target = seats.get("平民").get(1);
        game.vote(a, target);
        int other = a == 2 ? 3 : 2;
        if (other == dead || other == target) {
            other = 9;
        }
        game.vote(other, target);
        game.advance(game.host, "{\"action\":\"resolve_vote\"}");

        JsonNode view = game.snapshot(game.players.get(other - 1));
        JsonNode round = view.path("rounds").get(0);
        Assertions.assertEquals(1, round.path("dayNo").asInt(), view.path("rounds").toString());
        Assertions.assertEquals("[" + dead + "]", round.path("nightDeaths").toString());
        Assertions.assertEquals(a, round.path("sheriff").path("electedSeatNo").asInt());
        JsonNode tally = round.path("exile").get(0).path("tallies").get(0);
        Assertions.assertEquals(target, tally.path("targetSeatNo").asInt());
        Assertions.assertEquals(2.5, tally.path("weight").asDouble());
        Assertions.assertEquals(2, tally.path("voterSeatNos").size());
        Assertions.assertFalse(round.toString().contains("guard"), "对局中记录里没有夜间行动");
    }

    // ---- 出局待办（autoJudge）----

    @Test
    void everyOutPlayerGetsTheSameSkillPrompt() throws Exception {
        Game game = autoGame(false);
        Map<String, List<Integer>> seats = game.seatsByRole();
        int dead = seats.get("平民").get(0);
        JsonNode day = game.advance(game.host, "{\"action\":\"resolve_night\",\"deadSeatNos\":[" + dead + "]}");
        Assertions.assertEquals("skill_pending", day.path("data").path("currentPhase").asText(), day.toString());

        JsonNode own = game.snapshot(game.players.get(dead - 1)).path("pending");
        Assertions.assertEquals("skill_ask", own.path("kind").asText(), own.toString());
        Assertions.assertEquals("[\"skip\"]", own.path("options").toString(), "没技能的人只有「确认」");
        Assertions.assertTrue(game.snapshot(game.players.get(seats.get("平民").get(1) - 1)).path("pending").isNull(), "别人看不到待办细节");

        JsonNode after = game.skill(dead, "{\"decision\":\"skip\"}");
        Assertions.assertEquals("day_announce", after.path("currentPhase").asText());
    }

    @Test
    void hunterShotChainsIntoTheNextVictim() throws Exception {
        Game game = autoGame12(true);
        Map<String, List<Integer>> seats = game.seatsByRole();
        int hunter = seats.get("猎人").get(0);
        int sheriff = seats.get("平民").get(0);
        int heir = seats.get("平民").get(1);
        game.advance(game.host, "{\"action\":\"resolve_night\",\"deadSeatNos\":[" + hunter + "]}");
        game.sheriff(sheriff, "{\"candidate\":true}");
        JsonNode announce = game.advance(game.host, "{\"action\":\"close_sheriff_signup\"}");
        Assertions.assertEquals("skill_pending", announce.path("data").path("currentPhase").asText(), announce.toString());
        Assertions.assertEquals(sheriff, announce.path("data").path("sheriffSeatNo").asInt());

        JsonNode ask = game.snapshot(game.players.get(hunter - 1)).path("pending");
        Assertions.assertEquals("[\"use\",\"skip\"]", ask.path("options").toString());
        game.skill(hunter, "{\"decision\":\"use\"}");
        Assertions.assertEquals("hunter_shot", game.snapshot(game.players.get(hunter - 1)).path("pending").path("kind").asText());
        JsonNode shot = game.skill(hunter, "{\"decision\":\"use\",\"targetSeatNo\":" + sheriff + "}");
        Assertions.assertFalse(game.player(shot, sheriff).path("alive").asBoolean(), "猎人带走了警长");

        Assertions.assertEquals("skill_ask", game.snapshot(game.players.get(sheriff - 1)).path("pending").path("kind").asText());
        game.skill(sheriff, "{\"decision\":\"skip\"}");
        Assertions.assertEquals("badge", game.snapshot(game.players.get(sheriff - 1)).path("pending").path("kind").asText());
        JsonNode passed = game.skill(sheriff, "{\"decision\":\"use\",\"targetSeatNo\":" + heir + "}");
        Assertions.assertEquals(heir, passed.path("sheriffSeatNo").asInt());
        Assertions.assertEquals("day_announce", passed.path("currentPhase").asText());
    }

    @Test
    void poisonedHunterGetsNoShotOption() throws Exception {
        Game game = autoGame12(false);
        Map<String, List<Integer>> seats = game.seatsByRole();
        int hunter = seats.get("猎人").get(0);
        game.advance(game.host, "{\"action\":\"next_step\",\"stepIndex\":0}");
        game.record(seats.get("女巫").get(0), "poison", hunter);
        JsonNode day = game.advance(game.host, "{\"action\":\"resolve_night\"}");
        Assertions.assertFalse(game.player(day.path("data"), hunter).path("alive").asBoolean(), day.toString());
        JsonNode ask = game.snapshot(game.players.get(hunter - 1)).path("pending");
        Assertions.assertEquals("skill_ask", ask.path("kind").asText());
        Assertions.assertEquals("[\"skip\"]", ask.path("options").toString(), "被毒的猎人不能开枪");
        Assertions.assertNotEquals(0, game.call(game.players.get(hunter - 1), "/skill", "{\"decision\":\"use\"}").path("code").asInt());
    }

    @Test
    void revealedIdiotDiesOnSecondExile() throws Exception {
        Game game = autoGame12(false);
        Map<String, List<Integer>> seats = game.seatsByRole();
        int idiot = seats.get("白痴").get(0);
        List<Integer> voters = seats.get("狼人");

        game.advance(game.host, "{\"action\":\"resolve_night\",\"deadSeatNos\":[]}");
        game.advance(game.host, "{\"action\":\"start_speech\"}");
        game.advance(game.host, "{\"action\":\"start_vote\"}");
        game.vote(voters.get(0), idiot);
        game.vote(voters.get(1), idiot);
        JsonNode revealed = game.advance(game.host, "{\"action\":\"resolve_vote\"}");
        Assertions.assertTrue(game.player(revealed.path("data"), idiot).path("alive").asBoolean(), "第一次被放逐翻牌免死");
        Assertions.assertTrue(revealed.path("data").path("latestAnnouncement").asText().contains("白痴"));
        Assertions.assertEquals("vote_result", revealed.path("data").path("currentPhase").asText());

        game.advance(game.host, "{\"action\":\"continue\"}");
        game.advance(game.host, "{\"action\":\"resolve_night\",\"deadSeatNos\":[]}");
        game.advance(game.host, "{\"action\":\"start_speech\"}");
        game.advance(game.host, "{\"action\":\"start_vote\"}");
        Assertions.assertEquals(4003, game.call(game.players.get(idiot - 1), "/vote", "{\"targetSeatNo\":" + voters.get(0) + "}").path("code").asInt(),
                "翻牌的白痴没有投票权");
        game.vote(voters.get(0), idiot);
        JsonNode out = game.advance(game.host, "{\"action\":\"resolve_vote\"}");
        Assertions.assertFalse(game.player(out.path("data"), idiot).path("alive").asBoolean(), "第二次被放逐照样出局");
        Assertions.assertEquals("skill_pending", out.path("data").path("currentPhase").asText());
    }

    @Test
    void pendingIsDroppedOnceTheGameIsDecided() throws Exception {
        Game game = autoGame12(false);
        Map<String, List<Integer>> seats = game.seatsByRole();
        int hunter = seats.get("猎人").get(0);
        int seer = seats.get("预言家").get(0);
        int witch = seats.get("女巫").get(0);
        int idiot = seats.get("白痴").get(0);
        game.advance(game.host, "{\"action\":\"resolve_night\",\"deadSeatNos\":[" + seer + "," + witch + "," + idiot + "]}");
        for (int seat : List.of(seer, witch, idiot).stream().sorted().toList()) {
            game.skill(seat, "{\"decision\":\"skip\"}");
        }
        game.advance(game.host, "{\"action\":\"start_speech\"}");
        game.advance(game.host, "{\"action\":\"start_vote\"}");
        game.vote(seats.get("狼人").get(0), hunter);
        JsonNode finished = game.advance(game.host, "{\"action\":\"resolve_vote\"}");
        Assertions.assertEquals("finished", finished.path("data").path("roomStatus").asText(), finished.toString());
        Assertions.assertEquals("狼人", finished.path("data").path("winnerCamp").asText());
        Assertions.assertTrue(game.snapshot(game.players.get(hunter - 1)).path("pending").isNull(), "已经分出胜负，猎人不再开枪");
    }

    @Test
    void onlyTheJudgeCanForceOut() throws Exception {
        Game game = autoGame(false);
        game.advance(game.host, "{\"action\":\"resolve_night\",\"deadSeatNos\":[]}");
        Assertions.assertEquals(4003, game.advance(game.players.get(0), "{\"action\":\"force_out\",\"forceOutSeatNo\":3}").path("code").asInt());
        JsonNode out = game.advance(game.host, "{\"action\":\"force_out\",\"forceOutSeatNo\":3}");
        Assertions.assertEquals(0, out.path("code").asInt(), out.toString());
        Assertions.assertFalse(game.player(out.path("data"), 3).path("alive").asBoolean());
        Assertions.assertTrue(out.path("data").path("latestAnnouncement").asText().contains("法官判罚"));
        Assertions.assertEquals("skill_pending", out.path("data").path("currentPhase").asText());
    }

    // ---- 惰性推进（autoJudge）----

    @Test
    void pollingAfterDeadlineAdvancesTheStep() throws Exception {
        Game game = autoGame(false);
        Assertions.assertEquals(0, game.snapshot(game.host).path("currentStep").path("index").asInt());
        jdbcTemplate.update("UPDATE judge_rooms SET step_deadline = ? WHERE room_id = ?",
                java.sql.Timestamp.valueOf("2000-01-01 00:00:00"), game.roomId);
        Assertions.assertEquals(1, game.snapshot(game.players.get(0)).path("currentStep").path("index").asInt(), "谁来轮询都会推进");
    }

    @Test
    void dealCopiesTheMechanicToTheSeat() throws Exception {
        Game game = autoGame(false);
        int guard = game.seatsByRole().get("守卫").get(0);
        Assertions.assertEquals("guard", jdbcTemplate.queryForObject(
                "SELECT mechanic FROM judge_room_players WHERE room_id = ? AND seat_no = ?", String.class, game.roomId, guard));
    }

    @Test
    void roomsDealtBeforeMechanicsStillResolve() throws Exception {
        // 升级前开局的房间：座位上没有机制，按角色名兜底照样结算
        Game game = autoGame(false);
        jdbcTemplate.update("UPDATE judge_room_players SET mechanic = NULL WHERE room_id = ?", game.roomId);
        Map<String, List<Integer>> seats = game.seatsByRole();
        int target = seats.get("平民").get(0);
        expire(game);
        for (int wolf : seats.get("狼人")) {
            JsonNode kill = game.call(game.players.get(wolf - 1), "/night-action", "{\"actionType\":\"kill\",\"targetSeatNo\":" + target + "}");
            Assertions.assertEquals(0, kill.path("code").asInt(), kill.toString());
        }
        JsonNode day = game.snapshot(game.host);
        for (int i = 0; i < 10 && "night_action".equals(day.path("currentPhase").asText()); i++) {
            expire(game);
            day = game.snapshot(game.host);
        }
        Assertions.assertNotEquals("night_action", day.path("currentPhase").asText(), day.toString());
        Assertions.assertFalse(game.player(day, target).path("alive").asBoolean(), "被刀的平民天亮出局");
    }

    private void expire(Game game) throws Exception {
        jdbcTemplate.update("UPDATE judge_rooms SET step_deadline = ? WHERE room_id = ?",
                java.sql.Timestamp.valueOf("2000-01-01 00:00:00"), game.roomId);
        game.snapshot(game.host);
    }

    @Test
    void wolvesSubmittingAllAdvancesImmediately() throws Exception {
        Game game = autoGame(false);
        Map<String, List<Integer>> seats = game.seatsByRole();
        game.advance(game.host, "{\"action\":\"next_step\",\"stepIndex\":0}");
        int target = seats.get("平民").get(0);
        List<Integer> wolves = seats.get("狼人");
        for (int i = 0; i < wolves.size(); i++) {
            Assertions.assertEquals(1, game.snapshot(game.host).path("currentStep").path("index").asInt(), "狼没交齐之前不动");
            JsonNode submitted = game.call(game.players.get(wolves.get(i) - 1), "/night-action", "{\"actionType\":\"kill\",\"targetSeatNo\":" + target + "}");
            Assertions.assertEquals(0, submitted.path("code").asInt(), submitted.toString());
        }
        Assertions.assertEquals(2, game.snapshot(game.host).path("currentStep").path("index").asInt(), "狼交齐了马上进下一步");
    }

    @Test
    void singleHolderSubmitDoesNotAdvanceEarly() throws Exception {
        Game game = autoGame(false);
        Map<String, List<Integer>> seats = game.seatsByRole();
        int guard = seats.get("守卫").get(0);
        JsonNode submitted = game.call(game.players.get(guard - 1), "/night-action", "{\"actionType\":\"guard\",\"targetSeatNo\":" + guard + "}");
        Assertions.assertEquals(0, submitted.path("code").asInt(), submitted.toString());
        Assertions.assertEquals(0, game.snapshot(game.host).path("currentStep").path("index").asInt(), "守卫交了也等到点再切");
    }

    @Test
    void lateSubmitAfterTheDeadlineDoesNotRewindTheStep() throws Exception {
        // 守卫那一步已经到点：这次提交先把步骤推到狼队，守卫再交就算晚了，也不能把房间写回守卫那一步
        Game game = autoGame(false);
        int guard = game.seatsByRole().get("守卫").get(0);
        jdbcTemplate.update("UPDATE judge_rooms SET step_deadline = ? WHERE room_id = ?",
                java.sql.Timestamp.valueOf("2000-01-01 00:00:00"), game.roomId);
        JsonNode late = game.call(game.players.get(guard - 1), "/night-action", "{\"actionType\":\"guard\",\"targetSeatNo\":" + guard + "}");
        Assertions.assertEquals(4003, late.path("code").asInt(), late.toString());
        Integer step = jdbcTemplate.queryForObject("SELECT night_step FROM judge_rooms WHERE room_id = ?", Integer.class, game.roomId);
        Assertions.assertEquals(1, step, "步骤停在狼队那一步");
    }

    @Test
    void deadRoleStepLastsAsLongAsALiveOne() throws Exception {
        // 活着的守卫一定等满 30 秒，死了的守卫那一步也得等满，否则从时长听得出他死了
        Game game = autoGame(false);
        int guard = game.seatsByRole().get("守卫").get(0);
        game.advance(game.host, "{\"action\":\"resolve_night\",\"deadSeatNos\":[" + guard + "]}");
        Assertions.assertEquals(0, game.call(game.host, "/skill", "{\"decision\":\"skip\"}").path("code").asInt());
        game.advance(game.host, "{\"action\":\"start_speech\"}");
        game.advance(game.host, "{\"action\":\"start_vote\"}");
        game.advance(game.host, "{\"action\":\"resolve_vote\"}");
        JsonNode night = game.advance(game.host, "{\"action\":\"continue\"}");
        Assertions.assertEquals("night_action", night.path("data").path("currentPhase").asText(), night.toString());
        Assertions.assertEquals("守卫", night.path("data").path("currentStep").path("roleName").asText());
        java.time.LocalDateTime deadline = jdbcTemplate.queryForObject(
                "SELECT step_deadline FROM judge_rooms WHERE room_id = ?", java.time.LocalDateTime.class, game.roomId);
        long seconds = java.time.Duration.between(java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")), deadline).getSeconds();
        Assertions.assertTrue(seconds >= 28, "守卫那一步照样 30 秒，实际 " + seconds);
    }

    @Test
    void timedOutSkillPromptIsSkipped() throws Exception {
        Game game = autoGame(false);
        int dead = game.seatsByRole().get("平民").get(0);
        game.advance(game.host, "{\"action\":\"resolve_night\",\"deadSeatNos\":[" + dead + "]}");
        jdbcTemplate.update("UPDATE judge_rooms SET step_deadline = ? WHERE room_id = ?",
                java.sql.Timestamp.valueOf("2000-01-01 00:00:00"), game.roomId);
        Assertions.assertEquals("day_announce", game.snapshot(game.host).path("currentPhase").asText(), "技能询问超时当作不发动");
    }

    // ---- helpers ----

    /** 种子板子 2「预女猎白」：12 人，4 狼、预言家、女巫、猎人、白痴、4 平民；夜间顺序 狼人 → 女巫 → 预言家 → 猎人。 */
    private Game autoGame12(boolean sheriff) throws Exception {
        Game game = lobbyGame("{\"boardId\":2,\"judgeMode\":\"observer\",\"autoJudge\":true,\"sheriffEnabled\":" + sheriff + "}", 12);
        game.readyAll();
        game.start();
        return game;
    }

    private Game autoGame(boolean sheriff) throws Exception {
        Game game = lobbyGame("{\"boardId\":" + BOARD_ID + ",\"judgeMode\":\"observer\",\"autoJudge\":true,\"sheriffEnabled\":" + sheriff + "}");
        game.readyAll();
        game.start();
        return game;
    }

    private Game lobbyGame() throws Exception {
        return lobbyGame("{\"boardId\":" + BOARD_ID + ",\"judgeMode\":\"observer\"}");
    }

    private Game lobbyGame(String createBody) throws Exception {
        return lobbyGame(createBody, PLAYER_COUNT);
    }

    private Game lobbyGame(String createBody, int playerCount) throws Exception {
        int run = SEQUENCE.incrementAndGet();
        String host = login("judge-host-" + run);
        String created = mockMvc.perform(post("/api/judge/rooms")
                        .header("Authorization", "Bearer " + host)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andReturn().getResponse().getContentAsString();
        JsonNode createdRoot = objectMapper.readTree(created);
        Assertions.assertEquals(0, createdRoot.path("code").asInt(), created);
        Game game = new Game(host, createdRoot.path("data").path("roomId").asText());
        for (int i = 1; i <= playerCount; i++) {
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

        /** 法官替某个座位记一条夜间行动（必须成功）。 */
        void record(int actorSeat, String action, int targetSeat) throws Exception {
            JsonNode response = call(host, "/night-action",
                    "{\"actionType\":\"" + action + "\",\"targetSeatNo\":" + targetSeat + ",\"actorSeatNo\":" + actorSeat + "}");
            Assertions.assertEquals(0, response.path("code").asInt(), response.toString());
        }

        /** 某个座位的玩家投票（必须成功）。玩家按入座顺序坐，players.get(i) 是 i+1 号。 */
        void vote(int voterSeat, int targetSeat) throws Exception {
            JsonNode response = call(players.get(voterSeat - 1), "/vote", "{\"targetSeatNo\":" + targetSeat + "}");
            Assertions.assertEquals(0, response.path("code").asInt(), response.toString());
        }

        /** 某个座位的玩家处理出局待办（必须成功）。 */
        JsonNode skill(int seat, String body) throws Exception {
            JsonNode response = call(players.get(seat - 1), "/skill", body);
            Assertions.assertEquals(0, response.path("code").asInt(), response.toString());
            return response.path("data");
        }

        /** 某个座位的玩家调上警接口（必须成功）。 */
        void sheriff(int seat, String body) throws Exception {
            JsonNode response = call(players.get(seat - 1), "/sheriff", body);
            Assertions.assertEquals(0, response.path("code").asInt(), response.toString());
        }

        /** 法官视角下每个角色坐在哪些座位（升序）。 */
        Map<String, List<Integer>> seatsByRole() throws Exception {
            Map<String, List<Integer>> seats = new TreeMap<>();
            for (JsonNode player : snapshot(host).path("players")) {
                if (player.path("playing").asBoolean()) {
                    seats.computeIfAbsent(player.path("roleName").asText(), ignored -> new ArrayList<>()).add(player.path("seatNo").asInt());
                }
            }
            seats.values().forEach(list -> list.sort(Integer::compareTo));
            return seats;
        }

        JsonNode player(JsonNode snapshotData, int seatNo) {
            for (JsonNode player : snapshotData.path("players")) {
                if (player.path("seatNo").asInt() == seatNo) {
                    return player;
                }
            }
            throw new AssertionError("座位 " + seatNo + " 不在快照里");
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
