# 电子法官二期 B：小程序 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 小程序用上二期 A 的新流程：建房选模式和房间规则、大厅、夜里人人同屏加持有者操作面板、法官上帝视角（暂停 / 点杀）、出局技能、上警、发言、投票与票型、结果与对局记录，房主手机播报。

**Architecture:** 新流程房间换一个新的 `pages/judge/room.vue`：页面负责轮询快照、导航栏、暂停遮罩和把快照交给一个「当前视图」组件；每个视图是 `pages/judge/views/` 下的一个组件，页面里只嵌一层（遵守「组件里不再嵌组件」）。画面上的判断（该显示哪个视图、两侧座位怎么排、座位角标、各角色面板内容、房主 / 法官按钮、倒计时、播报台词）全部放进 `utils/judge/` 下的纯函数，用 Node 测试锁住；视图组件只渲染。旧流程房间（线上旧版建的）原样搬到 `pages/judge/room-legacy.vue`，新页面发现是旧房间就跳过去。

**Tech Stack:** uni-app（Vue 3 `<script setup>`）、微信小程序；Node 内置测试运行器；微信开发者工具的 `wcsc` / `wcc` 编译检查。

**Spec:** `docs/design/judge-auto-mode.md`（§4.4 夜里看到什么与零场外、§4.5 白天、§4.6 上警、§4.7 待办、§4.7b 对局记录、§4.8 网杀借鉴、§4.10 有法官模式、§6 小程序端）。原型画布：https://claude.ai/artifact/26tryPVskupYeWtP9Psnfg （各画板名对应下文视图）。后端接口以二期 A 为准（`CLAUDE.md` 电子法官一节）。

## Global Constraints

- **零场外**（§4.4）：夜里房主手机以外的所有手机画面一致——同一张当前角色立绘、同样的倒计时；持有者只是在立绘下多一块操作面板。不调用 `uni.vibrateShort` / `uni.vibrateLong`，不弹 toast 提示「轮到你」，不播任何声音；进入房间页 `uni.setKeepScreenOn({ keepScreenOn: true })`，离开时关。夜间轮询 2 秒，白天 3 秒，所有人同一节奏。
- **只有房主手机出声**（§6）：播报只在 `snapshot.host === true` 的手机上；本期用现有 `utils/judge/voice.js`（WechatSI）合成，2C 换成预生成片段。
- **组件不嵌组件**：视图组件里不能再用 `NavBar`、`BoardCard` 等任何自定义组件；头像、立绘卡都用页面内联结构加 `App.vue` 的全局 `nt-*` 类。WXSS 只用类选择器（不能用 `*`）。颜色用 `App.vue` 里的 CSS 变量，原型按 390 宽画，1px = 2rpx。
- **座位版式**（§4.4）：座位分两列（左 1–n、右 n+1–N，n = ⌈N/2⌉），圆形头像 + 底部号码徽标；每侧 ≤ 6 人用 46px（92rpx）头像，7–9 人用 34px（68rpx）。
- 所有页面 `navigationStyle: custom`，用 `components/NavBar.vue`；导航高度用 `utils/nav.js` 的 `getNavInsets()`。
- 接口错误文案用 `services/request-errors.js` 翻好的 `error.message`；4002（并发 / 状态变了）只刷新快照、不弹错。
- 文案中文；角色与世界观原创（不用商业作品名字）。
- 提交信息结尾：`Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`。

## Review Focus

1. 夜里非持有者的手机和持有者的手机除了操作面板之外有任何差异（标题、倒计时、立绘、按钮占位高度、提示语）→ 都算泄露身份（Task 6：`nightScreensMatchExceptThePanel`）。
2. 死掉的玩家夜里看到什么：和活人一样的立绘屏，没有操作面板，也没有「你已出局」之类只在夜里出现的差异（Task 6：`deadPlayersSeeTheSameNightScreen`）。
3. 网络抖动导致一次轮询失败或返回 4002：页面不能白屏或卡在旧视图，下一次轮询自动恢复（Task 2：`pollFailureKeepsTheLastSnapshot`）。
4. 房主手机切到后台再回来、或断网重连：同一步不重复播报，也不漏掉当前这一步（Task 12：`voiceCueIsPlayedOncePerStep`）。
5. 13–18 人的板子：座位改紧凑尺寸、不横向溢出、立绘高度跟两侧列对齐（Task 3：`compactSeatsForLargeBoards`）。

**命令约定**（在 `miniprogram-vue/` 下）：单测 `node --test "tests/*.test.mjs"`；构建 `npm run build:mp-weixin`；编译检查：在 `dist/build/mp-weixin` 下对每个 `.wxss` 跑 `"D:/App/微信web开发者工具/resources/app.asar.unpacked/node_modules/wcc-exec/wcsc.exe" -lc <file>`，再跑 `wcc.exe -d <所有 .wxml 相对路径>`，退出码都为 0。后端用例在 `backend/` 下 `JAVA_HOME=/d/Kf/jdk21 ./mvnw -q test -Dtest=JudgeFlowIntegrationTests`。

---

### Task 1: 后端小补：快照告诉客户端是不是新流程、狼队「空刀」

**Files:**
- Modify: `backend/.../dto/WolfbookDtos.java`、`backend/.../service/JudgeService.java`
- Test: `backend/.../controller/JudgeFlowIntegrationTests.java`

**Interfaces:**
- Produces: 快照加 `boolean autoJudge`（= `judge_rooms.auto_judge_enabled`）；`JudgeWolfPickView` 加 `boolean decided`（这只狼今晚交过刀人或空刀为 true；`targetSeatNo` 为 null 且 `decided` 为 true 即空刀）。

- [ ] **Step 1: 写失败测试**

```java
@Test void snapshotSaysWhetherTheRoomIsNewFlow()   // autoGame → autoJudge==true；startedGame（旧流程）→ autoJudge==false
@Test void wolfPicksTellNoKillFromUndecided()      // autoGame12：狼 A act(none)、狼 B 不动 → A 的 decided==true、targetSeatNo null；B 的 decided==false
```

- [ ] **Step 2: 跑测试确认失败。**
- [ ] **Step 3: 实现**（`buildNightInfo` 里把 `none` 提交也计入 decided）。
- [ ] **Step 4: 跑测试确认通过**，再跑后端全量。
- [ ] **Step 5: 提交** `feat(backend): snapshot flags new-flow rooms and wolves' no-kill choice`

---

### Task 2: 接口层、旧房间页搬家、新房间页骨架

**Files:**
- Modify: `src/services/api.js`（新接口 + 快照默认值）
- Move: `src/pages/judge/room.vue` → `src/pages/judge/room-legacy.vue`（内容不改，只改页面内跳转自身的路径）；`src/pages.json` 注册 `pages/judge/room-legacy`
- Create: `src/pages/judge/room.vue`（新页面骨架）、`src/utils/judge/room-poll.js`
- Test: `tests/judge-room-poll.test.mjs`

**Interfaces:**
- Produces（`api.js`）：`updateJudgeRoomRules(roomId, rules)`（`PUT /api/judge/rooms/{id}/rules`）、`submitJudgeSheriff(roomId, payload)`、`submitJudgeSkill(roomId, payload)`；`normalizeJudgeRoomSnapshot` 补默认值：`rounds: []`、`pkSeats: []`、`sheriffCandidates: []`、`nightInfo: null`、`pending: null`、`speech: null`、`currentStep: null`、`paused: false`、`phaseDeadline: null`、`autoJudge: false`。
- Produces（`room-poll.js`，纯函数）：
  - `pollInterval(snapshot) → number`：夜里（`currentPhase === 'night_action'`）2000，其余 3000，`lobby` 3000，`finished` 不轮询返回 0。
  - `mergePollResult(previous, result) → { snapshot, error }`：`result.ok` 为真用新快照；失败时保留 `previous`，`error` 只在非 4002 时给文案（4002 返回 `error: ''`）。
- 新 `room.vue`：`onLoad` 读 `roomId`，拉快照；`snapshot.autoJudge === false` 时 `uni.redirectTo('/pages/judge/room-legacy?roomId=…')`；按 `pollInterval` 用 `setTimeout` 链轮询（不用 `setInterval`，避免请求堆积）；`onShow` / `onHide` 开关轮询和常亮；本任务先渲染 `NavBar` + 房号 + 当前阶段文字占位。

- [ ] **Step 1: 写失败测试**

```js
test('nightPollsFasterThanDay', () => { /* night_action → 2000；day_speech → 3000；finished → 0 */ })
test('pollFailureKeepsTheLastSnapshot', () => {
  // previous=A，result={ok:false, code:4002} → snapshot===A, error===''
  // result={ok:false, code:5000, message:'服务器开小差'} → snapshot===A, error==='服务器开小差'
})
```

- [ ] **Step 2: 跑 `node --test "tests/judge-room-poll.test.mjs"` 确认失败**（模块不存在）。
- [ ] **Step 3: 实现**；搬旧页面，`pages.json` 注册新路径；`index.vue`、`create.vue` 跳转仍指向 `pages/judge/room`。
- [ ] **Step 4: 跑全部前端单测通过；`npm run build:mp-weixin` 成功；wcsc / wcc 检查通过。**
- [ ] **Step 5: 提交** `feat(miniprogram): new judge room page shell; legacy rooms keep the old console`

---

### Task 3: 房间视图的纯逻辑：选视图、排座位、倒计时

**Files:**
- Create: `src/utils/judge/room-view.js`
- Test: `tests/judge-room-view.test.mjs`

**Interfaces:**
- Produces:
  - `pickView(snapshot) → 'lobby' | 'night' | 'pending' | 'sheriff' | 'announce' | 'speech' | 'vote' | 'voteResult' | 'result'`：`roomStatus` lobby → lobby，finished → result；`currentPhase` 对应：`night_action` → night、`skill_pending` → pending、`sheriff_*` → sheriff、`day_announce` → announce、`day_speech` → speech、`exile_vote` / `vote_pk` → vote、`vote_result` → voteResult。
  - `seatColumns(players, playerCount) → { left, right, size, gap, cardHeight }`：按座位号排，左列 1..⌈N/2⌉；每侧 ≤ 6 人 `size: 92`、`gap: 20`（rpx），7–9 人 `size: 68`、`gap: 16`；`cardHeight = 每侧人数 × size + (人数 − 1) × gap`（立绘高度对齐两侧列）。空座位也占位（`empty: true`）。
  - `countdown(deadline, now, paused) → { seconds, text, warn }`：剩余秒数向下取整、不小于 0；`paused` 时 `text: '暂停'`；剩 ≤ 10 秒 `warn: true`。
  - `phaseTitle(snapshot) → string`：「第 N 夜 · 狼人请睁眼」「第 N 天 · 发言」等，夜里只用 `currentStep.roleName`，**不看查看者身份**。

- [ ] **Step 1: 写失败测试**

```js
test('pickViewFollowsStatusAndPhase', ...)          // 9 种组合各一条
test('seatsSplitLeftAndRight', ...)                  // 12 人：左 1–6、右 7–12，size 92
test('compactSeatsForLargeBoards', ...)              // 15 人：左 1–8、右 9–15，size 68，cardHeight = 8*68+7*16
test('countdownNeverGoesNegativeAndWarnsAtTen', ...)
test('nightTitleIsTheSameForEveryViewer', ...)       // 同一快照分别以持有者、平民、死人为 selfPlayer → phaseTitle 相同
```

- [ ] **Step 2: 跑测试确认失败。**
- [ ] **Step 3: 实现。**
- [ ] **Step 4: 跑全部前端单测通过。**
- [ ] **Step 5: 提交** `feat(miniprogram): judge room view model`

---

### Task 4: 建房页：选模式、房间规则

**Files:**
- Modify: `src/pages/judge/create.vue`、`src/utils/judge/meta.js`
- Create: `src/utils/judge/create-room.js`
- Test: `tests/judge-create-room.test.mjs`

**Interfaces:**
- Produces（`create-room.js`）：
  - `defaultRoomRules(board) → { sheriffEnabled, speechSeconds, sheriffSpeechSeconds, wolfSeconds, roleSeconds }`：取 `board.ruleDetail.judgeRules`，缺的用 `true / 120 / 150 / 60 / 30`。
  - `stepRule(value, key, delta) → number`：发言 / 警上发言 30–300 步长 30，狼队 30–120 步长 10，单人 15–60 步长 5，越界夹住。
  - `buildCreatePayload(board, mode, rules) → { boardId, judgeMode, autoJudge: true, rules }`。
  - `boardRuleLine(board) → string`：只读一行「屠边 · 女巫只首夜可自救 · 一晚一瓶」（取板子的胜负和 `judgeRules.witchSelfSave` / `witchOnePotionPerNight`）。
  - `canChooseJoined(board) → boolean`：`judgeSupportLevel === 'full'`。
- `meta.js` 里 `joined` 去掉「二期开放」；`create.vue` 按原型画板「建房 · 选模式与房间规则」重做：两种模式并排卡片、板子卡片下的只读规则行、「房间规则」面板（上警开关 + 四个时长步进器）、模式提示（有法官：你是法官不拿牌；无法官：你也拿牌，手机就是法官的声音、建议连蓝牙音箱）。

- [ ] **Step 1: 写失败测试**

```js
test('defaultRulesComeFromTheBoard', ...)      // 板子配了 speechSeconds 90 → 90；没配 → 120
test('stepRuleClampsToItsRange', ...)          // wolfSeconds 120 + 10 → 120；roleSeconds 15 − 5 → 15
test('payloadAlwaysAsksForTheNewFlow', ...)    // autoJudge === true，judgeMode 原样
test('joinedOnlyForFullBoards', ...)
test('boardRuleLineIsReadOnlyText', ...)       // 屠城板子 → 以「屠城」开头
```

- [ ] **Step 2–4: 失败 → 实现 → 单测、构建、wcsc / wcc 通过。**
- [ ] **Step 5: 提交** `feat(miniprogram): create judge rooms with a mode and room rules`

---

### Task 5: 大厅视图

**Files:**
- Create: `src/pages/judge/views/LobbyView.vue`、`src/utils/judge/lobby.js`
- Modify: `src/pages/judge/room.vue`（`view === 'lobby'` 时渲染）
- Test: `tests/judge-lobby.test.mjs`

**Interfaces:**
- Produces（`lobby.js`）：
  - `lobbySeats(snapshot) → [{ seatNo, nickname, avatar, ready, me, empty }]`（`playerCount` 个，空位 `empty: true`）。
  - `lobbyAction(snapshot) → { kind: 'start' | 'ready' | 'unready' | 'wait', label, disabled }`：房主且全员准备 → start；房主且未齐 → start + disabled + 「还差 N 人」；玩家 → ready / unready；有法官房间的法官本人不入座、无准备按钮。
  - `rulesRows(snapshot) → [{ key, label, value, editable }]`：五项节奏规则，`editable` 只给房主。
- `LobbyView.vue` 按原型画板「大厅 · 等人入座与本局规则」：房号大卡 + 邀请（沿用旧页面的分享逻辑）、座位网格、本局规则（房主可步进，调 `updateJudgeRoomRules`，失败回滚）、底部主按钮。

- [ ] **Step 1: 写失败测试**

```js
test('hostStartsOnlyWhenEveryoneIsReady', ...)
test('judgeHasNoReadyButton', ...)             // observer 房间法官 → kind 'start'，不出现 ready
test('joinedHostAlsoReadies', ...)             // joined 房间房主未准备 → 先 'ready'，全员准备后 'start'
test('onlyTheHostEditsRules', ...)
```

- [ ] **Step 2–4: 失败 → 实现 → 单测、构建、wcsc / wcc 通过。**
- [ ] **Step 5: 提交** `feat(miniprogram): judge lobby with room rules`

---

### Task 6: 夜晚视图：人人同屏 + 持有者面板

**Files:**
- Create: `src/pages/judge/views/NightView.vue`、`src/utils/judge/night-panel.js`
- Modify: `src/pages/judge/room.vue`
- Test: `tests/judge-night-panel.test.mjs`

**Interfaces:**
- Consumes: Task 3 `seatColumns` / `countdown` / `phaseTitle`；快照 `currentStep`（`line` 非空即本人是持有者或法官）、`nightInfo`、`selfPlayer`。
- Produces（`night-panel.js`）：
  - `isStepHolder(snapshot) → boolean`：`!judgeViewer && currentStep.line !== '' && selfPlayer.alive`。
  - `nightPanel(snapshot) → null | { kind: 'wolf' | 'witch' | 'seer' | 'guard' | 'hunter' | 'generic', ... }`：
    - wolf：`rows`（每只活狼：座位、是否本人、`decided` / 目标，文案「还没选 / 空刀 / X 号」）、`knife`（多数票目标、平票「平票 · 随机」、都空刀「空刀」，规则同后端）、按钮「空刀」「刀 X 号」。
    - witch：`knifeSeat`（null 显示「今晚平安」），解药 / 毒药两张卡（剩余数、能不能自救按 `rules.witchSelfSave` 和当晚是不是第一夜），按钮「救 X 号」「毒 X 号」「不用药」。
    - seer：`checkedSeat` / `checkResult`（交了才有）、按钮「查验 X 号」。
    - guard：上一夜守的人不能选（座位禁用）、按钮「守 X 号」「空守」。
    - hunter：「今晚你能开枪 / 被毒了不能开枪」由后端出局时决定，这里只给「确认」按钮。
  - `seatBadges(snapshot, selection) → Map<seatNo, { mate, votes, selected, disabled, dead }>`：狼队那一步给狼看队友红点和票数；其余人只有 `dead`。
- `NightView.vue` 按原型画板「夜晚 · 立绘屏 / 狼队 / 女巫 / 预言家」：两侧座位、居中当前角色立绘（`fullIllustration`，没有用卡背）、立绘下标题和倒计时、持有者才有的信息面板和底部按钮；**非持有者在同一位置渲染等高的空白占位**，画面结构不变；面板区的高度只由 `currentStep.kind` 决定（`panelHeight(kind)`，同一步所有人一样），不由查看者是谁决定。提交调 `submitJudgeNightAction`（英文 `actionType`），成功后不弹提示，只更新面板。

- [ ] **Step 1: 写失败测试**

```js
test('wolfPanelShowsPicksAndTheKnife', ...)      // 两狼选 7、一狼空刀、一狼未选 → 刀口「7 号 · 2 票」，行文案含「空刀」「还没选」
test('witchPanelHidesSaveWhenNoAntidote', ...)
test('guardCannotPickLastNightsSeat', ...)
test('nightScreensMatchExceptThePanel', () => {
  // 同一快照分别以持有者、平民、法官之外的另一玩家为 selfPlayer：
  // phaseTitle、countdown、立绘地址、seatBadges（去掉 mate/votes）都相同；只有 nightPanel 不同
})
test('deadPlayersSeeTheSameNightScreen', ...)     // selfPlayer.alive=false → nightPanel null，其余同平民
```

- [ ] **Step 2–4: 失败 → 实现 → 单测、构建、wcsc / wcc 通过。** 代码里搜不到 `vibrate`（`grep -rn "vibrate" src/pages/judge src/utils/judge` 无结果）。
- [ ] **Step 5: 提交** `feat(miniprogram): night screen shared by everyone, action panel for the holder`

---

### Task 7: 法官上帝视角、暂停 / 点杀、暂停遮罩

**Files:**
- Modify: `src/pages/judge/views/NightView.vue`、`src/pages/judge/room.vue`
- Create: `src/utils/judge/judge-bar.js`
- Test: `tests/judge-bar.test.mjs`

**Interfaces:**
- Produces（`judge-bar.js`）：
  - `godSeats(snapshot) → Map<seatNo, { roleShort, roleColor, votes }>`：只在 `judgeViewer` 时有值；角标取角色名首字，颜色按阵营。
  - `tonightRows(snapshot) → [{ role, seats, state }]`：从 `nightActions` 汇总每个角色今晚做了什么（「2→7 4→7 8→? · 刀口 7 号」「解药 1 · 毒药 1 · 还没轮到」）。
  - `judgeActions(snapshot) → { pause: bool, resume: bool, forceOut: bool }`：只给法官；暂停中只有 resume 和 forceOut。
  - `pausedOverlay(snapshot) → null | { title: '法官暂停', hint }`：所有人同一张。
- 法官视图按原型画板「有法官 · 夜里上帝视角」：座位带身份角标、今晚面板、底部「暂停计时 / 继续计时」「点杀」（选座位后确认，调 `advance force_out`）。`room.vue` 在 `paused` 时给所有视图盖同一张暂停遮罩、倒计时停表。

- [ ] **Step 1: 写失败测试**

```js
test('onlyTheJudgeSeesRoles', ...)               // judgeViewer=false → godSeats 为空
test('tonightRowsSummariseTheNight', ...)
test('pausedRoomOffersResumeOnly', ...)
test('everyoneGetsTheSamePauseOverlay', ...)
```

- [ ] **Step 2–4: 失败 → 实现 → 单测、构建、wcsc / wcc 通过。**
- [ ] **Step 5: 提交** `feat(miniprogram): judge god view with pause and force-out`

---

### Task 8: 出局待办视图（技能询问、猎人开枪、移交警徽）

**Files:**
- Create: `src/pages/judge/views/PendingView.vue`、`src/utils/judge/pending.js`
- Test: `tests/judge-pending.test.mjs`

**Interfaces:**
- Produces（`pending.js`）：`pendingPanel(snapshot) → null | { kind, title, options, needsTarget, targets }`：`pending` 只发给当事人；`skill_ask` 的 options 只有 `skip` 时按钮是「确认」，有 `use` 时「发动技能 / 不发动」；`hunter_shot` 和 `badge` 要选目标（活人，不含自己）。其他人看到 `{ kind: 'waiting', title: '等待出局玩家确认' }`，**不显示是谁、什么技能**。
- 视图按原型画板「出局 · 技能询问」，提交调 `submitJudgeSkill`。

- [ ] **Step 1: 写失败测试**

```js
test('villagerOnlyConfirms', ...)
test('hunterChoosesToShootThenPicksATarget', ...)
test('othersOnlySeeWaiting', ...)                 // 非当事人 → kind 'waiting'，title 不含座位号
```

- [ ] **Step 2–4: 失败 → 实现 → 单测、构建、wcsc / wcc 通过。**
- [ ] **Step 5: 提交** `feat(miniprogram): skill prompts after someone is out`

---

### Task 9: 上警与公布死讯

**Files:**
- Create: `src/pages/judge/views/SheriffView.vue`、`src/pages/judge/views/AnnounceView.vue`、`src/utils/judge/sheriff.js`
- Test: `tests/judge-sheriff.test.mjs`

**Interfaces:**
- Produces（`sheriff.js`）：
  - `sheriffPanel(snapshot) → { stage: 'signup' | 'speech' | 'vote' | 'pk', canSignUp, canWithdraw, canVote, targets, speakerSeat }`：报名阶段每个活人「上警 / 不上警」（不显示别人选了什么）；发言阶段警上的人有「退水」；投票阶段只有没上警的人能投、只能投 `sheriffCandidates`（PK 只能投 `pkSeats`）。
  - `announcePanel(snapshot) → { deaths, sheriffSeat, canChooseOrder, orderOptions }`：死讯来自 `latestAnnouncement` 和 `rounds` 当天的 `nightDeaths`；警长本人看到「警左 / 警右」（有死者再加「死左 / 死右」），选了调 `submitJudgeSheriff({ speechOrder })`。
- 视图按原型画板「白天 · 上警」「白天 · 公布死讯 / 定顺序」；房主操作栏在 Task 10。

- [ ] **Step 1: 写失败测试**

```js
test('signupHidesOthersChoices', ...)
test('candidatesCannotVote', ...)
test('pkVotesOnlyForTiedSeats', ...)
test('onlyTheSheriffChoosesTheOrder', ...)
```

- [ ] **Step 2–4: 失败 → 实现 → 单测、构建、wcsc / wcc 通过。**
- [ ] **Step 5: 提交** `feat(miniprogram): sheriff election and daybreak announcement`

---

### Task 10: 发言、投票、票型公布与房主操作栏

**Files:**
- Create: `src/pages/judge/views/SpeechView.vue`、`src/pages/judge/views/VoteView.vue`、`src/utils/judge/day.js`
- Test: `tests/judge-day.test.mjs`

**Interfaces:**
- Produces（`day.js`）：
  - `speechPanel(snapshot) → { order, index, speakerSeat, isSpeaker, sequence }`：发言者本人有「结束发言」（`advance next_speaker`）。
  - `votePanel(snapshot) → { stage: 'exile' | 'pk', canVote, targets, abstainAllowed: true, progress }`：翻牌白痴不能投；PK 平票者不能投、只能投平票者；`progress` 只显示投了几个人，不显示投给谁。
  - `voteResultPanel(snapshot) → { tallies, eliminatedSeat, revealedIdiot, pkSeats }`：来自 `voteTallies` 和 `rounds` 当天最后一轮放逐。
  - `hostActions(snapshot) → [{ action, label, disabled }]`：只给房主（`host`），按阶段给：`sheriff_signup` 截止报名、`sheriff_speech` 下一位 / 开始投票、`sheriff_vote` / `sheriff_pk` 结算、`day_announce` 开始发言、`day_speech` 跳过这位 / 发起投票、`exile_vote` / `vote_pk` 结算、`vote_result` 继续；无法官房主多一个「强制结束」（`finish`，二次确认）。**不带任何结果字段**（二期 A 会拒）。
- 视图按原型画板「白天 · 发言」「白天 · 放逐投票」「白天 · 票型公布」。

- [ ] **Step 1: 写失败测试**

```js
test('onlyTheSpeakerEndsTheirSpeech', ...)
test('voteProgressHidesTargets', ...)
test('revealedIdiotCannotVote', ...)
test('hostActionsFollowThePhase', ...)            // 每个阶段的按钮列表
test('hostActionsNeverCarryResultFields', ...)    // 所有 action payload 只有 action 字段
```

- [ ] **Step 2–4: 失败 → 实现 → 单测、构建、wcsc / wcc 通过。**
- [ ] **Step 5: 提交** `feat(miniprogram): speeches, votes and the host's day bar`

---

### Task 11: 结果页与对局记录

**Files:**
- Create: `src/pages/judge/views/ResultView.vue`、`src/pages/judge/views/LogSheet.vue`、`src/utils/judge/rounds.js`
- Test: `tests/judge-rounds.test.mjs`

**Interfaces:**
- Produces（`rounds.js`）：`roundRows(snapshot) → [{ dayNo, nightDeaths, sheriff, speechOrder, exiles: [{ tallies, abstain, out, pk }], skills }]`（对局中只有公开信息；结束后把 `nightHistory` 按天合进去）；`verdictText(snapshot) → { title, reason }`（`winVerdict`，流局显示「流局」）。
- `LogSheet.vue` 是房间页右上角常驻入口打开的底部面板（白天夜里都能开，§4.7b），`ResultView.vue` 按原型画板「结束 · 判定依据」。

- [ ] **Step 1: 写失败测试**

```js
test('roundsShowPublicInfoDuringTheGame', ...)    // 对局中没有夜间行动
test('finishedGameAddsNightActions', ...)
test('drawShowsLiuju', ...)
```

- [ ] **Step 2–4: 失败 → 实现 → 单测、构建、wcsc / wcc 通过。**
- [ ] **Step 5: 提交** `feat(miniprogram): result page and round log`

---

### Task 12: 房主手机播报

**Files:**
- Create: `src/utils/judge/voice-cue.js`
- Modify: `src/pages/judge/room.vue`（用现有 `createJudgeVoicePlayer`）
- Test: `tests/judge-voice-cue.test.mjs`

**Interfaces:**
- Produces（`voice-cue.js`）：`voiceCue(snapshot) → null | { key, lines }`：
  - `key = currentDay + phase + nightStep(或 speech.index / pending 第一条)`，同一个 key 只播一次。
  - 夜里每一步：「{roleName}请睁眼，{该机制的提示}」（只用 `roleName` 和 `kind`，不依赖 `line`，因为房主不一定是持有者）；第一步前加「天黑请闭眼」；夜晚结束「天亮了」。
  - 剩 10 秒且是夜里：「请尽快行动」（单独 key）；发言剩 30 秒：「发言还剩 30 秒」（§4.5，单独 key）。
  - 白天：公布死讯、开始投票、票型结果、当选警长、胜负。
  - 只在 `snapshot.host` 时返回非 null。
- `room.vue` 记住上次播过的 key（内存 + `storage`，切后台回来不重播），WechatSI 不可用时静默。

- [ ] **Step 1: 写失败测试**

```js
test('onlyTheHostSpeaks', ...)
test('voiceCueIsPlayedOncePerStep', ...)          // 同一快照两次 → key 相同；下一步 key 变
test('nightLinesDoNotNeedTheStepLine', ...)       // currentStep.line 为空也能出台词
test('tenSecondWarning', ...)
test('thirtySecondSpeechWarning', ...)
```

- [ ] **Step 2–4: 失败 → 实现 → 单测、构建、wcsc / wcc 通过。**
- [ ] **Step 5: 提交** `feat(miniprogram): host phone narrates the judge flow`

---

### Task 13: 联调验收与文档

**Files:**
- Modify: `CLAUDE.md`、`AGENTS.md`（前端说明里电子法官一节：新房间页与视图、旧房间页、纯函数清单、新增单测）、`docs/design/judge-auto-mode.md`（§16 二期 B 落地记录）

- [ ] **Step 1: 本地联调**：`scripts/start-backend-dev.ps1` 起后端（本地 MySQL，Mock 登录），`npm run dev:mp-weixin`，在微信开发者工具里用多账号（模拟器多开或「多账号调试」）跑完一局 9 人守卫九人局（有法官）和一局 12 人预女猎白（无法官）：建房、入座、夜里各角色操作、上警、发言、投票、PK、猎人开枪、白痴翻牌、暂停 / 点杀、结束、对局记录。记录发现的问题，当场修并补单测。
- [ ] **Step 2: 全部前端单测、构建、wcsc / wcc 通过；后端全量通过。**
- [ ] **Step 3: 改文档并提交** `docs: judge phase 2B`

---

## 留给执行时的说明

- 真机验收（两部真机）放在 Task 13 之后由用户做：开发者工具里多账号能覆盖流程，但 iOS 静音键、蓝牙音箱、锁屏常亮这些只能真机看。
- 语音片段（2C）不在本计划：本期继续用 WechatSI 合成，`voice-cue.js` 输出的是台词文本，2C 换成片段 key。
- 无法官房主的「撤销上一步」后端还没做，本期界面不放这个按钮。
- 警长归票（§4.5）后端没有接口，本期不做，发言结束后房主直接发起投票。
