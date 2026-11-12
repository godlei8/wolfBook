# 预女猎白 / 狼王守卫 / 通灵师机械狼人 规则对齐 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让线上 2 预女猎白、8 狼王守卫、5 机械狼通灵师三个板子能开无法官局，每一步和 `docs/judge/house-rules/` 的 01、03、08 一致。

**Architecture:** 规则全部落在 `service/judge/` 的纯函数里（`Mechanic` 加通灵师、机械狼两个机制；`NightResolver` 扩展一夜的结算；`NightPlan` 排步骤），`JudgeService` 只做编排：把机械狼的一步多动作存成事件、把「自爆待死」存进座位状态。板子级只加一个专属配置 `poisonBounce`。前端照后端快照新字段渲染。

**Tech Stack:** Spring Boot 3.5 / Java 21 / MyBatis-Plus / H2 测试；Vue 3 + TS 后台；uni-app 小程序（Node 内置测试）。

**Spec:** `docs/superpowers/specs/2026-11-12-judge-three-boards-rules-design.md`（规则原文：`docs/judge/house-rules/01-预女猎白.md`、`03-狼王守卫.md`、`08-通灵师机械狼人.md`、`README.md`；理念：`PRINCIPLES.md`）

## Global Constraints

- 机制跟角色走：角色行为只写在 `Mechanic` 上；板子级配置只有节奏项（已有）、`witchSelfSave`（已有）、`poisonBounce`（本计划新增，默认 `false`）。不加别的板子开关。
- 零场外：夜里每一步时长只看步骤种类，不看持有者死活、学没学、带没带刀；非本人快照里看不到机械狼的任何信息。
- 旧流程房间（`auto_judge_enabled = 0`）行为不变；没有机制的旧座位按角色名兜底。
- 时长：狼队 60 秒（首夜 90）、单人 30 秒、猎人 10 秒、白痴空步 5 秒。
- 公告文案（逐字）：开枪「X号发动技能，带走了Y号」；白痴放逐「X号是白痴，被放逐出局」；自爆「X号自爆」；狼王自刀 4000「狼王不能自刀」。
- 每个后端任务跑 `./mvnw -q test -Dtest=<类名>`（`backend/` 下，`$env:JAVA_HOME='D:\Kf\jdk21'`），最后一个任务跑全部三套测试。
- 提交信息结尾 `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`；不提交 `design/`、`docs/design/art`、`docs/design/img`。
- 其他会话在 `CLAUDE.md` / `AGENTS.md` / `docs/judge/house-rules/` 里有未提交改动：这些文件只用 `git add -p` 或分段暂存自己的那几行。

## Review Focus

1. 女巫的毒落在「真守卫和学守卫的机械狼同时守」的人身上、弹毒开：被守的人不死，毒仍反弹给女巫（弹毒只看机械狼守没守）。→ Task 5 `bounceFiresEvenWhenTheRealGuardAlsoGuards`
2. 学到女巫的机械狼毒的人同时被狼刀：只死一次、出局记录一条。→ Task 5 `mechPoisonAndKnifeOnTheSameSeatKillOnce`
3. 带刀机械狼普通刀和破盾刀打同一人、女巫救：解药耗掉、人死一次。→ Task 5 `pierceAndKnifeOnTheSameSeatConsumeTheAntidote`
4. 机械狼 `learn` 选自己、选已出局的人：4000。→ Task 7 `mechWolfCannotLearnItselfOrTheDead`
5. 自爆待死的狼当晚被狼队自刀：天亮只记一次出局、不进昨夜死讯。→ Task 10 `explodedWolfKnifedTheSameNightDiesOnce`

---

## 文件结构

| 文件 | 职责 |
|---|---|
| `backend/.../service/judge/Mechanic.java` | 新增 `PSYCHIC`、`MECH_WOLF`；`IDIOT` 改成 5 秒空步；`WOLF_KING` 去自爆；简介改字 |
| `backend/.../service/judge/NightKind.java` | 新增 `PSYCHIC`、`MECH_WOLF`、`IDLE` |
| `backend/.../service/judge/NightResolver.java` | 一夜结算：守卫挡毒、狼王夜死不开枪、机械狼全部技能、弹毒、通灵师结果、校验 |
| `backend/.../service/judge/NightPlan.java` | 机械狼最先、通灵师同预言家档、白痴空步 5 秒、机械狼须在狼前 |
| `backend/.../service/judge/RoleSkillRegistry.java` | 旧座位兜底名：通灵师、机械狼 |
| `backend/.../domain/JudgeRules.java` | `poisonBounce` |
| `backend/.../service/AdminService.java` | 保存板子时收 `poisonBounce` |
| `backend/.../service/JudgeService.java` | 一步多动作、机械狼状态、自爆待死、白痴放逐出局、狼王开枪条件、`nightInfo` / `JudgePlayerView` 新字段 |
| `backend/.../dto/WolfbookDtos.java` | `JudgeNightActionRequest.actions`、`JudgeNightInfoView` 机械狼 / 通灵师字段、`JudgePlayerView.exploded` |
| `backend/.../support/DatabaseSeeder.java`、`config/ContentSchemaInitializer.java`、`deploy/sql/2026-11-12-mech-wolf-psychic-mechanic.sql` | 机械狼 / 通灵师的机制 |
| `admin/src/types.ts`、`utils/board.ts`、`components/boards/BoardEditor.vue` | `poisonBounce`、两层配置分区、新机制的排序与狼队判断 |
| `miniprogram-vue/src/utils/judge/night-panel.js`、`stage.js`、`voice-cue.js`、`pages/judge/views/NightView.vue`、`pages/judge/room.vue`、`services/api.js` | 机械狼 / 通灵师面板、一步多动作、自爆待死座位、播报 |
| `miniprogram-vue/scripts/judge-e2e.mjs` | 加一局机械狼通灵师 |
| `docs/judge/house-rules/*`、`docs/design/judge-auto-mode.md`、`CLAUDE.md`、`AGENTS.md` | 用户改定、规则表、现状说明 |

---

### Task 1: 规则文件写回用户拍板

**Files:**
- Modify: `docs/judge/house-rules/01-预女猎白.md`、`02-预女猎白混.md`、`03-狼王守卫.md`、`README.md`

- [ ] **Step 1: 01 号**：猎人条末尾追加「（2026-11-12 用户改定：猎人开枪时不翻牌，所有板子一样。）」；白痴条末尾追加「（2026-11-12 用户改定：白痴被放逐照样出局，出局时翻牌公开身份；没有免死，也就没有「不能投票」。是警长时照常移交或撕掉警徽。）」。夜间顺序表第 5 步「白痴 | 空步 | 5 秒」保留。结算要点里白痴那条改成「白痴被放逐：出局，公告写明他是白痴。被刀、被毒、被枪杀和普通出局一样」。「待拍板」两条删掉，写「暂无」。
- [ ] **Step 2: 02 号**：白痴条追加同一句改定；待拍板里关于白痴的条目（如有）删掉。
- [ ] **Step 3: 03 号**：待拍板「狼王被猎人开枪带走能不能开枪」改成已拍板：结算要点加「狼王被放逐、被猎人开枪带走都能开枪；被刀、被毒、被点杀不能；是最后一只狼时游戏先结束、不开枪。猎人被狼王开枪带走也能开枪。」待拍板写「暂无」。
- [ ] **Step 4: README**：「各板通用」里「猎人开枪翻不翻牌」那条改成「**猎人开枪不翻牌**（2026-11-12 用户改定，所有板子一样）」；加一条「**最后一只狼自爆**：当场判好人胜，不进夜晚（2026-11-12 用户改定）」；「和引擎现状不同」那节先不动（Task 13 收尾时改）。
- [ ] **Step 5: Commit**

```bash
git add docs/judge/house-rules/01-预女猎白.md docs/judge/house-rules/02-预女猎白混.md docs/judge/house-rules/03-狼王守卫.md docs/judge/house-rules/README.md
git commit -m "docs(judge): record the rulings for hunter reveal, idiot, wolf king shots and last-wolf explosion"
```

（`docs/judge/house-rules/` 整个目录是另一个会话的未提交文件；`git add` 这四个文件会把它们第一次提交进仓库，是预期的。其余文件留在工作区。）

---

### Task 2: 板子专属配置 `poisonBounce`

**Files:**
- Modify: `backend/src/main/java/com/wolfbook/backend/domain/JudgeRules.java`
- Modify: `backend/src/main/java/com/wolfbook/backend/service/AdminService.java:506-530`
- Test: `backend/src/test/java/com/wolfbook/backend/domain/JudgeRulesTests.java`、`controller/BackendApplicationTests.java`

**Interfaces:**
- Produces: `JudgeRules` 第 9 个字段 `Boolean poisonBounce`；`JudgeRules.poisonBounce()` 补默认值后永远非 null；8 参数的旧构造器保留（`poisonBounce = null`）。

- [ ] **Step 1: 写失败测试**（`JudgeRulesTests`）

```java
@Test
void poisonBounceDefaultsToFalse() {
    assertFalse(JudgeRules.defaults().poisonBounce());
    assertFalse(new JudgeRules(null, null, null, null, null, null, null, null).withDefaults().poisonBounce());
    assertTrue(new JudgeRules(null, null, null, null, null, null, null, null, true).withDefaults().poisonBounce());
}
```

- [ ] **Step 2: 跑** `./mvnw -q test -Dtest=JudgeRulesTests` → 编译失败（9 参构造器不存在）。
- [ ] **Step 3: 实现**：record 加第 9 个组件 `Boolean poisonBounce`（javadoc：板子专属配置，学守卫的机械狼挡女巫的毒时反弹，默认不开）；加 8 参构造器委托 `this(..., null)`；`defaults()` 传 `false`；`resolve` 和 `withDefaults` 透传，`withDefaults` 里 `poisonBounce == null ? false : poisonBounce`。改所有 `new JudgeRules(` 8 参调用点（`AdminService`、`JudgeService.applyRoomRules`）保持编译（用 8 参构造器即可）。
- [ ] **Step 4: 跑** `JudgeRulesTests` → PASS。
- [ ] **Step 5: 写失败测试**（`BackendApplicationTests`，仿现有的女巫自救保存用例）：后台 `PUT /admin/boards/{id}` 带 `ruleDetail.judgeRules.poisonBounce = true` 后，`GET /api/boards/{id}` 的 `ruleDetail.judgeRules.poisonBounce` 为 `true`；再存 `false` 后为 `false`。
- [ ] **Step 6: 跑** → FAIL（保存后仍 false）。
- [ ] **Step 7: 实现** `AdminService.normalizeJudgeRules`：`Boolean bounce = Boolean.TRUE.equals(input.poisonBounce()) ? true : null;` 放进 9 参构造器；`empty` 判断加 `normalized.poisonBounce() == null`。`JudgeService.applyRoomRules` 把 `base.poisonBounce()` 原样带进房间规则（建房请求改不了）。
- [ ] **Step 8: 跑** `./mvnw -q test -Dtest=BackendApplicationTests,JudgeRulesTests` → PASS。
- [ ] **Step 9: Commit** `feat(judge): board-level poisonBounce rule`

---

### Task 3: 机制登记：通灵师、机械狼、白痴空步、狼王不自爆

**Files:**
- Modify: `service/judge/Mechanic.java`、`service/judge/NightKind.java`、`service/judge/RoleSkillRegistry.java`、`support/DatabaseSeeder.java:125-126`、`config/ContentSchemaInitializer.java:98-105`
- Create: `deploy/sql/2026-11-12-mech-wolf-psychic-mechanic.sql`
- Test: `service/judge/RoleSkillRegistryTests.java`

**Interfaces:**
- Produces: `NightKind.PSYCHIC`、`NightKind.MECH_WOLF`、`NightKind.IDLE`；`Mechanic.PSYCHIC`（code `psychic`，label 通灵师，nightKind PSYCHIC，actions `{check}`，priority 4，solo）；`Mechanic.MECH_WOLF`（code `mech_wolf`，label 机械狼，nightKind MECH_WOLF，actions `{learn, none, check, poison, guard, kill, kill_pierce}`，priority 0，wolfTeam false，solo true，shooter false，selfDestruct false）；`Mechanic.IDIOT` nightKind `IDLE`、actions `{confirm}`；`Mechanic.WOLF_KING.canSelfDestruct() == false`；`RoleSkillRegistry.legacyMechanic("通灵师") == PSYCHIC`、`("机械狼") == MECH_WOLF`。
- 新常量：`NightResolver.LEARN = "learn"`、`NightResolver.KILL_PIERCE = "kill_pierce"`（在 Task 5 加，这里测试只用字符串）。

- [ ] **Step 1: 写失败测试**（`RoleSkillRegistryTests`）

```java
@Test
void psychicAndMechWolfAreMechanics() {
    assertEquals(NightKind.PSYCHIC, Mechanic.PSYCHIC.nightKind());
    assertEquals(Integer.valueOf(4), Mechanic.PSYCHIC.nightPriority());
    assertEquals(NightKind.MECH_WOLF, Mechanic.MECH_WOLF.nightKind());
    assertEquals(Integer.valueOf(0), Mechanic.MECH_WOLF.nightPriority());
    assertFalse(Mechanic.MECH_WOLF.wolfTeam());
    assertFalse(Mechanic.MECH_WOLF.canShoot());
    assertFalse(Mechanic.MECH_WOLF.canSelfDestruct());
    assertEquals(Set.of("learn", "none", "check", "poison", "guard", "kill", "kill_pierce"), Mechanic.MECH_WOLF.actions());
    assertEquals(Optional.of(Mechanic.PSYCHIC), RoleSkillRegistry.legacyMechanic("通灵师"));
    assertEquals(Optional.of(Mechanic.MECH_WOLF), RoleSkillRegistry.legacyMechanic("机械狼"));
}

@Test
void idiotHasAFiveSecondIdleStep() {
    assertEquals(NightKind.IDLE, Mechanic.IDIOT.nightKind());
    assertEquals(Set.of("confirm"), Mechanic.IDIOT.actions());
}

@Test
void mechWolfBoardIsFull() {
    assertEquals(JudgeSupportLevels.FULL, RoleSkillRegistry.supportLevel(List.of(
            role("通灵师", "好人", "psychic"), role("女巫", "好人", "witch"), role("猎人", "好人", "hunter"),
            role("守卫", "好人", "guard"), role("狼人", "狼人", "wolf"), role("机械狼", "狼人", "mech_wolf"),
            role("平民", "好人", "villager"))));
    assertFalse(RoleSkillRegistry.isWolfTeam(Mechanic.MECH_WOLF, "机械狼", "狼人"));
}
```

并把 `onlyWolfAndWolfKingCanSelfDestruct` 改成 `onlyWolvesCanSelfDestruct`：exploders = `Set.of(Mechanic.WOLF)`。

- [ ] **Step 2: 跑** `-Dtest=RoleSkillRegistryTests` → 编译失败。
- [ ] **Step 3: 实现**：`NightKind` 加三个值（javadoc：PSYCHIC 验具体身份；MECH_WOLF 机械狼自己的一步；IDLE 空步只播报等 5 秒）。`Mechanic` 按 Interfaces 加两个枚举、改 `IDIOT`（brief「被放逐出局时翻牌公开身份，夜里有一个空步」）、`WOLF_KING` 最后一个参数 `false`、brief 改「和狼队一起刀人，被放逐或被猎人带走时可以开枪，被刀、被毒不能；不能自刀、不能自爆」、`GUARD` brief「守一人，不能连守，挡狼刀和女巫的毒」、`HUNTER` brief「出局时开枪带走一人，被毒不能开，开枪不翻牌」。`MECH_WOLF` brief「和狼人互不认识，每晚最先睁眼；整局学一次别人的技能，狼人全出局后带刀；不能自爆」，`PSYCHIC` brief「查验一人的具体身份」。`RoleSkillRegistry.LEGACY_NAMES` 加两条（`Map.of` 最多 10 项，改成 `Map.ofEntries`）。
- [ ] **Step 4: 跑** `RoleSkillRegistryTests` → PASS。
- [ ] **Step 5: 种子与迁移**：`DatabaseSeeder` 机制 map 加 `"机械狼", "mech_wolf", "通灵师", "psychic"`（`Map.of` 10 项上限，改 `Map.ofEntries`）；`ContentSchemaInitializer` 回填表同样加两对；新建 `deploy/sql/2026-11-12-mech-wolf-psychic-mechanic.sql`：

```sql
-- 机械狼、通灵师的技能机制（电子法官按机制结算）。按名字匹配，可重复执行。
UPDATE roles SET mechanic = 'mech_wolf' WHERE name = '机械狼' AND (mechanic IS NULL OR mechanic = '');
UPDATE roles SET mechanic = 'psychic' WHERE name = '通灵师' AND (mechanic IS NULL OR mechanic = '');
```

- [ ] **Step 6: 跑** `./mvnw -q test -Dtest=RoleSkillRegistryTests,BackendApplicationTests` → `BackendApplicationTests` 里凡是断言机械狼 / 通灵师没有机制、或板子 5 支持级别是 partial 的用例会失败：把它们的期望改成有机制 / full（`grep -n "机械狼\|通灵师\|partial" BackendApplicationTests.java` 逐条看）。`JudgeFlowIntegrationTests` 里狼王自爆的用例（如有）此时也会失败，留给 Task 9 改。
- [ ] **Step 7: Commit** `feat(judge): register psychic and mech wolf mechanics; idiot idle step; wolf king cannot self-destruct`

---

### Task 4: 夜间步骤：机械狼最先、通灵师、白痴空步

**Files:**
- Modify: `service/judge/NightPlan.java`
- Test: `service/judge/NightPlanTests.java`

**Interfaces:**
- Consumes: Task 3 的三个 `NightKind`、`Mechanic.MECH_WOLF.nightPriority() == 0`。
- Produces: `NightPlan.build(...)` 对 08 阵容（没配 nightOrder）给出 `MECH_WOLF → GUARD → WOLF → WITCH → PSYCHIC → HUNTER`；对 01 阵容给出 `WOLF → WITCH → SEER → HUNTER → IDLE`；`IDLE` 时长 5；`MECH_WOLF`、`PSYCHIC` 时长 `roleSeconds`；`validateOrder` 要求机械狼在狼队前。

- [ ] **Step 1: 写失败测试**（仿 `autoOrderFollowsMechanicPriority` 的角色构造；新增 helper `lineup08()` / `roles08()`：id 1 通灵师 psychic、2 女巫 witch、3 猎人 hunter、4 守卫 guard、6 狼人 wolf、12 机械狼 mech_wolf、9 平民 villager；`lineup01()` / `roles01()`：预言家、女巫、猎人、白痴 idiot、狼人、平民；`detailOrdering(int... roleIds)` 造一个只有 `nightOrder` 的 `BoardRuleDetail`）

```java
@Test
void mechWolfWakesFirstAndPsychicWithTheSeer() {
    // 阵容：平民、通灵师、女巫、猎人、守卫、狼人、机械狼
    List<NightKind> kinds = NightPlan.build(null, lineup08(), roles08(), 1).stream().map(NightPlan.NightStep::kind).toList();
    assertEquals(List.of(NightKind.MECH_WOLF, NightKind.GUARD, NightKind.WOLF, NightKind.WITCH, NightKind.PSYCHIC, NightKind.HUNTER), kinds);
}

@Test
void idiotGetsAFiveSecondIdleStepAtTheEnd() {
    List<NightPlan.NightStep> steps = NightPlan.build(null, lineup01(), roles01(), 1);
    NightPlan.NightStep last = steps.get(steps.size() - 1);
    assertEquals(NightKind.IDLE, last.kind());
    assertEquals(5, last.durationSeconds());
    assertEquals("白痴", last.roleName());
}

@Test
void mechWolfAndPsychicStepsUseTheRoleSeconds() {
    JudgeRules rules = new JudgeRules(null, null, null, null, null, null, 60, 45);
    List<NightPlan.NightStep> steps = NightPlan.build(null, lineup08(), roles08(), 2, rules);
    assertEquals(45, steps.get(0).durationSeconds());   // 机械狼
    assertEquals(45, steps.get(4).durationSeconds());   // 通灵师
}

@Test
void mechWolfAfterWolvesIsRejected() {
    // 配置 nightOrder：狼人 → 机械狼
    assertThrows(ApiException.class, () -> NightPlan.validateOrder(NightPlan.build(detailOrdering(wolfId, mechWolfId), lineup08(), roles08(), 1)));
}
```

- [ ] **Step 2: 跑** `-Dtest=NightPlanTests` → FAIL（顺序 / 时长不对；validateOrder 不抛）。
- [ ] **Step 3: 实现**：`priority()` 已按机制取，`MECH_WOLF` 的 0 自然最先；白痴 `IDLE` 不再被 `kind == NONE` 跳过；`duration`：`case MECH_WOLF, PSYCHIC -> timing.roleSeconds(); case IDLE -> 5`（常量 `IDLE_SECONDS = 5`）；`hint`：PSYCHIC「你要查验谁？」、MECH_WOLF「请决定今晚的行动。」、IDLE「请稍候。」；`validateOrder` 加：`mech = indexOf(MECH_WOLF)`，`mech >= 0 && mech > wolf` 抛 4000「夜间顺序：机械狼须在狼人前」（把现有那句错误文案改成「夜间顺序：守卫、机械狼须在狼人前，女巫须在狼人后」）。
- [ ] **Step 4: 跑** `NightPlanTests` → PASS。
- [ ] **Step 5: Commit** `feat(judge): night plan knows the mech wolf, psychic and idiot idle steps`

---

### Task 5: 一夜的结算

**Files:**
- Modify: `service/judge/NightResolver.java`
- Test: `service/judge/NightResolverTests.java`

**Interfaces:**
- Consumes: `JudgeRules.poisonBounce()`（Task 2）、Task 3 的机制与 NightKind。
- Produces:
  - 常量 `LEARN = "learn"`、`KILL_PIERCE = "kill_pierce"`、`CONFIRM = "confirm"`。
  - `SeatState` 扩成 `(int seat, String roleName, String faction, Mechanic mechanic, boolean alive, boolean antidoteLeft, boolean poisonLeft, Integer lastGuardSeat, Mechanic learned, String learnedRoleName, Integer learnedDay, boolean mechPoisonLeft)`；保留 8 参构造器（后四个 `null, null, null, true`）。
  - `static boolean learnedEffective(SeatState seat, int dayNo)`：`learned != null && learnedDay != null && dayNo > learnedDay`。学到猎人的「能开枪」不走这里（任何时候 `learned == HUNTER` 即可）。
  - `static boolean armed(Map<Integer, SeatState> seats)`：没有任何 `alive && mechanic.wolfTeam()`（没机制时按 `faction == 狼人 && mechanic == null`）的座位。
  - `static String identityOf(SeatState target, List<Submission> submissions, Map<Integer, SeatState> seats)`：通灵师看到的身份：目标是 `MECH_WOLF` 且（已学过，或今晚有它的 `learn` 提交）→ 学到的角色名；否则 `target.roleName()`。
  - `static Mechanic learnedMechanicOf(SeatState target)`：学到什么只看对方机制：`WOLF_KING → WOLF`；`IDIOT`、`VILLAGER`、`null`（没机制）→ `VILLAGER`；`WITCH / GUARD / PSYCHIC / SEER / HUNTER / WOLF` 原样（`SEER` 按原样存，但机械狼没有预言家技能分支，等于平民）。
  - `Outcome` 加 `Map<Integer, Learned> learnedTonight`（`record Learned(Mechanic mechanic, String roleName)`，key 机械狼座位）和 `Set<Integer> mechPoisonUsedBy`；`feedback` 里机械狼当晚 `learn` 的反馈是「X号是<角色名>」。
  - `resolve` 规则（设计稿 C7）：普通刀 K = 狼队票选；狼队全出局时取机械狼 `kill`。破盾刀 B = 机械狼 `kill_pierce`（只在 `armed && learned == WOLF && learnedEffective`）。守护集合 = 真守卫 `guard` + 学守卫的机械狼 `guard`（各自不能连守）。K：被守且没被救 → 活；被救且没被守 → 活；都有 → 死；都无 → 死。B 一律死，B == K 且被救 → 解药照耗。女巫毒 P：被真守卫守 → 不死；被机械狼守 → 不死，`poisonBounce` 时女巫死（算被毒）；都没守 → 死。机械狼毒 M（`learned == WITCH && learnedEffective && mechPoisonLeft`）一律死。`cannotShoot`：被 P / M / 弹回的毒杀死且（`mechanic.canShoot()` 或 `mechanic == MECH_WOLF && learned == HUNTER`）的座位；**另加所有今晚死亡的 `WOLF_KING`**（狼王夜死不开枪）。
  - `validate` 加：`WOLF && actor.mechanic == WOLF_KING && target == actor` → 4000「狼王不能自刀」；MECH_WOLF 的 `learn`：目标不能是自己（4000「不能学习自己」）、已学过 4000「本局已经学过了」；`check` 要 `learned == PSYCHIC && learnedEffective`，`poison` 要 `learned == WITCH && learnedEffective && mechPoisonLeft`（4000「毒药已经用过了」），`guard` 要 `learned == GUARD && learnedEffective` 且不能连守，`kill` 要 `armed`，`kill_pierce` 要 `armed && learned == WOLF && learnedEffective`；不满足 4000「你现在不能这样行动」。`validate` 新签名：`validate(Submission, SeatState actor, SeatState target, JudgeRules rules, int dayNo, Integer knifeSeat, Map<Integer, SeatState> seats)`（`armed` 要看全场）。

- [ ] **Step 1: 写失败测试**（放进 `NightResolverTests`；座位表仍用 `seats()`，机械狼用 `seats.put(6, new SeatState(6, "机械狼", "狼人", Mechanic.MECH_WOLF, true, false, false, null, learned, name, day, true))`，通灵师 `seats.put(1, ... Mechanic.PSYCHIC ...)`；新 helper `learn(actor, target)`、`pierce(actor, target)`、`mechCheck/mechPoison/mechGuard/mechKill(actor, target)`（kind `MECH_WOLF`））

```java
@Test void guardBlocksTheWitchsPoison()            // guard(4,8)+poison(2,8) → dead 空，poisonUsedBy {2}
@Test void knifedWolfKingCannotShoot()              // 改现有 knifedWolfKingCanShoot：cannotShoot 含 6
@Test void wolfKingCannotKnifeHimself()             // validate kill(6,6) 狼王 → ApiException 4000 "狼王不能自刀"
@Test void plainWolfMayStillKnifeHimself()          // validate kill(5,5) 狼人 → 不抛
@Test void psychicSeesTheExactRole()                // check(1,5) 通灵师 → feedback "狼人"；check(1,2) → "女巫"
@Test void psychicSeesAnUnlearnedMechWolfAsItself() // → "机械狼"
@Test void psychicSeesWhatTheMechWolfLearnedEvenTonight()  // 今晚 learn(6,2) + check(1,6) → "女巫"
@Test void mechWolfLearningGetsTheTargetsRole()     // learn(6,2) → feedback 6 = "2号是女巫"，learnedTonight {6: (WITCH,"女巫")}
@Test void mechWolfSkillsStartTheNextNight()        // learned WITCH day 1，dayNo 1 的 mechPoison(6,7) 不生效；dayNo 2 生效、7 死、mechPoisonUsedBy {6}
@Test void mechPoisonIgnoresGuardsAndTheWitch()     // guard(4,7)+save 不救；7 死；cannotShoot 若 7 是猎人
@Test void mechPoisonAndKnifeOnTheSameSeatKillOnce()// dead == [7]
@Test void mechGuardBlocksKnifeAndPoison()          // mechGuard(6,7)：kill 7 不死；poison 7 不死（bounce 关）女巫活
@Test void bounceSendsThePoisonBackToTheWitch()     // rules poisonBounce true：7 活、2 死、cannotShoot 不含 2
@Test void bounceFiresEvenWhenTheRealGuardAlsoGuards()  // guard(4,7)+mechGuard(6,7)+poison(2,7)，bounce 开 → 2 死
@Test void bouncedPoisonIsNotBlockedByTheGuard()    // guard(4,2) 守女巫 + bounce → 2 仍死
@Test void bothGuardsOnTheSameSeatStillSave()       // kill 7 + guard(4,7) + mechGuard(6,7) → 活；再加 save(2,7) → 死
@Test void mechWolfIsNotArmedWhileAWolfLives()      // validate mechKill 抛 4000；armed(seats) false
@Test void armedMechWolfsKillIsTheKnife()           // 5 号狼死；mechKill(6,7) → knifeSeat 7，女巫 feedback "7 号"
@Test void pierceIgnoresEveryGuard()                // learned WOLF、armed：pierce(6,7)+guard(4,7)+mechGuard... → 7 死
@Test void pierceAndKnifeOnTheSameSeatConsumeTheAntidote()  // mechKill(6,7)+pierce(6,7)+save(2,7) → 7 死，antidoteUsedBy {2}
@Test void pierceNeedsTheWolfSkillAndArms()         // 没学狼人 validate pierce 抛；没 armed 抛
@Test void mechWolfCannotLearnTwiceOrItself()       // learned 非空再 learn 抛；learn(6,6) 抛
@Test void learnedHunterCannotShootWhenPoisoned()   // learned HUNTER、poison(2,6) → cannotShoot {6}
@Test void learningAWolfKingOrAnIdiotMapsToWolfOrVillager()  // learnedMechanicOf 狼王座位 → WOLF；白痴座位 → VILLAGER；没机制 → VILLAGER
```

- [ ] **Step 2: 跑** `-Dtest=NightResolverTests` → 编译失败（新字段 / 常量）。
- [ ] **Step 3: 实现** `NightResolver`（按 Interfaces；`resolve` 内部按 C7 顺序分段：收集 → K → B → P → M → cannotShoot → feedback）。类注释改成新规则。
- [ ] **Step 4: 跑** `NightResolverTests` → PASS；同时跑 `-Dtest=NightResolverTests,NightPlanTests,RoleSkillRegistryTests`。
- [ ] **Step 5: Commit** `feat(judge): night resolver — guard blocks poison, wolf king rules, mech wolf and psychic`

---

### Task 6: 白痴放逐出局翻牌

**Files:**
- Modify: `service/JudgeService.java`（`handleResolveVote`、删 `revealIdiot`）、`service/judge/JudgeDayFlow.java:131`、`service/judge/WinJudge.java`（注释）
- Test: `controller/JudgeFlowIntegrationTests.java`（改 `revealedIdiotDiesOnSecondExile`、`renamedIdiotStillFlipsOnExile`）

**Interfaces:**
- Produces: 放逐白痴 → 出局；事件 `idiot_revealed`（public，payload `actorSeatNo`）照记；公告 `X号是白痴，被放逐出局`（后接胜负句）；之后照常 `skill_pending`（白痴只有 skip；是警长则移交）。

- [ ] **Step 1: 改测试**：`revealedIdiotDiesOnSecondExile` 改名 `exiledIdiotIsOutAndRevealed`：放逐白痴后快照里他 `alive == false`、`latestAnnouncement` 以「X号是白痴，被放逐出局」开头、事件里有一条 `idiot_revealed`、阶段进入 `skill_pending`（待办是他的 `skill_ask`，选项只有 `skip`）。`renamedIdiotStillFlipsOnExile` 改成改名后的白痴同样出局且公告带「是白痴」。新增 `idiotSheriffPassesTheBadgeWhenExiled`：白痴当警长（用 `electWolfSheriff` 的办法选他）被放逐 → 待办里有 `badge`。
- [ ] **Step 2: 跑** `-Dtest=JudgeFlowIntegrationTests#exiledIdiotIsOutAndRevealed+renamedIdiotStillFlipsOnExile+idiotSheriffPassesTheBadgeWhenExiled` → FAIL。
- [ ] **Step 3: 实现**：`handleResolveVote` 里 `boolean idiot = isIdiot(eliminatedSeatNo)`；一律 `markPlayersDead`；公告 `idiot ? seat + "号是白痴，被放逐出局" + verdict句 : buildVoteAnnouncement(...)`；`idiot` 时 `addEvent(EVENT_IDIOT_REVEALED, public, actorSeatNo)`；`startPending` 不再带 `!idiotRevealed` 条件。删掉 `revealIdiot` 和 `status.idiotRevealed`、`setCanVote(false)`。`JudgeDayFlow` 的 `idiot_revealed` 文案「X号翻牌是白痴」保留。`WinJudge` 注释删掉「翻牌的白痴仍算存活神职」。
- [ ] **Step 4: 跑** `-Dtest=JudgeFlowIntegrationTests` → PASS（其余用例不受影响）。
- [ ] **Step 5: Commit** `feat(judge): the idiot is out when exiled and flips his card`

---

### Task 7: 机械狼的一步多动作与座位状态

**Files:**
- Modify: `dto/WolfbookDtos.java`（`JudgeNightActionRequest` 加 `List<JudgeNightActionItem> actions`，`record JudgeNightActionItem(String actionType, Integer targetSeatNo)`）、`service/JudgeService.java`（`submitNightAction`、`tonightSubmissions`、`validateNightAction`、`handleNextStep` 刀口、`seatStates`、`applyNightStatus`、`resolveNight` 学习落库）
- Test: `controller/JudgeFlowIntegrationTests.java`

**Interfaces:**
- Consumes: Task 5 的 `SeatState` 12 参、`validate` 新签名、`Outcome.learnedTonight` / `mechPoisonUsedBy`、`NightResolver.armed`。
- Produces:
  - 请求：`actions` 非空时忽略 `actionType/targetSeatNo`，只有机制是 `MECH_WOLF` 的座位能带 `actions`（其他 4000「只有机械狼可以一次提交多个行动」）；每项分别校验；存一条 `night_action_submit` 事件，payload `actions: [{action, targetSeatNo}]`（`actionType` 写 `"multi"`）。
  - `tonightSubmissions` 遇到 payload 有 `actions` 时展开成多条 `Submission`（同一 actorSeat）；每个座位仍只认最后一条事件。
  - 座位 `status_json` 新键：`mechLearned`（机制 code）、`mechLearnedRole`（角色名）、`mechLearnedDay`、`mechPoison`（`false` 表示用过）；机械狼学守卫后 `lastGuardSeat` 复用。`seatStates` 读这些键。
  - `applyNightStatus`：`learnedTonight` 写三个键；`mechPoisonUsedBy` 写 `mechPoison=false`；学守卫的机械狼 `guard` 写 `lastGuardSeat`。
  - `handleNextStep` 关狼队那一步时刀口 = `NightResolver.tallyKnife(狼队 kill 票)`；狼队全出局（`NightResolver.armed(seats)`）时取机械狼的 `kill` 目标。
  - 新 helper `private Game mechWolfGame()`：`joinedGame(5, 12)`（板子 5 机械狼通灵师，无法官）；`private int seatOf(Game, String roleName)`：`SELECT seat_no FROM judge_room_players WHERE room_id=? AND role_name=?`；`private JsonNode nightMulti(Game, int seat, String actionsJson)`：POST `/night-action` 带 `{"actions":[...]}`；`private void closeStep(Game)`：把 `step_deadline` 设成过去再 snapshot 一次（仿 `expire`）。

- [ ] **Step 1: 写失败测试**

```java
@Test void mechWolfBoardIsFullAndJoinable()        // joinedLobby(5,12) 返回 0；快照 judgeSupportLevel full
@Test void mechWolfWakesFirstAndLearns()           // 首夜第 0 步 kind mech_wolf；机械狼 nightMulti learn 女巫座位 → 0；非机械狼的座位带 actions → 4000
@Test void mechWolfCannotLearnItselfOrTheDead()    // learn 自己 4000；learn 已出局座位 4000
@Test void learnedSkillStartsTheNextNight()        // 首夜学女巫；第二夜 poison 7 → 0 且天亮 7 出局、status mechPoison=false；首夜当晚 poison → 4000
@Test void mechWolfKnifesOnceTheWolvesAreGone()    // markDead 三只狼；第二夜机械狼 kill 7 → 天亮 7 出局，女巫那一步 nightInfo.knifeSeat == 7
@Test void mechWolfCannotKnifeWhileAWolfLives()    // kill → 4000
@Test void learnedWolfGivesTwoKnives()             // 学狼人（第一夜）、狼全死、第三夜 kill 7 + kill_pierce 8 → 两人出局
```

- [ ] **Step 2: 跑** → FAIL / 4000。
- [ ] **Step 3: 实现** `JudgeService`（按 Interfaces）。`validateNightAction` 对 MECH_WOLF：轮次照旧用 `step.roleIds()`；每项 `action` 必须在 `Mechanic.MECH_WOLF.actions()`；逐项调 `NightResolver.validate(..., seats)`。
- [ ] **Step 4: 跑** `-Dtest=JudgeFlowIntegrationTests` → PASS。
- [ ] **Step 5: Commit** `feat(judge): mech wolf submits several actions in one step and keeps what it learned`

---

### Task 8: 快照里的机械狼 / 通灵师信息与开枪选项

**Files:**
- Modify: `dto/WolfbookDtos.java`（`JudgeNightInfoView` 加 `String identityResult`（通灵师 / 学通灵师的机械狼的查验结果，角色名）、`Boolean armed`、`String learned`（机制 code）、`String learnedRole`、`Boolean learnedEffective`、`Boolean mechPoison`、`List<JudgeNightActionItem> myActions`）；`service/JudgeService.java`（`buildNightInfo`、`skillOptions`）
- Test: `controller/JudgeFlowIntegrationTests.java`

**Interfaces:**
- Produces: 通灵师提交 `check` 后 `nightInfo.checkedSeat` + `identityResult`（`checkResult` 为 null）；机械狼那一步本人 `nightInfo`：`armed`、`learned`、`learnedRole`、`learnedEffective`、`mechPoison`、`lastGuardSeat`（学守卫时）、`myActions`，学习提交后 `checkedSeat = 目标`、`identityResult = 对方角色名`；学通灵师后查验同通灵师。`skillOptions`：`mechanic.canShoot()` 或（`MECH_WOLF` 且 `mechLearned == hunter`）且 `canShoot != false` → `use/skip`。非本人、法官一律看不到这些（现有规则）。

- [ ] **Step 1: 写失败测试**

```java
@Test void psychicSeesTheExactRoleAtOnce()          // 通灵师 check 狼人座位 → nightInfo.identityResult "狼人"、checkResult null
@Test void psychicSeesTheLearnedRoleOfTheMechWolf() // 机械狼学了女巫（同晚）→ 通灵师验它 "女巫"；没学 → "机械狼"
@Test void mechWolfNightInfoIsOnlyForItself()       // 机械狼那一步：机械狼自己 nightInfo.armed false、learned null；其他 11 人 nightInfo 为 null 或不含这些字段；步骤时长和持有者无关（deadline - now == roleSeconds）
@Test void mechWolfLearningShowsTheTargetsRole()    // learn → identityResult 角色名
@Test void mechWolfWithTheHunterSkillShoots()       // 学猎人当晚被放逐 → pending 选项含 use；被女巫毒 → 只有 skip
```

- [ ] **Step 2: 跑** → FAIL。
- [ ] **Step 3: 实现**。
- [ ] **Step 4: 跑** `JudgeFlowIntegrationTests` → PASS。
- [ ] **Step 5: Commit** `feat(judge): psychic and mech wolf see their own night info`

---

### Task 9: 狼王：开枪条件、不自刀、不自爆

**Files:**
- Modify: `service/JudgeService.java`（无逻辑改动预期，狼王夜死不开枪来自 Task 5 的 `cannotShoot`；自爆来自 Task 3；自刀来自 Task 5 的 validate）
- Test: `controller/JudgeFlowIntegrationTests.java`

- [ ] **Step 1: 改 / 写测试**：`knifedWolfKingShoots` 改名 `knifedWolfKingOnlyConfirms`（选项只有 `skip`）；保留 `exiledWolfKingShootsBeforeTheGameMovesOn`、`wolfKingShotHunterShootsBack`（猎人被狼王带走能开枪）；新增 `hunterShotWolfKingShoots`（猎人放逐开枪带走狼王 → 狼王待办含 `use`）、`wolfKingCannotKnifeHimself`（狼队那一步狼王 `kill` 自己 → 4000「狼王不能自刀」；普通狼自刀 → 0）、`wolfKingCannotSelfDestruct`（白天发言狼王 `self-destruct` → 4003，`dayInfo.canSelfDestruct` false；同房普通狼 true）。
- [ ] **Step 2: 跑** → 新增的 FAIL / 改名的看情况。
- [ ] **Step 3: 实现**：只补漏（预期 Task 3 / 5 已覆盖；若 `handleResolveVote` 后的待办选项还给狼王 `use`，查 `status.canShoot` 是否在夜里写入）。
- [ ] **Step 4: 跑** `JudgeFlowIntegrationTests` → PASS。
- [ ] **Step 5: Commit** `test(judge): wolf king shoots only when exiled or shot, never knifes or explodes himself`

---

### Task 10: 自爆的狼当晚参与点刀，天亮才死

**Files:**
- Modify: `service/JudgeService.java`（`selfDestruct`、`explodeOnStage`、`resolveNight`、`winSeats`、`canSelfDestruct`、`toPlayerView`）、`dto/WolfbookDtos.java`（`JudgePlayerView` 加 `boolean exploded`）
- Test: `controller/JudgeFlowIntegrationTests.java`

**Interfaces:**
- Produces:
  - 座位 `status_json.exploded = true`（白天自爆、还活着）；`JudgePlayerView.exploded`；`winSeats` 把 `exploded` 当作出局；`canSelfDestruct` 要求 `!exploded`。
  - 白天发言自爆：不 `markPlayersDead`，写 `exploded`；胜负按「他已出局」判（最后一只狼 → 当场结束，此时再 `markPlayersDead`）；警长照旧移交后天黑；否则直接天黑。
  - 警上自爆：座位在 `pendingDeaths` 里 → 留在里面、不写 `exploded`（算昨夜死亡）；否则写 `exploded`。不再从 `pendingDeaths` 删他。
  - `resolveNight`：`exploded && alive` 的座位先 `markPlayersDead(PHASE_NIGHT_ACTION)` 并从 `deadSeatNos` 去掉；不进「昨夜出局」公告、不进 `startPending`、不进 `pendingDeaths`。
  - 夜里他和活人一样：狼队那一步在 `aliveHolders` 里、`teammateSeats` / `wolfPicks` 含他；可被验、守、毒。

- [ ] **Step 1: 改 / 写测试**：`wolfExplodesDuringDaySpeechAndNightFalls` 改成：自爆后快照里他 `alive true`、`exploded true`、阶段 `night_action`；狼队那一步 `wolfPicks` 含他、他 `kill 7` 返回 0；天亮：7 出局、他 `alive false`、公告「昨夜出局：7号」不含他、`rounds` 里只有一条他的自爆记录。`lastWolfExplodingEndsTheGame` 保持（当场结束）。`knifedWolfCanStillExplodeOnStage` 改成：他仍在 `pendingDeaths`，公布死讯时在昨夜死者里，`exploded` 不为 true，只一条出局。新增 `explodedWolfKnifedTheSameNightDiesOnce`（狼队自刀自爆的狼 → 天亮公告不含他、他只死一次）、`explodedWolfStaysOutOfTheDay`（自爆后 `dayInfo.canSelfDestruct` false；当天不再出现在发言 / 投票里：自爆当天本来就直接天黑，断言阶段）、`explodedSheriffPassesTheBadgeThenNight`（沿用 `sheriffExplodesThenPassesTheBadgeThenNight`，加断言他 `alive true` 直到天亮）。
- [ ] **Step 2: 跑** → FAIL。
- [ ] **Step 3: 实现**。
- [ ] **Step 4: 跑** `JudgeFlowIntegrationTests` 和 `JudgeFlowRepeatableReadTests` → PASS。
- [ ] **Step 5: Commit** `feat(judge): an exploded wolf still knifes that night and dies at dawn`

---

### Task 11: 后台：两层配置分区与新机制

**Files:**
- Modify: `admin/src/types.ts`（`BoardJudgeRules.poisonBounce?: boolean`）、`admin/src/utils/board.ts`（`MECHANIC_PRIORITY` 加 `mech_wolf: 0, psychic: 4`；`buildJudgeRules(existing, witchFirstNight, poisonBounce: boolean)`）、`admin/src/components/boards/BoardEditor.vue`（`form.poisonBounce`、`lineupHasMechWolf`、分区）
- Test: `admin/tests/board.test.mjs`

- [ ] **Step 1: 写失败测试**（`admin/tests/board.test.mjs`）：`buildJudgeRules({speechSeconds: 90}, false, true)` 得 `{speechSeconds: 90, witchSelfSave: 'never', poisonBounce: true}`；`isWolfTeam({mechanic: 'mech_wolf', faction: '狼人', name: '机械狼'})` 为 false；通用夜间顺序里机制 `mech_wolf` 的角色排第一、`psychic` 和 `seer` 同档（仿现有顺序用例）。
- [ ] **Step 2: 跑** `node --test "tests/*.test.mjs"`（`admin/` 下）→ FAIL。
- [ ] **Step 3: 实现** `board.ts`、`types.ts`。
- [ ] **Step 4: 跑** → PASS。
- [ ] **Step 5: 编辑页**：把「女巫自救」那个 `nt-field` 和新的「弹毒」`nt-field`（`v-if="lineupHasMechWolf"`，两档「不反弹」「反弹给女巫」，label「机械狼弹毒 · 学到守卫的机械狼挡女巫的毒时」）包进一个小标题为「板子专属配置 · 只在阵容里有对应角色时出现」的区块；它上面加一个「通用配置 · 建房时房主可调」区块，只读列出 `loadedJudgeRules` 的 `speechSeconds / sheriffSpeechSeconds / wolfSeconds / roleSeconds / sheriffDefault`（没配显示「默认」）。`lineupHasMechWolf` = 阵容里有角色 `mechanic === 'mech_wolf'`（仿 `lineupHasWitch`）。载入时 `form.poisonBounce = detail?.judgeRules?.poisonBounce === true`；保存时 `buildJudgeRules(loadedJudgeRules, form.witchFirstNight, form.poisonBounce)`。
- [ ] **Step 6: 跑** `npm run build`（`admin/` 下）→ 成功。
- [ ] **Step 7: Commit** `feat(admin): board rules split into common and board-specific; poisonBounce switch`

---

### Task 12: 小程序：机械狼 / 通灵师面板、一步多动作、自爆待死座位、播报

**Files:**
- Modify: `miniprogram-vue/src/utils/judge/night-panel.js`、`stage.js`、`voice-cue.js`、`pages/judge/views/NightView.vue`、`pages/judge/room.vue:284`、`services/api.js`
- Test: `miniprogram-vue/tests/judge-night-panel.test.mjs`、`judge-stage.test.mjs`、`judge-voice-cue.test.mjs`

**Interfaces:**
- Consumes: 快照 `nightInfo` 新字段（Task 8）、`players[].exploded`（Task 10）、`currentStep.kind` 为 `psychic` / `mech_wolf` / `idle`。
- Produces: `nightPanel` 返回 `kind: 'psychic'`（同 seer 结构，`result` 用 `identityResult`）；`kind: 'idle'`（`buttons: [confirm 「我知道了」]`）；`kind: 'mech_wolf'`：`{ kind, armedText, learnedText, done, sections: [...], buttons }`，`buttons` 每项 `{ action, label, target, disabled }`，提交按钮 `{ action: 'submit', actions: [{actionType, targetSeatNo}] }`。面板状态机（都在纯函数里，`selection` 是当前点的座位）：没学过 → 「学习 <选的座位>」+「不学」；学过且生效 → 按机制一组按钮（check / poison / guard+空守）；`armed` → 多一组「刀 <座位>」（学狼人且生效时再多「破盾刀 <座位>」）；已选好的动作显示在 `sections` 里，最后一个「确认提交」按钮把所有已选动作一起发。狼队面板：`selfPlayer.mechanic === 'wolf_king'` 时「刀」按钮在 `selection === self.seatNo` 时 `disabled` 且 label「狼王不能自刀」（若 `selfPlayer` 没有 `mechanic` 字段，在 `JudgePlayerView` 里加 `mechanic`，只给本人 `self` 下发）。`stage.js`：`player.exploded` 当作 `dead` 处理。`voice-cue.js` `NIGHT_HINTS` 加 `psychic: '请选择要查验的人'`、`mech_wolf: '请决定今晚的行动'`、`idle: '请稍候'`。

- [ ] **Step 1: 写失败测试**（三个测试文件，按 Interfaces 断言：面板结构、disabled、exploded 座位 dim、播报台词）。
- [ ] **Step 2: 跑** `node --test "tests/*.test.mjs"`（`miniprogram-vue/` 下）→ FAIL。
- [ ] **Step 3: 实现纯函数**。
- [ ] **Step 4: 跑** → PASS。
- [ ] **Step 5: 接线**：`NightView.vue` 加 `psychic`（复用 seer 块）、`idle`、`mech_wolf` 三个 `block`（机械狼块：`sections` 列表 + 按钮组；点「确认提交」`emit('act', { kind: 'night', actions })`）；`room.vue` 的 `night` 分支：`event.actions ? api.submitJudgeNightAction(id, { actions: event.actions }) : 原样`；`api.js` 的 `submitJudgeNightAction` 透传 body（确认不是只挑两个字段）。样式只用 `nt-*` 和已有 `jn-*` 类。
- [ ] **Step 6: 跑** `npm run dev:h5` 不需要；跑 `node --test` 再过一遍 → PASS。
- [ ] **Step 7: Commit** `feat(miniprogram): mech wolf and psychic night panels; exploded seats; cues`

---

### Task 13: 联调脚本、文档、全量测试

**Files:**
- Modify: `miniprogram-vue/scripts/judge-e2e.mjs`、`docs/judge/house-rules/README.md`（「和引擎现状不同」节）、`docs/design/judge-auto-mode.md`（§17 规则表）、`CLAUDE.md`、`AGENTS.md`

- [ ] **Step 1: e2e**：加模式 `mechwolf`（`both` 也跑）：无法官机械狼通灵师 12 人；机器人在 `mech_wolf` 那一步首夜 `learn` 一个活着的非自己座位，之后每晚按 `nightInfo`：`learnedEffective` 且 `learned === 'psychic'` → `check`；`'witch'` 且 `mechPoison` → `poison`；`'guard'` → `guard`；`armed` → 加 `kill`；都没有 → `none`；通灵师每晚 `check`。每轮检查零场外：非机械狼的快照 `nightInfo` 里没有 `armed/learned` 字段；机械狼那一步所有人的 `currentStep.deadline` 相同。脚本注释和 `CLAUDE.md` 的用法行同步。
- [ ] **Step 2: 跑 e2e**（本地后端 H2 8098：`cd backend; ./mvnw spring-boot:run -Dspring-boot.run.arguments="--server.port=8098 ..."`，参数同之前的 H2 跑法）：`node scripts/judge-e2e.mjs http://127.0.0.1:8098 mechwolf` → 一局跑完、没有 fail。
- [ ] **Step 3: 文档**：README「和引擎现状不同」删掉守卫挡毒（03、08）、狼王、自爆参与点刀、弹毒、机械狼人 / 通灵师五条（只剩梦魇猎人和其余机制）；`judge-auto-mode.md` §17 加通灵师、机械狼两行、改狼王（开枪条件、不自刀、不自爆）、守卫（挡毒）、白痴（出局翻牌）、猎人（不翻牌）；`CLAUDE.md` / `AGENTS.md` 电子法官节：机制列表加 psychic / mech_wolf、`Mechanic` 描述、`NightResolver` 一句、自爆一段（当晚参与点刀，天亮死；最后一狼当场结束）、快照新字段（`nightInfo.identityResult/armed/learned/...`、`players[].exploded`）、`night-action` 的 `actions`、板子专属配置 `poisonBounce`、e2e 模式；只改这几段。
- [ ] **Step 4: 全量**：`backend` `./mvnw test`；`miniprogram-vue` 和 `admin` `node --test "tests/*.test.mjs"`；`admin` `npm run build`。全部通过。
- [ ] **Step 5: Commit**（`CLAUDE.md` / `AGENTS.md` 用 `git add -p` 只挑本计划的段落；`README.md` 同理）`docs(judge): engine matches the three house-rule boards; e2e plays the mech wolf board`
