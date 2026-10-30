# 电子法官二期 A：后端与后台 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让后端支撑二期的两种模式（无法官开放、有法官切到同一套自动流程），并把角色技能机制落成 `mechanic` 字段，后台能配。

**Architecture:** 引擎从「按角色名登记」改成「按 `Mechanic` 枚举登记」，发身份时把机制拷进座位；房间规则在建房 / 大厅时合并进 `rules_json`；新流程房间里推进权按「房主」与「法官」两种身份分开校验，法官只剩暂停 / 继续和点杀；投票和上警报名接入惰性推进的超时；快照按查看者补上狼队、女巫、预言家各自的私密信息。旧流程房间（线上旧版小程序建的）行为不变。

**Tech Stack:** Spring Boot 3.5 / Java 21 / MyBatis-Plus / H2 测试；Vue 3 + TypeScript 后台。

**Spec:** `docs/design/judge-auto-mode.md`（§4.3、§4.4、§4.5、§4.6、§4.9、§4.10、§4.11、§5、§7、§9、§10、§11 二期）

**分拆说明：** 二期跨三个子系统，拆成三份计划，每份单独可测：
- **2A（本计划）**：后端 + 后台。
- **2B**：小程序（建房、大厅、房间页按视图拆分、闭眼屏与各角色面板、上帝视角、白天操作栏）——吃 2A 的快照。
- **2C**：语音片段（TTS 预生成、清单接口、角色专属语音、客户端播放与 WechatSI 兜底）。

## Global Constraints

- 旧流程房间（`auto_judge_enabled = 0`）的接口行为不变：法官代记、`next_step`、`resolve_night` 带 `deadSeatNos` 改判、`finish` 流局都照旧（§5「兼容线上旧小程序」）。
- 零场外：夜里任何一台非房主手机和别的手机不能不同；私密信息只发给对应身份；暂停时所有人看到同一张画面（§4.4、§4.10）。
- 有法官模式：房主必须是法官，不入座；法官对局中的干预只有「暂停 / 继续计时」和「点杀」；没有改判、代操作、提前切步、撤销、流局（§4.10）。
- 玩法规则跟板子走：胜负规则、女巫自救、女巫一晚一瓶不能在房间里改；房间规则只有上警开关、发言时长、警上 / 警长发言时长、狼队行动时长、单人角色行动时长（§4.10）。
- 机制清单（§4.11）：`villager` 平民、`wolf` 狼人、`guard` 守卫、`witch` 女巫、`seer` 预言家、`hunter` 猎人、`idiot` 白痴；夜间排序 守卫 1 → 狼队 2 → 女巫 3 → 预言家 4；`roles.mechanic` 为 NULL = 没有引擎认识的机制，只播报不结算。
- 抛 `ApiException`：4000 参数不对、4002 状态不对 / 并发、4003 没权限；文案中文。
- 表结构改动四处一起改：`schema.sql`、`schema-h2.sql`、`JudgeSchemaInitializer`（judge 表）、`ContentSchemaInitializer` 补列（已有库）。后台可清空的列标 `@TableField(updateStrategy = FieldStrategy.ALWAYS)`。
- 真实密钥只在 gitignore 的文件里，任何提交都不带。
- 提交信息结尾：`Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`。

## Review Focus

1. 暂停时正有出局技能询问在倒计时 → 继续后每条待办的截止时间一起顺延，不会一继续就超时跳过（Task 6：`pauseShiftsPendingDeadlines`）。
2. 无法官模式的房主夜里出局 → 白天推进按钮照样归他（§2「房主出局后照旧」）（Task 5：`deadJoinedHostStillAdvancesTheDay`）。
3. 升级前已经开局的房间，座位上没有 `mechanic` → 继续结算不报错、按角色名兜底（Task 3：`roomsDealtBeforeMechanicsStillResolve`）。
4. 狼队意见、队友座位只给狼队，女巫刀口只在她那一步给她，好人和死后的人拿不到别人的私密信息（Task 8：`nonWolvesNeverSeeWolfInfo`）。
5. 投票全员投完自动结算的同时房主点了「结算」→ 只结算一次，另一个拿到 4002（Task 7：`autoAndManualVoteResolveOnlyOnce`）。

**命令约定**（在 `backend/` 下）：单测 `JAVA_HOME=/d/Kf/jdk21 ./mvnw -q test -Dtest=<类名>`；全量 `JAVA_HOME=/d/Kf/jdk21 ./mvnw -q test`，结果看 `target/surefire-reports`。后台在 `admin/` 下 `npm run build`。

---

### Task 1: `Mechanic` 枚举与按机制登记

**Files:**
- Create: `backend/src/main/java/com/wolfbook/backend/service/judge/Mechanic.java`
- Modify: `backend/src/main/java/com/wolfbook/backend/service/judge/RoleSkillRegistry.java`
- Modify: `entity/RoleEntity.java`（加 `mechanic`，`FieldStrategy.ALWAYS`）、`entity/JudgeRoomPlayerEntity.java`（加 `mechanic`）、`backend/src/main/resources/schema.sql` 与 `backend/src/test/resources/schema-h2.sql`（两表加 `mechanic VARCHAR(40) NULL`）——实体加字段后 MyBatis-Plus 会查这一列，所以列和字段必须同一任务加；已有库补列、回填、种子、后台接口在 Task 2。
- Test: `backend/src/test/java/com/wolfbook/backend/service/judge/RoleSkillRegistryTests.java`

**Interfaces:**
- Produces:
  - `enum Mechanic { VILLAGER, WOLF, GUARD, WITCH, SEER, HUNTER, IDIOT }`，字段 `String code()`（小写英文）、`String label()`（中文名）、`NightKind nightKind()`、`Set<String> actions()`、`Integer nightPriority()`（无夜间行动为 null）、`boolean wolfTeam()`、`boolean solo()`（单人神职：守卫 / 女巫 / 预言家 / 猎人 / 白痴）、`String brief()`（一句话，后台下拉用）。
  - `static Optional<Mechanic> Mechanic.fromCode(String code)`：大小写不敏感、去空格；空或未知 → empty。
  - `RoleSkillRegistry.legacyMechanic(String roleName)`：一期的名字表（守卫、狼人、女巫、预言家、猎人、白痴、平民）→ `Optional<Mechanic>`，只给升级前的数据兜底。
  - `RoleSkillRegistry.isWolfTeam(Mechanic mechanic, String roleName, String faction)`：有机制按 `mechanic.wolfTeam()`；没有机制沿用一期规则（阵营狼人且不是石像鬼 / 机械狼）。
  - `RoleSkillRegistry.supportLevel(List<RoleEntity> lineup)` 改按 `Mechanic.fromCode(role.getMechanic())` 判断；种子角色要到 Task 2 才有机制，所以本任务结束时 `boardDetailReportsHowMuchTheElectronicJudgeSupports` 这类依赖种子的用例可能暂时变红——本任务的 Step 4 只要求 `RoleSkillRegistryTests` 绿、工程能编译，全量绿放到 Task 2 结束时。
  - 删掉 `forRole(String, String)` 和名字登记表 `REGISTERED`；`RoleSkill` record 去掉，调用方直接用 `Mechanic`。

- [ ] **Step 1: 写失败测试**（替换 `RoleSkillRegistryTests` 里按名字的用例）

```java
@Test void fromCodeIgnoresCaseAndUnknown() {
    assertEquals(Optional.of(Mechanic.GUARD), Mechanic.fromCode(" Guard "));
    assertTrue(Mechanic.fromCode("demon_hunter").isEmpty());
    assertTrue(Mechanic.fromCode(null).isEmpty());
}
@Test void renamedRoleWithAMechanicCountsAsSupported() {
    // 角色叫「看门人」，机制是守卫：板子仍是 full
    assertEquals(JudgeSupportLevels.FULL, RoleSkillRegistry.supportLevel(List.of(
            role("看门人", "好人", "guard"), role("狼人", "狼人", "wolf"), role("村民", "好人", "villager"))));
}
@Test void roleWithoutMechanicMakesTheBoardPartial() {
    assertEquals(JudgeSupportLevels.PARTIAL, RoleSkillRegistry.supportLevel(List.of(
            role("守卫", "好人", null), role("狼人", "狼人", "wolf"))));
}
@Test void wolfTeamFollowsTheMechanicFirst() {
    assertTrue(RoleSkillRegistry.isWolfTeam(Mechanic.WOLF, "夜行者", "狼人"));
    assertFalse(RoleSkillRegistry.isWolfTeam(null, "石像鬼", "狼人"));
}
@Test void nightPriorityMatchesTheSpec() {
    assertEquals(List.of(1, 2, 3, 4), List.of(Mechanic.GUARD.nightPriority(), Mechanic.WOLF.nightPriority(),
            Mechanic.WITCH.nightPriority(), Mechanic.SEER.nightPriority()));
}
```

- [ ] **Step 2: 跑测试确认失败**：`-Dtest=RoleSkillRegistryTests` → 编译失败（`Mechanic` 不存在）。
- [ ] **Step 3: 实现 `Mechanic` 和新的 `RoleSkillRegistry` 方法**；动作集合沿用一期：守卫 `guard/none`、狼 `kill/none`、女巫 `save/poison/none`、预言家 `check`、猎人 `confirm`，平民 / 白痴空集。
- [ ] **Step 4: 跑测试确认通过**；调用方（`NightPlan`、`NightResolver`、`JudgeService`、`BoardService`）的编译错误留给 Task 3 一起改——本任务结束时整个工程要能编译：先把调用方改成 `legacyMechanic(name)` 过渡，Task 3 再换成座位上的机制。
- [ ] **Step 5: 提交** `feat(backend): judge engine registers roles by mechanic`

---

### Task 2: 机制落库与后台角色接口

**Files:**
- Modify: `config/ContentSchemaInitializer.java`（两列补列 + 回填）、`config/JudgeSchemaInitializer.java`（建表语句带 `mechanic`）
- Modify: `support/DatabaseSeeder.java`（7 个种子角色写机制）、`dto/WolfbookDtos.java`、`service/AdminService.java`、`controller/admin/AdminController.java`
- Create: `deploy/sql/2026-10-30-role-mechanic.sql`
- Test: `backend/src/test/java/com/wolfbook/backend/BackendApplicationTests.java`

**Interfaces:**
- Produces:
  - 列：`roles.mechanic VARCHAR(40) NULL`、`judge_room_players.mechanic VARCHAR(40) NULL`。
  - `ContentSchemaInitializer`：补 `roles.mechanic` 列**时**顺带执行按名字回填（守卫 → guard、狼人 → wolf、女巫 → witch、预言家 → seer、猎人 → hunter、白痴 → idiot、平民 → villager，只更新 `mechanic IS NULL` 的行）；补 `judge_room_players.mechanic` 列不回填。迁移脚本内容同回填，可重复执行。
  - `AdminRoleRequest` 加 `String mechanic`（可空；非空时必须是 `Mechanic.fromCode` 认识的，否则 4000「未知的技能机制」）；`AdminRoleView` 加 `String mechanic`。
  - `GET /admin/roles/mechanics` → `List<MechanicView>`，`record MechanicView(String code, String label, String brief, Integer nightPriority, boolean wolfTeam)`，按 `Mechanic` 声明顺序。

- [ ] **Step 1: 写失败测试**

```java
@Test void adminCanSetAndClearARoleMechanic()      // 保存 mechanic=guard → 读回 guard；再保存 mechanic=null → 读回 null
@Test void adminRejectsAnUnknownMechanic()         // mechanic=demon_hunter → code 4000，msg 含「技能机制」
@Test void mechanicsEndpointListsTheSevenMechanics() // GET /admin/roles/mechanics → 7 项，第一项 code=villager，含 guard 的 nightPriority=1
@Test void seededRolesCarryTheirMechanic()         // /admin/roles 里 守卫 的 mechanic=guard、骑士 的 mechanic=null
```

- [ ] **Step 2: 跑测试确认失败**：`-Dtest=BackendApplicationTests` → 4 个新用例失败。
- [ ] **Step 3: 实现**：补列与回填；种子用 `saveRole` 之后补一句按名字 `UPDATE`（不改 `saveRole` 的参数列表）；接口和校验。
- [ ] **Step 4: 跑测试确认通过**，再跑全量确认没有回归。
- [ ] **Step 5: 提交** `feat(backend): roles carry a judge mechanic`

---

### Task 3: 夜间编排按机制

**Files:**
- Modify: `service/judge/NightPlan.java`、`service/judge/NightResolver.java`、`service/JudgeService.java`、`service/BoardService.java`
- Test: `service/judge/NightPlanTests.java`、`service/judge/NightResolverTests.java`、`controller/JudgeFlowIntegrationTests.java`

**Interfaces:**
- Consumes: Task 1 `Mechanic`、`RoleSkillRegistry.legacyMechanic / isWolfTeam`；Task 2 `RoleEntity.mechanic`、`JudgeRoomPlayerEntity.mechanic`。
- Produces:
  - `NightPlan` 没配 `nightOrder` 时：阵容角色按 `mechanic.nightPriority()` 升序排（同优先级按阵容顺序），狼队机制合成一步；没有机制的角色排在所有有机制的步骤之后，每个一步、`NightKind.GENERIC`。删掉 `DEFAULT_ORDER`。配了 `nightOrder` 的行为不变。
  - `NightResolver.SeatState` 的 `roleName, faction` 之后加 `Mechanic mechanic`（可空）；猎人 / 女巫判断改看 `mechanic`。
  - `JudgeService.startRoom` 发身份时 `player.setMechanic(role.getMechanic())`。
  - `JudgeService` 私有 `Optional<Mechanic> mechanicOf(JudgeRoomPlayerEntity player)`：`Mechanic.fromCode(player.getMechanic())`，没有再 `legacyMechanic(player.getRoleName())`（只给升级前的座位兜底）；`nightKindOf`、`validateNightAction`、`skillOptions`、`seatStates` 全部改用它。

- [ ] **Step 1: 写失败测试**

```java
// NightPlanTests
@Test void autoOrderFollowsMechanicPriority()   // 阵容 预见者(seer)、夜行者(wolf)、配药人(witch)、看门人(guard)、无名(null) → kinds = GUARD, WOLF, WITCH, SEER, GENERIC
@Test void configuredNightOrderStillWins()       // 配了 nightOrder 时顺序照配置
// NightResolverTests：把 SeatState 构造全部补上 mechanic 参数，新增
@Test void poisonedHunterIsFoundByMechanic()     // 座位名叫「枪手」、mechanic=HUNTER，被毒 → cannotShoot 含该座位
// JudgeFlowIntegrationTests
@Test void dealCopiesTheMechanicToTheSeat()      // 开局后 jdbc 查 judge_room_players：守卫座位 mechanic='guard'
@Test void roomsDealtBeforeMechanicsStillResolve() // 开局后 jdbc 把本房所有座位 mechanic 置 NULL，狼刀一人、走完夜晚 → 天亮出局名单含被刀的人
```

- [ ] **Step 2: 跑测试确认失败**：`-Dtest=NightPlanTests,NightResolverTests,JudgeFlowIntegrationTests`。
- [ ] **Step 3: 实现**；`BoardService` 的支持级别已在 Task 1 改为按机制，这里只确认它拿到的 `RoleEntity` 带 `mechanic`。
- [ ] **Step 4: 跑测试确认通过**，再跑全量。
- [ ] **Step 5: 提交** `feat(backend): night steps follow role mechanics`

---

### Task 4: 房间规则

**Files:**
- Modify: `domain/JudgeRules.java`、`service/judge/NightPlan.java`、`service/JudgeService.java`、`service/AdminService.java`、`dto/WolfbookDtos.java`、`controller/api/ApiJudgeController.java`
- Test: `domain/JudgeRulesTests.java`、`service/judge/NightPlanTests.java`、`controller/JudgeFlowIntegrationTests.java`

**Interfaces:**
- Produces:
  - `JudgeRules` 末尾加 `Integer wolfSeconds`（默认 60）、`Integer roleSeconds`（默认 30）；保留 6 参构造器（委托到 8 参，新字段传 null）让现有调用不变；`withDefaults()` 补默认值。后台 `normalizeJudgeRules` 校验 `wolfSeconds` 30–120、`roleSeconds` 15–60。
  - `NightPlan.build(..., int dayNo, JudgeRules rules)` 新重载：狼队首夜 `wolfSeconds + 30`、之后 `wolfSeconds`；守卫 / 女巫 / 预言家 `roleSeconds`；猎人 10；GENERIC 25。旧签名委托 `JudgeRules.defaults()`。`JudgeService.nightSteps` 用 `roomRules(room)`。
  - `record JudgeRoomRulesInput(Boolean sheriffEnabled, Integer speechSeconds, Integer sheriffSpeechSeconds, Integer wolfSeconds, Integer roleSeconds)`；`JudgeRoomCreateRequest` 末尾加 `JudgeRoomRulesInput rules`。范围：发言 30–300、狼队 30–120、单人 15–60，超范围 4000「房间规则超出范围」。
  - 合并规则：板子 `JudgeRules.fromBoard` 打底，`rules` 里非空的字段覆盖节奏项；胜负 / 女巫三项永远取板子的（输入里没有这些字段）。`sheriffEnabled` 写 `judge_rooms.sheriff_enabled`。
  - `PUT /api/judge/rooms/{roomId}/rules`（body `JudgeRoomRulesInput`）：只认房主（Task 5 的 `requireHost`，本任务先用 `requireJudgeRoom`，Task 5 统一替换）；非大厅 4002「开局后规则已锁定」。

- [ ] **Step 1: 写失败测试**

```java
@Test void defaultsIncludeActionSeconds()           // JudgeRules.defaults().wolfSeconds()==60, roleSeconds()==30
@Test void durationsComeFromTheRoomRules()          // NightPlan.build(..., 1, rules(wolf=40, role=20)) → 狼队 70 秒、守卫 20 秒；第 2 夜狼队 40
@Test void createRoomAppliesTheRoomRules()          // 建房 rules{wolfSeconds:40} → 开局后第一步是守卫（板子 3），把守卫那步过掉后狼队那步 deadline 离现在约 70 秒（≥68）
@Test void rulesCanOnlyChangeInTheLobby()           // 大厅里改 speechSeconds=90 → 快照 rules.speechSeconds==90；开局后再改 → 4002
@Test void outOfRangeRoomRulesAreRejected()         // speechSeconds=10 → 4000
@Test void roomRulesNeverChangeTheWinCondition()    // 建房 rules 里多塞 winCondition 字段被忽略，快照 rules.winCondition 仍是板子的 side
```

- [ ] **Step 2: 跑测试确认失败。**
- [ ] **Step 3: 实现。**
- [ ] **Step 4: 跑测试确认通过**，再跑全量。
- [ ] **Step 5: 提交** `feat(backend): room pacing rules set at creation and in the lobby`

---

### Task 5: 开放无法官模式，推进权分房主与法官

**Files:**
- Modify: `service/JudgeService.java`、`dto/WolfbookDtos.java`
- Test: `controller/JudgeFlowIntegrationTests.java`、`controller/JudgeFlowRepeatableReadTests.java`（继承，无需改）

**Interfaces:**
- Produces:
  - `normalizeJudgeMode`：`joined` 只对支持级别 `full` 的板子开放，否则 4000「这个板子只能开有法官」；不再抛「二期开放」。
  - 建房 `joined`：强制 `autoJudge = true`；房主插入为座位 1 的玩家（`playing=true, ready=false, judgeObserver=false, roomOwner=true`），房间写 `ownerPlayerId / ownerSeatNo`。`observer` 照旧（房主是法官观察者）。
  - `private JudgeRoomEntity requireHost(String roomId, String openid)`：`owner_user_id` 不等 → 4003「只有房主可以执行此操作」。
  - 权限表（新流程房间 = `auto_judge_enabled`）：
    - `start`、`PUT rules`、advance 的 `broadcast / start_vote / resolve_vote / close_sheriff_signup / start_sheriff_vote / resolve_sheriff_vote / start_speech / continue` → `requireHost`（旧流程房间仍 `requireJudgeRoom`，有法官时两者是同一人）。
    - `next_speaker`：发言者本人或房主（替换原来的「或法官」判断为「或房主」）。
    - `force_out`、`pause`、`resume`（Task 6）→ `requireJudgeRoom`；无法官房间没有法官，自然 4003。
    - 新流程房间里：`next_step`、`resolve_night` → 4003「新流程由系统推进」；`finish` → 无法官房主可用（强制结束，记流局），有法官 4003「有法官模式不能流局」；`night-action` 带 `actorSeatNo` → 4003「新流程里请玩家自己操作」；`/skill` 只认当前待办的座位本人（删掉法官代答分支）；`close_sheriff_signup` 带 `sheriffCandidates` → 4000「上警由玩家自己选」。
  - 快照：`canStart`、`canAdvance` 改按 `host` 判断。
  - 测试辅助（`Game` 内）：`JsonNode act(int seat, String action, Integer target)`（座位本人提交夜间行动并断言 0）、`void expireDeadline()`（jdbc 把 `step_deadline` 置为 2000-01-01 后用房主拉一次快照）、`void finishNight()`（夜里反复 `expireDeadline`，最多 10 次直到离开 `night_action`）、`void skipPending()`（`skill_pending` 时反复 `expireDeadline`）；`Game joinedGame(int boardId, int playerCount)`（房主坐 1 号，其余人入座，全员准备，开局）。无法官房间没有法官，房主快照看不到别人的身份，所以 `seatsByRole()` 在无法官房间改为用 jdbc 查 `judge_room_players` 的 `seat_no, role_name`。
  - **改写现有用例**：`autoGame` / `autoGame12` 里凡是用 `record(...)`、`next_step`、`resolve_night` 的，改用 `act` + `finishNight` / `skipPending`：要死人就让所有狼 `act(kill, x)`（要毒就女巫在她那一步 `act(poison, x)`），平安夜就什么都不交直接 `finishNight`。断言的业务含义不变；改写后 `autoGame` 房间里不再出现法官代操作。

- [ ] **Step 1: 写失败测试**

```java
@Test void joinedHostTakesSeatOneAndStartsTheGame()    // joined 建房 → 快照 selfPlayer.seatNo==1、judgeViewer==false、host==true；其余 8 人入座准备后房主 start → playing
@Test void joinedModeIsOnlyForFullBoards()             // 板子 1（含狼美人、骑士）joined 建房 → 4000
@Test void judgeCannotDriveTheAutoFlow()               // autoGame：法官 actorSeatNo 代记 → 4003；next_step → 4003；resolve_night → 4003；finish → 4003
@Test void joinedHostCanForceFinish()                  // joinedGame 里房主 finish → finished，winnerCamp=流局
@Test void deadJoinedHostStillAdvancesTheDay()         // joinedGame：所有狼 act(kill, 1)（狼可以自刀，1 号拿什么牌都刀得到）、finishNight、skipPending → 1 号已出局，房主 start_speech 仍成功
@Test void playersCannotUseHostActions()               // joinedGame：2 号调 start_speech → 4003
```

- [ ] **Step 2: 跑测试确认失败。**
- [ ] **Step 3: 实现权限与建房；改写现有 `autoGame` 用例。**
- [ ] **Step 4: 跑 `JudgeFlowIntegrationTests,JudgeFlowRepeatableReadTests` 确认全绿**，再跑全量。
- [ ] **Step 5: 提交** `feat(backend): no-judge rooms open; hosts drive the day, judges only pause and force out`

---

### Task 6: 暂停 / 继续，点杀不丢夜间截止时间

**Files:**
- Modify: `service/JudgeService.java`、`dto/WolfbookDtos.java`
- Test: `controller/JudgeFlowIntegrationTests.java`

**Interfaces:**
- Produces:
  - advance `pause` / `resume`：只认法官（`requireJudgeRoom`），只在新流程房间、`playing` 时可用。`pause`：`phase_data.pausedAt = now`；已暂停再 pause → 4002。`resume`：`delta = now - pausedAt`，`step_deadline` 和 `phase_data.pending[*].deadline` 全部 `+delta`，删 `pausedAt`；没暂停时 resume → 4002。各写一条 `public` 事件 `judge_paused` / `judge_resumed`。
  - 暂停中：`tick` 直接返回；`night-action`、`/vote`、`/sheriff`、`/skill`、`next_speaker` → 4002「法官暂停中」；房主的白天推进动作也 4002。
  - 快照加 `boolean paused`、`LocalDateTime phaseDeadline`（= `room.stepDeadline`，所有阶段都下发，给客户端倒计时；暂停时照发原值，客户端按 `paused` 停表）。
  - 点杀修正：`startPending` 从一个有 `step_deadline` 的阶段进入时，把原截止时间存进 `phase_data.resumeDeadline`；待办处理完回到原阶段时恢复（夜里点杀后步骤照常倒计时）。

- [ ] **Step 1: 写失败测试**

```java
@Test void pauseFreezesTheNightStep()        // autoGame：pause；expireDeadline；快照 currentStep.index 仍为 0、paused==true；resume 后 phaseDeadline 在现在之后
@Test void pauseShiftsPendingDeadlines()     // 狼刀一个平民 → skill_pending；pause；等 2 秒；resume → pending.deadline 比暂停前晚 ≥2 秒
@Test void onlyTheJudgePauses()              // autoGame 里玩家 pause → 4003；joinedGame 房主 pause → 4003
@Test void submissionsWaitWhilePaused()      // pause 后守卫 act → 4002
@Test void forceOutAtNightKeepsTheStepClock() // 夜里法官点杀一个平民 → skipPending 回到 night_action 后 phaseDeadline 不为空且 currentStep.index 不变
```

- [ ] **Step 2: 跑测试确认失败。**
- [ ] **Step 3: 实现。**
- [ ] **Step 4: 跑测试确认通过**，再跑全量。
- [ ] **Step 5: 提交** `feat(backend): judges pause and resume the clock`

---

### Task 7: 投票与上警报名的超时、投完自动结算

**Files:**
- Modify: `service/JudgeService.java`、`service/judge/JudgeNightFlow.java`（或新建 `JudgeDayClock.java`，纯逻辑判断「该不该收票」）
- Test: `controller/JudgeFlowIntegrationTests.java`、`service/judge/` 下对应单测

**Interfaces:**
- Produces:
  - 进入 `exile_vote`、`vote_pk`、`sheriff_vote`、`sheriff_pk` 时 `step_deadline = now + 30 秒`；进入 `sheriff_signup` 时 `now + 20 秒`（§4.5、§4.6）。
  - 纯逻辑 `static boolean shouldCloseVote(Set<Integer> eligible, Set<Integer> voted, LocalDateTime deadline, LocalDateTime now)`：全员投完或过了截止时间。
  - `tick` 新增：投票阶段 `shouldCloseVote` 为真 → 调各自的结算（放逐 / PK 走 `handleResolveVote`，警长走 `handleResolveSheriffVote`），没投的人按弃票；`sheriff_signup` 过期 → `handleCloseSheriffSignup`（没选的人算不上警）。结算仍走 `updateRoomGuarded`，和房主手动结算并发时只成功一个。
  - 发言不做强制超时（§12「发言计时到点不强制跳下一位」），只下发 `phaseDeadline` 供显示：进入 `day_speech` / `sheriff_speech` 每轮切人时 `step_deadline = now + 发言时长`（警长 / 警上用 `sheriffSpeechSeconds`），到点不自动切。

- [ ] **Step 1: 写失败测试**

```java
@Test void shouldCloseVoteWhenEveryoneVotedOrTimeIsUp()   // 单测四种组合
@Test void voteClosesItselfWhenEveryoneVoted()             // autoGame 平安夜后进投票，9 人都投完 → 快照 currentPhase=vote_result
@Test void missingVotesCountAsAbstainOnTimeout()           // 只 2 人投、expireDeadline → vote_result，记录里 abstainSeatNos 有 7 人
@Test void sheriffSignupClosesAfterTwentySeconds()         // 上警开，天亮进 sheriff_signup，phaseDeadline 约 20 秒；1 人报名后 expireDeadline → 直接当选进 day_announce
@Test void autoAndManualVoteResolveOnlyOnce()              // 全员投完（tick 已结算）后房主 resolve_vote → 4002，票型只有一轮
@Test void speechClockIsShownButNotForced()                // start_speech 后 phaseDeadline 约 120 秒；expireDeadline 后 speech.index 不变
```

- [ ] **Step 2: 跑测试确认失败。**
- [ ] **Step 3: 实现。**
- [ ] **Step 4: 跑测试确认通过**，再跑全量（Task 5 改写的用例里若有「投完票再手动结算」的写法，按新行为改成直接读 `vote_result`）。
- [ ] **Step 5: 提交** `feat(backend): votes and sheriff sign-up close on time or when everyone is in`

---

### Task 8: 玩家各自的夜间私密信息

**Files:**
- Modify: `service/JudgeService.java`、`dto/WolfbookDtos.java`
- Test: `controller/JudgeFlowIntegrationTests.java`

**Interfaces:**
- Produces:
  - 快照加 `JudgeNightInfoView nightInfo`（按查看者算，没有可给的就是 null）：
    - `record JudgeNightInfoView(List<Integer> teammateSeats, List<JudgeWolfPickView> wolfPicks, Integer knifeSeat, Integer checkedSeat, String checkResult)`；`record JudgeWolfPickView(int seatNo, Integer targetSeatNo)`。
    - `teammateSeats`：查看者是狼队机制（活着或死了都给，整局可见）时，其他狼队座位；否则空列表。
    - `wolfPicks`：当前是狼队那一步且查看者是活着的狼队时，每只活狼的当前选择（没选为 null）；其他时候空列表。
    - `knifeSeat`：当前是女巫那一步、查看者是活着的女巫时，`phase_data.knifeSeat`（空刀为 null）；解药用完照给（§12 按官方每晚告知）。
    - `checkedSeat` / `checkResult`：查看者是预言家且今晚已提交查验时，目标和「好人」/「狼人」（提交后立刻可见，不等天亮）。
  - 法官观察者的 `nightInfo` 为 null（他已经有 `nightActions` 全量）。

- [ ] **Step 1: 写失败测试**（用板子 2「预女猎白」的 `autoGame12`，有 4 狼、女巫、预言家）

```java
@Test void wolvesSeeTeammatesAndLivePicks()   // 狼队那一步：一只狼 act(kill, x) 后，另一只狼的快照 teammateSeats 是其余 3 狼、wolfPicks 里那只狼 target=x
@Test void witchSeesTheKnifeOnlyOnHerStep()   // 狼队那步女巫快照 knifeSeat==null；关掉狼队那步后女巫快照 knifeSeat==x
@Test void seerSeesTheResultRightAway()       // 预言家那一步 act(check, 某狼座位) 后自己的快照 checkResult=="狼人"
@Test void nonWolvesNeverSeeWolfInfo()        // 狼队那步：平民、预言家快照 nightInfo 为 null 或 teammateSeats/wolfPicks 为空、knifeSeat 为 null
```

- [ ] **Step 2: 跑测试确认失败。**
- [ ] **Step 3: 实现。**
- [ ] **Step 4: 跑测试确认通过**，再跑全量。
- [ ] **Step 5: 提交** `feat(backend): night info for wolves, witch and seer on their own phones`

---

### Task 9: 后台：技能机制下拉、支持级别徽标、自动夜间顺序

**Files:**
- Modify: `admin/src/types.ts`（`Role.mechanic: string | null`、`Mechanic` 类型）、`admin/src/services/api.ts`（`listMechanics()` → `GET /admin/roles/mechanics`）
- Modify: `admin/src/components/roles/RoleEditor.vue`（「技能机制」单选下拉 + 说明卡，可选「不设置」）、`admin/src/components/RoleManagement.vue`（列表标机制 / 「未设置」）
- Modify: `admin/src/utils/roleCompleteness.ts`（建议项「技能机制」：没设置时提示「不设置只能在有法官模式下播报」）
- Modify: `admin/src/utils/board.ts`（`judgeSupportLevel(lineup, rolesById)`：全部有机制且无第三方 = full，部分 = partial，都没有 = manual_only；通用夜间顺序改按机制优先级，和后端 `NightPlan` 同口径）、`admin/src/components/boards/BoardList.vue` 与 `BoardEditor.vue`（支持级别徽标；不是 full 时列出没有机制的角色名；夜间顺序区显示「按角色自动生成」的顺序，配了就显示配置的）

**Interfaces:**
- Consumes: Task 2 的接口与字段。
- Produces: 无（终端 UI）。

- [ ] **Step 1: 实现**（样式用 `src/style.css` 的 `nt-*` 类，参照原型画布「后台 · 角色技能机制」）。
- [ ] **Step 2: 跑 `npm run build`**，期望 vue-tsc 无类型错误、构建成功。
- [ ] **Step 3: 本地起后端（`scripts/start-backend-dev.ps1`）和后台（`npm run dev`）手动核对**：新建角色选「守卫」机制保存后列表显示「守卫」；清空机制后显示「未设置」；种子板子 2 徽标为 full；把板子 2 的白痴机制清空后变 partial 并列出「白痴」。
- [ ] **Step 4: 提交** `feat(admin): role mechanic picker and judge support badges`

---

### Task 10: 文档

**Files:**
- Modify: `CLAUDE.md`、`AGENTS.md`（电子法官一节：机制字段、房间规则、两种模式的权限、暂停、投票超时、`nightInfo`、`phaseDeadline`、`paused`；`ContentSchemaInitializer` 补列清单加 `roles.mechanic`、`judge_room_players.mechanic`；后台角色页的机制下拉）
- Modify: `docs/design/judge-auto-mode.md`（§14 后追加「二期 A 落地记录」：和设计稿不同或设计稿没说的地方）

- [ ] **Step 1: 改文档。**
- [ ] **Step 2: 跑全量后端测试**，期望全绿；`admin` 下 `npm run build` 通过。
- [ ] **Step 3: 提交** `docs: judge phase 2A`

---

## 留给执行时的说明

- 「撤销上一步」（无法官房主）本计划不做：用户还没定无法官房主是否保留强制结束和撤销（2026-10-29 问过两次未答）。Task 5 先保留现有的「强制结束 = 流局」，撤销等用户拍板后单独加。
- 上线时要跑 `deploy/sql/2026-10-30-role-mechanic.sql`（`ContentSchemaInitializer` 补列时也会回填，脚本是给手动核对和重复执行用的）。线上「守卫九人局」里的魔术师没有机制，板子仍是 partial，属预期。
