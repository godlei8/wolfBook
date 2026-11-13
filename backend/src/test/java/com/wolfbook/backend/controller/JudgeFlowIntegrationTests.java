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
    private static final int WOLF_KING_BOARD_ID = 8;
    private static final int PLAYER_COUNT = 9;
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;


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
        Game game = autoGame12(false);
        int wolf = game.seatsByRole().get("狼人").get(0);
        Assertions.assertEquals(4000, game.call(game.players.get(wolf - 1), "/night-action", "{\"actionType\":\"kill\",\"targetSeatNo\":99}").path("code").asInt());
        Assertions.assertEquals(4000, game.call(game.players.get(wolf - 1), "/night-action", "{\"actionType\":\"kill\",\"targetSeatNo\":-1}").path("code").asInt());
        int dead = game.seatsByRole().get("平民").get(0);
        markDead(game, List.of(dead));
        Assertions.assertEquals(4000, game.call(game.players.get(wolf - 1), "/night-action", "{\"actionType\":\"kill\",\"targetSeatNo\":" + dead + "}").path("code").asInt());
    }



    @Test
    void finishRequiresAPlayingRoomAndCannotRunTwice() throws Exception {
        // 无法官房主可以强制结束（记流局）；大厅里不能结束，结束过不能再结束
        Game lobby = lobbyGame("{\"boardId\":" + BOARD_ID + ",\"judgeMode\":\"joined\"}", PLAYER_COUNT - 1);
        Assertions.assertEquals(4002, lobby.advance(lobby.host, "{\"action\":\"finish\"}").path("code").asInt());

        Game game = joinedGame(BOARD_ID, PLAYER_COUNT);
        JsonNode finished = game.advance(game.host, "{\"action\":\"finish\"}");
        Assertions.assertEquals(0, finished.path("code").asInt(), finished.toString());
        Assertions.assertEquals("finished", finished.path("data").path("roomStatus").asText());
        Assertions.assertEquals("流局", finished.path("data").path("winnerCamp").asText());
        Assertions.assertEquals(4002, game.advance(game.host, "{\"action\":\"finish\"}").path("code").asInt());
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
    void lobbyDoesNotShowAWinnerSuggestion() throws Exception {
        Game game = lobbyGame();
        Assertions.assertEquals("", game.snapshot(game.host).path("winnerSuggestion").asText());
    }

    @Test
    void finishedRoomRevealsAllRolesToPlayers() throws Exception {
        Game game = joinedGame(BOARD_ID, PLAYER_COUNT);
        game.advance(game.host, "{\"action\":\"finish\"}");
        JsonNode view = game.snapshot(game.players.get(1));
        for (JsonNode player : view.path("players")) {
            if (player.path("playing").asBoolean()) {
                Assertions.assertFalse(player.path("roleName").isNull(), player.toString());
            }
        }
    }

    @Test
    void nobodyRecordsANightActionForAnotherSeat() throws Exception {
        // 法官代记是旧流程的功能，已下掉：带 actorSeatNo 一律 4003
        Game game = startedGame();
        JsonNode proxied = game.call(game.host, "/night-action", "{\"actionType\":\"guard\",\"targetSeatNo\":2,\"actorSeatNo\":1}");
        Assertions.assertEquals(4003, proxied.path("code").asInt(), proxied.toString());
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
        JsonNode announced = game.finishNight(dead, null);
        Assertions.assertEquals("skill_pending", announced.path("currentPhase").asText(), "公布死讯前先问出局者的技能");
        Assertions.assertTrue(announced.path("latestAnnouncement").asText().contains(dead + "号"));
        Assertions.assertEquals("last_words", game.skill(dead, "{\"decision\":\"skip\"}").path("currentPending").path("kind").asText(),
                "第一夜死的人问完技能留遗言");
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
        JsonNode signup = game.finishNight(dead, null);
        Assertions.assertEquals("sheriff_signup", signup.path("currentPhase").asText(), signup.toString());
        Assertions.assertTrue(game.player(signup, dead).path("alive").asBoolean(), "上警时还没公布昨夜死讯");

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
        nobody.finishNight();
        JsonNode lost = nobody.advance(nobody.host, "{\"action\":\"close_sheriff_signup\"}");
        Assertions.assertEquals("day_announce", lost.path("data").path("currentPhase").asText(), lost.toString());
        Assertions.assertTrue(lost.path("data").path("sheriffSeatNo").isNull());

        Game everyone = autoGame(true);
        everyone.finishNight();
        for (int seat = 1; seat <= PLAYER_COUNT; seat++) {
            everyone.sheriff(seat, "{\"candidate\":true}");
        }
        everyone.advance(everyone.host, "{\"action\":\"close_sheriff_signup\"}");
        JsonNode all = everyone.snapshot(everyone.host);
        Assertions.assertEquals("day_announce", all.path("currentPhase").asText());
        Assertions.assertTrue(all.path("sheriffSeatNo").isNull());
    }

    @Test
    void candidatesCannotVoteForSheriff() throws Exception {
        Game game = autoGame(true);
        game.finishNight();
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
        game.finishNight();
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
    void roundsRecordDeathsVotesAndSheriffForEveryPlayer() throws Exception {
        Game game = autoGame(true);
        Map<String, List<Integer>> seats = game.seatsByRole();
        int dead = seats.get("平民").get(0);
        // 第 0 步是守卫：守卫自己守一个别人，最后检查它没出现在公开记录里
        game.act(seats.get("守卫").get(0), "guard", seats.get("平民").get(2));
        game.finishNight(dead, null);
        int a = dead == 1 ? 2 : 1;
        game.sheriff(a, "{\"candidate\":true}");
        game.advance(game.host, "{\"action\":\"close_sheriff_signup\"}");
        game.skill(dead, "{\"decision\":\"skip\"}");
        skipLastWords(game);
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
        JsonNode day = game.finishNight(dead, null);
        Assertions.assertEquals("skill_pending", day.path("currentPhase").asText(), day.toString());

        JsonNode own = game.snapshot(game.players.get(dead - 1)).path("pending");
        Assertions.assertEquals("skill_ask", own.path("kind").asText(), own.toString());
        Assertions.assertEquals("[\"skip\"]", own.path("options").toString(), "没技能的人只有「确认」");
        Assertions.assertTrue(game.snapshot(game.players.get(seats.get("平民").get(1) - 1)).path("pending").isNull(), "别人看不到待办细节");

        game.skill(dead, "{\"decision\":\"skip\"}");
        Assertions.assertEquals("day_announce", skipLastWords(game).path("currentPhase").asText());
    }

    @Test
    void hunterShotChainsIntoTheNextVictim() throws Exception {
        Game game = autoGame12(true);
        Map<String, List<Integer>> seats = game.seatsByRole();
        int hunter = seats.get("猎人").get(0);
        int sheriff = seats.get("平民").get(0);
        int heir = seats.get("平民").get(1);
        game.finishNight(hunter, null);
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
        skipLastWords(game);
        Assertions.assertEquals("badge", game.snapshot(game.players.get(sheriff - 1)).path("pending").path("kind").asText(),
                "先开枪、再遗言、最后移交警徽");
        JsonNode passed = game.skill(sheriff, "{\"decision\":\"use\",\"targetSeatNo\":" + heir + "}");
        Assertions.assertEquals(heir, passed.path("sheriffSeatNo").asInt());
        Assertions.assertEquals("day_announce", passed.path("currentPhase").asText());
    }

    @Test
    void poisonedHunterGetsNoShotOption() throws Exception {
        Game game = autoGame12(false);
        Map<String, List<Integer>> seats = game.seatsByRole();
        int hunter = seats.get("猎人").get(0);
        JsonNode day = game.finishNight(null, hunter);
        Assertions.assertFalse(game.player(day, hunter).path("alive").asBoolean(), day.toString());
        JsonNode ask = game.snapshot(game.players.get(hunter - 1)).path("pending");
        Assertions.assertEquals("skill_ask", ask.path("kind").asText());
        Assertions.assertEquals("[\"skip\"]", ask.path("options").toString(), "被毒的猎人不能开枪");
        Assertions.assertNotEquals(0, game.call(game.players.get(hunter - 1), "/skill", "{\"decision\":\"use\"}").path("code").asInt());
    }

    @Test
    void exiledIdiotIsOutAndRevealed() throws Exception {
        Game game = autoGame12(false);
        Map<String, List<Integer>> seats = game.seatsByRole();
        int idiot = seats.get("白痴").get(0);
        List<Integer> voters = seats.get("狼人");

        game.finishNight();
        game.advance(game.host, "{\"action\":\"start_speech\"}");
        game.advance(game.host, "{\"action\":\"start_vote\"}");
        game.vote(voters.get(0), idiot);
        game.vote(voters.get(1), idiot);
        JsonNode out = game.advance(game.host, "{\"action\":\"resolve_vote\"}").path("data");
        Assertions.assertFalse(game.player(out, idiot).path("alive").asBoolean(), "白痴被放逐照样出局");
        Assertions.assertTrue(out.path("latestAnnouncement").asText().startsWith(idiot + "号是白痴，被放逐出局"), out.path("latestAnnouncement").asText());
        Assertions.assertEquals("skill_pending", out.path("currentPhase").asText());
        JsonNode ask = game.snapshot(game.players.get(idiot - 1)).path("pending");
        Assertions.assertEquals("skill_ask", ask.path("kind").asText(), ask.toString());
        Assertions.assertEquals("[\"skip\"]", ask.path("options").toString());
        long reveals = 0;
        for (JsonNode event : game.snapshot(game.host).path("timeline")) {
            if ("idiot_revealed".equals(event.path("actionType").asText())) {
                reveals++;
            }
        }
        Assertions.assertEquals(1, reveals, "翻牌事件照记");
    }

    @Test
    void idiotSheriffPassesTheBadgeWhenExiled() throws Exception {
        Game game = autoGame12(true);
        Map<String, List<Integer>> seats = game.seatsByRole();
        int idiot = seats.get("白痴").get(0);
        int seer = seats.get("预言家").get(0);
        game.finishNight(seats.get("平民").get(0), null);
        game.sheriff(idiot, "{\"candidate\":true}");
        game.sheriff(seer, "{\"candidate\":true}");
        game.advance(game.host, "{\"action\":\"close_sheriff_signup\"}");
        game.advance(game.host, "{\"action\":\"start_sheriff_vote\"}");
        for (int seat = 1; seat <= 12; seat++) {
            if (seat != idiot && seat != seer) {
                game.sheriff(seat, "{\"targetSeatNo\":" + idiot + "}");
            }
        }
        game.advance(game.host, "{\"action\":\"resolve_sheriff_vote\"}");
        game.skipPending();
        JsonNode speech = game.advance(game.host, "{\"action\":\"start_speech\"}").path("data");
        Assertions.assertEquals(idiot, speech.path("sheriffSeatNo").asInt(), speech.toString());
        game.advance(game.host, "{\"action\":\"start_vote\"}");
        for (int wolf : seats.get("狼人")) {
            game.vote(wolf, idiot);
        }
        game.advance(game.host, "{\"action\":\"resolve_vote\"}");
        game.skill(idiot, "{\"decision\":\"skip\"}");
        skipLastWords(game);
        JsonNode badge = game.snapshot(game.players.get(idiot - 1)).path("pending");
        Assertions.assertEquals("badge", badge.path("kind").asText(), badge.toString());
    }

    @Test
    void pendingIsDroppedOnceTheGameIsDecided() throws Exception {
        Game game = autoGame12(false);
        Map<String, List<Integer>> seats = game.seatsByRole();
        int hunter = seats.get("猎人").get(0);
        int seer = seats.get("预言家").get(0);
        int witch = seats.get("女巫").get(0);
        int idiot = seats.get("白痴").get(0);
        game.finishNight();
        // 法官点杀三个神职（点杀走正常出局流程），只剩猎人一个神职
        for (int seat : List.of(seer, witch, idiot)) {
            JsonNode out = game.advance(game.host, "{\"action\":\"force_out\",\"forceOutSeatNo\":" + seat + "}");
            Assertions.assertEquals(0, out.path("code").asInt(), out.toString());
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
        game.finishNight();
        Assertions.assertEquals(4003, game.advance(game.players.get(0), "{\"action\":\"force_out\",\"forceOutSeatNo\":3}").path("code").asInt());
        JsonNode out = game.advance(game.host, "{\"action\":\"force_out\",\"forceOutSeatNo\":3}");
        Assertions.assertEquals(0, out.path("code").asInt(), out.toString());
        Assertions.assertFalse(game.player(out.path("data"), 3).path("alive").asBoolean());
        Assertions.assertTrue(out.path("data").path("latestAnnouncement").asText().contains("法官判罚"));
        Assertions.assertEquals("skill_pending", out.path("data").path("currentPhase").asText());
    }

    // ---- 无法官模式与推进权 ----

    @Test
    void joinedHostTakesSeatOneAndStartsTheGame() throws Exception {
        Game game = joinedLobby(2, 12);
        JsonNode lobby = game.snapshot(game.host);
        Assertions.assertEquals(1, lobby.path("selfPlayer").path("seatNo").asInt(), lobby.toString());
        Assertions.assertFalse(lobby.path("judgeViewer").asBoolean());
        Assertions.assertTrue(lobby.path("host").asBoolean());
        game.readyAll();
        Assertions.assertTrue(game.snapshot(game.host).path("canStart").asBoolean());
        game.start();
        JsonNode playing = game.snapshot(game.host);
        Assertions.assertEquals("playing", playing.path("roomStatus").asText());
        Assertions.assertTrue(playing.path("canAdvance").asBoolean());
        Assertions.assertTrue(playing.path("nightActions").isEmpty(), "房主是玩家，看不到别人的夜间行动");
    }

    @Test
    void joinedModeIsOnlyForFullBoards() throws Exception {
        String host = login("judge-host-partial-" + SEQUENCE.incrementAndGet());
        String created = mockMvc.perform(post("/api/judge/rooms")
                        .header("Authorization", "Bearer " + host)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"boardId\":1,\"judgeMode\":\"joined\"}"))
                .andReturn().getResponse().getContentAsString();
        Assertions.assertEquals(4000, objectMapper.readTree(created).path("code").asInt(), created);
    }

    @Test
    void hostCannotDictateResultsInAutoRooms() throws Exception {
        // 无法官房主只是普通玩家：不能指定谁出局、谁赢，也不能改系统定的发言顺序
        Game game = joinedGame(2, 12);
        game.finishNight();
        game.skipPending();
        Assertions.assertEquals(4000, game.advance(game.host, "{\"action\":\"start_speech\",\"speechStartSeat\":5}").path("code").asInt());
        Assertions.assertEquals(4000, game.advance(game.host, "{\"action\":\"start_speech\",\"speechDirection\":\"right\"}").path("code").asInt());
        game.advance(game.host, "{\"action\":\"start_speech\"}");
        game.advance(game.host, "{\"action\":\"start_vote\"}");
        Assertions.assertEquals(4000, game.advance(game.host, "{\"action\":\"resolve_vote\",\"eliminatedSeatNo\":5}").path("code").asInt());
        Assertions.assertEquals(4000, game.advance(game.host, "{\"action\":\"resolve_vote\",\"winnerCamp\":\"好人\"}").path("code").asInt());
        Assertions.assertEquals(4000, game.advance(game.host, "{\"action\":\"finish\",\"winnerCamp\":\"好人\"}").path("code").asInt());
        Assertions.assertEquals("exile_vote", game.snapshot(game.host).path("currentPhase").asText());
    }

    @Test
    void renamedIdiotStillFlipsOnExile() throws Exception {
        // 角色改了名字，机制还是白痴：被放逐照样出局翻牌
        Game game = autoGame12(false);
        int idiot = game.seatsByRole().get("白痴").get(0);
        jdbcTemplate.update("UPDATE judge_room_players SET role_name = '傻子' WHERE room_id = ? AND seat_no = ?", game.roomId, idiot);
        List<Integer> wolves = game.seatsByRole().get("狼人");
        game.finishNight();
        game.advance(game.host, "{\"action\":\"start_speech\"}");
        game.advance(game.host, "{\"action\":\"start_vote\"}");
        game.vote(wolves.get(0), idiot);
        JsonNode revealed = game.advance(game.host, "{\"action\":\"resolve_vote\"}");
        Assertions.assertFalse(game.player(revealed.path("data"), idiot).path("alive").asBoolean(), revealed.toString());
        Assertions.assertTrue(revealed.path("data").path("latestAnnouncement").asText().startsWith(idiot + "号是白痴"), revealed.toString());
    }

    @Test
    void concurrentPauseIsNeverLost() throws Exception {
        // 暂停和关步骤的惰性推进同时发生：法官拿到「暂停成功」，房间就必须真的暂停
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 25; round++) {
                Game game = autoGame(false);
                jdbcTemplate.update("UPDATE judge_rooms SET step_deadline = ? WHERE room_id = ?",
                        java.sql.Timestamp.valueOf("2000-01-01 00:00:00"), game.roomId);
                java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
                java.util.concurrent.Future<JsonNode> pause = pool.submit(() -> {
                    go.await();
                    return game.advance(game.host, "{\"action\":\"pause\"}");
                });
                java.util.concurrent.Future<JsonNode> poll = pool.submit(() -> {
                    go.await();
                    return game.snapshot(game.players.get(0));
                });
                go.countDown();
                JsonNode paused = pause.get();
                poll.get();
                if (paused.path("code").asInt() == 0) {
                    String phaseData = jdbcTemplate.queryForObject("SELECT phase_data FROM judge_rooms WHERE room_id = ?", String.class, game.roomId);
                    Assertions.assertTrue(phaseData != null && phaseData.contains("pausedAt"), "第 " + round + " 轮暂停被覆盖：" + phaseData);
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void judgeCannotDriveTheAutoFlow() throws Exception {
        Game game = autoGame(false);
        int guard = game.seatsByRole().get("守卫").get(0);
        JsonNode proxy = game.call(game.host, "/night-action", "{\"actionType\":\"guard\",\"targetSeatNo\":" + guard + ",\"actorSeatNo\":" + guard + "}");
        Assertions.assertEquals(4003, proxy.path("code").asInt(), proxy.toString());
        Assertions.assertEquals(4003, game.advance(game.host, "{\"action\":\"next_step\",\"stepIndex\":0}").path("code").asInt());
        Assertions.assertEquals(4003, game.advance(game.host, "{\"action\":\"resolve_night\",\"deadSeatNos\":[]}").path("code").asInt());
        Assertions.assertEquals(4003, game.advance(game.host, "{\"action\":\"finish\"}").path("code").asInt());
    }

    @Test
    void joinedHostCanForceFinish() throws Exception {
        Game game = joinedGame(2, 12);
        JsonNode finished = game.advance(game.host, "{\"action\":\"finish\"}");
        Assertions.assertEquals(0, finished.path("code").asInt(), finished.toString());
        Assertions.assertEquals("流局", finished.path("data").path("winnerCamp").asText());
    }

    @Test
    void deadJoinedHostStillAdvancesTheDay() throws Exception {
        Game game = joinedGame(2, 12);
        // 狼可以自刀，1 号（房主）拿什么牌都刀得到
        game.finishNight(1, null);
        game.skipPending();
        JsonNode day = game.snapshot(game.host);
        Assertions.assertFalse(game.player(day, 1).path("alive").asBoolean(), day.toString());
        JsonNode speech = game.advance(game.host, "{\"action\":\"start_speech\"}");
        Assertions.assertEquals(0, speech.path("code").asInt(), speech.toString());
        Assertions.assertEquals("day_speech", speech.path("data").path("currentPhase").asText());
    }

    @Test
    void playersCannotUseHostActions() throws Exception {
        Game game = joinedGame(2, 12);
        game.finishNight();
        game.skipPending();
        Assertions.assertEquals(4003, game.advance(game.players.get(1), "{\"action\":\"start_speech\"}").path("code").asInt());
    }

    // ---- 暂停 / 继续 ----

    @Test
    void pauseFreezesTheNightStep() throws Exception {
        Game game = autoGame(false);
        Assertions.assertEquals(0, game.advance(game.host, "{\"action\":\"pause\"}").path("code").asInt());
        JsonNode paused = game.expireDeadline();
        Assertions.assertTrue(paused.path("paused").asBoolean(), paused.toString());
        Assertions.assertEquals(0, paused.path("currentStep").path("index").asInt(), "暂停时到点也不切步");
        Assertions.assertTrue(game.snapshot(game.players.get(0)).path("paused").asBoolean(), "所有人都看到暂停");
        Assertions.assertEquals(4002, game.advance(game.host, "{\"action\":\"pause\"}").path("code").asInt(), "不能重复暂停");
        JsonNode resumed = game.advance(game.host, "{\"action\":\"resume\"}");
        Assertions.assertEquals(0, resumed.path("code").asInt(), resumed.toString());
        Assertions.assertFalse(resumed.path("data").path("paused").asBoolean());
        Assertions.assertEquals(4002, game.advance(game.host, "{\"action\":\"resume\"}").path("code").asInt(), "没暂停不能继续");
    }

    @Test
    void resumeShiftsTheDeadlineByThePause() throws Exception {
        Game game = autoGame(false);
        java.time.LocalDateTime before = java.time.LocalDateTime.parse(game.snapshot(game.host).path("phaseDeadline").asText());
        game.advance(game.host, "{\"action\":\"pause\"}");
        Thread.sleep(2000);
        game.advance(game.host, "{\"action\":\"resume\"}");
        java.time.LocalDateTime after = java.time.LocalDateTime.parse(game.snapshot(game.host).path("phaseDeadline").asText());
        Assertions.assertTrue(java.time.Duration.between(before, after).toMillis() >= 1900, before + " → " + after);
    }

    @Test
    void pauseShiftsPendingDeadlines() throws Exception {
        Game game = autoGame(false);
        int dead = game.seatsByRole().get("平民").get(0);
        Assertions.assertEquals("skill_pending", game.finishNight(dead, null).path("currentPhase").asText());
        java.time.LocalDateTime before = java.time.LocalDateTime.parse(
                game.snapshot(game.players.get(dead - 1)).path("pending").path("deadline").asText());
        game.advance(game.host, "{\"action\":\"pause\"}");
        Thread.sleep(2000);
        game.advance(game.host, "{\"action\":\"resume\"}");
        java.time.LocalDateTime after = java.time.LocalDateTime.parse(
                game.snapshot(game.players.get(dead - 1)).path("pending").path("deadline").asText());
        Assertions.assertTrue(java.time.Duration.between(before, after).toMillis() >= 1900, before + " → " + after);
    }

    @Test
    void onlyTheJudgePauses() throws Exception {
        Game game = autoGame(false);
        Assertions.assertEquals(4003, game.advance(game.players.get(0), "{\"action\":\"pause\"}").path("code").asInt());
        Game joined = joinedGame(2, 12);
        Assertions.assertEquals(4003, joined.advance(joined.host, "{\"action\":\"pause\"}").path("code").asInt(), "无法官房间没有法官");
    }

    @Test
    void submissionsWaitWhilePaused() throws Exception {
        Game game = autoGame(false);
        int guard = game.seatsByRole().get("守卫").get(0);
        game.advance(game.host, "{\"action\":\"pause\"}");
        JsonNode blocked = game.call(game.players.get(guard - 1), "/night-action", "{\"actionType\":\"guard\",\"targetSeatNo\":" + guard + "}");
        Assertions.assertEquals(4002, blocked.path("code").asInt(), blocked.toString());
    }

    @Test
    void skillAskGivesFifteenSeconds() throws Exception {
        Game game = autoGame(false);
        int villager = game.seatsByRole().get("平民").get(0);
        JsonNode out = game.advance(game.host, "{\"action\":\"force_out\",\"forceOutSeatNo\":" + villager + "}");
        Assertions.assertEquals("skill_pending", out.path("data").path("currentPhase").asText(), out.toString());
        long seconds = secondsToDeadline(game);
        Assertions.assertTrue(seconds >= 13 && seconds <= 15, "技能询问 15 秒，实际 " + seconds);
    }

    @Test
    void forceOutAtNightKeepsTheStepClock() throws Exception {
        Game game = autoGame(false);
        int villager = game.seatsByRole().get("平民").get(0);
        JsonNode out = game.advance(game.host, "{\"action\":\"force_out\",\"forceOutSeatNo\":" + villager + "}");
        Assertions.assertEquals("skill_pending", out.path("data").path("currentPhase").asText(), out.toString());
        JsonNode night = game.skipPending();
        Assertions.assertEquals("night_action", night.path("currentPhase").asText(), night.toString());
        Assertions.assertEquals(0, night.path("currentStep").path("index").asInt());
        Assertions.assertTrue(java.time.LocalDateTime.parse(night.path("phaseDeadline").asText())
                .isAfter(java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"))), "夜间步骤照常倒计时");
    }

    // ---- 投票、上警报名的超时与自动结算 ----

    @Test
    void voteClosesItselfWhenEveryoneVoted() throws Exception {
        Game game = autoGame(false);
        game.finishNight();
        game.advance(game.host, "{\"action\":\"start_speech\"}");
        game.advance(game.host, "{\"action\":\"start_vote\"}");
        Assertions.assertTrue(secondsToDeadline(game) <= 30 && secondsToDeadline(game) >= 28, "投票 30 秒");
        for (int seat = 1; seat <= PLAYER_COUNT; seat++) {
            game.vote(seat, seat == 2 ? 3 : 2);
        }
        JsonNode result = game.snapshot(game.host);
        Assertions.assertEquals("skill_pending", result.path("currentPhase").asText(), "全员投完自动结算，2 号出局先问技能");
        Assertions.assertFalse(game.player(result, 2).path("alive").asBoolean());
    }

    @Test
    void missingVotesCountAsAbstainOnTimeout() throws Exception {
        Game game = autoGame(false);
        game.finishNight();
        game.advance(game.host, "{\"action\":\"start_speech\"}");
        game.advance(game.host, "{\"action\":\"start_vote\"}");
        game.vote(1, 2);
        game.vote(3, 2);
        JsonNode result = game.expireDeadline();
        Assertions.assertNotEquals("exile_vote", result.path("currentPhase").asText(), result.toString());
        JsonNode exile = result.path("rounds").get(0).path("exile").get(0);
        Assertions.assertEquals(7, exile.path("abstainSeatNos").size(), exile.toString());
        Assertions.assertEquals(2, exile.path("eliminatedSeatNo").asInt());
    }

    @Test
    void playersSeeTheirOwnSignupAndVoteOnly() throws Exception {
        Game game = autoGame(true);
        Assertions.assertEquals("sheriff_signup", game.finishNight().path("currentPhase").asText());
        game.sheriff(4, "{\"candidate\":true}");
        Assertions.assertTrue(game.snapshot(game.players.get(3)).path("dayInfo").path("sheriffCandidate").asBoolean(false));
        Assertions.assertTrue(game.snapshot(game.players.get(4)).path("dayInfo").path("sheriffCandidate").isNull(),
                "5 号自己还没选（null），看不到 4 号上没上警");

        Game vote = autoGame(false);
        vote.finishNight();
        vote.advance(vote.host, "{\"action\":\"start_speech\"}");
        vote.advance(vote.host, "{\"action\":\"start_vote\"}");
        JsonNode before = vote.snapshot(vote.players.get(0)).path("dayInfo");
        Assertions.assertTrue(before.path("canVote").asBoolean(), before.toString());
        Assertions.assertFalse(before.path("voted").asBoolean(true), before.toString());
        Assertions.assertEquals(0, before.path("votedCount").asInt());
        Assertions.assertEquals(PLAYER_COUNT, before.path("voterCount").asInt());
        vote.vote(1, 2);
        JsonNode mine = vote.snapshot(vote.players.get(0)).path("dayInfo");
        Assertions.assertTrue(mine.path("voted").asBoolean(), mine.toString());
        Assertions.assertEquals(2, mine.path("myVote").asInt());
        JsonNode other = vote.snapshot(vote.players.get(2)).path("dayInfo");
        Assertions.assertEquals(1, other.path("votedCount").asInt(), other.toString());
        Assertions.assertFalse(other.path("voted").asBoolean(true));
        Assertions.assertTrue(other.path("myVote").isNull() || other.path("myVote").isMissingNode(), "看不到别人投给谁");
    }

    @Test
    void sheriffSignupClosesAfterTwentySeconds() throws Exception {
        Game game = autoGame(true);
        Assertions.assertEquals("sheriff_signup", game.finishNight().path("currentPhase").asText());
        long seconds = secondsToDeadline(game);
        Assertions.assertTrue(seconds >= 18 && seconds <= 20, "上警报名 20 秒，实际 " + seconds);
        game.sheriff(4, "{\"candidate\":true}");
        JsonNode elected = game.expireDeadline();
        Assertions.assertEquals("day_announce", elected.path("currentPhase").asText(), elected.toString());
        Assertions.assertEquals(4, elected.path("sheriffSeatNo").asInt(), "只有一人上警直接当选");
    }

    @Test
    void sheriffVoteClosesOnTime() throws Exception {
        Game game = autoGame(true);
        game.finishNight();
        game.sheriff(1, "{\"candidate\":true}");
        game.sheriff(2, "{\"candidate\":true}");
        game.advance(game.host, "{\"action\":\"close_sheriff_signup\"}");
        game.advance(game.host, "{\"action\":\"start_sheriff_vote\"}");
        game.sheriff(3, "{\"targetSeatNo\":1}");
        JsonNode elected = game.expireDeadline();
        Assertions.assertEquals(1, elected.path("sheriffSeatNo").asInt(), elected.toString());
    }

    @Test
    void autoAndManualVoteResolveOnlyOnce() throws Exception {
        Game game = autoGame(false);
        game.finishNight();
        game.advance(game.host, "{\"action\":\"start_speech\"}");
        game.advance(game.host, "{\"action\":\"start_vote\"}");
        for (int seat = 1; seat <= PLAYER_COUNT; seat++) {
            game.vote(seat, seat == 2 ? 3 : 2);
        }
        game.snapshot(game.host);
        Assertions.assertEquals(4002, game.advance(game.host, "{\"action\":\"resolve_vote\"}").path("code").asInt());
        Assertions.assertEquals(1, game.snapshot(game.host).path("rounds").get(0).path("exile").size(), "只结算了一轮");
    }

    @Test
    void lastSpeakerFinishingOpensTheExileVote() throws Exception {
        // 通用流程 6：所有存活玩家发完言，自动进入放逐投票，不用房主点
        Game game = autoGame(false);
        game.finishNight();
        JsonNode speech = game.advance(game.host, "{\"action\":\"start_speech\"}").path("data");
        int speakers = speech.path("speech").path("order").size();
        Assertions.assertTrue(speakers > 1, speech.toString());
        JsonNode view = speech;
        for (int i = 0; i < speakers - 1; i++) {
            view = game.advance(game.host, "{\"action\":\"next_speaker\"}").path("data");
            Assertions.assertEquals("day_speech", view.path("currentPhase").asText(), "还有人没说：" + i);
        }
        view = game.advance(game.host, "{\"action\":\"next_speaker\"}").path("data");
        Assertions.assertEquals("exile_vote", view.path("currentPhase").asText(), view.toString());
        long seconds = secondsToDeadline(game);
        Assertions.assertTrue(seconds >= 28 && seconds <= 30, "投票 30 秒，实际 " + seconds);
    }

    @Test
    void lastSheriffSpeakerDoesNotStartTheSheriffVote() throws Exception {
        // 警上发言结束后的警长投票仍由房主点，这次不改
        Game game = autoGame12(true);
        Map<String, List<Integer>> roles = game.seatsByRole();
        JsonNode stage = toSheriffSpeech(game, null, roles.get("预言家").get(0), roles.get("平民").get(0));
        int speakers = stage.path("speech").path("order").size();
        JsonNode view = stage;
        for (int i = 0; i < speakers; i++) {
            view = game.advance(game.host, "{\"action\":\"next_speaker\"}").path("data");
        }
        Assertions.assertEquals("sheriff_speech", view.path("currentPhase").asText(), view.toString());
    }

    @Test
    void speechClockIsShownButNotForced() throws Exception {
        Game game = autoGame(false);
        game.finishNight();
        game.advance(game.host, "{\"action\":\"start_speech\"}");
        long seconds = secondsToDeadline(game);
        Assertions.assertTrue(seconds >= 118 && seconds <= 120, "每人发言 120 秒，实际 " + seconds);
        JsonNode after = game.expireDeadline();
        Assertions.assertEquals("day_speech", after.path("currentPhase").asText());
        Assertions.assertEquals(0, after.path("speech").path("index").asInt(), "到点不强制切下一位");
    }

    // ---- 玩家各自的夜间私密信息 ----

    @Test
    void wolvesSeeTeammatesAndLivePicks() throws Exception {
        Game game = autoGame12(false);
        List<Integer> wolves = game.seatsByRole().get("狼人");
        int target = game.seatsByRole().get("平民").get(0);
        game.act(wolves.get(0), "kill", target);
        JsonNode info = game.snapshot(game.players.get(wolves.get(1) - 1)).path("nightInfo");
        List<Integer> mates = new ArrayList<>(wolves);
        mates.remove(wolves.get(1));
        Assertions.assertEquals(mates.toString(), info.path("teammateSeats").toString().replace(",", ", "), info.toString());
        Assertions.assertEquals(4, info.path("wolfPicks").size());
        for (JsonNode pick : info.path("wolfPicks")) {
            if (pick.path("seatNo").asInt() == wolves.get(0)) {
                Assertions.assertEquals(target, pick.path("targetSeatNo").asInt());
            } else {
                Assertions.assertTrue(pick.path("targetSeatNo").isNull(), pick.toString());
            }
        }
    }

    @Test
    void roomsRunTheNewFlowEvenWhenTheClientDoesNotAsk() throws Exception {
        // 旧流程已下掉：建房请求不带 autoJudge 也是新流程
        Game game = startedGame();
        JsonNode snapshot = game.snapshot(game.host);
        Assertions.assertTrue(snapshot.path("autoJudge").asBoolean());
        Assertions.assertFalse(snapshot.path("currentStep").isNull(), "夜里按步骤走");
    }

    @Test
    void wolfPicksTellNoKillFromUndecided() throws Exception {
        Game game = autoGame12(false);
        List<Integer> wolves = game.seatsByRole().get("狼人");
        game.act(wolves.get(0), "none", null);
        JsonNode picks = game.snapshot(game.players.get(wolves.get(1) - 1)).path("nightInfo").path("wolfPicks");
        for (JsonNode pick : picks) {
            Assertions.assertTrue(pick.path("targetSeatNo").isNull(), pick.toString());
            Assertions.assertEquals(pick.path("seatNo").asInt() == wolves.get(0), pick.path("decided").asBoolean(), pick.toString());
        }
    }

    @Test
    void witchSeesHerPotionsAndGuardSeesLastNight() throws Exception {
        Game game = autoGame(false);
        Map<String, List<Integer>> seats = game.seatsByRole();
        int guard = seats.get("守卫").get(0);
        int witch = seats.get("女巫").get(0);
        int villager = seats.get("平民").get(0);
        jdbcTemplate.update("UPDATE judge_room_players SET status_json = ? WHERE room_id = ? AND seat_no = ?",
                "{\"lastGuardSeat\":" + villager + "}", game.roomId, guard);
        jdbcTemplate.update("UPDATE judge_room_players SET status_json = ? WHERE room_id = ? AND seat_no = ?",
                "{\"antidote\":false}", game.roomId, witch);
        JsonNode guardInfo = game.snapshot(game.players.get(guard - 1)).path("nightInfo");
        Assertions.assertEquals(villager, guardInfo.path("lastGuardSeat").asInt(), guardInfo.toString());
        Assertions.assertTrue(guardInfo.path("antidote").isNull() || guardInfo.path("antidote").isMissingNode(), "守卫没有药");
        JsonNode witchInfo = game.snapshot(game.players.get(witch - 1)).path("nightInfo");
        Assertions.assertFalse(witchInfo.path("antidote").asBoolean(true), witchInfo.toString());
        Assertions.assertTrue(witchInfo.path("poison").asBoolean(false), witchInfo.toString());
        JsonNode villagerInfo = game.snapshot(game.players.get(villager - 1)).path("nightInfo");
        Assertions.assertTrue(villagerInfo.isNull() || villagerInfo.isMissingNode(), "平民没有私密信息");
    }

    @Test
    void holdersSeeWhatTheySubmittedTonight() throws Exception {
        Game game = autoGame(false);
        Map<String, List<Integer>> seats = game.seatsByRole();
        int guard = seats.get("守卫").get(0);
        int target = seats.get("平民").get(0);
        Assertions.assertEquals("guard", game.snapshot(game.host).path("currentStep").path("kind").asText(), "守卫九人局第一步是守卫");
        JsonNode before = game.snapshot(game.players.get(guard - 1)).path("nightInfo");
        Assertions.assertTrue(before.path("myAction").isNull() || before.path("myAction").isMissingNode(), before.toString());
        game.act(guard, "guard", target);
        JsonNode after = game.snapshot(game.players.get(guard - 1)).path("nightInfo");
        Assertions.assertEquals("guard", after.path("myAction").asText(), after.toString());
        Assertions.assertEquals(target, after.path("myTarget").asInt(), after.toString());
    }

    @Test
    void witchSeesTheKnifeOnlyOnHerStep() throws Exception {
        Game game = autoGame12(false);
        Map<String, List<Integer>> seats = game.seatsByRole();
        int witch = seats.get("女巫").get(0);
        int target = seats.get("平民").get(0);
        Assertions.assertTrue(game.snapshot(game.players.get(witch - 1)).path("nightInfo").path("knifeSeat").isMissingNode()
                || game.snapshot(game.players.get(witch - 1)).path("nightInfo").path("knifeSeat").isNull(), "狼队那一步女巫还不知道刀口");
        for (int wolf : seats.get("狼人")) {
            game.act(wolf, "kill", target);
        }
        Assertions.assertEquals("witch", game.expireDeadline().path("currentStep").path("kind").asText());
        Assertions.assertEquals(target, game.snapshot(game.players.get(witch - 1)).path("nightInfo").path("knifeSeat").asInt());
    }

    @Test
    void knifeStandsWhenEveryWolfWhoChoseAgrees() throws Exception {
        // 通用流程 3.2：没交的狼不算（控刀），交了的都指同一个人就是刀口
        Game game = autoGame12(false);
        Map<String, List<Integer>> seats = game.seatsByRole();
        int witch = seats.get("女巫").get(0);
        int target = seats.get("平民").get(0);
        List<Integer> wolves = seats.get("狼人");
        for (int i = 0; i < wolves.size() - 1; i++) {
            game.act(wolves.get(i), "kill", target);
        }
        Assertions.assertEquals("witch", game.expireDeadline().path("currentStep").path("kind").asText());
        Assertions.assertEquals(target, game.snapshot(game.players.get(witch - 1)).path("nightInfo").path("knifeSeat").asInt());
    }

    @Test
    void wolvesKnifeByMajority() throws Exception {
        // 2026-11-12 用户改定：刀口按多数，平票随机，都没选才空刀；点「空刀」的狼不计票
        Game game = autoGame12(false);
        Map<String, List<Integer>> seats = game.seatsByRole();
        int witch = seats.get("女巫").get(0);
        List<Integer> wolves = seats.get("狼人");
        List<Integer> villagers = seats.get("平民");
        game.act(wolves.get(0), "kill", villagers.get(0));
        game.act(wolves.get(1), "kill", villagers.get(0));
        game.act(wolves.get(2), "kill", villagers.get(1));
        Assertions.assertEquals("witch", game.expireDeadline().path("currentStep").path("kind").asText());
        Assertions.assertEquals(villagers.get(0), game.snapshot(game.players.get(witch - 1)).path("nightInfo").path("knifeSeat").asInt(), "2 比 1，多数的刀口");

        Game second = autoGame12(false);
        Map<String, List<Integer>> seats2 = second.seatsByRole();
        List<Integer> wolves2 = seats2.get("狼人");
        second.act(wolves2.get(0), "kill", seats2.get("平民").get(0));
        second.act(wolves2.get(1), "none", null);
        Assertions.assertEquals("witch", second.expireDeadline().path("currentStep").path("kind").asText());
        Assertions.assertEquals(seats2.get("平民").get(0),
                second.snapshot(second.players.get(seats2.get("女巫").get(0) - 1)).path("nightInfo").path("knifeSeat").asInt(), "点空刀的狼不计票");

        Game third = autoGame12(false);
        Map<String, List<Integer>> seats3 = third.seatsByRole();
        third.act(seats3.get("狼人").get(0), "none", null);
        Assertions.assertEquals("witch", third.expireDeadline().path("currentStep").path("kind").asText());
        JsonNode info3 = third.snapshot(third.players.get(seats3.get("女巫").get(0) - 1)).path("nightInfo");
        Assertions.assertTrue(info3.path("knifeSeat").isNull() || info3.path("knifeSeat").isMissingNode(), "都没选目标是空刀：" + info3);
    }

    @Test
    void witchLosesTheKnifeWithHerAntidote() throws Exception {
        // roles/女巫.md：只在还有解药时告知刀口，解药用完后不再告知；毒药照常能用
        Game game = autoGame12(false);
        Map<String, List<Integer>> seats = game.seatsByRole();
        int witch = seats.get("女巫").get(0);
        List<Integer> villagers = seats.get("平民");
        for (int wolf : seats.get("狼人")) {
            game.act(wolf, "kill", villagers.get(0));
        }
        Assertions.assertEquals("witch", game.expireDeadline().path("currentStep").path("kind").asText());
        game.act(witch, "save", villagers.get(0));
        JsonNode dawn = game.snapshot(game.host);
        for (int i = 0; i < 8 && "night_action".equals(dawn.path("currentPhase").asText()); i++) {
            dawn = game.expireDeadline();
        }
        Assertions.assertEquals("day_announce", dawn.path("currentPhase").asText(), dawn.toString());
        toNextNight(game);
        untilStep(game, "wolf");
        for (int wolf : seats.get("狼人")) {
            game.act(wolf, "kill", villagers.get(1));
        }
        Assertions.assertEquals("witch", game.expireDeadline().path("currentStep").path("kind").asText());
        JsonNode info = game.snapshot(game.players.get(witch - 1)).path("nightInfo");
        Assertions.assertTrue(info.path("knifeSeat").isNull() || info.path("knifeSeat").isMissingNode(), "没解药不告知刀口：" + info);
        Assertions.assertFalse(info.path("antidote").asBoolean(), info.toString());
        Assertions.assertTrue(info.path("poison").asBoolean(), info.toString());
        Assertions.assertEquals(0, game.act(witch, "poison", villagers.get(1)).path("code").asInt());
    }

    @Test
    void seerSeesTheResultRightAway() throws Exception {
        Game game = autoGame12(false);
        Map<String, List<Integer>> seats = game.seatsByRole();
        int seer = seats.get("预言家").get(0);
        int wolf = seats.get("狼人").get(0);
        game.expireDeadline();
        game.expireDeadline();
        Assertions.assertEquals("seer", game.snapshot(game.host).path("currentStep").path("kind").asText());
        game.act(seer, "check", wolf);
        JsonNode info = game.snapshot(game.players.get(seer - 1)).path("nightInfo");
        Assertions.assertEquals(wolf, info.path("checkedSeat").asInt(), info.toString());
        Assertions.assertEquals("狼人", info.path("checkResult").asText());
    }

    @Test
    void nonWolvesNeverSeeWolfInfo() throws Exception {
        Game game = autoGame12(false);
        Map<String, List<Integer>> seats = game.seatsByRole();
        game.act(seats.get("狼人").get(0), "kill", seats.get("平民").get(1));
        for (int seat : List.of(seats.get("平民").get(0), seats.get("预言家").get(0), seats.get("女巫").get(0))) {
            JsonNode info = game.snapshot(game.players.get(seat - 1)).path("nightInfo");
            Assertions.assertTrue(info.isNull() || (info.path("teammateSeats").isEmpty() && info.path("wolfPicks").isEmpty()
                    && info.path("knifeSeat").isNull()), seat + " 号看到了 " + info);
        }
        Assertions.assertTrue(game.snapshot(game.host).path("nightInfo").isNull(), "法官已经有全量 nightActions");
    }

    @Test
    void othersNeverSeeAHoldersSubmission() throws Exception {
        Game game = autoGame(false);
        Map<String, List<Integer>> seats = game.seatsByRole();
        int guard = seats.get("守卫").get(0);
        Assertions.assertEquals("guard", game.snapshot(game.host).path("currentStep").path("kind").asText());
        game.act(guard, "guard", seats.get("平民").get(0));
        for (int seat = 1; seat <= game.players.size(); seat++) {
            if (seat == guard) {
                continue;
            }
            JsonNode info = game.snapshot(game.players.get(seat - 1)).path("nightInfo");
            Assertions.assertTrue(info.isNull() || info.isMissingNode()
                    || (info.path("myAction").isNull() && info.path("myTarget").isNull() && info.path("lastGuardSeat").isNull()),
                    seat + " 号看到了守卫的提交：" + info);
        }
    }

    // ---- 狼王（三期 A）----

    @Test
    void wolfKingBoardIsFullAndJoinable() throws Exception {
        String board = mockMvc.perform(get("/api/boards/" + WOLF_KING_BOARD_ID)).andReturn().getResponse().getContentAsString();
        Assertions.assertEquals("full", objectMapper.readTree(board).path("data").path("judgeSupportLevel").asText(), board);
        Game game = joinedGame(WOLF_KING_BOARD_ID, 12);
        Assertions.assertEquals("playing", game.snapshot(game.host).path("roomStatus").asText());
    }

    @Test
    void knifedWolfKingShoots() throws Exception {
        // 2026-11-12 用户改定：狼王可以自刀，被刀照样能开枪；只有被毒、最后一狼不能
        Game game = wolfKingGame();
        Map<String, List<Integer>> seats = game.seatsByRole();
        int wolfKing = seats.get("黑狼王").get(0);
        int villager = seats.get("平民").get(0);
        JsonNode day = game.finishNight(wolfKing, null);
        Assertions.assertFalse(game.player(day, wolfKing).path("alive").asBoolean(), day.toString());
        JsonNode ask = game.snapshot(game.players.get(wolfKing - 1)).path("pending");
        Assertions.assertEquals("skill_ask", ask.path("kind").asText(), ask.toString());
        Assertions.assertEquals("[\"use\",\"skip\"]", ask.path("options").toString(), "自刀的狼王照样能开枪");
        game.skill(wolfKing, "{\"decision\":\"use\"}");
        JsonNode shot = game.skill(wolfKing, "{\"decision\":\"use\",\"targetSeatNo\":" + villager + "}");
        Assertions.assertFalse(game.player(shot, villager).path("alive").asBoolean());
    }

    @Test
    void wolfKingMayKnifeHimself() throws Exception {
        Game game = wolfKingGame();
        Map<String, List<Integer>> seats = game.seatsByRole();
        int wolfKing = seats.get("黑狼王").get(0);
        int wolf = seats.get("狼人").get(0);
        Assertions.assertEquals("wolf", game.expireDeadline().path("currentStep").path("kind").asText());
        game.act(wolfKing, "kill", wolfKing);
        game.act(wolf, "kill", wolf);
        // 本人快照里带自己的机制（面板据此禁用狼王自刀），别人看不到
        Assertions.assertEquals("wolf_king", game.snapshot(game.players.get(wolfKing - 1)).path("selfPlayer").path("mechanic").asText());
        JsonNode othersView = game.player(game.snapshot(game.players.get(wolf - 1)), wolfKing);
        Assertions.assertTrue(othersView.path("mechanic").isNull() || othersView.path("mechanic").isMissingNode(), othersView.toString());
    }

    @Test
    void wolfKingSelfDestructsWithoutShooting() throws Exception {
        Game game = wolfKingGame();
        Map<String, List<Integer>> seats = game.seatsByRole();
        int wolfKing = seats.get("黑狼王").get(0);
        game.finishNight(seats.get("平民").get(0), null);
        game.skipPending();
        Assertions.assertEquals("day_speech", game.advance(game.host, "{\"action\":\"start_speech\"}").path("data").path("currentPhase").asText());
        Assertions.assertTrue(game.snapshot(game.players.get(wolfKing - 1)).path("dayInfo").path("canSelfDestruct").asBoolean());
        JsonNode explode = game.call(game.players.get(wolfKing - 1), "/self-destruct", null);
        Assertions.assertEquals(0, explode.path("code").asInt(), explode.toString());
        Assertions.assertEquals("last_words", explode.path("data").path("currentPending").path("kind").asText(), "自爆不问技能，只有遗言");
        Assertions.assertEquals("night_action", skipLastWords(game).path("currentPhase").asText(), "遗言完直接天黑");
        Assertions.assertFalse(game.player(explode.path("data"), wolfKing).path("alive").asBoolean());
    }

    @Test
    void exiledWolfKingShootsBeforeTheGameMovesOn() throws Exception {
        Game game = wolfKingGame();
        Map<String, List<Integer>> seats = game.seatsByRole();
        int wolfKing = seats.get("黑狼王").get(0);
        game.finishNight();
        JsonNode out = exile(game, seats.get("平民"), wolfKing);
        Assertions.assertEquals("skill_pending", out.path("currentPhase").asText(), out.toString());
        Assertions.assertEquals("[\"use\",\"skip\"]", game.snapshot(game.players.get(wolfKing - 1)).path("pending").path("options").toString());
    }

    @Test
    void poisonedWolfKingOnlyConfirms() throws Exception {
        Game game = wolfKingGame();
        int wolfKing = game.seatsByRole().get("黑狼王").get(0);
        JsonNode day = game.finishNight(null, wolfKing);
        Assertions.assertFalse(game.player(day, wolfKing).path("alive").asBoolean(), day.toString());
        JsonNode ask = game.snapshot(game.players.get(wolfKing - 1)).path("pending");
        Assertions.assertEquals("[\"skip\"]", ask.path("options").toString(), "被毒的狼王不能开枪");
    }

    @Test
    void forcedOutWolfKingOnlyConfirms() throws Exception {
        Game game = wolfKingGame();
        int wolfKing = game.seatsByRole().get("黑狼王").get(0);
        JsonNode out = game.advance(game.host, "{\"action\":\"force_out\",\"forceOutSeatNo\":" + wolfKing + "}");
        Assertions.assertEquals(0, out.path("code").asInt(), out.toString());
        Assertions.assertEquals("[\"skip\"]", game.snapshot(game.players.get(wolfKing - 1)).path("pending").path("options").toString(),
                "被点杀的狼王不能开枪");
    }

    @Test
    void wolfKingShotHunterShootsBack() throws Exception {
        Game game = wolfKingGame();
        Map<String, List<Integer>> seats = game.seatsByRole();
        int wolfKing = seats.get("黑狼王").get(0);
        int hunter = seats.get("猎人").get(0);
        game.finishNight();
        exile(game, seats.get("平民"), wolfKing);
        game.skill(wolfKing, "{\"decision\":\"use\"}");
        game.skill(wolfKing, "{\"decision\":\"use\",\"targetSeatNo\":" + hunter + "}");
        JsonNode ask = game.snapshot(game.players.get(hunter - 1)).path("pending");
        Assertions.assertEquals("skill_ask", ask.path("kind").asText(), ask.toString());
        Assertions.assertEquals("[\"use\",\"skip\"]", ask.path("options").toString(), "被狼王带走的猎人没中毒，可以接着开枪");
    }

    @Test
    void hunterShotWolfKingShoots() throws Exception {
        Game game = wolfKingGame();
        Map<String, List<Integer>> seats = game.seatsByRole();
        int wolfKing = seats.get("黑狼王").get(0);
        int hunter = seats.get("猎人").get(0);
        game.finishNight(hunter, null);
        game.skill(hunter, "{\"decision\":\"use\"}");
        game.skill(hunter, "{\"decision\":\"use\",\"targetSeatNo\":" + wolfKing + "}");
        JsonNode ask = game.snapshot(game.players.get(wolfKing - 1)).path("pending");
        Assertions.assertEquals("skill_ask", ask.path("kind").asText(), ask.toString());
        Assertions.assertEquals("[\"use\",\"skip\"]", ask.path("options").toString(), "被猎人带走的狼王（不是最后一只狼）可以开枪");
    }

    @Test
    void hunterAndWolfKingAnnounceTheSameWay() throws Exception {
        Game game = wolfKingGame();
        Map<String, List<Integer>> seats = game.seatsByRole();
        int hunter = seats.get("猎人").get(0);
        int villager = seats.get("平民").get(0);
        game.finishNight(hunter, null);
        game.skill(hunter, "{\"decision\":\"use\"}");
        String announcement = game.skill(hunter, "{\"decision\":\"use\",\"targetSeatNo\":" + villager + "}").path("latestAnnouncement").asText();
        Assertions.assertTrue(announcement.matches("\\d+号发动技能，带走了\\d+号"), announcement);
        Assertions.assertFalse(announcement.contains("开枪"), announcement);
    }

    @Test
    void wolfKingAloneEndsTheWolfStepOnceHeConfirms() throws Exception {
        Game game = wolfKingGame();
        Map<String, List<Integer>> seats = game.seatsByRole();
        int wolfKing = seats.get("黑狼王").get(0);
        Assertions.assertEquals("guard", game.snapshot(game.host).path("currentStep").path("kind").asText());
        markDead(game, seats.get("狼人"));
        Assertions.assertEquals("wolf", game.expireDeadline().path("currentStep").path("kind").asText());
        game.act(wolfKing, "kill", seats.get("平民").get(0));
        Assertions.assertTrue(secondsToDeadline(game) > 10, "只选了不确定，照常等");
        game.act(wolfKing, "confirm", null);
        Assertions.assertTrue(secondsToDeadline(game) <= 5, "只剩狼王一只活狼：他确定了 5 秒后结束");
    }

    @Test
    void lastWolfKingOrHunterOutEndsAtOnce() throws Exception {
        Game good = wolfKingGame();
        Map<String, List<Integer>> seats = good.seatsByRole();
        markDead(good, seats.get("狼人"));
        good.finishNight();
        JsonNode goodWins = exile(good, seats.get("平民"), seats.get("黑狼王").get(0));
        Assertions.assertEquals("finished", goodWins.path("roomStatus").asText(), goodWins.toString());
        Assertions.assertEquals("好人", goodWins.path("winnerCamp").asText());
        Assertions.assertTrue(goodWins.path("pending").isNull() || goodWins.path("pending").isMissingNode(), "最后一只狼是狼王也不能开枪");

        Game wolves = wolfKingGame();
        Map<String, List<Integer>> wolfSeats = wolves.seatsByRole();
        markDead(wolves, List.of(wolfSeats.get("预言家").get(0), wolfSeats.get("女巫").get(0), wolfSeats.get("守卫").get(0)));
        wolves.finishNight();
        JsonNode wolvesWin = exile(wolves, wolfSeats.get("狼人"), wolfSeats.get("猎人").get(0));
        Assertions.assertEquals("finished", wolvesWin.path("roomStatus").asText(), wolvesWin.toString());
        Assertions.assertEquals("狼人", wolvesWin.path("winnerCamp").asText());
        Assertions.assertTrue(wolvesWin.path("pending").isNull() || wolvesWin.path("pending").isMissingNode(), "最后一个神职是猎人也不能开枪");
    }

    @Test
    void wolfKingGameRunsToTheEnd() throws Exception {
        Game game = wolfKingGame();
        Map<String, List<Integer>> seats = game.seatsByRole();
        List<Integer> wolfSide = new ArrayList<>(seats.get("狼人"));
        wolfSide.addAll(seats.get("黑狼王"));
        JsonNode view = game.snapshot(game.host);
        for (int i = 0; i < 60 && !"finished".equals(view.path("roomStatus").asText()); i++) {
            List<Integer> aliveGood = new ArrayList<>();
            List<Integer> aliveWolves = new ArrayList<>();
            for (JsonNode player : view.path("players")) {
                if (player.path("seatNo").isInt() && player.path("alive").asBoolean()) {
                    (wolfSide.contains(player.path("seatNo").asInt()) ? aliveWolves : aliveGood).add(player.path("seatNo").asInt());
                }
            }
            switch (view.path("currentPhase").asText()) {
                case "night_action" -> view = game.finishNight(aliveGood.get(0), null);
                case "skill_pending" -> view = game.skipPending();
                case "day_announce" -> view = game.advance(game.host, "{\"action\":\"start_speech\"}").path("data");
                case "day_speech" -> view = game.advance(game.host, "{\"action\":\"start_vote\"}").path("data");
                case "exile_vote", "vote_pk" -> {
                    for (int voter : aliveGood) {
                        game.vote(voter, aliveWolves.get(0));
                    }
                    view = game.advance(game.host, "{\"action\":\"resolve_vote\"}").path("data");
                }
                case "vote_result" -> view = game.advance(game.host, "{\"action\":\"continue\"}").path("data");
                default -> Assertions.fail("没想到的阶段：" + view);
            }
        }
        Assertions.assertEquals("finished", view.path("roomStatus").asText(), view.toString());
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

    // ---- 遗言（通用流程 12）----

    /** 房主把正在进行的遗言一条条跳过（遗言全场可见，房主能跳过）。返回房主快照。 */
    private JsonNode skipLastWords(Game game) throws Exception {
        JsonNode view = game.snapshot(game.host);
        for (int i = 0; i < 10 && "last_words".equals(view.path("currentPending").path("kind").asText()); i++) {
            JsonNode skipped = game.call(game.host, "/skill", "{\"decision\":\"skip\"}");
            Assertions.assertEquals(0, skipped.path("code").asInt(), skipped.toString());
            view = skipped.path("data");
        }
        return view;
    }

    /** 跳过当前待办，直到轮到 kind（或者待办处理完）。返回房主快照。 */
    private JsonNode pendingUntil(Game game, String kind) throws Exception {
        JsonNode view = game.snapshot(game.host);
        for (int i = 0; i < 10 && "skill_pending".equals(view.path("currentPhase").asText())
                && !kind.equals(view.path("currentPending").path("kind").asText()); i++) {
            view = game.expireDeadline();
        }
        return view;
    }

    @Test
    void firstNightDeathSpeaksAfterTheSkillAsk() throws Exception {
        Game game = autoGame(false);
        int villager = game.seatsByRole().get("平民").get(0);
        game.finishNight(villager, null);
        JsonNode mine = game.snapshot(game.players.get(villager - 1));
        Assertions.assertEquals("skill_ask", mine.path("pending").path("kind").asText(), "先问技能：" + mine.path("pending"));
        game.skill(villager, "{\"decision\":\"skip\"}");
        int other = villager == 1 ? 2 : 1;
        JsonNode othersView = game.snapshot(game.players.get(other - 1));
        Assertions.assertEquals("skill_pending", othersView.path("currentPhase").asText());
        Assertions.assertEquals("last_words", othersView.path("currentPending").path("kind").asText(), "遗言全场可见：" + othersView);
        Assertions.assertEquals(villager, othersView.path("currentPending").path("seatNo").asInt());
        Assertions.assertEquals("请" + villager + "号留遗言", othersView.path("latestAnnouncement").asText());
        long seconds = secondsToDeadline(game);
        Assertions.assertTrue(seconds >= 118 && seconds <= 120, "遗言 120 秒，轮到时才开始计时，实际 " + seconds);
        Assertions.assertEquals(4003, game.call(game.players.get(other - 1), "/skill", "{\"decision\":\"skip\"}").path("code").asInt(),
                "别人不能替他结束遗言");
        JsonNode done = game.skill(villager, "{\"decision\":\"skip\"}");
        Assertions.assertEquals("day_announce", done.path("currentPhase").asText(), "本人点结束遗言：" + done);
    }

    @Test
    void theHostMaySkipSomeonesLastWords() throws Exception {
        Game game = autoGame12(false);
        int villager = game.seatsByRole().get("平民").get(0);
        game.finishNight(villager, null);
        Assertions.assertEquals("last_words", pendingUntil(game, "last_words").path("currentPending").path("kind").asText());
        JsonNode skipped = game.call(game.host, "/skill", "{\"decision\":\"skip\"}");
        Assertions.assertEquals(0, skipped.path("code").asInt(), skipped.toString());
        Assertions.assertNotEquals("last_words", skipped.path("data").path("currentPending").path("kind").asText(), skipped.toString());
    }

    @Test
    void secondNightDeathsHaveNoLastWords() throws Exception {
        Game game = autoGame12(false);
        Map<String, List<Integer>> seats = game.seatsByRole();
        game.finishNight(seats.get("平民").get(0), null);
        toNextNight(game);
        int second = seats.get("平民").get(1);
        game.finishNight(second, null);
        game.skill(second, "{\"decision\":\"skip\"}");
        JsonNode after = game.snapshot(game.host);
        Assertions.assertEquals("day_announce", after.path("currentPhase").asText(), "第二夜死的没有遗言：" + after);
    }

    @Test
    void anExiledPlayerHasLastWords() throws Exception {
        Game game = autoGame12(false);
        Map<String, List<Integer>> seats = game.seatsByRole();
        game.finishNight();
        game.skipPending();
        int target = seats.get("平民").get(0);
        List<Integer> voters = new ArrayList<>();
        for (int seat = 1; seat <= 12; seat++) {
            if (seat != target) {
                voters.add(seat);
            }
        }
        exile(game, voters, target);
        JsonNode words = pendingUntil(game, "last_words");
        Assertions.assertEquals("last_words", words.path("currentPending").path("kind").asText(), words.toString());
        Assertions.assertEquals(target, words.path("currentPending").path("seatNo").asInt());
    }

    @Test
    void aJudgeKillHasNoLastWords() throws Exception {
        Game game = autoGame(false);
        int villager = game.seatsByRole().get("平民").get(0);
        game.finishNight();
        game.skipPending();
        JsonNode out = game.advance(game.host, "{\"action\":\"force_out\",\"forceOutSeatNo\":" + villager + "}");
        Assertions.assertEquals(0, out.path("code").asInt(), out.toString());
        game.skill(villager, "{\"decision\":\"skip\"}");
        JsonNode after = game.snapshot(game.host);
        Assertions.assertNotEquals("skill_pending", after.path("currentPhase").asText(), "点杀没有遗言：" + after);
    }

    @Test
    void anExplodedWolfHasSixtySecondsThenNightFalls() throws Exception {
        Game game = autoGame(false);
        int wolf = toDaySpeech(game);
        JsonNode data = game.call(game.players.get(wolf - 1), "/self-destruct", null).path("data");
        Assertions.assertEquals("skill_pending", data.path("currentPhase").asText(), data.toString());
        Assertions.assertEquals("last_words", data.path("currentPending").path("kind").asText(), "自爆不问技能，直接遗言");
        long seconds = secondsToDeadline(game);
        Assertions.assertTrue(seconds >= 58 && seconds <= 60, "自爆的狼 60 秒遗言，实际 " + seconds);
        JsonNode night = game.skill(wolf, "{\"decision\":\"skip\"}");
        Assertions.assertEquals("night_action", night.path("currentPhase").asText(), night.toString());
        Assertions.assertEquals(2, night.path("currentDay").asInt());
    }

    @Test
    void shotThenLastWordsThenTheBadge() throws Exception {
        // 猎人当了警长，第二夜被刀：问技能 → 开枪带走平民 → 平民问技能 → 平民遗言（白天被带走）→ 猎人移交警徽。
        // 猎人第二夜死，没有遗言
        Game game = autoGame12(true);
        Map<String, List<Integer>> seats = game.seatsByRole();
        int hunter = seats.get("猎人").get(0);
        int villager = seats.get("平民").get(1);
        game.finishNight(seats.get("平民").get(0), null);
        game.sheriff(hunter, "{\"candidate\":true}");
        game.advance(game.host, "{\"action\":\"close_sheriff_signup\"}");
        Assertions.assertEquals(hunter, game.snapshot(game.host).path("sheriffSeatNo").asInt(), "只有一人上警直接当选");
        toNextNight(game);
        game.finishNight(hunter, null);
        Assertions.assertEquals("skill_ask", game.snapshot(game.players.get(hunter - 1)).path("pending").path("kind").asText());
        game.skill(hunter, "{\"decision\":\"use\"}");
        game.skill(hunter, "{\"decision\":\"use\",\"targetSeatNo\":" + villager + "}");
        Assertions.assertEquals("skill_ask", game.snapshot(game.players.get(villager - 1)).path("pending").path("kind").asText(),
                "被带走的人先问技能");
        game.skill(villager, "{\"decision\":\"skip\"}");
        JsonNode words = game.snapshot(game.host);
        Assertions.assertEquals("last_words", words.path("currentPending").path("kind").asText(), words.toString());
        Assertions.assertEquals(villager, words.path("currentPending").path("seatNo").asInt(), "被带走的人有遗言，第二夜死的猎人没有");
        game.skill(villager, "{\"decision\":\"skip\"}");
        Assertions.assertEquals("badge", game.snapshot(game.players.get(hunter - 1)).path("pending").path("kind").asText(),
                "最后移交警徽");
    }

    // ---- 自爆 ----

    @Test
    void wolfExplodesDuringDaySpeechAndNightFalls() throws Exception {
        Game game = autoGame(false);
        int wolf = toDaySpeech(game);
        JsonNode response = game.call(game.players.get(wolf - 1), "/self-destruct", null);
        Assertions.assertEquals(0, response.path("code").asInt(), response.toString());
        Assertions.assertEquals("last_words", response.path("data").path("currentPending").path("kind").asText(), "自爆的狼先留 60 秒遗言");
        JsonNode data = game.skill(wolf, "{\"decision\":\"skip\"}");
        Assertions.assertEquals("night_action", data.path("currentPhase").asText());
        Assertions.assertEquals(2, data.path("currentDay").asInt());
        Assertions.assertEquals(wolf + "号自爆，天黑请闭眼", data.path("latestAnnouncement").asText());
        Assertions.assertFalse(game.player(data, wolf).path("alive").asBoolean(), "自爆的狼当场出局（2026-11-12 用户改定）");
        JsonNode skills = data.path("rounds").get(0).path("skills");
        Assertions.assertTrue(skills.toString().contains("\"kind\":\"self_destruct\""), skills.toString());
        Assertions.assertTrue(skills.toString().contains("\"actorSeatNo\":" + wolf), skills.toString());

        // 当晚他不在狼队那一步
        untilStep(game, "wolf");
        int other = game.seatsByRole().get("狼人").stream().filter(seat -> seat != wolf).findFirst().orElseThrow();
        JsonNode picks = game.snapshot(game.players.get(other - 1)).path("nightInfo").path("wolfPicks");
        Assertions.assertFalse(picks.toString().contains("\"seatNo\":" + wolf + ","), picks.toString());
        int villager = game.seatsByRole().get("平民").get(1);
        JsonNode day = game.finishNight(villager, null);
        Assertions.assertFalse(game.player(day, villager).path("alive").asBoolean(), day.toString());
        Assertions.assertEquals("[" + villager + "]", nightDeathsOf(day, 2).toString());
    }

    /** 某一天的昨夜死讯（对局记录里的 nightDeaths）。 */
    private JsonNode nightDeathsOf(JsonNode snapshot, int dayNo) {
        for (JsonNode round : snapshot.path("rounds")) {
            if (round.path("dayNo").asInt() == dayNo) {
                return round.path("nightDeaths");
            }
        }
        return objectMapper.createArrayNode();
    }

    @Test
    void onlyAliveSelfDestructSeatsMayExplode() throws Exception {
        Game game = autoGame(false);
        Map<String, List<Integer>> roles = game.seatsByRole();
        int wolf = toDaySpeech(game);
        int villager = roles.get("平民").stream().filter(seat -> {
            try {
                return game.player(game.snapshot(game.host), seat).path("alive").asBoolean();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }).findFirst().orElseThrow();
        Assertions.assertEquals(4003, game.call(game.players.get(villager - 1), "/self-destruct", null).path("code").asInt(), "平民不能自爆");
        int deadWolf = roles.get("狼人").stream().filter(seat -> seat != wolf).findFirst().orElseThrow();
        markDead(game, List.of(deadWolf));
        Assertions.assertEquals(4003, game.call(game.players.get(deadWolf - 1), "/self-destruct", null).path("code").asInt(), "死人不能自爆");

        Assertions.assertEquals(0, game.advance(game.host, "{\"action\":\"pause\"}").path("code").asInt());
        Assertions.assertEquals(4002, game.call(game.players.get(wolf - 1), "/self-destruct", null).path("code").asInt(), "暂停中不能自爆");
        Assertions.assertEquals(0, game.advance(game.host, "{\"action\":\"resume\"}").path("code").asInt());

        Assertions.assertEquals(0, game.advance(game.host, "{\"action\":\"start_vote\"}").path("code").asInt());
        Assertions.assertEquals(4003, game.call(game.players.get(wolf - 1), "/self-destruct", null).path("code").asInt(), "投票阶段不能自爆");
    }

    @Test
    void onlyTheWolfSeesItCanExplode() throws Exception {
        Game game = autoGame(false);
        int wolf = toDaySpeech(game);
        int villager = game.seatsByRole().get("预言家").get(0);
        Assertions.assertTrue(game.snapshot(game.players.get(wolf - 1)).path("dayInfo").path("canSelfDestruct").asBoolean());
        Assertions.assertFalse(game.snapshot(game.players.get(villager - 1)).path("dayInfo").path("canSelfDestruct").asBoolean());
        Assertions.assertFalse(game.snapshot(game.host).path("dayInfo").path("canSelfDestruct").asBoolean());
    }

    @Test
    void lastWolfExplodingEndsTheGame() throws Exception {
        Game game = autoGame(false);
        int wolf = toDaySpeech(game);
        markDead(game, game.seatsByRole().get("狼人").stream().filter(seat -> seat != wolf).toList());
        JsonNode response = game.call(game.players.get(wolf - 1), "/self-destruct", null);
        Assertions.assertEquals(0, response.path("code").asInt(), response.toString());
        Assertions.assertEquals("finished", response.path("data").path("roomStatus").asText());
        Assertions.assertEquals("好人", response.path("data").path("winnerCamp").asText());
        Assertions.assertTrue(response.path("data").path("pending").isMissingNode() || response.path("data").path("pending").isNull());
        Assertions.assertFalse(game.player(response.path("data"), wolf).path("alive").asBoolean(), "当场结束时他出局");
    }

    @Test
    void sheriffExplodesThenPassesTheBadgeThenNight() throws Exception {
        for (boolean timeout : List.of(false, true)) {
            Game game = autoGame(true);
            int wolf = electWolfSheriff(game);
            JsonNode response = game.call(game.players.get(wolf - 1), "/self-destruct", null);
            Assertions.assertEquals(0, response.path("code").asInt(), response.toString());
            Assertions.assertEquals("skill_pending", response.path("data").path("currentPhase").asText());
            Assertions.assertEquals("last_words", response.path("data").path("currentPending").path("kind").asText(), "自爆不问技能，先遗言");
            game.skill(wolf, "{\"decision\":\"skip\"}");
            JsonNode own = game.snapshot(game.players.get(wolf - 1)).path("pending");
            Assertions.assertEquals("badge", own.path("kind").asText(), "遗言完再移交警徽：" + own);
            Assertions.assertEquals(4003, game.call(game.players.get(wolf - 1), "/self-destruct", null).path("code").asInt(), "不能再爆一次");
            int heir = game.seatsByRole().get("预言家").get(0);
            JsonNode after = timeout ? game.expireDeadline()
                    : game.skill(wolf, "{\"decision\":\"use\",\"targetSeatNo\":" + heir + "}");
            Assertions.assertEquals("night_action", after.path("currentPhase").asText(), after.toString());
            Assertions.assertEquals(2, after.path("currentDay").asInt());
            Assertions.assertFalse(game.player(after, wolf).path("alive").asBoolean(), "自爆当场出局");
            if (!timeout) {
                Assertions.assertEquals(heir, after.path("sheriffSeatNo").asInt());
            }
        }
    }

    @Test
    void secondExplosionInTheSameDayIsRejected() throws Exception {
        Game game = autoGame(false);
        int wolf = toDaySpeech(game);
        int other = game.seatsByRole().get("狼人").stream().filter(seat -> seat != wolf).findFirst().orElseThrow();
        Assertions.assertEquals(0, game.call(game.players.get(wolf - 1), "/self-destruct", null).path("code").asInt());
        Assertions.assertEquals(4003, game.call(game.players.get(other - 1), "/self-destruct", null).path("code").asInt());
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM judge_action_events WHERE room_id = ? AND action_type = 'self_destruct'",
                Integer.class, game.roomId);
        Assertions.assertEquals(1, count);
    }

    @Test
    void explodingOnStageDefersTheElection() throws Exception {
        Game game = autoGame(true);
        Map<String, List<Integer>> roles = game.seatsByRole();
        int wolf = roles.get("狼人").get(0);
        int knifed = roles.get("平民").get(0);
        toSheriffSpeech(game, knifed, wolf, roles.get("预言家").get(0), roles.get("守卫").get(0));
        JsonNode exploded = game.call(game.players.get(wolf - 1), "/self-destruct", null);
        Assertions.assertEquals(0, exploded.path("code").asInt(), exploded.toString());
        Assertions.assertEquals("skill_pending", exploded.path("data").path("currentPhase").asText(), "先问昨夜死者的技能");
        game.skill(knifed, "{\"decision\":\"skip\"}");
        JsonNode announce = skipLastWords(game);
        Assertions.assertEquals("day_announce", announce.path("currentPhase").asText(), "昨夜死者和自爆的狼都留完遗言：" + announce);
        Assertions.assertTrue(announce.path("nightNext").asBoolean());
        String text = announce.path("latestAnnouncement").asText();
        Assertions.assertTrue(text.contains(wolf + "号自爆，警长竞选推迟到明天"), text);
        Assertions.assertTrue(text.contains("昨夜出局：" + knifed + "号"), text);
        JsonNode night = game.advance(game.host, "{\"action\":\"start_night\"}");
        Assertions.assertEquals(0, night.path("code").asInt(), night.toString());
        Assertions.assertEquals("night_action", night.path("data").path("currentPhase").asText());
        Assertions.assertEquals(2, night.path("data").path("currentDay").asInt());
    }

    @Test
    void explodedAnnounceOnlyGoesToNight() throws Exception {
        Game game = autoGame(true);
        Map<String, List<Integer>> roles = game.seatsByRole();
        int wolf = roles.get("狼人").get(0);
        toSheriffSpeech(game, null, wolf, roles.get("预言家").get(0));
        JsonNode exploded = game.call(game.players.get(wolf - 1), "/self-destruct", null);
        Assertions.assertEquals("last_words", exploded.path("data").path("currentPending").path("kind").asText(), "自爆的狼 60 秒遗言");
        exploded = game.call(game.host, "/skill", "{\"decision\":\"skip\"}");
        Assertions.assertEquals("day_announce", exploded.path("data").path("currentPhase").asText(), "平安夜也停在公布死讯");
        Assertions.assertTrue(exploded.path("data").path("nightNext").asBoolean());
        // 旧版小程序的房主只有「开始发言」：当作天黑，不能让房间卡住
        JsonNode oldClient = game.advance(game.host, "{\"action\":\"start_speech\"}");
        Assertions.assertEquals(0, oldClient.path("code").asInt(), oldClient.toString());
        Assertions.assertEquals("night_action", oldClient.path("data").path("currentPhase").asText());

        Game normal = autoGame(false);
        normal.finishNight(normal.seatsByRole().get("平民").get(0), null);
        normal.skipPending();
        Assertions.assertEquals(4002, normal.advance(normal.host, "{\"action\":\"start_night\"}").path("code").asInt(), "没人自爆不能直接天黑");
    }

    @Test
    void knifedWolfCanStillExplodeOnStage() throws Exception {
        Game game = autoGame(true);
        Map<String, List<Integer>> roles = game.seatsByRole();
        int wolf = roles.get("狼人").get(0);
        JsonNode speech = toSheriffSpeech(game, wolf, wolf, roles.get("预言家").get(0));
        Assertions.assertTrue(game.player(speech, wolf).path("alive").asBoolean(), "昨夜死讯还没公布");
        Assertions.assertTrue(game.snapshot(game.players.get(wolf - 1)).path("dayInfo").path("canSelfDestruct").asBoolean());
        JsonNode data = game.call(game.players.get(wolf - 1), "/self-destruct", null).path("data");
        Assertions.assertFalse(game.player(data, wolf).path("alive").asBoolean());
        String text = data.path("latestAnnouncement").asText();
        Assertions.assertEquals(1, text.split(wolf + "号", -1).length - 1, text);
        Assertions.assertFalse(nightDeathsOf(data, 1).toString().contains(String.valueOf(wolf)), data.path("rounds").toString());
        Integer deaths = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM judge_room_players WHERE room_id = ? AND alive = 0",
                Integer.class, game.roomId);
        Assertions.assertEquals(1, deaths, "只算一次出局（算自爆）");
    }

    @Test
    void deferredElectionResumesOnDayTwoWithTheSameCandidates() throws Exception {
        Game game = autoGame(true);
        Map<String, List<Integer>> roles = game.seatsByRole();
        int wolf = roles.get("狼人").get(0);
        int seer = roles.get("预言家").get(0);
        int guard = roles.get("守卫").get(0);
        List<Integer> villagers = roles.get("平民");
        toSheriffSpeech(game, null, wolf, seer, guard, villagers.get(1));
        game.sheriff(guard, "{\"withdraw\":true}");
        game.call(game.players.get(wolf - 1), "/self-destruct", null);
        skipLastWords(game);
        game.advance(game.host, "{\"action\":\"start_night\"}");

        JsonNode dayTwo = game.finishNight(villagers.get(2), null);
        Assertions.assertEquals("sheriff_speech", dayTwo.path("currentPhase").asText(), dayTwo.toString());
        Assertions.assertTrue(dayTwo.path("latestAnnouncement").asText().contains("昨天的警长竞选继续"), dayTwo.path("latestAnnouncement").asText());
        Assertions.assertEquals(java.util.stream.Stream.of(seer, villagers.get(1)).sorted().toList().toString().replace(" ", ""),
                dayTwo.path("sheriffCandidates").toString());
        Assertions.assertTrue(game.player(dayTwo, villagers.get(2)).path("alive").asBoolean(), "第二晚死讯竞选完才公布");

        Assertions.assertEquals("sheriff_vote", game.advance(game.host, "{\"action\":\"start_sheriff_vote\"}").path("data").path("currentPhase").asText());
        for (int seat = 1; seat <= PLAYER_COUNT; seat++) {
            // 上过警的（含退水的守卫）不能投；警下全投完会自动结算
            if (seat != wolf && seat != seer && seat != guard && seat != villagers.get(1)) {
                game.sheriff(seat, "{\"targetSeatNo\":" + seer + "}");
            }
        }
        JsonNode elected = game.snapshot(game.host);
        if ("sheriff_vote".equals(elected.path("currentPhase").asText())) {
            elected = game.advance(game.host, "{\"action\":\"resolve_sheriff_vote\"}").path("data");
        }
        Assertions.assertEquals(seer, elected.path("sheriffSeatNo").asInt(), elected.toString());
        Assertions.assertFalse(game.player(elected, villagers.get(2)).path("alive").asBoolean());
    }

    @Test
    void secondExplosionOnStageLosesTheBadge() throws Exception {
        Game game = autoGame(true);
        Map<String, List<Integer>> roles = game.seatsByRole();
        List<Integer> wolves = roles.get("狼人");
        int seer = roles.get("预言家").get(0);
        toSheriffSpeech(game, null, wolves.get(0), wolves.get(1), seer);
        game.call(game.players.get(wolves.get(0) - 1), "/self-destruct", null);
        skipLastWords(game);
        game.advance(game.host, "{\"action\":\"start_night\"}");
        JsonNode dayTwo = game.finishNight();
        Assertions.assertEquals("sheriff_speech", dayTwo.path("currentPhase").asText(), dayTwo.toString());
        JsonNode second = game.call(game.players.get(wolves.get(1) - 1), "/self-destruct", null).path("data");
        Assertions.assertEquals("last_words", second.path("currentPending").path("kind").asText());
        Assertions.assertEquals("请" + wolves.get(1) + "号留遗言", second.path("latestAnnouncement").asText());
        JsonNode announced = skipLastWords(game);
        Assertions.assertEquals("day_announce", announced.path("currentPhase").asText());
        Assertions.assertTrue(announced.path("latestAnnouncement").asText().contains(wolves.get(1) + "号自爆，警徽流失"),
                "遗言完公告恢复：" + announced.path("latestAnnouncement").asText());
        game.advance(game.host, "{\"action\":\"start_night\"}");
        JsonNode dayThree = game.finishNight();
        Assertions.assertEquals("day_announce", dayThree.path("currentPhase").asText(), "警徽流失后不再竞选");
        Assertions.assertTrue(dayThree.path("sheriffSeatNo").isNull() || dayThree.path("sheriffSeatNo").isMissingNode());
    }

    @Test
    void deferredElectionWithOneCandidateLeftElectsThem() throws Exception {
        Game game = autoGame(true);
        Map<String, List<Integer>> roles = game.seatsByRole();
        int wolf = roles.get("狼人").get(0);
        int seer = roles.get("预言家").get(0);
        int villager = roles.get("平民").get(0);
        toSheriffSpeech(game, null, wolf, seer, villager);
        game.call(game.players.get(wolf - 1), "/self-destruct", null);
        skipLastWords(game);
        game.advance(game.host, "{\"action\":\"start_night\"}");
        markDead(game, List.of(villager));
        JsonNode dayTwo = game.finishNight();
        Assertions.assertEquals("day_announce", dayTwo.path("currentPhase").asText(), dayTwo.toString());
        Assertions.assertEquals(seer, dayTwo.path("sheriffSeatNo").asInt());
    }

    @Test
    void deferredElectionWithNoneLeftLosesTheBadge() throws Exception {
        Game game = autoGame(true);
        Map<String, List<Integer>> roles = game.seatsByRole();
        int wolf = roles.get("狼人").get(0);
        int villager = roles.get("平民").get(0);
        int other = roles.get("平民").get(1);
        toSheriffSpeech(game, null, wolf, villager, other);
        game.call(game.players.get(wolf - 1), "/self-destruct", null);
        skipLastWords(game);
        game.advance(game.host, "{\"action\":\"start_night\"}");
        markDead(game, List.of(villager, other));
        JsonNode dayTwo = game.finishNight();
        Assertions.assertEquals("day_announce", dayTwo.path("currentPhase").asText(), dayTwo.toString());
        Assertions.assertTrue(dayTwo.path("latestAnnouncement").asText().contains("警徽流失"), dayTwo.path("latestAnnouncement").asText());
    }

    @Test
    void explodingAfterTheElectionIsPlain() throws Exception {
        Game game = autoGame(true);
        electWolfSheriff(game);
        int other = game.seatsByRole().get("狼人").get(1);
        game.call(game.players.get(other - 1), "/self-destruct", null);
        JsonNode night = skipLastWords(game);
        Assertions.assertEquals("night_action", night.path("currentPhase").asText(), night.toString());
        JsonNode dayTwo = game.finishNight();
        Assertions.assertEquals("day_announce", dayTwo.path("currentPhase").asText(), "竞选结束后的自爆不影响警徽");
    }

    @Test
    void lastWolfExplodingOnStageEndsTheGame() throws Exception {
        Game game = autoGame(true);
        Map<String, List<Integer>> roles = game.seatsByRole();
        int wolf = roles.get("狼人").get(0);
        toSheriffSpeech(game, null, wolf, roles.get("预言家").get(0));
        markDead(game, roles.get("狼人").stream().filter(seat -> seat != wolf).toList());
        JsonNode data = game.call(game.players.get(wolf - 1), "/self-destruct", null).path("data");
        Assertions.assertEquals("finished", data.path("roomStatus").asText(), data.path("latestAnnouncement").asText());
        Assertions.assertEquals("好人", data.path("winnerCamp").asText());
        Assertions.assertFalse(game.player(data, wolf).path("alive").asBoolean());
    }

    @Test
    void otherCandidatesVoteInTheSheriffPk() throws Exception {
        Game game = autoGame(true);
        Map<String, List<Integer>> roles = game.seatsByRole();
        List<Integer> villagers = roles.get("平民");
        int a = roles.get("预言家").get(0);
        int b = roles.get("守卫").get(0);
        int c = villagers.get(0);
        int withdrawn = villagers.get(1);
        toSheriffSpeech(game, null, a, b, c, withdrawn);
        game.sheriff(withdrawn, "{\"withdraw\":true}");
        game.advance(game.host, "{\"action\":\"start_sheriff_vote\"}");
        List<Integer> below = new ArrayList<>();
        for (int seat = 1; seat <= PLAYER_COUNT; seat++) {
            if (!List.of(a, b, c, withdrawn).contains(seat)) {
                below.add(seat);
            }
        }
        Assertions.assertEquals(4003, game.call(game.players.get(c - 1), "/sheriff", "{\"targetSeatNo\":" + a + "}").path("code").asInt(),
                "第一轮只有警下能投");
        game.sheriff(below.get(0), "{\"targetSeatNo\":" + a + "}");
        game.sheriff(below.get(1), "{\"targetSeatNo\":" + a + "}");
        game.sheriff(below.get(2), "{\"targetSeatNo\":" + b + "}");
        game.sheriff(below.get(3), "{\"targetSeatNo\":" + b + "}");
        JsonNode pk = game.advance(game.host, "{\"action\":\"resolve_sheriff_vote\"}").path("data");
        Assertions.assertEquals("sheriff_pk", pk.path("currentPhase").asText(), pk.toString());

        JsonNode own = game.call(game.players.get(c - 1), "/sheriff", "{\"targetSeatNo\":" + a + "}");
        Assertions.assertEquals(0, own.path("code").asInt(), "PK 时其余警上的人也能投：" + own);
        Assertions.assertTrue(game.snapshot(game.players.get(c - 1)).path("dayInfo").path("canVote").asBoolean());
        Assertions.assertEquals(4003, game.call(game.players.get(withdrawn - 1), "/sheriff", "{\"targetSeatNo\":" + a + "}").path("code").asInt(),
                "退水的人不能投");
        Assertions.assertEquals(4003, game.call(game.players.get(a - 1), "/sheriff", "{\"targetSeatNo\":" + b + "}").path("code").asInt(),
                "PK 的人不能投");
        Assertions.assertFalse(game.snapshot(game.players.get(withdrawn - 1)).path("dayInfo").path("canVote").asBoolean());
    }

    /** 第一夜刀 knife（null = 平安夜），candidates 报名上警，截止进警上发言。返回警上发言时的房主快照。 */
    private JsonNode toSheriffSpeech(Game game, Integer knife, int... candidates) throws Exception {
        JsonNode signup = game.finishNight(knife, null);
        Assertions.assertEquals("sheriff_signup", signup.path("currentPhase").asText(), signup.toString());
        for (int seat : candidates) {
            game.sheriff(seat, "{\"candidate\":true}");
        }
        JsonNode speech = game.advance(game.host, "{\"action\":\"close_sheriff_signup\"}");
        Assertions.assertEquals("sheriff_speech", speech.path("data").path("currentPhase").asText(), speech.toString());
        return speech.path("data");
    }

    /** 第一夜刀一个平民，处理完待办，进白天发言。返回一只活着的狼。 */
    private int toDaySpeech(Game game) throws Exception {
        Map<String, List<Integer>> roles = game.seatsByRole();
        game.finishNight(roles.get("平民").get(0), null);
        game.skipPending();
        JsonNode speech = game.advance(game.host, "{\"action\":\"start_speech\"}");
        Assertions.assertEquals("day_speech", speech.path("data").path("currentPhase").asText(), speech.toString());
        return roles.get("狼人").get(0);
    }

    /** 第一夜刀一个平民，让一只狼和预言家上警，警下全投狼，进白天发言。返回当选警长的狼。 */
    private int electWolfSheriff(Game game) throws Exception {
        Map<String, List<Integer>> roles = game.seatsByRole();
        int wolf = roles.get("狼人").get(0);
        int seer = roles.get("预言家").get(0);
        game.finishNight(roles.get("平民").get(0), null);
        game.sheriff(wolf, "{\"candidate\":true}");
        game.sheriff(seer, "{\"candidate\":true}");
        Assertions.assertEquals("sheriff_speech", game.advance(game.host, "{\"action\":\"close_sheriff_signup\"}").path("data").path("currentPhase").asText());
        Assertions.assertEquals("sheriff_vote", game.advance(game.host, "{\"action\":\"start_sheriff_vote\"}").path("data").path("currentPhase").asText());
        for (int seat = 1; seat <= PLAYER_COUNT; seat++) {
            if (seat != wolf && seat != seer) {
                game.sheriff(seat, "{\"targetSeatNo\":" + wolf + "}");
            }
        }
        game.advance(game.host, "{\"action\":\"resolve_sheriff_vote\"}");
        game.skipPending();
        JsonNode speech = game.advance(game.host, "{\"action\":\"start_speech\"}");
        Assertions.assertEquals("day_speech", speech.path("data").path("currentPhase").asText(), speech.toString());
        Assertions.assertEquals(wolf, speech.path("data").path("sheriffSeatNo").asInt());
        return wolf;
    }

    private void expire(Game game) throws Exception {
        jdbcTemplate.update("UPDATE judge_rooms SET step_deadline = ? WHERE room_id = ?",
                java.sql.Timestamp.valueOf("2000-01-01 00:00:00"), game.roomId);
        game.snapshot(game.host);
    }

    // ---- 房间规则 ----

    @Test
    void createRoomAppliesTheRoomRules() throws Exception {
        Game game = lobbyGame("{\"boardId\":" + BOARD_ID + ",\"judgeMode\":\"observer\",\"autoJudge\":true,"
                + "\"rules\":{\"wolfSeconds\":40,\"roleSeconds\":20,\"speechSeconds\":90}}");
        game.readyAll();
        game.start();
        JsonNode snapshot = game.snapshot(game.host);
        Assertions.assertEquals(40, snapshot.path("rules").path("wolfSeconds").asInt());
        Assertions.assertEquals(90, snapshot.path("rules").path("speechSeconds").asInt());
        Assertions.assertEquals("守卫", snapshot.path("currentStep").path("roleName").asText());
        Assertions.assertTrue(secondsToDeadline(game) <= 20, "守卫那一步用单人时长 20 秒");
        expire(game);
        Assertions.assertEquals("狼人", game.snapshot(game.host).path("currentStep").path("roleName").asText());
        long wolf = secondsToDeadline(game);
        Assertions.assertTrue(wolf >= 68 && wolf <= 70, "首夜狼队 40 + 30 秒，实际 " + wolf);
    }

    @Test
    void rulesCanOnlyChangeInTheLobby() throws Exception {
        Game game = lobbyGame("{\"boardId\":" + BOARD_ID + ",\"judgeMode\":\"observer\",\"autoJudge\":true}");
        Assertions.assertEquals(0, putRules(game, game.host, "{\"speechSeconds\":90,\"sheriffEnabled\":true}").path("code").asInt());
        JsonNode lobby = game.snapshot(game.host);
        Assertions.assertEquals(90, lobby.path("rules").path("speechSeconds").asInt());
        Assertions.assertEquals(4003, putRules(game, game.players.get(0), "{\"speechSeconds\":60}").path("code").asInt(), "玩家不能改");
        game.readyAll();
        game.start();
        Assertions.assertEquals(4002, putRules(game, game.host, "{\"speechSeconds\":60}").path("code").asInt(), "开局后锁定");
    }

    @Test
    void outOfRangeRoomRulesAreRejected() throws Exception {
        Game game = lobbyGame("{\"boardId\":" + BOARD_ID + ",\"judgeMode\":\"observer\",\"autoJudge\":true}");
        Assertions.assertEquals(4000, putRules(game, game.host, "{\"speechSeconds\":10}").path("code").asInt());
        Assertions.assertEquals(4000, putRules(game, game.host, "{\"wolfSeconds\":200}").path("code").asInt());
        Assertions.assertEquals(4000, putRules(game, game.host, "{\"roleSeconds\":5}").path("code").asInt());
    }

    @Test
    void witchCannotSaveHerselfOnABoardThatKeepsTheDefault() throws Exception {
        Game game = autoGame12(false);
        Assertions.assertEquals("never", game.snapshot(game.host).path("rules").path("witchSelfSave").asText());
        Map<String, List<Integer>> seats = game.seatsByRole();
        int witch = seats.get("女巫").get(0);
        for (int wolf : seats.get("狼人")) {
            game.act(wolf, "kill", witch);
        }
        Assertions.assertEquals("witch", game.expireDeadline().path("currentStep").path("kind").asText());
        JsonNode selfSave = game.call(game.players.get(witch - 1), "/night-action", "{\"actionType\":\"save\",\"targetSeatNo\":" + witch + "}");
        Assertions.assertEquals(4000, selfSave.path("code").asInt(), selfSave.toString());
    }

    @Test
    void oldRoomWithFirstNightSelfSaveStillCannotSelfSave() throws Exception {
        Game game = autoGame12(false);
        jdbcTemplate.update("UPDATE judge_rooms SET rules_json = ? WHERE room_id = ?",
                "{\"winCondition\":\"side\",\"witchSelfSave\":\"first_night\",\"witchOnePotionPerNight\":false}", game.roomId);
        Map<String, List<Integer>> seats = game.seatsByRole();
        int witch = seats.get("女巫").get(0);
        for (int wolf : seats.get("狼人")) {
            game.act(wolf, "kill", witch);
        }
        Assertions.assertEquals("witch", game.expireDeadline().path("currentStep").path("kind").asText());
        JsonNode selfSave = game.call(game.players.get(witch - 1), "/night-action", "{\"actionType\":\"save\",\"targetSeatNo\":" + witch + "}");
        Assertions.assertEquals(4000, selfSave.path("code").asInt(), "全程不可自救，旧房间存的 first_night 也不算：" + selfSave);
        JsonNode rules = game.snapshot(game.host).path("rules");
        Assertions.assertEquals("never", rules.path("witchSelfSave").asText());
        Assertions.assertTrue(rules.path("witchOnePotionPerNight").asBoolean(), "一晚一瓶是全局规则");
    }

    @Test
    void roomRulesNeverChangeTheWinCondition() throws Exception {
        Game game = lobbyGame("{\"boardId\":" + BOARD_ID + ",\"judgeMode\":\"observer\",\"autoJudge\":true,"
                + "\"rules\":{\"winCondition\":\"city\",\"witchSelfSave\":\"first_night\"}}");
        JsonNode rules = game.snapshot(game.host).path("rules");
        Assertions.assertEquals("side", rules.path("winCondition").asText());
        Assertions.assertEquals("never", rules.path("witchSelfSave").asText(), "女巫自救跟板子走，建房请求改不了");
    }

    private JsonNode putRules(Game game, String token, String body) throws Exception {
        String response = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/judge/rooms/" + game.roomId + "/rules")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    private long secondsToDeadline(Game game) {
        java.time.LocalDateTime deadline = jdbcTemplate.queryForObject(
                "SELECT step_deadline FROM judge_rooms WHERE room_id = ?", java.time.LocalDateTime.class, game.roomId);
        return java.time.Duration.between(java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")), deadline).getSeconds();
    }

    @Test
    void wolvesWhoOnlyPickWaitForConfirmOrTheDeadline() throws Exception {
        Game game = autoGame(false);
        Map<String, List<Integer>> seats = game.seatsByRole();
        game.expireDeadline();
        int target = seats.get("平民").get(0);
        List<Integer> wolves = seats.get("狼人");
        for (int i = 0; i < wolves.size(); i++) {
            Assertions.assertEquals(1, game.snapshot(game.host).path("currentStep").path("index").asInt(), "狼没交齐之前不动");
            JsonNode submitted = game.call(game.players.get(wolves.get(i) - 1), "/night-action", "{\"actionType\":\"kill\",\"targetSeatNo\":" + target + "}");
            Assertions.assertEquals(0, submitted.path("code").asInt(), submitted.toString());
        }
        Assertions.assertEquals(1, game.snapshot(game.host).path("currentStep").path("index").asInt(), "狼都选了但没确定，不提前结束");
        Assertions.assertTrue(secondsToDeadline(game) > 10);
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
        game.finishNight(guard, null);
        game.skipPending();
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
        Assertions.assertEquals("skill_pending", game.finishNight(dead, null).path("currentPhase").asText());
        Assertions.assertEquals("last_words", game.expireDeadline().path("currentPending").path("kind").asText(), "技能询问超时当作不发动");
        Assertions.assertEquals("day_announce", game.expireDeadline().path("currentPhase").asText(), "遗言到点自动结束");
    }

    // ---- 通灵师机械狼人（种子板子 5，无法官）----

    private static final int MECH_WOLF_BOARD_ID = 5;

    private Game mechWolfGame() throws Exception {
        return joinedGame(MECH_WOLF_BOARD_ID, 12);
    }

    /** 座位本人提交一个夜间行动，返回整个响应（不断言成功）。 */
    private JsonNode mechAct(Game game, int seat, String action, int target) throws Exception {
        return game.call(game.players.get(seat - 1), "/night-action", "{\"actionType\":\"" + action + "\",\"targetSeatNo\":" + target + "}");
    }

    /** 天亮后：待办跳过，发言、投票没人投（无人出局），票型公布后入夜。 */
    private JsonNode toNextNight(Game game) throws Exception {
        game.skipPending();
        game.advance(game.host, "{\"action\":\"start_speech\"}");
        game.advance(game.host, "{\"action\":\"start_vote\"}");
        game.advance(game.host, "{\"action\":\"resolve_vote\"}");
        JsonNode night = game.advance(game.host, "{\"action\":\"continue\"}").path("data");
        Assertions.assertEquals("night_action", night.path("currentPhase").asText(), night.toString());
        return night;
    }

    private Map<String, Object> statusOf(Game game, int seat) throws Exception {
        String json = jdbcTemplate.queryForObject("SELECT status_json FROM judge_room_players WHERE room_id = ? AND seat_no = ?",
                String.class, game.roomId, seat);
        return json == null ? Map.of() : objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
        });
    }

    @Test
    void mechWolfBoardIsFullAndJoinable() throws Exception {
        String board = mockMvc.perform(get("/api/boards/" + MECH_WOLF_BOARD_ID)).andReturn().getResponse().getContentAsString();
        Assertions.assertEquals("full", objectMapper.readTree(board).path("data").path("judgeSupportLevel").asText(), board);
        Game game = mechWolfGame();
        Assertions.assertEquals("playing", game.snapshot(game.host).path("roomStatus").asText());
    }

    private java.time.LocalDateTime stepDeadline(Game game) {
        return jdbcTemplate.queryForObject("SELECT step_deadline FROM judge_rooms WHERE room_id = ?",
                java.time.LocalDateTime.class, game.roomId);
    }

    private static void assertSameSecond(java.time.LocalDateTime expected, java.time.LocalDateTime actual, String message) {
        Assertions.assertTrue(Math.abs(java.time.Duration.between(expected, actual).toMillis()) < 1000,
                message + "：" + expected + " vs " + actual);
    }

    @Test
    void wolfStepEndsFiveSecondsAfterEveryWolfConfirms() throws Exception {
        Game game = mechWolfGame();
        Map<String, List<Integer>> seats = game.seatsByRole();
        List<Integer> wolves = seats.get("狼人");
        int target = seats.get("平民").get(0);
        int wolfStep = untilStep(game, "wolf").path("currentStep").path("index").asInt();
        java.time.LocalDateTime full = stepDeadline(game);
        JsonNode early = game.call(game.players.get(wolves.get(0) - 1), "/night-action", "{\"actionType\":\"confirm\"}");
        Assertions.assertEquals(4000, early.path("code").asInt(), "没选目标不能确定：" + early);
        for (int wolf : wolves) {
            game.act(wolf, "kill", target);
        }
        assertSameSecond(full, stepDeadline(game), "只选了不确定，照常等总时长");
        game.act(wolves.get(0), "confirm", null);
        game.act(wolves.get(1), "confirm", null);
        JsonNode info = game.snapshot(game.players.get(wolves.get(0) - 1)).path("nightInfo");
        Assertions.assertTrue(info.path("myConfirmed").asBoolean(), info.toString());
        int confirmedPicks = 0;
        for (JsonNode pick : info.path("wolfPicks")) {
            confirmedPicks += pick.path("confirmed").asBoolean() ? 1 : 0;
        }
        Assertions.assertEquals(2, confirmedPicks, info.toString());
        assertSameSecond(full, stepDeadline(game), "还有狼没确定");
        game.act(wolves.get(2), "confirm", null);
        java.time.LocalDateTime shortened = stepDeadline(game);
        Assertions.assertTrue(shortened.isBefore(full.minusSeconds(10)), "全员确定后 5 秒结束：" + shortened);
        Assertions.assertEquals(wolfStep, game.snapshot(game.host).path("currentStep").path("index").asInt(), "5 秒内还没结束");
        game.act(wolves.get(2), "reselect", null);
        assertSameSecond(full, stepDeadline(game), "有人重新选择，回到总时长");
        Assertions.assertFalse(game.snapshot(game.players.get(wolves.get(2) - 1)).path("nightInfo").path("myConfirmed").asBoolean());
        game.act(wolves.get(2), "kill", seats.get("平民").get(1));
        game.act(wolves.get(2), "confirm", null);
        Assertions.assertTrue(stepDeadline(game).isBefore(full.minusSeconds(10)));
        Assertions.assertEquals(wolfStep + 1, game.expireDeadline().path("currentStep").path("index").asInt());
    }

    @Test
    void onlyWolvesConfirmInTheWolfStep() throws Exception {
        Game game = mechWolfGame();
        Map<String, List<Integer>> seats = game.seatsByRole();
        untilStep(game, "wolf");
        JsonNode witch = game.call(game.players.get(seats.get("女巫").get(0) - 1), "/night-action", "{\"actionType\":\"confirm\"}");
        Assertions.assertEquals(4003, witch.path("code").asInt(), witch.toString());
    }

    @Test
    void mechWolfWakesFirstAndLearns() throws Exception {
        Game game = mechWolfGame();
        Map<String, List<Integer>> seats = game.seatsByRole();
        int mech = seats.get("机械狼").get(0);
        int witch = seats.get("女巫").get(0);
        Assertions.assertEquals("mech_wolf", game.snapshot(game.host).path("currentStep").path("kind").asText());
        JsonNode other = mechAct(game, witch, "learn", mech);
        Assertions.assertEquals(4003, other.path("code").asInt(), "还没轮到女巫");
        JsonNode learned = mechAct(game, mech, "learn", witch);
        Assertions.assertEquals(0, learned.path("code").asInt(), learned.toString());
    }

    @Test
    void mechWolfCannotLearnItselfOrTheDead() throws Exception {
        Game game = mechWolfGame();
        Map<String, List<Integer>> seats = game.seatsByRole();
        int mech = seats.get("机械狼").get(0);
        int villager = seats.get("平民").get(0);
        Assertions.assertEquals(4000, mechAct(game, mech, "learn", mech).path("code").asInt());
        markDead(game, List.of(villager));
        Assertions.assertEquals(4000, mechAct(game, mech, "learn", villager).path("code").asInt());
    }

    @Test
    void learnedSkillStartsTheNextNight() throws Exception {
        Game game = mechWolfGame();
        Map<String, List<Integer>> seats = game.seatsByRole();
        int mech = seats.get("机械狼").get(0);
        int witch = seats.get("女巫").get(0);
        int villager = seats.get("平民").get(0);
        Assertions.assertEquals(0, mechAct(game, mech, "learn", witch).path("code").asInt());
        JsonNode sameNight = mechAct(game, mech, "poison", villager);
        Assertions.assertEquals(4000, sameNight.path("code").asInt(), "学的当晚还不能用：" + sameNight);
        game.finishNight();
        Assertions.assertEquals("witch", statusOf(game, mech).get("mechLearned"));
        toNextNight(game);
        Assertions.assertEquals(0, mechAct(game, mech, "poison", villager).path("code").asInt());
        JsonNode day = game.finishNight();
        Assertions.assertFalse(game.player(day, villager).path("alive").asBoolean(), day.toString());
        Assertions.assertEquals(Boolean.FALSE, statusOf(game, mech).get("mechPoison"));
    }

    @Test
    void mechWolfKnifesOnceTheWolvesAreGone() throws Exception {
        Game game = mechWolfGame();
        Map<String, List<Integer>> seats = game.seatsByRole();
        int mech = seats.get("机械狼").get(0);
        int witch = seats.get("女巫").get(0);
        int villager = seats.get("平民").get(0);
        markDead(game, seats.get("狼人"));
        JsonNode inOwnStep = mechAct(game, mech, "kill", villager);
        Assertions.assertEquals(4000, inOwnStep.path("code").asInt(), "刀口在狼人那一步选：" + inOwnStep);
        Assertions.assertTrue(game.snapshot(game.players.get(mech - 1)).path("nightInfo").path("armed").asBoolean(), "机械狼那一步就告诉它能开刀");
        untilStep(game, "wolf");
        Assertions.assertFalse(game.snapshot(game.players.get(mech - 1)).path("currentStep").path("line").asText().isEmpty(),
                "狼人那一步带刀机械狼是持有者，拿得到台词");
        java.time.LocalDateTime full = stepDeadline(game);
        game.act(mech, "kill", villager);
        game.act(mech, "confirm", null);
        Assertions.assertTrue(stepDeadline(game).isBefore(full.minusSeconds(10)), "带刀机械狼确定后 5 秒结束");
        untilStep(game, "witch");
        Assertions.assertEquals(villager, game.snapshot(game.players.get(witch - 1)).path("nightInfo").path("knifeSeat").asInt());
        JsonNode day = game.finishNight();
        Assertions.assertFalse(game.player(day, villager).path("alive").asBoolean(), day.toString());
    }

    @Test
    void mechWolfCannotKnifeWhileAWolfLives() throws Exception {
        Game game = mechWolfGame();
        Map<String, List<Integer>> seats = game.seatsByRole();
        int mech = seats.get("机械狼").get(0);
        Assertions.assertEquals(4000, mechAct(game, mech, "kill", seats.get("平民").get(0)).path("code").asInt());
        untilStep(game, "wolf");
        JsonNode inWolfStep = game.call(game.players.get(mech - 1), "/night-action",
                "{\"actionType\":\"kill\",\"targetSeatNo\":" + seats.get("平民").get(0) + "}");
        Assertions.assertEquals(4003, inWolfStep.path("code").asInt(), "狼人还在，狼人那一步没有机械狼：" + inWolfStep);
    }

    @Test
    void learnedWolfGivesTwoKnives() throws Exception {
        Game game = mechWolfGame();
        Map<String, List<Integer>> seats = game.seatsByRole();
        int mech = seats.get("机械狼").get(0);
        int first = seats.get("平民").get(0);
        int second = seats.get("平民").get(1);
        Assertions.assertEquals(0, mechAct(game, mech, "learn", seats.get("狼人").get(0)).path("code").asInt());
        game.finishNight();
        markDead(game, seats.get("狼人"));
        toNextNight(game);
        JsonNode pierce = mechAct(game, mech, "kill_pierce", second);
        Assertions.assertEquals(0, pierce.path("code").asInt(), "破盾刀在机械狼那一步：" + pierce);
        untilStep(game, "wolf");
        game.act(mech, "kill", first);
        Assertions.assertEquals("kill_pierce", game.snapshot(game.players.get(mech - 1)).path("nightInfo").path("myActions").path(0).path("actionType").asText(),
                "狼人那一步出刀不会冲掉机械狼那一步的破盾刀");
        JsonNode day = game.finishNight();
        Assertions.assertFalse(game.player(day, first).path("alive").asBoolean(), day.toString());
        Assertions.assertFalse(game.player(day, second).path("alive").asBoolean(), day.toString());
    }

    /** 一直到点，直到当前步骤是 kind（最多 8 步）。 */
    private JsonNode untilStep(Game game, String kind) throws Exception {
        JsonNode view = game.snapshot(game.host);
        for (int i = 0; i < 8 && !kind.equals(view.path("currentStep").path("kind").asText()); i++) {
            view = game.expireDeadline();
        }
        Assertions.assertEquals(kind, view.path("currentStep").path("kind").asText(), view.toString());
        return view;
    }

    @Test
    void psychicSeesTheExactRoleAtOnce() throws Exception {
        Game game = mechWolfGame();
        Map<String, List<Integer>> seats = game.seatsByRole();
        int psychic = seats.get("通灵师").get(0);
        untilStep(game, "psychic");
        game.act(psychic, "check", seats.get("狼人").get(0));
        JsonNode info = game.snapshot(game.players.get(psychic - 1)).path("nightInfo");
        Assertions.assertEquals("狼人", info.path("identityResult").asText(), info.toString());
        Assertions.assertTrue(info.path("checkResult").isNull() || info.path("checkResult").isMissingNode(), info.toString());
    }

    @Test
    void psychicSeesTheLearnedRoleOfTheMechWolf() throws Exception {
        Game learned = mechWolfGame();
        Map<String, List<Integer>> seats = learned.seatsByRole();
        int mech = seats.get("机械狼").get(0);
        int psychic = seats.get("通灵师").get(0);
        Assertions.assertEquals(0, mechAct(learned, mech, "learn", seats.get("女巫").get(0)).path("code").asInt());
        untilStep(learned, "psychic");
        learned.act(psychic, "check", mech);
        Assertions.assertEquals("女巫", learned.snapshot(learned.players.get(psychic - 1)).path("nightInfo").path("identityResult").asText());

        Game plain = mechWolfGame();
        Map<String, List<Integer>> plainSeats = plain.seatsByRole();
        untilStep(plain, "psychic");
        plain.act(plainSeats.get("通灵师").get(0), "check", plainSeats.get("机械狼").get(0));
        Assertions.assertEquals("机械狼",
                plain.snapshot(plain.players.get(plainSeats.get("通灵师").get(0) - 1)).path("nightInfo").path("identityResult").asText());
    }

    @Test
    void mechWolfNightInfoIsOnlyForItself() throws Exception {
        Game game = mechWolfGame();
        Map<String, List<Integer>> seats = game.seatsByRole();
        int mech = seats.get("机械狼").get(0);
        JsonNode mine = game.snapshot(game.players.get(mech - 1));
        Assertions.assertEquals("mech_wolf", mine.path("currentStep").path("kind").asText());
        Assertions.assertFalse(mine.path("nightInfo").path("armed").asBoolean(true), mine.path("nightInfo").toString());
        Assertions.assertTrue(mine.path("nightInfo").path("learned").isNull() || mine.path("nightInfo").path("learned").isMissingNode());
        String deadline = mine.path("currentStep").path("deadline").asText();
        for (int seat = 1; seat <= 12; seat++) {
            if (seat == mech) {
                continue;
            }
            JsonNode view = game.snapshot(game.players.get(seat - 1));
            JsonNode info = view.path("nightInfo");
            Assertions.assertTrue(info.path("armed").isNull() || info.path("armed").isMissingNode(), seat + "号看到了机械狼的信息：" + info);
            Assertions.assertTrue(info.path("learned").isNull() || info.path("learned").isMissingNode(), info.toString());
            Assertions.assertEquals(deadline, view.path("currentStep").path("deadline").asText(), "机械狼那一步所有人同一个截止时间");
        }
    }

    @Test
    void mechWolfLearningShowsTheTargetsRole() throws Exception {
        Game game = mechWolfGame();
        Map<String, List<Integer>> seats = game.seatsByRole();
        int mech = seats.get("机械狼").get(0);
        int guard = seats.get("守卫").get(0);
        mechAct(game, mech, "learn", guard);
        JsonNode info = game.snapshot(game.players.get(mech - 1)).path("nightInfo");
        Assertions.assertEquals(guard, info.path("checkedSeat").asInt(), info.toString());
        Assertions.assertEquals("守卫", info.path("identityResult").asText(), info.toString());
        Assertions.assertEquals("learn", info.path("myActions").path(0).path("actionType").asText(), info.toString());
    }

    @Test
    void mechWolfWithTheHunterSkillShoots() throws Exception {
        Game exiled = mechWolfGame();
        Map<String, List<Integer>> seats = exiled.seatsByRole();
        int mech = seats.get("机械狼").get(0);
        mechAct(exiled, mech, "learn", seats.get("猎人").get(0));
        exiled.finishNight();
        exile(exiled, seats.get("平民"), mech);
        Assertions.assertEquals("[\"use\",\"skip\"]", exiled.snapshot(exiled.players.get(mech - 1)).path("pending").path("options").toString(),
                "学到猎人的机械狼被放逐可以开枪");

        Game poisoned = mechWolfGame();
        Map<String, List<Integer>> poisonSeats = poisoned.seatsByRole();
        int poisonedMech = poisonSeats.get("机械狼").get(0);
        mechAct(poisoned, poisonedMech, "learn", poisonSeats.get("猎人").get(0));
        JsonNode day = poisoned.finishNight(null, poisonedMech);
        Assertions.assertFalse(poisoned.player(day, poisonedMech).path("alive").asBoolean(), day.toString());
        Assertions.assertEquals("[\"skip\"]", poisoned.snapshot(poisoned.players.get(poisonedMech - 1)).path("pending").path("options").toString(),
                "学猎人的当晚被女巫毒死：不能开枪");
    }

    // ---- 审稿后修复 ----


    @Test
    void nightActionsOtherThanPassingNeedATarget() throws Exception {
        // 新流程房间：除了不行动 / 确认，都要选目标
        Game game = mechWolfGame();
        Map<String, List<Integer>> seats = game.seatsByRole();
        int mech = seats.get("机械狼").get(0);
        JsonNode noTarget = game.call(game.players.get(mech - 1), "/night-action", "{\"actionType\":\"learn\"}");
        Assertions.assertEquals(4000, noTarget.path("code").asInt(), noTarget.toString());
        Assertions.assertEquals(0, mechAct(game, mech, "learn", seats.get("女巫").get(0)).path("code").asInt());
    }

    @Test
    void theMultiActionBodyIsGone() throws Exception {
        // 机械狼「一次交多件事」的接口删掉了：刀口在狼人那一步，机械狼那一步一次一件事
        Game game = mechWolfGame();
        int mech = game.seatsByRole().get("机械狼").get(0);
        JsonNode multi = game.call(game.players.get(mech - 1), "/night-action",
                "{\"actions\":[{\"actionType\":\"learn\",\"targetSeatNo\":" + (mech == 1 ? 2 : 1) + "}]}");
        Assertions.assertNotEquals(0, multi.path("code").asInt(), multi.toString());
    }

    @Test
    void judgeSeesEachOfTheMechWolfsActions() throws Exception {
        Game game = lobbyGame("{\"boardId\":" + MECH_WOLF_BOARD_ID + ",\"judgeMode\":\"observer\",\"autoJudge\":true,\"sheriffEnabled\":false}", 12);
        game.readyAll();
        game.start();
        Map<String, List<Integer>> seats = game.seatsByRole();
        int mech = seats.get("机械狼").get(0);
        int witch = seats.get("女巫").get(0);
        int villager = seats.get("平民").get(0);
        markDead(game, seats.get("狼人"));
        Assertions.assertEquals(0, mechAct(game, mech, "learn", witch).path("code").asInt());
        untilStep(game, "wolf");
        game.act(mech, "kill", villager);
        JsonNode actions = game.snapshot(game.host).path("nightActions");
        List<String> mine = new ArrayList<>();
        for (JsonNode action : actions) {
            if (action.path("actorSeatNo").asInt() == mech) {
                mine.add(action.path("actionType").asText() + ":" + action.path("targetSeatNo").asInt());
            }
        }
        Assertions.assertEquals(List.of("learn:" + witch, "kill:" + villager), mine, actions.toString());
        JsonNode mechView = game.player(game.snapshot(game.host), mech);
        Assertions.assertEquals("mech_wolf", mechView.path("mechanic").asText(), "法官看得到各座位的机制");
    }

    // ---- 架构修复 ----

    /** 每条用例跑完核对一遍：库里的 phase_data / status_json 只能有 JudgeStateKeys 里登记过的键。 */
    @org.junit.jupiter.api.AfterEach
    void stateUsesOnlyRegisteredKeys() throws Exception {
        for (String json : jdbcTemplate.queryForList("SELECT phase_data FROM judge_rooms WHERE phase_data IS NOT NULL", String.class)) {
            objectMapper.readTree(json).fieldNames().forEachRemaining(key -> Assertions.assertTrue(
                    com.wolfbook.backend.service.judge.JudgeStateKeys.PHASE_DATA.contains(key), "phase_data 里有没登记的键 " + key + "：" + json));
        }
        for (String json : jdbcTemplate.queryForList("SELECT status_json FROM judge_room_players WHERE status_json IS NOT NULL", String.class)) {
            objectMapper.readTree(json).fieldNames().forEachRemaining(key -> Assertions.assertTrue(
                    com.wolfbook.backend.service.judge.JudgeStateKeys.SEAT_STATUS.contains(key), "status_json 里有没登记的键 " + key + "：" + json));
        }
    }

    @Test
    @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    void tickFailuresAreLoggedWithTheRoom(org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        // 推进出错（这里是板子读不到）不能静默：记一条带房间号、阶段、步骤的错误日志
        Game game = autoGame(false);
        jdbcTemplate.update("UPDATE judge_rooms SET board_id = 99999, rules_json = 'broken', step_deadline = ? WHERE room_id = ?",
                java.sql.Timestamp.valueOf("2000-01-01 00:00:00"), game.roomId);
        mockMvc.perform(get("/api/judge/rooms/" + game.roomId).header("Authorization", "Bearer " + game.host));
        Assertions.assertTrue(output.getOut().contains("电子法官推进失败") && output.getOut().contains(game.roomId), output.getOut());
    }

    @Test
    void eventsKeepTheOrderTheyWereWrittenIn() throws Exception {
        // 事件时间只到秒：同一秒里写的几条要按写入顺序读出来，不能按事件号排
        Game game = lobbyGame();
        jdbcTemplate.update("UPDATE judge_action_events SET create_time = ? WHERE room_id = ?",
                java.sql.Timestamp.valueOf("2026-01-01 00:00:00"), game.roomId);
        List<Map<String, Object>> joins = jdbcTemplate.queryForList(
                "SELECT event_id, payload_json FROM judge_action_events WHERE room_id = ? AND action_type = 'player_joined'", game.roomId);
        for (Map<String, Object> join : joins) {
            int seatNo = objectMapper.readTree(String.valueOf(join.get("payload_json"))).path("seatNo").asInt();
            // 事件号倒过来：1 号的事件号字典序最大
            jdbcTemplate.update("UPDATE judge_action_events SET event_id = ? WHERE event_id = ?", "ev_" + (50 - seatNo), join.get("event_id"));
        }
        List<Integer> seats = new ArrayList<>();
        for (JsonNode event : game.snapshot(game.host).path("timeline")) {
            if ("player_joined".equals(event.path("actionType").asText())) {
                seats.add(event.path("actorSeatNo").asInt());
            }
        }
        List<Integer> expected = new ArrayList<>();
        for (int i = 1; i <= PLAYER_COUNT; i++) {
            expected.add(i);
        }
        Assertions.assertEquals(expected, seats);
    }

    @Test
    void snapshotCarriesTheServerClock() throws Exception {
        // 手机按服务器时间算倒计时，不看自己的时钟
        Game game = autoGame(false);
        String text = game.snapshot(game.host).path("serverNow").asText();
        java.time.LocalDateTime serverNow = java.time.LocalDateTime.parse(text);
        long drift = java.time.Duration.between(java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")), serverNow).abs().getSeconds();
        Assertions.assertTrue(drift < 5, text);
    }

    @Test
    void aStartedGameKeepsItsBoardEvenIfTheBoardIsGone() throws Exception {
        // 开局时把阵容、夜间顺序、各角色机制存进房间：之后后台改板子、删板子，这一局照常走
        Game game = autoGame(false);
        String kind = game.snapshot(game.host).path("currentStep").path("kind").asText();
        jdbcTemplate.update("UPDATE judge_rooms SET board_id = 99999 WHERE room_id = ?", game.roomId);
        JsonNode after = game.snapshot(game.host);
        Assertions.assertEquals(kind, after.path("currentStep").path("kind").asText());
        Assertions.assertNotEquals(kind, game.expireDeadline().path("currentStep").path("kind").asText(), "照常往下走");
    }

    // ---- 零场外扫描：对局中每个玩家的快照里不能有别人的身份、只给法官或别人的记录、别人的夜间私密信息 ----

    private static final java.util.Set<String> WOLF_TEAM = java.util.Set.of("狼人", "黑狼王", "狼王");

    /** 扫一遍所有入座玩家此刻的快照。 */
    private void assertNoLeaks(Game game) throws Exception {
        Map<Integer, String> roleOf = new java.util.HashMap<>();
        game.seatsByRole().forEach((role, list) -> list.forEach(seat -> roleOf.put(seat, role)));
        for (int i = 0; i < game.players.size(); i++) {
            int seat = i + 1;
            if (!roleOf.containsKey(seat)) {
                continue;
            }
            assertNoLeak(game.snapshot(game.players.get(i)), seat, roleOf.get(seat));
        }
    }

    private void assertNoLeak(JsonNode snap, int self, String myRole) {
        if (!"playing".equals(snap.path("roomStatus").asText())) {
            return;
        }
        String where = self + " 号（" + myRole + "）在 " + snap.path("currentPhase").asText() + " 第 " + snap.path("currentDay").asInt() + " 天：";
        Assertions.assertFalse(snap.path("judgeViewer").asBoolean(), where);
        for (JsonNode player : snap.path("players")) {
            if (player.path("seatNo").asInt() == self) {
                continue;
            }
            boolean revealedIdiot = "白痴".equals(player.path("roleName").asText()) && !player.path("alive").asBoolean();
            if (revealedIdiot) {
                continue; // 白痴被放逐时公开翻牌
            }
            Assertions.assertTrue(player.path("roleName").isNull() && player.path("faction").isNull() && player.path("roleId").isNull()
                    && (player.path("mechanic").isMissingNode() || player.path("mechanic").isNull()),
                    where + "看到了 " + player.path("seatNo").asInt() + " 号的身份 " + player);
        }
        for (JsonNode event : snap.path("timeline")) {
            String visibility = event.path("visibility").asText();
            Assertions.assertNotEquals("judge_only", visibility, where + "看到了只给法官的记录 " + event);
            if ("actor_only".equals(visibility)) {
                Assertions.assertEquals(self, event.path("actorSeatNo").asInt(), where + "看到了别人的私密记录 " + event);
            }
        }
        Assertions.assertEquals(0, snap.path("nightActions").size(), where + "看到了今晚的行动记录");
        Assertions.assertEquals(0, snap.path("nightHistory").size(), where + "看到了历次夜晚的行动记录");
        for (JsonNode round : snap.path("rounds")) {
            Assertions.assertTrue(round.path("deathCauses").isNull() || round.path("deathCauses").isMissingNode() || round.path("deathCauses").isEmpty(),
                    where + "看到了死因 " + round);
        }
        JsonNode info = snap.path("nightInfo");
        if (info.isMissingNode() || info.isNull()) {
            return;
        }
        if (!WOLF_TEAM.contains(myRole)) {
            Assertions.assertEquals(0, info.path("teammateSeats").size(), where + "看到了狼队");
            Assertions.assertEquals(0, info.path("wolfPicks").size(), where + "看到了狼的刀口");
        }
        if (!"女巫".equals(myRole)) {
            Assertions.assertTrue(info.path("knifeSeat").isNull() || info.path("knifeSeat").isMissingNode(), where + "看到了刀口");
        }
        if (!"预言家".equals(myRole)) {
            Assertions.assertTrue(info.path("checkResult").isNull() || info.path("checkResult").isMissingNode(), where + "看到了查验结果");
        }
        if (!"机械狼".equals(myRole)) {
            for (String field : List.of("armed", "learned", "learnedRole", "learnedEffective", "mechPoison")) {
                Assertions.assertTrue(info.path(field).isNull() || info.path(field).isMissingNode(), where + "看到了机械狼的 " + field);
            }
        }
        if (!"猎人".equals(myRole)) {
            Assertions.assertTrue(info.path("canShoot").isNull() || info.path("canShoot").isMissingNode(), where + "看到了猎人的开枪状态");
        }
        if (!"通灵师".equals(myRole) && !"机械狼".equals(myRole)) {
            Assertions.assertTrue(info.path("identityResult").isNull() || info.path("identityResult").isMissingNode(), where + "看到了验人结果");
        }
    }

    /** 一直到点往下走，每一步扫一遍；夜里狼刀第一个平民、预言家查第一只狼，白天没人投票。 */
    private void playAndScan(Game game, int maxSteps) throws Exception {
        Map<String, List<Integer>> roles = game.seatsByRole();
        int maxDay = 0;
        for (int i = 0; i < maxSteps; i++) {
            JsonNode view = game.snapshot(game.host);
            maxDay = Math.max(maxDay, view.path("currentDay").asInt());
            if (!"playing".equals(view.path("roomStatus").asText())) {
                break;
            }
            assertNoLeaks(game);
            String kind = view.path("currentStep").path("kind").asText();
            int target = roles.get("平民").get(0);
            if ("night_action".equals(view.path("currentPhase").asText()) && "wolf".equals(kind)) {
                for (String role : WOLF_TEAM) {
                    for (int wolf : roles.getOrDefault(role, List.of())) {
                        if (game.player(view, wolf).path("alive").asBoolean()) {
                            game.call(game.players.get(wolf - 1), "/night-action", "{\"actionType\":\"kill\",\"targetSeatNo\":" + target + "}");
                        }
                    }
                }
                assertNoLeaks(game);
            } else if ("seer".equals(kind) || "psychic".equals(kind)) {
                String role = "seer".equals(kind) ? "预言家" : "通灵师";
                int checker = roles.get(role).get(0);
                game.call(game.players.get(checker - 1), "/night-action", "{\"actionType\":\"check\",\"targetSeatNo\":" + roles.get("狼人").get(0) + "}");
                assertNoLeaks(game);
            } else if ("mech_wolf".equals(kind)) {
                int mech = roles.get("机械狼").get(0);
                game.call(game.players.get(mech - 1), "/night-action", "{\"actionType\":\"learn\",\"targetSeatNo\":" + roles.get("女巫").get(0) + "}");
                assertNoLeaks(game);
            }
            if ("day_announce".equals(view.path("currentPhase").asText())) {
                game.advance(game.host, "{\"action\":\"start_speech\"}");
            } else if ("day_speech".equals(view.path("currentPhase").asText())) {
                game.advance(game.host, "{\"action\":\"start_vote\"}");
            } else if ("vote_result".equals(view.path("currentPhase").asText())) {
                game.advance(game.host, "{\"action\":\"continue\"}");
            } else {
                game.expireDeadline();
            }
        }
        Assertions.assertTrue(maxDay >= 2, "扫描至少要走到第二天，现在只到第 " + maxDay + " 天");
    }

    @Test
    void noPlayerSeesAnotherPlayersSecretsOnTheSeerBoard() throws Exception {
        playAndScan(autoGame12(false), 40);
    }

    @Test
    void noPlayerSeesAnotherPlayersSecretsOnTheMechWolfBoard() throws Exception {
        playAndScan(mechWolfGame(), 40);
    }

    @Test
    void noPlayerSeesAnotherPlayersSecretsOnTheWolfKingBoard() throws Exception {
        playAndScan(wolfKingGame(), 40);
    }

    @Test
    void signupTellsUndecidedFromDeclined() throws Exception {
        // 实测问题 2：点「不上警」要有反馈 —— 没选是 null，选了不上警是 false，上警是 true
        Game game = autoGame12(true);
        game.finishNight();
        int seat = game.seatsByRole().get("平民").get(0);
        String token = game.players.get(seat - 1);
        Assertions.assertTrue(game.snapshot(token).path("dayInfo").path("sheriffCandidate").isNull(), "还没选");
        game.sheriff(seat, "{\"candidate\":false}");
        Assertions.assertFalse(game.snapshot(token).path("dayInfo").path("sheriffCandidate").isNull(), "选了不上警");
        Assertions.assertFalse(game.snapshot(token).path("dayInfo").path("sheriffCandidate").asBoolean());
        game.sheriff(seat, "{\"candidate\":true}");
        Assertions.assertTrue(game.snapshot(token).path("dayInfo").path("sheriffCandidate").asBoolean());
    }

    @Test
    void everyoneSeesTheSheriffsSpeechOrderChoice() throws Exception {
        // 实测问题 3：警长定完发言顺序，大家都看得到选了哪边
        Game game = autoGame12(true);
        game.finishNight();
        int sheriff = game.seatsByRole().get("平民").get(0);
        game.sheriff(sheriff, "{\"candidate\":true}");
        JsonNode announce = game.advance(game.host, "{\"action\":\"close_sheriff_signup\"}").path("data");
        Assertions.assertEquals("day_announce", announce.path("currentPhase").asText(), announce.toString());
        Assertions.assertTrue(announce.path("sheriffOrder").isNull(), "还没选");
        game.sheriff(sheriff, "{\"speechOrder\":\"right\"}");
        int other = game.seatsByRole().get("平民").get(1);
        Assertions.assertEquals("right", game.snapshot(game.players.get(other - 1)).path("sheriffOrder").asText());
        // 发言阶段也只告诉大家是警左还是警右（实际用的方向）
        JsonNode speech = game.advance(game.host, "{\"action\":\"start_speech\"}").path("data");
        Assertions.assertEquals("day_speech", speech.path("currentPhase").asText(), speech.toString());
        Assertions.assertEquals("right", game.snapshot(game.players.get(other - 1)).path("speech").path("direction").asText());
    }

    @Test
    void hunterIsToldWhetherHeCanShootTonight() throws Exception {
        // 猎人那一步告知开枪状态：被女巫毒了不能开；只有猎人本人、只在这一步看得到
        Game game = autoGame12(false);
        Map<String, List<Integer>> seats = game.seatsByRole();
        int hunter = seats.get("猎人").get(0);
        int witch = seats.get("女巫").get(0);
        for (int wolf : seats.get("狼人")) {
            game.act(wolf, "kill", seats.get("平民").get(0));
        }
        untilStep(game, "witch");
        game.act(witch, "poison", hunter);
        JsonNode atHunter = untilStep(game, "hunter");
        Assertions.assertFalse(game.snapshot(game.players.get(hunter - 1)).path("nightInfo").path("canShoot").asBoolean(true), "被毒了不能开枪");
        JsonNode other = game.snapshot(game.players.get(witch - 1)).path("nightInfo").path("canShoot");
        Assertions.assertTrue(other.isNull() || other.isMissingNode(), "别人看不到猎人的开枪状态");

        Game safe = autoGame12(false);
        int hunter2 = safe.seatsByRole().get("猎人").get(0);
        untilStep(safe, "hunter");
        Assertions.assertTrue(safe.snapshot(safe.players.get(hunter2 - 1)).path("nightInfo").path("canShoot").asBoolean(false), "没被毒能开枪");
    }

    @Test
    void deathCausesAreForTheJudgeUntilTheGameEnds() throws Exception {
        // 对局记录写死因：对局中只给法官，结束后全场可见
        Game game = autoGame12(false);
        Map<String, List<Integer>> seats = game.seatsByRole();
        int villager = seats.get("平民").get(0);
        int hunter = seats.get("猎人").get(0);
        game.finishNight(villager, hunter);
        JsonNode judgeRound = game.snapshot(game.host).path("rounds").path(0);
        Assertions.assertEquals("knife", judgeRound.path("deathCauses").path(String.valueOf(villager)).path(0).asText(), judgeRound.toString());
        Assertions.assertEquals("poison", judgeRound.path("deathCauses").path(String.valueOf(hunter)).path(0).asText(), judgeRound.toString());
        JsonNode playerRound = game.snapshot(game.players.get(seats.get("平民").get(1) - 1)).path("rounds").path(0);
        Assertions.assertTrue(playerRound.path("deathCauses").isNull() || playerRound.path("deathCauses").isMissingNode()
                || playerRound.path("deathCauses").isEmpty(), "对局中玩家看不到死因：" + playerRound);

        Game joined = joinedGame(BOARD_ID, PLAYER_COUNT);
        Map<String, List<Integer>> joinedSeats = joined.seatsByRole();
        int knifed = joinedSeats.get("平民").get(0);
        joined.finishNight(knifed, null);
        JsonNode done = joined.advance(joined.host, "{\"action\":\"finish\"}").path("data");
        Assertions.assertEquals("finished", done.path("roomStatus").asText(), done.toString());
        JsonNode round = joined.snapshot(joined.players.get(joinedSeats.get("平民").get(1) - 1)).path("rounds").path(0);
        Assertions.assertEquals("knife", round.path("deathCauses").path(String.valueOf(knifed)).path(0).asText(), round.toString());
    }

    // ---- 大厅换座（用户给定：按顺序入座；空位点了直接换；有人的座位申请换座，对方 30 秒内同意才换；换过座的人变回未准备）----

    private JsonNode seat(Game game, String token, String action, int seatNo) throws Exception {
        return game.call(token, "/seat", "{\"action\":\"" + action + "\",\"seatNo\":" + seatNo + "}");
    }

    private int seatOf(Game game, String token) throws Exception {
        return game.snapshot(token).path("selfPlayer").path("seatNo").asInt();
    }

    @Test
    void playersSitInOrderAndMayMoveToAnEmptySeat() throws Exception {
        Game game = lobbyGame("{\"boardId\":" + BOARD_ID + ",\"judgeMode\":\"observer\"}", PLAYER_COUNT - 2);
        for (int i = 0; i < game.players.size(); i++) {
            Assertions.assertEquals(i + 1, seatOf(game, game.players.get(i)), "按进房顺序入座");
        }
        String first = game.players.get(0);
        game.call(first, "/ready", null);
        JsonNode moved = seat(game, first, "move", PLAYER_COUNT);
        Assertions.assertEquals(0, moved.path("code").asInt(), moved.toString());
        Assertions.assertEquals(PLAYER_COUNT, moved.path("data").path("selfPlayer").path("seatNo").asInt());
        Assertions.assertFalse(moved.path("data").path("selfPlayer").path("ready").asBoolean(), "换过座变回未准备");
        Assertions.assertEquals(4002, seat(game, first, "move", 2).path("code").asInt(), "有人的座位不能直接坐");
        Assertions.assertEquals(4000, seat(game, first, "move", PLAYER_COUNT + 1).path("code").asInt(), "没有这个座位");
        Assertions.assertEquals(0, seat(game, game.players.get(1), "move", 1).path("code").asInt(), "空出来的 1 号别人能坐");
    }

    @Test
    void swappingNeedsTheOtherPlayersConsent() throws Exception {
        Game game = lobbyGame();
        String one = game.players.get(0);
        String two = game.players.get(1);
        game.call(one, "/ready", null);
        game.call(two, "/ready", null);
        JsonNode asked = seat(game, one, "request", 2);
        Assertions.assertEquals(0, asked.path("code").asInt(), asked.toString());
        Assertions.assertEquals(2, asked.path("data").path("seatSwap").path("outgoing").path("seatNo").asInt(), asked.toString());
        JsonNode incoming = game.snapshot(two).path("seatSwap").path("incoming");
        Assertions.assertEquals(1, incoming.path("seatNo").asInt(), incoming.toString());
        Assertions.assertFalse(incoming.path("nickname").asText().isEmpty());
        Assertions.assertFalse(incoming.path("deadline").isNull());
        Assertions.assertTrue(game.snapshot(game.players.get(2)).path("seatSwap").isNull(), "别人看不到这份申请");
        Assertions.assertEquals(1, seatOf(game, one), "对方同意之前不换");
        JsonNode accepted = seat(game, two, "accept", 1);
        Assertions.assertEquals(0, accepted.path("code").asInt(), accepted.toString());
        Assertions.assertEquals(2, seatOf(game, one));
        Assertions.assertEquals(1, seatOf(game, two));
        Assertions.assertFalse(game.snapshot(one).path("selfPlayer").path("ready").asBoolean());
        Assertions.assertFalse(game.snapshot(two).path("selfPlayer").path("ready").asBoolean());
        Assertions.assertTrue(game.snapshot(one).path("seatSwap").isNull(), "换完申请就没了");
    }

    @Test
    void swapRequestsCanBeRejectedCancelledOrExpire() throws Exception {
        Game game = lobbyGame();
        String one = game.players.get(0);
        String two = game.players.get(1);
        String three = game.players.get(2);
        seat(game, one, "request", 2);
        Assertions.assertEquals(4002, seat(game, three, "request", 2).path("code").asInt(), "对方正在处理别人的申请");
        Assertions.assertEquals(0, seat(game, two, "reject", 1).path("code").asInt());
        Assertions.assertTrue(game.snapshot(one).path("seatSwap").isNull(), "被拒绝后申请没了");
        Assertions.assertEquals(1, seatOf(game, one));

        seat(game, one, "request", 2);
        Assertions.assertEquals(0, seat(game, one, "cancel", 2).path("code").asInt());
        Assertions.assertTrue(game.snapshot(two).path("seatSwap").isNull(), "撤回后对方看不到了");
        Assertions.assertEquals(4002, seat(game, two, "accept", 1).path("code").asInt());

        seat(game, one, "request", 2);
        String phaseData = jdbcTemplate.queryForObject("SELECT phase_data FROM judge_rooms WHERE room_id = ?", String.class, game.roomId);
        jdbcTemplate.update("UPDATE judge_rooms SET phase_data = ? WHERE room_id = ?",
                phaseData.replaceAll("\"deadline\":\"[^\"]+\"", "\"deadline\":\"2000-01-01T00:00:00\""), game.roomId);
        Assertions.assertTrue(game.snapshot(two).path("seatSwap").isNull(), "30 秒没理算拒绝");
        Assertions.assertEquals(4002, seat(game, two, "accept", 1).path("code").asInt());
        Assertions.assertEquals(4000, seat(game, one, "request", 1).path("code").asInt(), "不能和自己换");
    }

    @Test
    void movingAwayDropsTheSwapRequest() throws Exception {
        Game game = lobbyGame("{\"boardId\":" + BOARD_ID + ",\"judgeMode\":\"observer\"}", PLAYER_COUNT - 1);
        String one = game.players.get(0);
        String two = game.players.get(1);
        seat(game, one, "request", 2);
        seat(game, two, "move", PLAYER_COUNT);
        Assertions.assertTrue(game.snapshot(one).path("seatSwap").isNull(), "对方换走了，申请作废");
        Assertions.assertEquals(4002, seat(game, two, "accept", 1).path("code").asInt());
    }

    @Test
    void noSeatChangesOnceTheGameStarts() throws Exception {
        Game game = startedGame();
        Assertions.assertEquals(4002, seat(game, game.players.get(0), "request", 2).path("code").asInt());
        Game lobby = lobbyGame();
        Assertions.assertEquals(4003, seat(lobby, lobby.host, "move", 1).path("code").asInt(), "有法官局的法官不入座");
    }

    @Test
    void hostOfAJoinedRoomMayLeaveSeatOne() throws Exception {
        Game game = lobbyGame("{\"boardId\":" + BOARD_ID + ",\"judgeMode\":\"joined\",\"autoJudge\":true}", PLAYER_COUNT - 2);
        Assertions.assertEquals(1, seatOf(game, game.host));
        JsonNode moved = seat(game, game.host, "move", PLAYER_COUNT);
        Assertions.assertEquals(0, moved.path("code").asInt(), moved.toString());
        Assertions.assertTrue(moved.path("data").path("host").asBoolean(), "房主换了座位还是房主");
    }

    // ---- helpers ----

    /** 种子板子 2「预女猎白」：12 人，4 狼、预言家、女巫、猎人、白痴、4 平民；夜间顺序 狼人 → 女巫 → 预言家 → 猎人。 */
    private Game autoGame12(boolean sheriff) throws Exception {
        Game game = lobbyGame("{\"boardId\":2,\"judgeMode\":\"observer\",\"autoJudge\":true,\"sheriffEnabled\":" + sheriff + "}", 12);
        game.readyAll();
        game.start();
        return game;
    }

    /** 狼王守卫（种子板子 8）有法官、不上警。 */
    private Game wolfKingGame() throws Exception {
        Game game = lobbyGame("{\"boardId\":" + WOLF_KING_BOARD_ID + ",\"judgeMode\":\"observer\",\"autoJudge\":true,\"sheriffEnabled\":false}", 12);
        game.readyAll();
        game.start();
        return game;
    }

    /** 直接在库里把这些座位设成出局（造残局用）。 */
    private void markDead(Game game, List<Integer> seats) {
        for (int seat : seats) {
            jdbcTemplate.update("UPDATE judge_room_players SET alive = 0 WHERE room_id = ? AND seat_no = ?", game.roomId, seat);
        }
    }

    /** 天亮后直接发言、投票：voters 全投 target，然后结算。返回结算后的快照。 */
    private JsonNode exile(Game game, List<Integer> voters, int target) throws Exception {
        game.advance(game.host, "{\"action\":\"start_speech\"}");
        game.advance(game.host, "{\"action\":\"start_vote\"}");
        for (int voter : voters) {
            game.vote(voter, target);
        }
        JsonNode resolved = game.advance(game.host, "{\"action\":\"resolve_vote\"}");
        Assertions.assertEquals(0, resolved.path("code").asInt(), resolved.toString());
        return resolved.path("data");
    }

    private JsonNode autoGameHostSnapshot() throws Exception {
        Game game = autoGame(false);
        return game.snapshot(game.host);
    }

    private Game autoGame(boolean sheriff) throws Exception {
        Game game = lobbyGame("{\"boardId\":" + BOARD_ID + ",\"judgeMode\":\"observer\",\"autoJudge\":true,\"sheriffEnabled\":" + sheriff + "}");
        game.readyAll();
        game.start();
        return game;
    }

    /** 无法官房：房主坐 1 号，其余人入座，还没准备。players.get(0) 就是房主。 */
    private Game joinedLobby(int boardId, int playerCount) throws Exception {
        int run = SEQUENCE.incrementAndGet();
        String host = login("judge-host-" + run);
        String created = mockMvc.perform(post("/api/judge/rooms")
                        .header("Authorization", "Bearer " + host)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"boardId\":" + boardId + ",\"judgeMode\":\"joined\",\"sheriffEnabled\":false}"))
                .andReturn().getResponse().getContentAsString();
        JsonNode createdRoot = objectMapper.readTree(created);
        Assertions.assertEquals(0, createdRoot.path("code").asInt(), created);
        Game game = new Game(host, createdRoot.path("data").path("roomId").asText());
        game.players.add(host);
        for (int i = 2; i <= playerCount; i++) {
            String token = login("judge-player-" + run + "-" + i);
            Assertions.assertEquals(0, game.call(token, "/join", null).path("code").asInt());
            game.players.add(token);
        }
        return game;
    }

    private Game joinedGame(int boardId, int playerCount) throws Exception {
        Game game = joinedLobby(boardId, playerCount);
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

        /** 座位本人提交夜间行动（必须成功）。target 为空是不行动。 */
        JsonNode act(int seat, String action, Integer target) throws Exception {
            String body = target == null ? "{\"actionType\":\"" + action + "\"}"
                    : "{\"actionType\":\"" + action + "\",\"targetSeatNo\":" + target + "}";
            JsonNode response = call(players.get(seat - 1), "/night-action", body);
            Assertions.assertEquals(0, response.path("code").asInt(), response.toString());
            return response.path("data");
        }

        /** 把当前截止时间改到过去，再用房主拉一次快照，让惰性推进走一步。 */
        JsonNode expireDeadline() throws Exception {
            jdbcTemplate.update("UPDATE judge_rooms SET step_deadline = ? WHERE room_id = ?",
                    java.sql.Timestamp.valueOf("2000-01-01 00:00:00"), roomId);
            return snapshot(host);
        }

        /** 平安夜：谁都不行动，每一步到点。 */
        JsonNode finishNight() throws Exception {
            return finishNight(null, null);
        }

        /** 走完今晚：狼队那一步所有活狼刀 knife（null = 空刀），女巫那一步毒 poison（null = 不用药），其余步骤到点。返回天亮后的快照。 */
        JsonNode finishNight(Integer knife, Integer poison) throws Exception {
            Map<String, List<Integer>> roles = seatsByRole();
            JsonNode view = snapshot(host);
            boolean knifed = false;
            boolean poisoned = false;
            for (int i = 0; i < 12 && "night_action".equals(view.path("currentPhase").asText()); i++) {
                String kind = view.path("currentStep").path("kind").asText();
                if ("wolf".equals(kind) && knife != null && !knifed) {
                    knifed = true;
                    List<Integer> wolves = new ArrayList<>(roles.getOrDefault("狼人", List.of()));
                    wolves.addAll(roles.getOrDefault("黑狼王", List.of()));
                    for (int wolf : wolves) {
                        if (player(view, wolf).path("alive").asBoolean()) {
                            act(wolf, "kill", knife);
                        }
                    }
                    view = snapshot(host);
                    continue;
                }
                if ("witch".equals(kind) && poison != null && !poisoned) {
                    poisoned = true;
                    act(roles.get("女巫").get(0), "poison", poison);
                }
                view = expireDeadline();
            }
            return view;
        }

        /** 出局技能询问全部超时跳过。 */
        JsonNode skipPending() throws Exception {
            JsonNode view = snapshot(host);
            for (int i = 0; i < 10 && "skill_pending".equals(view.path("currentPhase").asText()); i++) {
                view = expireDeadline();
            }
            return view;
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

        /** 每个角色坐在哪些座位（升序）。 */
        Map<String, List<Integer>> seatsByRole() throws Exception {
            // 直接查库：无法官房的房主快照里看不到别人的身份
            Map<String, List<Integer>> seats = new TreeMap<>();
            for (Map<String, Object> row : jdbcTemplate.queryForList(
                    "SELECT seat_no, role_name FROM judge_room_players WHERE room_id = ? AND is_playing = 1 ORDER BY seat_no", roomId)) {
                Object role = row.get("ROLE_NAME") != null ? row.get("ROLE_NAME") : row.get("role_name");
                Object seat = row.get("SEAT_NO") != null ? row.get("SEAT_NO") : row.get("seat_no");
                seats.computeIfAbsent(String.valueOf(role), ignored -> new ArrayList<>()).add(((Number) seat).intValue());
            }
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
