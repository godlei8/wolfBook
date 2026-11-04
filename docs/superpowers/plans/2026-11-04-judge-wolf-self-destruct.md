# 狼人自爆 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新流程电子法官房间里，角色配置了「可以自爆」的狼白天发言时能自爆，包括警上自爆把竞选挪到第二天、第二天再爆吞警徽。

**Architecture:** 角色上加一列 `can_self_destruct`，开局拷进座位。`JudgeService` 加一个玩家自己提交的 `selfDestruct`，按当时阶段分两条路：白天发言 → 直接天黑（警长先移交警徽）；警上发言 → 公布昨夜死讯、停在 `day_announce`，房主点新动作 `start_night` 天黑。「竞选延到明天」不加列，从事件里推出来：有过警上自爆、还没有 `sheriff_resolved`。小程序把按钮放进「身份」弹层。

**Tech Stack:** Spring Boot 3.5 / Java 21 / MyBatis-Plus / H2 测试；uni-app Vue 3 + Node 内置测试；Vue 3 + Vite 后台。

**Spec:** `docs/superpowers/specs/2026-11-04-judge-wolf-self-destruct-design.md`

## Global Constraints

- 只对新流程房间（`auto_judge_enabled = 1`）生效；旧流程房间提交自爆一律 4002「这个房间不支持自爆」。
- 能自爆的阶段只有 `sheriff_speech` 和 `day_speech`；报名、投票、票型公布、技能询问、公布死讯阶段都不能。暂停中 4002「法官暂停中」。
- 公告固定写法：「X号自爆」，不提角色。
- 自爆的人没有遗言、不进技能询问（狼王也不能开枪）；是警长的话照常进移交警徽待办。
- 昨夜死讯没公布前，死者在库里仍是活的，可以自爆、仍算延期竞选的候选人（沿用现在「死者也能上警」的口径）。
- 自爆和压着的昨夜死讯算同一次出局判胜负，双方同时达成算狼人胜（`WinJudge` 现有规则）。
- 延期竞选：候选人 = 第一天报名、没退水、还活着的人，不再报名；0 人或全员都在警上 → 警徽流失，1 人 → 直接当选（和 `handleCloseSheriffSignup` 同口径）。
- 角色补列初始值：机制是 `wolf` 或 `wolf_king` 的角色 `can_self_destruct = 1`，其余 0（依据 `docs/judge/boards/`：狼人、黑狼王可以自爆；狼美人、机械狼、梦魇、石像鬼不能；白狼王的自爆带人以后单独做）。非狼人阵营（`faction != '狼人'`）的角色保存时一律存 0。
- 座位上 `can_self_destruct` 为 null（升级前开局）按不能自爆。
- 零场外：「能不能自爆」只在本人快照的 `dayInfo.canSelfDestruct` 里，别人拿不到；按钮只出现在「身份」弹层里。
- 后端测试命令：在 `backend/` 下 `JAVA_HOME=/d/Kf/jdk21 ./mvnw test`（单个类加 `-Dtest=…`）；小程序 `node --test "tests/*.test.mjs"`（在 `miniprogram-vue/`）；后台 `node --test "tests/*.test.mjs"` 和 `npm run build`（在 `admin/`）。

## Review Focus

1. 第一夜被刀的狼在警上自爆：他只算一次出局，公告里不重复写他，`nightDeaths` 和自爆座位都标成出局。→ Task 3 测试 `knifedWolfCanStillExplodeOnStage`。
2. 自爆后马上判出胜负（比如最后一只狼自爆）：不进待办、不进黑夜，直接 `finished`。→ Task 2 测试 `lastWolfExplodingEndsTheGame`。
3. 警长在白天发言时自爆：先移交警徽待办，处理完（含超时）进黑夜，不回到 `day_speech`。→ Task 2 测试 `sheriffExplodesThenPassesTheBadgeThenNight`。
4. 两个狼几乎同时点自爆：第二个拿到 4002，只记一次自爆。→ Task 2 测试 `secondExplosionInTheSameDayIsRejected`。
5. 警上自爆后房主在 `day_announce` 点「开始发言」：被拒（4002），只能点「天黑」；平安夜也停在 `day_announce`。→ Task 3 测试 `explodedAnnounceOnlyGoesToNight`。

---

### Task 1: 角色「可以自爆」配置并拷进座位

**Files:**
- Modify: `backend/src/main/resources/schema.sql`、`backend/src/test/resources/schema-h2.sql`（`roles`、`judge_room_players` 各加 `can_self_destruct TINYINT(1)`，roles 默认 0）
- Modify: `backend/src/main/java/com/wolfbook/backend/config/ContentSchemaInitializer.java`（两张表补列；roles 补列成功时 `UPDATE roles SET can_self_destruct = 1 WHERE mechanic IN ('wolf', 'wolf_king')`）
- Modify: `backend/src/main/java/com/wolfbook/backend/config/JudgeSchemaInitializer.java`（`judge_room_players` 建表语句加列）
- 不写 `deploy/sql` 脚本（Ruling：设计稿 §4.1 提到一个，但 `ContentSchemaInitializer` 不限 profile，线上启动时补列并回填，列一旦存在回填就不该再跑，否则会冲掉后台改过的值）
- Modify: `entity/RoleEntity.java`、`entity/JudgeRoomPlayerEntity.java`（`Boolean canSelfDestruct`）
- Modify: `dto/WolfbookDtos.java`：`AdminRoleRequest` 末尾加 `Boolean canSelfDestruct`，`AdminRoleView` 末尾加 `boolean canSelfDestruct`
- Modify: `service/AdminService.java`（`saveRole`：`entity.setCanSelfDestruct("狼人".equals(request.faction()) && Boolean.TRUE.equals(request.canSelfDestruct()))`；转 view 时带上）
- Modify: `support/DatabaseSeeder.java`（`seedRoleMechanics` 后给机制 `wolf` / `wolf_king` 的种子角色设 `canSelfDestruct = true`）
- Modify: `service/JudgeService.java:413`（开局 `player.setCanSelfDestruct(role.getCanSelfDestruct())`）
- Test: `backend/src/test/java/com/wolfbook/backend/BackendApplicationTests.java`、`controller/JudgeFlowIntegrationTests.java`

**Interfaces:**
- Produces: `RoleEntity.getCanSelfDestruct()` / `JudgeRoomPlayerEntity.getCanSelfDestruct(): Boolean`；后台角色接口字段名 `canSelfDestruct`（Task 5 用）。

- [ ] **Step 1: 写失败测试**
  - `BackendApplicationTests.adminSavesWhetherAWolfCanSelfDestruct`：PUT 一个 `faction=狼人`、`canSelfDestruct=true` 的角色 → 返回 `canSelfDestruct == true`；同一请求改成 `faction=好人` → 返回 `false`；GET 角色列表里种子「狼人」「黑狼王」是 `true`、「狼美人」「白狼王」是 `false`。
  - `JudgeFlowIntegrationTests.dealCopiesSelfDestructToTheSeat`：`startedGame()` 后查 `SELECT can_self_destruct FROM judge_room_players WHERE room_id = ? AND role_name = '狼人'` 全是 1，`role_name = '平民'` 全是 0。
- [ ] **Step 2:** `./mvnw test -Dtest=BackendApplicationTests#adminSavesWhetherAWolfCanSelfDestruct+JudgeFlowIntegrationTests#dealCopiesSelfDestructToTheSeat` → FAIL（字段 / 列不存在）。
- [ ] **Step 3:** 按 Files 实现。
- [ ] **Step 4:** 同一命令 → PASS；再跑全量 `./mvnw test` → 全绿。
- [ ] **Step 5:** 提交 `feat(judge): add a per-role self-destruct switch`。

### Task 2: 白天发言时自爆

**Files:**
- Modify: `controller/api/ApiJudgeController.java`（`@PostMapping("/rooms/{roomId}/self-destruct")`，无请求体，调 `judgeService.selfDestruct(authorization, roomId)`）
- Modify: `service/JudgeService.java`
- Modify: `service/judge/JudgeDayFlow.java:131`（`"self_destruct"` 也进 `round.skills`，`actorSeatNo` 是自爆座位，`targetSeatNo` 为 null）
- Modify: `dto/WolfbookDtos.java`：`JudgeDayInfoView` 末尾加 `boolean canSelfDestruct`（所有现有构造处补 `false`）
- Test: `controller/JudgeFlowIntegrationTests.java`

**Interfaces:**
- Produces（`JudgeService`）：
  - `public JudgeRoomSnapshotView selfDestruct(String authorization, String roomId)`（`@Transactional(isolation = READ_COMMITTED)`，开头照 `submitSheriff` 的写法 `resolveRoomId` → `tick` → `lockRoom` → `requireNotPaused`）。
  - 常量 `EVENT_SELF_DESTRUCT = "self_destruct"`；事件 payload `{"seatNo": n, "duringSheriff": bool}`，`VISIBILITY_PUBLIC`。
  - `private boolean canSelfDestruct(JudgeRoomEntity room, JudgeRoomPlayerEntity self)`：新流程、`playing`、本人入座且活着、座位 `canSelfDestruct == TRUE`、阶段是 `sheriff_speech` / `day_speech`、没暂停。快照 `dayInfo` 和提交校验共用它。
  - `private void enterNight(JudgeRoomEntity room, String announcement, LocalDateTime now)`：从 `handleContinue` 抽出来的「天数 +1、`night_action`、写公告、`openNight`」，`handleContinue` 改成 `enterNight(room, "天黑请闭眼", now)`。
- 提交校验：不在新流程房间 4002「这个房间不支持自爆」；`canSelfDestruct` 为假 4003「你现在不能自爆」（阶段不对也是 4003）。
- `day_speech` 分支：`markPlayersDead([seat], day, PHASE_DAY_SPEECH)`，记事件，公告「X号自爆」；`verdictOf` 有结果 → `finishByVerdict(room, verdict, "X号自爆。" + reason)`；否则自爆者是警长 → `startPending` 只放一条 `badge`（不放 `skill_ask`），并在 `phase_data` 写 `nightAfterPending: true`；不是警长 → `enterNight(room, "X号自爆，天黑请闭眼", now)`。写回用 `updateRoomGuarded(room, PLAYING, PHASE_DAY_SPEECH)`。
- `resolvePending` 里 `pending.isEmpty()` 时：`phase_data.nightAfterPending` 为真 → `enterNight(room, 当前公告 + "，天黑请闭眼", now)`，不回 `resumePhase`。
- `buildDayInfo`：`sheriff_speech` / `day_speech` 也返回 `JudgeDayInfoView`（`sheriffCandidate` null、投票字段 0/false），`canSelfDestruct` 用上面的函数；其他阶段原样，`canSelfDestruct` 填 false。

- [ ] **Step 1: 写失败测试**（用 `autoGame(false)`，把它推到 `day_speech`：`finishNight(平民, null)` → 过掉待办 → `start_speech`；狼座位用 `seatsByRole().get("狼人")`）
  - `wolfExplodesDuringDaySpeechAndNightFalls`：狼提交 → `code 0`，`currentPhase == "night_action"`，`currentDay == 2`，`latestAnnouncement == "X号自爆，天黑请闭眼"`，事件列表里有 `self_destruct`，该狼 `alive == false`；`rounds[0].skills` 里有 `{kind: self_destruct, actorSeatNo: 狼}`。
  - `onlyAliveSelfDestructSeatsMayExplode`：平民提交 → 4003；死了的狼 → 4003（先 `markDead`）；`exile_vote` 阶段的狼 → 4003；旧流程房间（`startedGame()`）→ 4002；暂停中 → 4002。
  - `onlyTheWolfSeesItCanExplode`：`day_speech` 时狼的快照 `dayInfo.canSelfDestruct == true`，平民的 `false`，法官的 `dayInfo` 是 null 或 false。
  - `lastWolfExplodingEndsTheGame`：`markDead` 掉其他狼，最后一只狼自爆 → `roomStatus == "finished"`、`winnerCamp` 是好人、没有 `pending`。
  - `sheriffExplodesThenPassesTheBadgeThenNight`：用 `autoGame(true)` 选出一个狼当警长（参照 `sheriffElectionEndsWithOneAndAHalfVotes` 让狼上警当选），进 `day_speech` 后警长狼自爆 → `skill_pending`，待办只有一条 `badge`、没有技能询问；警长提交 `{"decision":"use","targetSeatNo":x}` → `night_action`，x 是警长。另一路：不提交，`expire(game)` 后轮询 → 也是 `night_action`。
  - `secondExplosionInTheSameDayIsRejected`：两只狼先后提交，第二个 → 4003（已经天黑，阶段不对），`self_destruct` 事件只有 1 条。
- [ ] **Step 2:** `./mvnw test -Dtest=JudgeFlowIntegrationTests` → 新测试 FAIL（404 / 字段不存在）。
- [ ] **Step 3:** 实现。
- [ ] **Step 4:** 同一命令 → PASS；全量 `./mvnw test` → 全绿（`JudgeFlowRepeatableReadTests` 会自动多跑一遍）。
- [ ] **Step 5:** 提交 `feat(judge): wolves can self-destruct during day speech`。

### Task 3: 警上自爆、延期竞选、双爆吞警徽

**Files:**
- Modify: `service/JudgeService.java`
- Modify: `dto/WolfbookDtos.java`：`JudgeRoomSnapshotView` 加 `boolean nightNext`（`day_announce` 且 `phase_data.nightNext` 为真；也覆盖待办期间，因为待办后回到 `day_announce`）
- Test: `controller/JudgeFlowIntegrationTests.java`

**Interfaces:**
- Consumes: Task 2 的 `selfDestruct` / `canSelfDestruct` / `enterNight` / `EVENT_SELF_DESTRUCT`。
- Produces:
  - 推进动作 `start_night`（房主，`requireAdvancePermission` 走默认房主分支即可）：`requirePhase(day_announce)` 且 `phase_data.nightNext` 为真，否则 4002「现在不能直接天黑」；然后 `enterNight`。
  - `handleStartSpeech`：`phase_data.nightNext` 为真时 4002「今天有人自爆，直接天黑」。
  - `private boolean sheriffDeferred(String roomId)`：事件里有 `self_destruct` 且 payload `duringSheriff == true`，并且没有任何 `sheriff_resolved` 事件。
  - `enterDayAnnounce(room, players, prefix, now)` 加一个参数 `boolean nightNext`：为真时写进 `phase_data`（在 `startPending` 之前），现有调用传 false。
  - `private void openSheriffSpeech(JudgeRoomEntity room, List<JudgeRoomPlayerEntity> players, List<Integer> candidates, String opening, LocalDateTime now)`：从 `handleCloseSheriffSignup` 抽出「0 人或全员 → 流失、1 人 → 当选、否则进 `sheriff_speech` 并排发言顺序」，两处共用。
- `sheriff_speech` 分支（`selfDestruct` 里）：
  - 自爆座位 `markPlayersDead(.., PHASE_SHERIFF_SPEECH)`，记 `self_destruct`（`duringSheriff: true`）。
  - 如果这是延期的那次竞选（调用前 `sheriffDeferred` 已经为真）→ 先记一条和 `finishSheriff` 同格式的 `sheriff_resolved`（`electedSeatNo: null`、`lost: true`），前缀「X号自爆，警徽流失。」；否则前缀「X号自爆，警长竞选推迟到明天。」。
  - 从 `phase_data.pendingDeaths` 去掉自爆座位后调 `enterDayAnnounce(room, players, prefix, now, true)`（它标昨夜死者出局、判胜负、写 `night_resolved`、开待办）。
  - 写回 `updateRoomGuarded(room, PLAYING, PHASE_SHERIFF_SPEECH)`。
- `resolveNight` 里进上警的条件改成 `autoJudge && sheriffEnabled && (dayNo == 1 || sheriffDeferred(roomId))`：
  - 第一天照旧进报名。
  - 延期：`pendingDeaths` 照旧压着，公告前缀「天亮了，昨天的警长竞选继续。」，调 `openSheriffSpeech(onStage(players), …)`；流失 / 当选的路径会走 `finishSheriff` → `enterDayAnnounce`。写回仍是 `updateRoomGuarded(room, PLAYING, PHASE_NIGHT_ACTION, expectedNightStep)`。

- [ ] **Step 1: 写失败测试**（`autoGame(true)`；让两个好人和一只狼报名，`close_sheriff_signup` 进 `sheriff_speech`）
  - `explodingOnStageDefersTheElection`：第一夜刀一个没上警的平民；狼自爆 → `skill_pending`（平民的技能询问）；平民 `skip` 后 `day_announce`、`nightNext == true`、公告含「X号自爆，警长竞选推迟到明天」和昨夜死讯；房主 `start_night` → `night_action`、`currentDay == 2`。
  - `explodedAnnounceOnlyGoesToNight`：上一条的 `day_announce` 里房主 `start_speech` → 4002；平安夜（`finishNight` 空刀）那一路也停在 `day_announce`。
  - `knifedWolfCanStillExplodeOnStage`：第一夜刀那只上警的狼（`finishNight(狼, null)`），他在 `sheriff_speech` 里仍 `alive` 且 `canSelfDestruct == true`；自爆后公告里他只出现一次（「X号自爆」），`night_resolved` 的 `deadSeatNos` 不含他，他 `alive == false`。
  - `deferredElectionResumesOnDayTwoWithTheSameCandidates`：接第一条，第二夜结束后 → `sheriff_speech`（不是 `sheriff_signup`），公告含「昨天的警长竞选继续」，警上的人 = 第一天报名、没退水、活着的；投票选出警长后 → `day_announce`，第二夜死讯此时才公布。
  - `secondExplosionOnStageLosesTheBadge`：第二天竞选里另一只狼自爆 → 公告含「警徽流失」，之后第三天不再进任何 `sheriff_*` 阶段，`sheriffSeatNo` 为 null。
  - `deferredElectionWithOneCandidateLeftElectsThem` / `…WithNoneLeftLosesTheBadge`：延期时 `markDead` 到只剩 1 个 / 0 个候选人 → 直接 `day_announce`，前者 `sheriffSeatNo` 是那个人，后者公告含「警徽流失」。
  - `explodingAfterTheElectionIsPlain`：第一天正常选出警长后进 `day_speech` 狼自爆 → 第二天夜里结束直接 `day_announce`，不再竞选。
- [ ] **Step 2:** `./mvnw test -Dtest=JudgeFlowIntegrationTests` → 新测试 FAIL。
- [ ] **Step 3:** 实现。
- [ ] **Step 4:** 同一命令 → PASS；全量 `./mvnw test` → 全绿。
- [ ] **Step 5:** 提交 `feat(judge): self-destruct on stage defers the sheriff election`。

### Task 4: 小程序

**Files:**
- Modify: `miniprogram-vue/src/services/api.js`（`selfDestructJudge(roomId)`：`POST /api/judge/rooms/${roomId}/self-destruct`，写法同 `skill`）
- Modify: `miniprogram-vue/src/utils/judge/identity.js`（`identityCard` 返回值加 `canSelfDestruct: Boolean(snapshot.dayInfo?.canSelfDestruct)`）
- Modify: `miniprogram-vue/src/pages/judge/room.vue`（身份弹层里 `identity.canSelfDestruct` 时多一个红色按钮「自爆」，点了 `uni.showModal` 二次确认「确定自爆？自爆后直接出局，没有遗言」→ 调接口 → 关弹层、刷新快照；失败按现有 `act` 的错误提示）
- Modify: `miniprogram-vue/src/utils/judge/day.js`（`hostActions`：`day_announce` 且 `snapshot.nightNext` 时动作是 `[['start_night', '天黑']]`）
- Modify: `miniprogram-vue/src/utils/judge/rounds.js`（`skillText` 加 `case 'self_destruct': return \`${skill.actorSeatNo} 号自爆\``）
- Modify: `miniprogram-vue/src/utils/judge/voice-cue.js`（`day_announce` 且 `nightNext`：台词是 `[snapshot.latestAnnouncement, '天黑请闭眼']`，key 带 `:explode`；`sheriff_speech` 在 `currentDay > 1` 时第一句前加「昨天的警长竞选继续」）
- Modify: `miniprogram-vue/src/pages/judge/views/SheriffView.vue`（`currentDay > 1` 时标题写「昨天的竞选继续」）
- Test: `miniprogram-vue/tests/judge-identity.test.mjs`（没有就新建）、`judge-day.test.mjs`、`judge-rounds.test.mjs`、`judge-voice-cue.test.mjs`

**Interfaces:**
- Consumes: 快照 `dayInfo.canSelfDestruct`、`nightNext`、事件 / rounds 里的 `self_destruct`、推进动作 `start_night`。

- [ ] **Step 1: 写失败测试**
  - `identityCard` 在 `dayInfo.canSelfDestruct: true` 时 `canSelfDestruct === true`，`dayInfo` 为 null 时 `false`。
  - `hostActions({host: true, roomStatus: 'playing', currentPhase: 'day_announce', nightNext: true})` 的 action 列表是 `['start_night']`（无法官房间再加 `finish`）；`nightNext` 为假时仍是 `['start_speech']`。
  - rounds：`skills: [{kind: 'self_destruct', actorSeatNo: 5}]` → 文案「5 号自爆」。
  - voice-cue：`day_announce` + `nightNext` → `lines` 末尾是「天黑请闭眼」，key 和不带 `nightNext` 的不同。
- [ ] **Step 2:** `node --test "tests/*.test.mjs"` → 新测试 FAIL。
- [ ] **Step 3:** 实现。
- [ ] **Step 4:** 同一命令 → 全绿；`npm run build:mp-weixin` 通过。
- [ ] **Step 5:** 提交 `feat(miniprogram): self-destruct from the identity sheet`。

### Task 5: 后台角色编辑页

**Files:**
- Modify: `admin/src/types.ts`（角色类型和保存请求加 `canSelfDestruct: boolean`）
- Modify: `admin/src/components/roles/RoleEditor.vue`（技能机制下拉下面，`form.faction === '狼人'` 时显示开关「可以自爆」，说明「电子法官里，这个角色白天发言时可以自爆」；保存时带 `canSelfDestruct: form.faction === '狼人' && form.canSelfDestruct`）

- [ ] **Step 1:** 改 `types.ts` 和 `RoleEditor.vue`。
- [ ] **Step 2:** 在 `admin/` 下 `npm run build` → 通过；`node --test "tests/*.test.mjs"` → 全绿。
- [ ] **Step 3:** 提交 `feat(admin): self-destruct switch on wolf roles`。

### Task 6: 联调脚本和文档

**Files:**
- Modify: `miniprogram-vue/scripts/judge-e2e.mjs`（新模式 `explode`，`both` 里也跑：无法官「预女猎白」，开上警；第一天让一只狼和两个好人上警，警上第一位发言时狼自爆；校验推迟公告、房主 `start_night`；第二天校验直接进 `sheriff_speech`、另一只狼再自爆、公告含「警徽流失」；之后按现有逻辑打到结束。每轮快照照常过零场外检查，另外检查非狼人的 `dayInfo.canSelfDestruct` 从来不是 true）
- Modify: `docs/design/judge-auto-mode.md`（§17 全局规则表加「自爆」一行，写落地记录；§11 分期里那段自爆口径规划时已改成双爆吞警徽）
- Modify: `docs/judge/boards/README.md`（2.8 自爆、2.11 全局裁定表的 ✅ 标记；2.4 / 2.8 的口径在规划时已按用户决定改过，这里只把 🔧 换成 ✅）、`02-预女猎白.md` / `08-狼王守卫.md` 里自爆相关条目标 ✅
- Modify: `CLAUDE.md`、`AGENTS.md`（电子法官：状态机 / 推进动作加 `start_night` 和 `POST /self-destruct`、快照字段 `dayInfo.canSelfDestruct` / `nightNext`、角色 `can_self_destruct`、`ContentSchemaInitializer` 补列清单、测试清单）

- [ ] **Step 1:** 改脚本；本地起 H2 后端（见 CLAUDE.md 外的做法：`spring-boot:run` + test classpath + H2 参数），`node scripts/judge-e2e.mjs http://127.0.0.1:8088 explode` → 打完一局、无报错。
- [ ] **Step 2:** 改文档。
- [ ] **Step 3:** 全量回归：后端 `./mvnw test`、小程序和后台 `node --test`、后台 `npm run build` 全绿。
- [ ] **Step 4:** 提交 `docs(judge): wolf self-destruct rules and e2e`。
