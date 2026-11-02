# 电子法官三期 A：狼王 + 全局规则 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 「狼王守卫」「预女猎白」都能无法官全自动跑完一局；女巫规则、同时达成判定、开枪公告改成全局规则。

**Architecture:** 新增机制 `Mechanic.WOLF_KING`（夜里同狼队，出局同猎人开枪）；把「能开枪」从 `== HUNTER` 抽成机制上的标记，`NightResolver` 和 `JudgeService` 都按标记判断。女巫规则只在 `JudgeRules.withDefaults()` / `defaults()` 一处强制成全局值，引擎各处拿到的规则对象天然是全局值。`WinJudge` 先查狼胜。小程序只改文案和女巫按钮的判断。

**Tech Stack:** Spring Boot 3.5 / Java 21 / MyBatis-Plus / JUnit 5 + MockMvc（H2）；uni-app Vue 3，Node 内置测试运行器。

**Spec:** `docs/superpowers/specs/2026-11-02-judge-phase3a-wolf-king-design.md`

## Global Constraints

- 后端测试：`backend/` 下 `JAVA_HOME=/d/Kf/jdk21 ./mvnw -q test`（单个类 `-Dtest=类名`）；小程序测试：`miniprogram-vue/` 下 `node --test "tests/*.test.mjs"`。
- 开枪公告（后端 `latestAnnouncement`）统一为 `X号发动技能，带走了Y号`，猎人、狼王一样；对局记录（小程序 `rounds.js`）统一为 `X 号发动技能，带走 Y 号`。任何给玩家看的文字都不能出现「猎人开枪」「狼王开枪」。
- 女巫全局规则：只有首夜可以自救（`first_night`）、一晚一瓶（`true`）。板子、建房请求、房间 `rules_json` 里写的别的值一律忽略，不报错。
- 胜负：同一次出局里双方条件同时达成判狼人胜；一达成马上结束，不进技能询问（最后出局的猎人 / 狼王不能开枪，现有顺序，不改）。
- 新机制代码 `wolf_king`，中文标签「狼王」；图鉴里的「黑狼王」用它。事件类型沿用 `hunter_shot`。
- 不改小程序视图组件结构（不嵌自定义组件）；不写死色值。
- 提交信息结尾带 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`。不要提交 `design/art`、`docs/design/art`、`docs/design/img`、`design/prompts/` 下未跟踪的文件。

## Review Focus

1. 狼王是最后一只狼、最后一个神职是猎人：出局后对局立刻结束，快照里没有 `pending`（Task 4 测试 `lastWolfKingOrHunterOutEndsAtOnce`）。
2. 狼王被刀后狼队里只剩狼王一只活狼：狼队那一步只有狼王能提交，交了就提前关步（Task 4 测试 `wolfKingAloneClosesTheWolfStep`）。
3. 被毒的狼王天亮技能询问只有「确认」，和平民、被毒的猎人看到的一样（Task 4 测试 `poisonedWolfKingOnlyConfirms`）。
4. 旧房间 `rules_json` 写了 `witchSelfSave: "never"`：第一夜女巫仍能自救（Task 2 测试 `oldRoomWithNeverStillSelfSavesOnNightOne`）。
5. 本地开发库已经灌过种子（种子只往空表灌）：跑过迁移 SQL 后有「狼王守卫」板子、黑狼王带 `wolf_king`，脚本重复执行不出错、不重复插（Task 5 手动核对步骤）。

---

### Task 1: 同时达成判狼人胜

**Files:**
- Modify: `backend/src/main/java/com/wolfbook/backend/service/judge/WinJudge.java`
- Test: `backend/src/test/java/com/wolfbook/backend/service/judge/WinJudgeTests.java`

**Interfaces:**
- Produces: `WinJudge.judge(List<Seat>, String)` 签名不变，只改判定顺序。

- [ ] **Step 1: 改写测试**：把 `simultaneousWipeoutGoesToGood` 换成三条（用现有 `seats(存活狼, 出局狼, 存活神, 出局神, 存活民, 出局民)`）：

```java
@Test
void simultaneousWipeoutGoesToWolvesWhenGodsAreOut() {
    WinJudge.Verdict verdict = WinJudge.judge(seats(0, 2, 0, 3, 2, 1), "side").orElseThrow();
    assertEquals("狼人", verdict.camp());
    assertEquals("神职全部出局，狼人阵营胜利", verdict.reason());
}

@Test
void simultaneousWipeoutGoesToWolvesWhenVillagersAreOut() {
    assertEquals("平民全部出局，狼人阵营胜利", WinJudge.judge(seats(0, 2, 2, 1, 0, 3), "side").orElseThrow().reason());
}

@Test
void cityRuleSimultaneousWipeoutGoesToWolves() {
    assertEquals("狼人", WinJudge.judge(seats(0, 2, 0, 3, 0, 3), "city").orElseThrow().camp());
    assertEquals("好人", WinJudge.judge(seats(0, 2, 0, 3, 1, 2), "city").orElseThrow().camp(), "屠城还剩平民：好人胜");
}
```

- [ ] **Step 2: 跑测试确认失败**：`./mvnw -q test -Dtest=WinJudgeTests`，预期前两条 FAIL（实际 camp 为「好人」）。
- [ ] **Step 3: 改 `WinJudge.judge`**：先按屠边 / 屠城查狼胜，再查 `wolves == 0` 判好人胜。类注释改成「先查狼胜（同时达成算狼人胜）」。
- [ ] **Step 4: 跑 `-Dtest=WinJudgeTests`**，预期全部 PASS。
- [ ] **Step 5: 提交** `fix(backend): a simultaneous wipeout goes to the wolves`

### Task 2: 女巫规则改成全局

**Files:**
- Modify: `backend/src/main/java/com/wolfbook/backend/domain/JudgeRules.java`
- Modify: `backend/src/main/java/com/wolfbook/backend/service/AdminService.java`（`normalizeJudgeRules`）
- Test: `backend/src/test/java/com/wolfbook/backend/domain/JudgeRulesTests.java`、`backend/src/test/java/com/wolfbook/backend/controller/JudgeFlowIntegrationTests.java`、`backend/src/test/java/com/wolfbook/backend/BackendApplicationTests.java`、`backend/src/test/java/com/wolfbook/backend/service/judge/NightResolverTests.java`

**Interfaces:**
- Produces: `JudgeRules.withDefaults()` 和 `defaults()` 返回的 `witchSelfSave()` 恒为 `"first_night"`、`witchOnePotionPerNight()` 恒为 `true`；`JudgeRules.SELF_SAVE_NEVER` 删除。

- [ ] **Step 1: 改写测试**
  - `JudgeRulesTests.partialJudgeRulesFallBackPerField`、`boardJudgeRulesAreKeptButWinConditionComesFromTheBoardColumn`：传入 `"never"` / `false` 后断言 `witchSelfSave()` 为 `"first_night"`、`witchOnePotionPerNight()` 为 `true`；其他字段（上警、发言时长）照旧保留传入值。
  - `NightResolverTests` 里用 `SELF_SAVE_NEVER` 的那条（约 103 行）：删掉「never 规则」那半，只保留首夜可以 / 第二夜不行的断言。
  - `JudgeFlowIntegrationTests` 新增：

```java
@Test
void oldRoomWithNeverStillSelfSavesOnNightOne() throws Exception {
    Game game = autoGame12(false);
    jdbcTemplate.update("UPDATE judge_rooms SET rules_json = ? WHERE room_id = ?",
            "{\"winCondition\":\"side\",\"witchSelfSave\":\"never\",\"witchOnePotionPerNight\":false}", game.roomId);
    Map<String, List<Integer>> seats = game.seatsByRole();
    int witch = seats.get("女巫").get(0);
    for (int wolf : seats.get("狼人")) {
        game.act(wolf, "kill", witch);
    }
    Assertions.assertEquals("witch", game.snapshot(game.host).path("currentStep").path("kind").asText());
    game.act(witch, "save", witch);
    Assertions.assertEquals("first_night", game.snapshot(game.host).path("rules").path("witchSelfSave").asText());
}
```

  - `JudgeFlowIntegrationTests.roomRulesNeverChangeTheWinCondition` 不用改（已断言 `first_night`）。
  - `BackendApplicationTests` 新增：后台保存板子时 `ruleDetail.judgeRules` 带 `"witchSelfSave":"bogus"` 返回 code 0（以前是 4000），再取 `/api/boards/{id}` 的 `ruleDetail.judgeRules.witchSelfSave` 为 `first_night`。
- [ ] **Step 2: 跑 `-Dtest=JudgeRulesTests+JudgeFlowIntegrationTests#oldRoomWithNeverStillSelfSavesOnNightOne+BackendApplicationTests`**，预期新断言 FAIL（`never`、save 返回 4000、`bogus` 返回 4000）。
- [ ] **Step 3: 实现**
  - `JudgeRules.withDefaults()`：`witchSelfSave` 恒为 `SELF_SAVE_FIRST_NIGHT`，`witchOnePotionPerNight` 恒为 `true`，不看自身字段；记录注释写「女巫规则是全局规则（三期 A），字段只为读旧 JSON 保留」。删 `SELF_SAVE_NEVER`。
  - `AdminService.normalizeJudgeRules`：删掉女巫取值校验，构造时女巫两项传 `null`（不存），`empty` 判断去掉这两项。
- [ ] **Step 4: 跑全部后端测试**，预期全 PASS（`witchSelfSaveIsRejectedByRule` 是第二夜自救，仍然 4000）。
- [ ] **Step 5: 提交** `feat(backend): witch rules are global for every board`

### Task 3: 狼王机制（纯逻辑）

**Files:**
- Modify: `backend/src/main/java/com/wolfbook/backend/service/judge/Mechanic.java`、`RoleSkillRegistry.java`、`NightResolver.java`
- Test: `backend/src/test/java/com/wolfbook/backend/service/judge/RoleSkillRegistryTests.java`、`NightResolverTests.java`、`NightPlanTests.java`

**Interfaces:**
- Produces:
  - `Mechanic.WOLF_KING("wolf_king", "狼王", "和狼队一起刀人，出局时可以开枪带走一人，被毒不能开", NightKind.WOLF, Set.of("kill", "none"), 2, true, true)`，放在 `WOLF` 之后。
  - `Mechanic.canShoot()`：`HUNTER`、`WOLF_KING` 为 true，其余 false（构造参数加一个 `boolean shooter`）。
  - `RoleSkillRegistry.legacyMechanic("黑狼王")` → `WOLF_KING`。
  - `NightResolver.Outcome.cannotShoot()`：被毒且 `mechanic().canShoot()` 的座位。

- [ ] **Step 1: 写测试**
  - `RoleSkillRegistryTests`：`wolfKingBoardIsFull()` —— `supportLevel(List.of(role("预言家","好人","seer"), role("女巫","好人","witch"), role("猎人","好人","hunter"), role("守卫","好人","guard"), role("黑狼王","狼人","wolf_king"), role("狼人","狼人","wolf"), role("平民","好人","villager")))` 为 `full`；`Mechanic.fromCode("wolf_king")` 是 `WOLF_KING`，`wolfTeam()` true，`nightKind()` 是 `WOLF`，`canShoot()` true；`legacyMechanic("黑狼王")` 是 `WOLF_KING`；`Mechanic.HUNTER.canShoot()` true，`WOLF.canShoot()` false。
  - `NightResolverTests`：`poisonedWolfKingCannotShoot()` —— `seats()` 里把 6 号换成 `new SeatState(6, "黑狼王", "狼人", Mechanic.WOLF_KING, true, false, false, null)`，`resolve(List.of(poison(2, 6)), …, RULES, 2, RANDOM).cannotShoot()` 等于 `Set.of(6)`；`knifedWolfKingCanShoot()` —— `resolve(List.of(kill(5, 6)), …)` 的 `deadSeats` 含 6、`cannotShoot` 为空。
  - `NightPlanTests`：阵容含 `wolf_king` 时只有一步 `wolf`，`wolf_king` 不单独成步。
- [ ] **Step 2: 跑 `-Dtest=RoleSkillRegistryTests+NightResolverTests+NightPlanTests`**，预期编译失败（`WOLF_KING`、`canShoot()` 不存在）。
- [ ] **Step 3: 实现**（`Mechanic` 加枚举值和 `shooter` 标记；`LEGACY_NAMES` 加「黑狼王」，`Map.of` 超过 10 对时改 `Map.ofEntries`；`NightResolver` 里 `target.mechanic() == Mechanic.HUNTER` 改成 `target.mechanic() != null && target.mechanic().canShoot()`，Outcome 注释同步）。
- [ ] **Step 4: 跑同上测试**，预期 PASS；再跑全部后端测试。
- [ ] **Step 5: 提交** `feat(backend): wolf king mechanic in the engine`

### Task 4: 狼王进流程、公告去身份、种子板子

**Files:**
- Modify: `backend/src/main/java/com/wolfbook/backend/service/JudgeService.java`（`skillOptions`、开枪公告，约 1857 行）
- Modify: `backend/src/main/java/com/wolfbook/backend/support/DatabaseSeeder.java`（`seedRoleMechanics` 加「黑狼王」→ `wolf_king`；新增种子板子 8「狼王守卫」）
- Test: `backend/src/test/java/com/wolfbook/backend/controller/JudgeFlowIntegrationTests.java`

**Interfaces:**
- Consumes: Task 3 的 `Mechanic.canShoot()`、`WOLF_KING`。
- Produces: 种子板子 id 8「狼王守卫」，12 人，屠边，`roleRef(1,1) 预言家, (2,1) 女巫, (3,1) 猎人, (4,1) 守卫, (11,1) 黑狼王, (6,3) 狼人, (9,4) 平民`，其余字段照 `saveBoard` 现有种子的写法（不配 `ruleDetail`，夜间顺序走自动编排）。测试常量 `WOLF_KING_BOARD_ID = 8`。

- [ ] **Step 1: 写测试**（新增 `wolfKingGame()`：`lobbyGame("{\"boardId\":8,\"judgeMode\":\"observer\",\"autoJudge\":true,\"sheriffEnabled\":false}", 12)` + readyAll + start；`Game.finishNight` 里狼队那一步的刀手从 `roles.get("狼人")` 扩到「狼人」+「黑狼王」）：
  - `wolfKingBoardIsFullAndJoinable()`：`/api/boards/8` 的 `judgeSupportLevel` 为 `full`；`joinedGame(8, 12)` 开局成功。
  - `knifedWolfKingShoots()`：狼刀黑狼王（自刀），`finishNight` 后进 `skill_pending`，黑狼王的 `pending.options` 含 `use`；`skill(wolfKing, use)` → 再 `skill(wolfKing, {"decision":"use","targetSeatNo":平民})`；`latestAnnouncement` 等于 `wolfKing + "号发动技能，带走了" + 平民 + "号"`。
  - `exiledWolfKingShootsBeforeTheGameMovesOn()`：白天全员投黑狼王，结算后进 `skill_pending`，`options` 含 `use`。
  - `poisonedWolfKingOnlyConfirms()`：女巫毒黑狼王，天亮后黑狼王 `pending.options` 等于 `["skip"]`。
  - `forcedOutWolfKingOnlyConfirms()`：法官点杀黑狼王，`options` 等于 `["skip"]`。
  - `wolfKingShotHunterShootsBack()`：黑狼王开枪带走猎人 → 猎人 `pending` 出现、`options` 含 `use`。
  - `hunterAndWolfKingAnnounceTheSameWay()`：猎人开枪后的公告匹配 `\d+号发动技能，带走了\d+号`，不含「开枪」二字。
  - `wolfKingAloneClosesTheWolfStep()`：开局后（第一夜第一步是守卫）用 `jdbcTemplate` 把三只狼人设成 `alive = 0`（`judge_room_players`），`expireDeadline()` 进狼队那一步，只有黑狼王 `act(kill, 平民)`，房主快照的 `currentStep.kind` 立即不是 `wolf`。
  - `lastWolfKingOrHunterOutEndsAtOnce()`：狼人全部设死、只剩黑狼王，白天放逐黑狼王 → `roomStatus` 为 `finished`、`winnerCamp` 为「好人」、`pending` 为空；另起一局把神职只留猎人，放逐猎人 → `finished`、「狼人」。
  - `wolfKingGameRunsToTheEnd()`：用 `finishNight` + 放逐循环（最多 6 天，每天全员投一个活着的狼阵营座位，技能询问 `skipPending`）打到 `finished`。
- [ ] **Step 2: 跑 `-Dtest=JudgeFlowIntegrationTests`**，预期新用例 FAIL（板子 8 不存在 / `options` 不含 `use` / 公告是「开枪带走了」）。
- [ ] **Step 3: 实现**：`skillOptions` 按 `mechanic.canShoot()` 判断；开枪公告改成 `seat + "号发动技能，带走了" + target + "号"`；种子加黑狼王机制和板子 8；`JudgeService` 里其余 `== Mechanic.HUNTER` 的地方（搜一遍）凡是指「能开枪」的都换成 `canShoot()`，指「夜里确认开枪状态那一步」的不动。
- [ ] **Step 4: 跑全部后端测试**（含 `JudgeFlowRepeatableReadTests`），预期全 PASS。
- [ ] **Step 5: 提交** `feat(backend): wolf king shoots like the hunter, announcements hide who shot`

### Task 5: 已有库回填

**Files:**
- Modify: `backend/src/main/java/com/wolfbook/backend/config/ContentSchemaInitializer.java`（按名回填表加 `{"黑狼王", "wolf_king"}`）
- Create: `deploy/sql/2026-11-02-wolf-king-mechanic.sql`
- Modify: `deploy/sql/2026-10-30-role-mechanic.sql`（加一行黑狼王，保持两边映射一致）

**Interfaces:**
- Produces: 迁移脚本，可重复执行：① `UPDATE roles SET mechanic = 'wolf_king' WHERE name = '黑狼王' AND mechanic IS NULL`；② 没有名为「狼王守卫」的板子时插入一个（字段同 Task 4 种子板子，`board_roles` 按角色名查 id 插入，`judge_support_level` 写 `full`）。脚本开头注释写清：先启动新版后端（补 `mechanic` 列）再跑；生产库已有「狼王守卫」，第②段不会动它。

- [ ] **Step 1**：`ContentSchemaInitializer` 改回填表；`./mvnw -q test` 全 PASS（H2 下这段跳过，确认没破坏启动）。
- [ ] **Step 2**：写 SQL 脚本。
- [ ] **Step 3: 手动核对**（本地 MySQL，用户的 IDEA 后端连的那个库；只在本地跑）：跑两遍脚本，`SELECT name, mechanic FROM roles WHERE name='黑狼王'` 为 `wolf_king`；`SELECT COUNT(*) FROM boards WHERE name='狼王守卫'` 为 1。本地库连不上就跳过这步并在报告里说明。
- [ ] **Step 4: 提交** `chore(deploy): backfill the wolf king mechanic and board`

### Task 6: 小程序文案和女巫按钮

**Files:**
- Modify: `miniprogram-vue/src/utils/judge/pending.js`、`rounds.js`、`night-panel.js`、`create-room.js`
- Test: `miniprogram-vue/tests/judge-pending.test.mjs`、`judge-rounds.test.mjs`、`judge-night-panel.test.mjs`、`judge-create-room.test.mjs`

**Interfaces:**
- Produces: `KIND_LABELS.hunter_shot = '开枪'`；`skillText` 的 `hunter_shot` 返回 `${actor} 号发动技能，带走 ${target} 号`；`boardRuleLine(board)` 有女巫时恒为 `… · 女巫只首夜可自救 · 一晚一瓶`。

- [ ] **Step 1: 改测试**
  - `judge-pending`：`judge.detail` 断言改成 `'2 号 · 开枪'`；加一条：`hunter_shot` 面板的 `title`、`detail`、所有按钮 `label` 都不含「猎人」。
  - `judge-rounds`：`hunter_shot` 记录的文字为 `'4 号发动技能，带走 7 号'`。
  - `judge-night-panel`：fixture 的 `rules` 换成 `{ witchSelfSave: 'never', witchOnePotionPerNight: false }` 后，第一夜被刀的女巫仍有 `save` 按钮、第二夜仍没有。
  - `judge-create-room`：`witchSelfSave: 'never'` 的板子显示 `'屠边 · 女巫只首夜可自救 · 一晚一瓶'`。
- [ ] **Step 2: 跑 `node --test "tests/*.test.mjs"`**，预期这四处 FAIL。
- [ ] **Step 3: 实现**：`night-panel.js` 自救只看是不是第一夜；`create-room.js` 去掉 `SELF_SAVE_TEXT` 映射，女巫那段固定文字，文件头注释改成「女巫规则是全局规则」；`pending.js`、`rounds.js` 改文案。
- [ ] **Step 4: 跑全部小程序测试**，预期 PASS；`npm run build:mp-weixin` 成功；按 `wechat-devtools-compilers` 的做法对 `dist/build/mp-weixin/pages/judge` 跑 `wcsc` / `wcc` 无错。
- [ ] **Step 5: 提交** `feat(miniprogram): shot text hides the role, witch rules are global`

### Task 7: 联调脚本和文档

**Files:**
- Modify: `miniprogram-vue/scripts/judge-e2e.mjs`（第三个模式 `wolfking`：`{ mode: 'joined', keyword: '狼王守卫', count: 12 }`；`both` 时三局都跑；用法注释同步）
- Modify: `docs/design/judge-auto-mode.md`（§4.11 机制表狼王行改「三期 A 已有」；§8「同时达成算好人胜」改成狼人胜；§16 删掉「今晚汇总按通用顺序判断，可能不准」那半句；新增「§17 三期 A 落地记录」：全局规则表照设计稿 §3 抄，再写和设计稿不同的地方）
- Modify: `CLAUDE.md`、`AGENTS.md`（结算引擎那段：机制列表加 `wolf_king`；「板子级规则（女巫自救、一晚一瓶…）」改成女巫规则全局、板子级只剩上警默认和时长；`WinJudge` 那句「双方同时达成算好人胜」改成狼人胜；单测清单不变的就不动）

- [ ] **Step 1**：改脚本。
- [ ] **Step 2: 跑联调**（需要本地后端在 8088，且 Task 5 的脚本已在本地库跑过）：`node scripts/judge-e2e.mjs http://127.0.0.1:8088 both`，预期三局都打到结束、没有零场外报错。后端没起就跳过并在报告里说明。
- [ ] **Step 3: 浏览器手动跑一局**（本地后端在 8088 时）：`npm run dev:h5`，在「我的」登录后建「狼王守卫」无法官房，`node scripts/judge-bots.mjs <房号> 12` 坐满，看狼王开枪那一段的待办、公告和对局记录（不出现「猎人」）。浏览器里没跑到就在报告里说明。
- [ ] **Step 4**：改文档。
- [ ] **Step 5: 跑全部后端 + 小程序测试**，全 PASS。
- [ ] **Step 6: 提交** `docs: judge phase 3A`（同时提交设计稿和本计划）
