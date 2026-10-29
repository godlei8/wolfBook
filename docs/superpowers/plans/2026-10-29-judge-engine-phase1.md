# 电子法官一期 · 后端结算引擎 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把夜间步骤、死亡结算、投票结算、上警、出局技能待办和胜负判定全部搬到后端，让有法官模式先跑在同一套引擎上，为二期无法官模式打底。

**Architecture:** `service/judge/` 下新增四个无 IO 的纯逻辑类（`RoleSkillRegistry`、`NightPlan`、`NightResolver`、`WinJudge`、`VoteResolver`），全部单测；`JudgeService` 只负责鉴权、读写库、组快照，并按 `auto_judge_enabled` 在「旧三段阶段机」和「新阶段机」之间切换，旧小程序建的房间行为不变。房间级规则在开局时从板子拷进 `rules_json`。

**Tech Stack:** Spring Boot 3.5 / Java 21、MyBatis-Plus、JUnit 5 + MockMvc（H2，`src/test/resources/schema-h2.sql`）。

**Spec:** `docs/design/judge-auto-mode.md`（§3–§5、§8–§11）。

## Global Constraints

- 只改 `backend/`；不动 `admin/` 和 `miniprogram-vue/`（二期做）。
- 现有小程序不传新字段也要能完整跑一局：`resolve_night` 带 `deadSeatNos` 时以法官为准；不带 `autoJudge` 建的房间不出现新阶段。
- 表结构三处同步：`backend/src/main/resources/schema.sql`、`backend/src/test/resources/schema-h2.sql`、`config/JudgeSchemaInitializer.java`；已有库补列放 `config/ContentSchemaInitializer.java`（不限 profile）。
- 所有状态切换走现有 `updateRoomGuarded`（WHERE 当前状态 + 阶段），并发重复请求 4002。
- 错误一律抛 `ApiException`：参数问题 4000、状态不对 4002、权限 4003、找不到 4004。
- 跑测试：`cd backend && export JAVA_HOME=/d/Kf/jdk21 && ./mvnw -q test -Dtest=<类名>`；全量 `./mvnw -q test`，现有 80 个用例必须继续通过。
- 提交信息格式沿用仓库：`feat(backend): …` / `test(backend): …`，结尾 `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`。
- 规则默认值（来自设计稿 §4.3、§4.5、§5）：狼队首夜 90 秒 / 其余 60；守卫、女巫、预言家 30；猎人 10；假步骤随机 15–25；上警报名 20；投票 30；技能询问 10 + 选目标 20；移交警徽 30；发言 120、警上 150。

## Review Focus

1. 同一房间两台手机同时提交或同时推进步骤（轮询恰好撞上）：第二个请求必须 4002 而不是把同一步结算两次。→ Task 8 的 `advancingTheSameStepTwiceIsRejected`。
2. 女巫自己被刀、板子规则「首夜可自救」、现在是第 2 夜：解药按钮不能生效，提交 `save` 要 4000。→ Task 4 的 `witchCannotSaveHerselfAfterFirstNight` + Task 8 的 `witchSelfSaveIsRejectedByRule`。
3. 猎人被毒同时被刀（两种死法叠加）：不能开枪。→ Task 4 的 `poisonedHunterCannotShootEvenIfAlsoKnifed`。
4. 屠边板子里最后一只狼和最后一个神职同一夜出局：好人胜，不是狼胜。→ Task 5 的 `simultaneousWipeoutGoesToGood`。
5. 白痴翻牌后第二天再被投：这次真出局，且仍然没有投票权、算存活神职直到出局。→ Task 10 的 `revealedIdiotDiesOnSecondExile`。

---

### Task 1: 房间级规则 `JudgeRules` 与板子 `judgeRules`

**Files:**
- Create: `backend/src/main/java/com/wolfbook/backend/domain/JudgeRules.java`
- Modify: `backend/src/main/java/com/wolfbook/backend/domain/BoardRuleDetail.java`（加字段 `judgeRules`）
- Modify: `backend/src/main/java/com/wolfbook/backend/service/AdminService.java`（`normalizeRuleDetail` 校验取值）
- Test: `backend/src/test/java/com/wolfbook/backend/domain/JudgeRulesTests.java`

**Interfaces:**
- Produces: `record JudgeRules(String winCondition, boolean sheriffDefault, String witchSelfSave, boolean witchOnePotionPerNight, int speechSeconds, int sheriffSpeechSeconds)`，常量 `WIN_SIDE = "side"`、`WIN_CITY = "city"`、`SELF_SAVE_FIRST_NIGHT = "first_night"`、`SELF_SAVE_NEVER = "never"`；`static JudgeRules defaults()`；`static JudgeRules fromBoard(Board board)`（`ruleDetail.judgeRules` 为空用默认，`winCondition` 由 `board.winCondition()` 文字推出：含「屠城」→ `city`，否则 `side`）；`JudgeRules withDefaults()` 把空字段补默认。
- `BoardRuleDetail` 新增第五个成分 `JudgeRules judgeRules`（可为 null，`isEmpty()` 把它算进去）。

- [ ] **Step 1: 写失败的单测**

```java
@Test void defaultsMatchTheSpec() {
    JudgeRules r = JudgeRules.defaults();
    assertEquals("side", r.winCondition()); assertTrue(r.sheriffDefault());
    assertEquals("first_night", r.witchSelfSave()); assertTrue(r.witchOnePotionPerNight());
    assertEquals(120, r.speechSeconds()); assertEquals(150, r.sheriffSpeechSeconds());
}
@Test void cityIsReadFromTheBoardsWinConditionText() {
    Board board = boardWith(winCondition = "屠城", ruleDetail = BoardRuleDetail.empty());
    assertEquals("city", JudgeRules.fromBoard(board).winCondition());
}
@Test void partialJudgeRulesFallBackPerField() {
    JudgeRules partial = new JudgeRules(null, false, "never", false, 0, 0);
    JudgeRules r = partial.withDefaults();
    assertEquals("never", r.witchSelfSave()); assertEquals(120, r.speechSeconds()); assertFalse(r.sheriffDefault());
}
```

- [ ] **Step 2: 跑，确认编译失败（类不存在）**

Run: `./mvnw -q test -Dtest=JudgeRulesTests` → Expected: FAIL (cannot find symbol JudgeRules)

- [ ] **Step 3: 实现 `JudgeRules`，给 `BoardRuleDetail` 加 `judgeRules` 成分**

`BoardRuleDetail` 是 record，加成分后更新 `empty()` 和所有 `new BoardRuleDetail(...)` 调用点（`grep -rn "new BoardRuleDetail(" backend/src`）。`AdminService.normalizeRuleDetail` 里：`witchSelfSave` 只接受两个常量，`speechSeconds`/`sheriffSpeechSeconds` 范围 30–300，否则 `ApiException(4000, "法官规则取值不合法")`。

- [ ] **Step 4: 跑单测和全量**

Run: `./mvnw -q test -Dtest=JudgeRulesTests` → PASS；`./mvnw -q test` → 全部通过。

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/wolfbook/backend/domain backend/src/main/java/com/wolfbook/backend/service/AdminService.java backend/src/test/java/com/wolfbook/backend/domain
git commit -m "feat(backend): board-level judge rules"
```

---

### Task 2: 角色技能登记表 `RoleSkillRegistry` 与真实的支持级别

**Files:**
- Create: `backend/src/main/java/com/wolfbook/backend/service/judge/RoleSkillRegistry.java`
- Modify: `backend/src/main/java/com/wolfbook/backend/service/BoardService.java:95-130`（详情和卡片的 `judgeSupportLevel` 改为计算值）
- Modify: `backend/src/main/java/com/wolfbook/backend/support/DatabaseSeeder.java:116`（白痴 `roleType` 改「神职」）
- Create: `deploy/sql/2026-10-29-idiot-role-type.sql`（`UPDATE roles SET role_type='神职' WHERE name='白痴' AND role_type='平民';`）
- Test: `backend/src/test/java/com/wolfbook/backend/service/judge/RoleSkillRegistryTests.java`

**Interfaces:**
- Produces: `enum NightKind { GUARD, WOLF, WITCH, SEER, HUNTER, NONE, GENERIC }`；`record RoleSkill(NightKind nightKind, Set<String> actions, boolean solo)`（`actions` 是允许的 `actionType` 英文枚举：`guard/none`、`kill/none`、`save/poison/none`、`check`、`confirm`）；`static Optional<RoleSkill> forRole(String roleName, String faction)`；`static boolean isWolfTeam(String roleName, String faction)`（阵营为狼人且不是石像鬼、机械狼）；`static String supportLevel(List<RoleEntity> lineup)` 返回 `JudgeSupportLevels` 的三个常量之一；`static final Map<String,String> LEGACY_ACTIONS`（中文 → 英文：刀人→kill、空刀→none、解药→save、毒药→poison、不用药→none、查验→check、守护→guard、空守→none、确认→confirm）。
- 一期登记：守卫、狼人（及所有 `isWolfTeam` 的狼按 WOLF）、女巫、预言家、猎人、白痴（NONE，不睁眼）、平民（NONE）。

- [ ] **Step 1: 写失败的单测**

```java
@Test void standardRolesAreRegistered() {
    assertEquals(NightKind.WITCH, RoleSkillRegistry.forRole("女巫", "好人").orElseThrow().nightKind());
    assertEquals(NightKind.NONE, RoleSkillRegistry.forRole("白痴", "好人").orElseThrow().nightKind());
    assertTrue(RoleSkillRegistry.forRole("摄梦人", "好人").isEmpty());
}
@Test void wolfTeamExcludesSoloWolves() {
    assertTrue(RoleSkillRegistry.isWolfTeam("白狼王", "狼人"));
    assertFalse(RoleSkillRegistry.isWolfTeam("石像鬼", "狼人"));
}
@Test void supportLevelFollowsTheLineup() {
    assertEquals("full", RoleSkillRegistry.supportLevel(roles("狼人","预言家","女巫","守卫","平民")));
    assertEquals("partial", RoleSkillRegistry.supportLevel(roles("狼人","预言家","摄梦人","平民")));
    assertEquals("manual_only", RoleSkillRegistry.supportLevel(roles("摄梦人","丘比特")));
}
```

- [ ] **Step 2: 跑，确认失败** — `./mvnw -q test -Dtest=RoleSkillRegistryTests` → FAIL

- [ ] **Step 3: 实现登记表；`BoardService` 的两处 `JudgeSupportLevels.normalize(null)` 改为 `RoleSkillRegistry.supportLevel(阵容角色实体)`；改种子和迁移 SQL**

- [ ] **Step 4: 加一条 MockMvc 用例到 `BackendApplicationTests`：`GET /api/boards/3` 的 `judgeSupportLevel` 为 `full`，`GET /api/boards/6`（黑狼王织梦人）为 `partial`**

- [ ] **Step 5: 跑单测 + 全量** → PASS

- [ ] **Step 6: Commit** — `feat(backend): role skill registry drives judge support level`

---

### Task 3: 夜间步骤表 `NightPlan`

**Files:**
- Create: `backend/src/main/java/com/wolfbook/backend/service/judge/NightPlan.java`
- Test: `backend/src/test/java/com/wolfbook/backend/service/judge/NightPlanTests.java`

**Interfaces:**
- Consumes: Task 2 的 `RoleSkillRegistry`、`NightKind`。
- Produces: `record NightStep(int index, NightKind kind, List<Integer> roleIds, String roleName, boolean team, String line, int durationSeconds)`；`static List<NightStep> build(Board board, Map<Integer, RoleEntity> rolesById, int dayNo)`：板子 `ruleDetail.nightOrder` 非空按它（狼队合并成一步，`team=true`，`roleIds` 为所有狼队角色），否则按 `DEFAULT_ORDER = [守卫, 狼人, 女巫, 预言家, 猎人]` 从阵容里挑；只生成阵容里有的角色（没有的不叫）；`line` 为「XX 请睁眼，<提示>」（提示见 `RoleSkillRegistry` 的 kind 默认句）；`durationSeconds`：WOLF 首夜 90 / 其余 60，GUARD/WITCH/SEER 30，HUNTER 10，GENERIC 25。
- `static void validateOrder(List<NightStep> steps)`：女巫必须在狼队之后、守卫在狼队之前，否则 `ApiException(4000, "夜间顺序：守卫须在狼人前、女巫须在狼人后")`（供 `AdminService.normalizeRuleDetail` 调用）。

- [ ] **Step 1: 写失败的单测**

```java
@Test void defaultOrderOnlyIncludesRolesInTheLineup()   // 守卫九人局 → [守卫, 狼人(team), 女巫, 预言家]，没有猎人
@Test void configuredOrderMergesWolvesIntoOneStep()     // nightOrder 里狼人、白狼王各一条 → 一步 team=true, roleIds 含两者
@Test void wolfDurationIs90OnTheFirstNightThen60()
@Test void witchBeforeWolvesIsRejected()                // validateOrder 抛 4000
```

- [ ] **Step 2: 跑，确认失败** → FAIL
- [ ] **Step 3: 实现 `NightPlan`；`AdminService.normalizeRuleDetail` 保存板子时调用 `validateOrder`**
- [ ] **Step 4: 跑单测 + 全量** → PASS
- [ ] **Step 5: Commit** — `feat(backend): server-side night plan`

---

### Task 4: 夜晚结算 `NightResolver`

**Files:**
- Create: `backend/src/main/java/com/wolfbook/backend/service/judge/NightResolver.java`
- Test: `backend/src/test/java/com/wolfbook/backend/service/judge/NightResolverTests.java`

**Interfaces:**
- Produces: `record Submission(int actorSeat, NightKind kind, String action, Integer targetSeat)`；`record SeatState(int seat, String roleName, String faction, boolean alive, boolean antidoteLeft, boolean poisonLeft, Integer lastGuardSeat)`；`record Outcome(Integer knifeSeat, List<Integer> deadSeats, Map<Integer,String> feedback, Set<Integer> cannotShoot, Set<Integer> antidoteUsedBy, Set<Integer> poisonUsedBy)`；
- `static Integer tallyKnife(List<Integer> picks, Random random)`：空列表 → null；按目标计数取最多；平票在平票目标中 `random` 选。
- `static Outcome resolve(List<Submission> submissions, Map<Integer,SeatState> seats, JudgeRules rules, int dayNo, Random random)`：刀口 = `tallyKnife(狼队 kill 提交)`；守护 = guard 提交（目标等于 `lastGuardSeat` 时视为空守）；解药仅当目标 = 刀口且女巫有药，且（目标 ≠ 女巫自己，或 `witchSelfSave = first_night` 且 `dayNo == 1`）；毒药不受守护影响；同守同救照死；`feedback`：预言家 seat → 「好人」/「狼人」，女巫 seat → 刀口描述；`cannotShoot` = 被毒的猎人座位。
- `static void validate(Submission s, SeatState actor, SeatState target, JudgeRules rules, int dayNo, Integer knifeSeat)` 抛 `ApiException(4000, …)`：守卫连守、女巫非法自救、女巫一晚两瓶（`witchOnePotionPerNight`）、没药还用药、目标已出局。

- [ ] **Step 1: 写失败的单测**

```java
@Test void majorityKnifeWins()                           // picks [3,3,7] → 3
@Test void tiedKnifeIsRandomAmongTied()                  // picks [3,7]，Random 固定种子 → 结果 ∈ {3,7}，且两种种子分别得到 3 和 7
@Test void noPicksMeansNoKnife()                         // [] → null，deadSeats 空
@Test void guardAndSaveOnTheSameSeatKillsHim()           // 守 3 + 救 3 + 刀 3 → 3 死
@Test void guardAloneSaves_poisonIgnoresGuard()          // 守 3 刀 3 → 活；守 4 毒 4 → 4 死
@Test void witchCannotSaveHerselfAfterFirstNight()       // dayNo=2，刀 女巫，save 女巫 → validate 抛 4000
@Test void witchMaySaveHerselfOnNightOneWhenRuleAllows() // dayNo=1，first_night → 活
@Test void poisonedHunterCannotShootEvenIfAlsoKnifed()   // 刀 猎人 + 毒 猎人 → deadSeats 含猎人，cannotShoot 含猎人
@Test void seerFeedbackNamesTheCamp()                    // check 狼 → "狼人"
@Test void consecutiveGuardIsRejected()                  // lastGuardSeat=5，guard 5 → 4000
```

- [ ] **Step 2: 跑，确认失败** → FAIL
- [ ] **Step 3: 实现 `NightResolver`**（纯函数，不碰 Spring）
- [ ] **Step 4: 跑单测** → PASS
- [ ] **Step 5: Commit** — `feat(backend): night resolver`

---

### Task 5: 胜负判定 `WinJudge`

**Files:**
- Create: `backend/src/main/java/com/wolfbook/backend/service/judge/WinJudge.java`
- Test: `backend/src/test/java/com/wolfbook/backend/service/judge/WinJudgeTests.java`

**Interfaces:**
- Produces: `record Seat(boolean alive, String faction, String roleType)`；`record Verdict(String camp, String reason)`（`camp` 为「好人」/「狼人」，`reason` 如「狼人阵营全部出局」「神职全部出局」「平民全部出局」「好人全部出局」）；`static Optional<Verdict> judge(List<Seat> seats, String winCondition)`：先查好人胜（存活狼为 0），再查狼胜（`side`：神职全灭或平民全灭；`city`：神职和平民全灭）。翻牌白痴由调用方传 `alive=true`、`roleType=神职`。

- [ ] **Step 1: 写失败的单测**

```java
@Test void goodWinsWhenAllWolvesAreOut()
@Test void sideRuleWolvesWinWhenAllGodsAreOut()         // 3 民活、0 神 → 狼
@Test void cityRuleNeedsAllGoodOut()                     // 同上输入 city → empty
@Test void simultaneousWipeoutGoesToGood()               // 0 狼 0 神 2 民 side → 好人
@Test void nothingYet()                                  // 2 狼 2 神 2 民 → empty
```

- [ ] **Step 2: 跑，确认失败** → FAIL
- [ ] **Step 3: 实现** — [ ] **Step 4: 跑单测** → PASS
- [ ] **Step 5: Commit** — `feat(backend): win judge`

---

### Task 6: 投票结算 `VoteResolver`

**Files:**
- Create: `backend/src/main/java/com/wolfbook/backend/service/judge/VoteResolver.java`
- Test: `backend/src/test/java/com/wolfbook/backend/service/judge/VoteResolverTests.java`

**Interfaces:**
- Produces: `record Ballot(int voterSeat, Integer targetSeat, boolean sheriff)`（`targetSeat` null = 弃票）；`record Tally(int targetSeat, double weight, List<Integer> voterSeats)`；`record Outcome(Integer eliminatedSeat, List<Integer> tiedSeats, List<Tally> tallies)`；`static Outcome resolve(List<Ballot> ballots, Set<Integer> allowedTargets)`：警长票重 1.5；最高且唯一 → `eliminatedSeat`；最高并列 → `tiedSeats`（≥2）且 `eliminatedSeat` null；全弃票 → 两者都空。

- [ ] **Step 1: 写失败的单测**

```java
@Test void sheriffVoteWeighsOnePointFive()               // 2 票投 A，警长 1 票 + 1 普通票投 B → B (2.5)
@Test void tieReturnsTiedSeatsAndNoElimination()
@Test void allAbstainEliminatesNobody()
@Test void ballotsOutsideAllowedTargetsAreIgnored()      // PK 时只能投平票者
```

- [ ] **Step 2–4: 失败 → 实现 → PASS**
- [ ] **Step 5: Commit** — `feat(backend): vote resolver with sheriff weight`

---

### Task 7: 表结构与实体

**Files:**
- Modify: `backend/src/main/resources/schema.sql`（judge_rooms、judge_room_players 两张表）
- Modify: `backend/src/test/resources/schema-h2.sql`（同上）
- Modify: `backend/src/main/java/com/wolfbook/backend/config/JudgeSchemaInitializer.java`（建表语句）
- Modify: `backend/src/main/java/com/wolfbook/backend/config/ContentSchemaInitializer.java:58`（之后追加 `addColumnIfMissing`）
- Modify: `backend/src/main/java/com/wolfbook/backend/entity/JudgeRoomEntity.java`、`JudgeRoomPlayerEntity.java`
- Test: 现有 `JudgeFlowIntegrationTests` 全部仍过（H2 建表即验证）

**Interfaces:**
- `judge_rooms` 新列：`sheriff_enabled TINYINT DEFAULT 0`、`night_step TINYINT NULL`、`step_deadline DATETIME NULL`、`phase_data JSON NULL`、`rules_json JSON NULL`。复用已有 `auto_judge_enabled`（新阶段机开关）。
- `judge_room_players` 新列：`is_sheriff TINYINT DEFAULT 0`、`sheriff_candidate TINYINT DEFAULT 0`、`sheriff_withdrew TINYINT DEFAULT 0`、`can_vote TINYINT DEFAULT 1`、`status_json JSON NULL`。
- 实体字段同名驼峰；`phaseData`、`rulesJson`、`statusJson`、`stepDeadline`、`nightStep` 标 `@TableField(updateStrategy = FieldStrategy.ALWAYS)`（要能清空）。

- [ ] **Step 1: 三处 DDL + `ContentSchemaInitializer` 补列 + 实体字段**
- [ ] **Step 2: 全量测试** → 80+ 用例通过（H2 用新建表语句）
- [ ] **Step 3: Commit** — `feat(backend): judge room columns for the resolution engine`

---

### Task 8: `JudgeService` 接入引擎（服务端夜间步骤 + 引擎结算 + 自动胜负）

**Files:**
- Modify: `backend/src/main/java/com/wolfbook/backend/service/JudgeService.java`（`createRoom`、`startRoom`、`advanceRoom`、`submitNightAction`、`handleResolveNight`、`handleResolveVote`、`handleFinish`、`buildSnapshot`）
- Modify: `backend/src/main/java/com/wolfbook/backend/dto/WolfbookDtos.java`（`JudgeRoomCreateRequest` 加 `Boolean autoJudge`、`Boolean sheriffEnabled`；`JudgeNightActionRequest.actionType` 接受英文枚举；`JudgeRoomSnapshotView` 加 `currentStep`、`rules`、`host`、`winVerdict`；新增 `record JudgeStepView(int index, int total, String kind, String roleName, String line, LocalDateTime deadline)`、`record JudgeVerdictView(String camp, String reason)`）
- Test: `backend/src/test/java/com/wolfbook/backend/controller/JudgeFlowIntegrationTests.java`

**Interfaces:**
- Consumes: Task 1–6 全部。
- 行为：
  - `createRoom`：`rulesJson = JudgeRules.fromBoard(board)`；`autoJudgeEnabled = request.autoJudge() == true`；`sheriffEnabled = autoJudge && (request.sheriffEnabled() != null ? it : rules.sheriffDefault())`；`joined` 仍抛 4002「二期开放」（二期放开）。
  - `startRoom`：`nightStep = 0`，`stepDeadline = now + steps[0].duration`，`phaseData = {}`。
  - 新增 `advance` action `next_step`（法官手动推进）：关闭当前步骤（WOLF 步关闭时 `phaseData.knifeSeat = tallyKnife(...)`，随机种子用 `new Random()`），`nightStep + 1`，更新 deadline；最后一步之后进入 `handleResolveNight` 的引擎路径。
  - `submitNightAction`：`actionType` 先经 `LEGACY_ACTIONS` 映射；校验「当前步骤轮到这个角色」（`nightStep` 不为 null 时）和 `NightResolver.validate`；写事件时 payload 存英文 `actionType`。旧客户端（房间 `nightStep` 为 null 的历史房）跳过步骤校验。
  - `handleResolveNight`：`deadSeatNos` 非空 → 法官改判（照旧，另记 `night_override` 事件，`judge_only`）；为空 → `NightResolver.resolve` 算死亡，私密反馈写 `actor_only` 事件 `night_feedback`；女巫用药写回 `statusJson`；被毒猎人 `statusJson.canShoot=false`。之后 `WinJudge.judge`：有结果 → `finished`，`winnerCamp = camp`，公告 = reason + 「，X 阵营胜利」，事件 `game_finished_auto`；无结果 → `autoJudgeEnabled ? day_announce : day_speech`。
  - `handleResolveVote`：票由 `VoteResolver.resolve` 算（警长权重）；`eliminatedSeatNo` 传入仍以法官为准；之后同样 `WinJudge`。
  - `handleFinish` 不传 `winnerCamp` 时记为「流局」而不是推断。
  - `buildSnapshot`：`currentStep` 对持有者给完整 `line`，对其他玩家 `line` 为空字符串；`host = self != null && openid.equals(room.getOwnerUserId())`；`winVerdict` 结束后给。
  - 删除 `inferWinnerCamp` 的「狼 ≥ 好人」分支，`winnerSuggestion` 改为 `WinJudge` 的 reason 预览（没达成时空串）。

- [ ] **Step 1: 改写 `automaticWinnerIsOnlyASuggestionUntilJudgeConfirms`，并新增用例（都先写、先红）**

```java
@Test void engineResolvesTheNightWhenJudgeOmitsDeadSeats()
   // 守卫九人局：狼 kill 5，女巫 save 5 → resolve_night 不带 deadSeatNos → 平安夜，5 号仍 alive
@Test void judgeOverrideStillWins()                        // 带 deadSeatNos:[2] → 2 号死，事件里有 night_override
@Test void guardAndSaveTogetherKillInIntegration()          // guard 5 + kill 5 + save 5 → 5 死
@Test void witchSelfSaveIsRejectedByRule()                  // 第 2 夜女巫被刀，save 自己 → code 4000
@Test void sideRuleFinishesTheGameAutomatically()           // 改判杀光 3 个神职 → roomStatus finished，winnerCamp 狼人，winVerdict.reason 含「神职」
@Test void advancingTheSameStepTwiceIsRejected()            // 连发两次 next_step（第二次用过期的步骤号）→ 第二次 4002
@Test void legacyChineseActionTypesStillWork()              // actionType "查验" 仍 0
@Test void sheriffVoteCountsOnePointFive()                  // 直接设 is_sheriff（通过 Task 9 的接口或 SQL）后 2 vs 1.5+1 → 后者出局
```

- [ ] **Step 2: 跑 `JudgeFlowIntegrationTests`** → 新用例 FAIL，旧用例 PASS
- [ ] **Step 3: 实现上述行为**（建议把夜间步骤相关私有方法放到同包新类 `JudgeNightFlow`，`JudgeService` 组合它，避免再往 1500 行里加）
- [ ] **Step 4: 跑 `JudgeFlowIntegrationTests` + 全量** → PASS
- [ ] **Step 5: Commit** — `feat(backend): judge rooms resolve nights and verdicts on the server`

---

### Task 9: 新阶段机：`day_announce`、上警、PK（仅 `auto_judge_enabled`）

**Files:**
- Modify: `backend/src/main/java/com/wolfbook/backend/service/JudgeService.java`
- Create: `backend/src/main/java/com/wolfbook/backend/service/judge/JudgeDayFlow.java`（白天阶段的私有逻辑，`JudgeService` 组合）
- Modify: `backend/src/main/java/com/wolfbook/backend/controller/api/ApiJudgeController.java`（`POST /rooms/{roomId}/sheriff`）
- Modify: `backend/src/main/java/com/wolfbook/backend/dto/WolfbookDtos.java`（`record JudgeSheriffRequest(Boolean candidate, Boolean withdraw, Integer targetSeatNo, String speechOrder)`；`JudgeRoomAdvanceRequest` 加 `List<Integer> sheriffCandidates`、`Integer speechStartSeat`、`String speechDirection`；快照加 `sheriffSeatNo`、`speech{order:List<Integer>, index:int}`、`pkSeats`）
- Test: `JudgeFlowIntegrationTests`

**Interfaces:**
- 阶段常量：`day_announce`、`sheriff_signup`、`sheriff_speech`、`sheriff_vote`、`sheriff_pk`、`vote_result`（票型公布，快照 `voteTallies` 对全场可见、含每位投票人的目标）、`vote_pk`。
- `advance` 新 action：`start_speech`（`day_announce → day_speech`，参数 `speechStartSeat/speechDirection`，没传则：有死者从死者左边顺时针，否则随机）、`next_speaker`（**当前发言者本人或房主**可调；`JudgeNightFlow.tick` 在发言超时后也调它）、`continue`（`vote_result → ` 遗言待办 / `vote_pk` / 下一夜）、`close_sheriff_signup`（报名截止 → 0 人或全员 → 警徽流失进 `day_announce`；1 人直接当选；否则 `sheriff_speech`）、`start_sheriff_vote`、`resolve_sheriff_vote`（`VoteResolver`，投票人限警下；平票 → `sheriff_pk`，PK 后再平 → 流失）、`start_night`。
- `POST /sheriff`：`candidate` 报名（仅 `sheriff_signup`）、`withdraw` 退水（仅 `sheriff_speech`）、`targetSeatNo` 警长投票（仅 `sheriff_vote`/`sheriff_pk`，投票人须非候选）、`speechOrder`（警长在 `day_announce` 指定 `left/right/dead_left/dead_right`）。
- 放逐平票：`handleResolveVote` 得到 `tiedSeats` 且房间 `autoJudgeEnabled` → `vote_pk`，`phaseData.pkSeats`；PK 投票只收非平票者的票、只能投 `pkSeats`；再平 → 无人出局进下一夜。旧房间（非 autoJudge）保持现状：平票无人出局。
- 有法官模式：法官用 `advance` 带 `sheriffCandidates` 代勾报名。
- 对局记录：`JudgeDayFlow.buildRounds(room, players, events)` 把公开事件按 `dayNo` 折叠成 `List<JudgeRoundView>`（`record JudgeRoundView(int dayNo, List<Integer> nightDeaths, JudgeSheriffRoundView sheriff, JudgeSpeechOrderView speechOrder, List<JudgeExileRoundView> exile, List<JudgeSkillRecordView> skills)`，子 record 字段见设计稿 §10），快照对**所有人**下发；`vote_resolved` 的 `resultPayload.tallies` 补上 `abstainSeatNos` 和 `weight`（警长 1.5）。旧房间（非 autoJudge）同样能生成，只是没有警长和 PK 段。

- [ ] **Step 1: 新用例（先红）**

```java
@Test void autoJudgeRoomGoesToDayAnnounceThenSpeech()
@Test void sheriffElectionEndsWithOneAndAHalfVotes()      // 报名 2 人，警下投票，当选者 is_sheriff=1，sheriffSeatNo 在快照里
@Test void nobodyOrEveryoneOnStageLosesTheBadge()
@Test void candidatesCannotVoteForSheriff()               // 候选人投 → 4003
@Test void exileTieGoesToPkAndOnlyOthersVote()            // 平票 → vote_pk；平票者投票 4003；再平 → 无人出局、进下一夜
@Test void legacyRoomsNeverSeeTheNewPhases()              // 不带 autoJudge 的房 resolve_night 后 currentPhase == day_speech
@Test void roundsRecordDeathsVotesAndSheriffForEveryPlayer() // 玩家视角快照 rounds[0] 有昨夜死讯、警长当选、放逐票型（含 voterSeatNos 和 weight），但没有夜间行动
```

- [ ] **Step 2: 跑** → FAIL — [ ] **Step 3: 实现** — [ ] **Step 4: 跑 + 全量** → PASS
- [ ] **Step 5: Commit** — `feat(backend): sheriff election, day announce and PK phases`

---

### Task 10: 出局待办 `skill_pending`：猎人开枪、移交警徽、白痴翻牌、点杀

**Files:**
- Modify: `JudgeService.java` / `JudgeDayFlow.java`
- Modify: `ApiJudgeController.java`（`POST /rooms/{roomId}/skill`）
- Modify: `WolfbookDtos.java`（`record JudgeSkillRequest(String decision, Integer targetSeatNo)`，`decision ∈ use/skip`；快照加 `pending{kind, deadline, options}` 只发当事人；`JudgeRoomAdvanceRequest` 加 `Integer forceOutSeatNo`）
- Test: `JudgeFlowIntegrationTests`

**Interfaces:**
- 出局事件统一入口 `JudgeDayFlow.onPlayersOut(room, players, seats, cause)`：按座位号顺序把每个出局者压入 `phaseData.pending[]`（`{seat, kind: skill_ask|hunter_shot|badge, deadline}`），`phaseData.resumePhase = 当前阶段`，阶段切到 `skill_pending`。**先问所有人的技能再回到遗言**，每个出局者都有一条 `skill_ask`（10 秒）：有技能者 `use` 进入 `hunter_shot`（20 秒选人），`skip`/超时略过；无技能者只有 `skip`；警长出局追加 `badge`（30 秒；`use` + `targetSeatNo` 移交，`skip`/超时撕掉）。猎人身份的警长：先 `hunter_shot` 再 `badge`。
- 被枪者再次走 `onPlayersOut`（连锁）。每次出局后调 `WinJudge`；对局结束时清空 `pending`，不再处理。
- 白痴：`handleResolveVote` 放逐到未翻牌白痴 → 不死，`statusJson.idiotRevealed=true`、`can_vote=0`，公告「X 号是白痴，翻牌免死」，无待办；已翻牌再被放逐 → 正常出局。`WinJudge` 的 `Seat` 由 `roleType`（已是神职）构成。
- 点杀：`advance` action `force_out`（仅 `judgeViewer`，`forceOutSeatNo`）→ `onPlayersOut(cause=judge)`，公告注明「法官判罚」。
- 旧房间（非 autoJudge）不走待办，猎人仍由法官手动处理（现状）。

- [ ] **Step 1: 新用例（先红）**

```java
@Test void everyOutPlayerGetsTheSameSkillPrompt()        // 平民出局也有 skill_ask，options 只有 skip
@Test void hunterShotChainsIntoTheNextVictim()           // 猎人开枪带走警长 → pending 再出现 badge
@Test void poisonedHunterGetsNoShotOption()
@Test void revealedIdiotDiesOnSecondExile()
@Test void pendingIsDroppedOnceTheGameIsDecided()        // 最后一个神职是猎人，被刀 → finished，无 pending
@Test void onlyTheJudgeCanForceOut()
```

- [ ] **Step 2: 跑** → FAIL — [ ] **Step 3: 实现** — [ ] **Step 4: 跑 + 全量** → PASS
- [ ] **Step 5: Commit** — `feat(backend): pending skills, idiot reveal and judge force-out`

---

### Task 11: 惰性推进（超时）与假步骤

**Files:**
- Modify: `JudgeService.java` / `JudgeNightFlow.java`（`getRoomSnapshot`、`submitNightAction`、`submitVote`、`submitSheriff`、`submitSkill` 入口先调 `tick(room)`）
- Test: `backend/src/test/java/com/wolfbook/backend/service/judge/JudgeNightFlowTests.java`（纯逻辑：`shouldClose(step, submissions, holders, now)`）+ `JudgeFlowIntegrationTests`

**Interfaces:**
- `JudgeNightFlow.tick(JudgeRoomEntity room, List<JudgeRoomPlayerEntity> players)`：仅 `autoJudgeEnabled` 房间；当前阶段有 `stepDeadline` 且（`now > deadline`，或这一步是狼队步骤且所有存活狼已提交）→ 做与 `next_step` / 对应 `resolve_*` 相同的推进。**单人步骤提交了也不提前关闭**（设计稿「零场外」原则：提前切步等于告诉全场持有者动作快慢）；用 `updateRoomGuarded` 带 `night_step` 条件，失败静默（另一请求已推进）。
- 假步骤：`NightPlan.build` 照常生成所有阵容角色的步骤；持有者全部出局的步骤 `durationSeconds = 15 + random(0..10)`，无人可提交，到点自动过。
- 超时默认：未提交的持有者按 `none` 处理；投票超时 = 弃票；`skill_ask` 超时 = skip。
- `ZoneId` 继续用 `APP_ZONE`；测试里通过把 `stepDeadline` 直接写成过去时间来模拟超时（`JudgeRoomMapper.updateById`）。

- [ ] **Step 1: 新用例（先红）**

```java
// 纯逻辑
@Test void wolfStepClosesWhenAllAliveWolvesSubmitted()
@Test void singleHolderStepWaitsForDeadlineEvenAfterSubmit()
@Test void closesWhenDeadlinePassed()
@Test void deadRoleStepHasNoHoldersButStillWaits()       // 持有者全死 → holders 空，deadline 15–25 秒内
// 集成
@Test void pollingAfterDeadlineAdvancesTheStep()         // 把 deadline 改成过去 → GET 快照后 nightStep+1
@Test void wolvesSubmittingAllAdvancesImmediately()
```

- [ ] **Step 2: 跑** → FAIL — [ ] **Step 3: 实现** — [ ] **Step 4: 跑 + 全量** → PASS
- [ ] **Step 5: Commit** — `feat(backend): lazy step advancement with timeouts and decoy steps`

---

### Task 12: 文档与收尾

**Files:**
- Modify: `CLAUDE.md`、`AGENTS.md`（电子法官小节：引擎、阶段机、`auto_judge_enabled` 开关、新接口、新测试文件）
- Modify: `docs/design/judge-auto-mode.md`（把一期落地时与设计不同的决定记进 §12）

- [ ] **Step 1: 更新两份说明文件里的「电子法官子系统」段落（保持两份一致）**
- [ ] **Step 2: 全量测试** → PASS
- [ ] **Step 3: Commit** — `docs: judge engine phase 1`

---

## 自检记录

- 设计稿 §4.1 四个组件 → Task 2–6；§4.2 阶段机 → Task 8–10；§4.3 惰性推进与假步骤 → Task 11；§4.5 白天与投票 → Task 9；§4.6 上警 → Task 9；§4.7 待办 → Task 10；§4.8 点杀 / 流局 → Task 10 / Task 8；§5 表结构 → Task 7；§8 胜负 → Task 5 + Task 8；§9 支持级别 → Task 2；§10 接口 → Task 8–10。
- 不在一期：撤销上一步（rollback）、`requireHost` 与无法官模式的 `joined` 放开、语音片段、所有前端——二期计划另写。
- 类型一致性：`NightKind` 在 Task 2 定义、Task 3/4 复用；`JudgeRules` 在 Task 1 定义、Task 4/8 复用；`Verdict` 在 Task 5 定义、Task 8 的 `JudgeVerdictView` 由它转换。
