"""Play one electronic-judge game against the local backend and capture release material.

Screenshots of every phase from a suitable player's phone (PNG, 780x1688), plus screen recordings of
the judge's phone (create room -> lobby -> start) and the seer's phone (the whole game), with a
timeline of phase changes for cutting the video.

Usage (local backend on 8088 with the test profile, H5 dev server on 5180):
  PW_CHROME=<chrome.exe> python scripts/release-game.py release/game
Bots come from miniprogram-vue/scripts/judge-bots.mjs (BOT_RUN=promo, HOST_TOKEN = the judge).
Only for the local backend: MockAuthProvider accepts any login code.
"""
import json
import os
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

from playwright.sync_api import sync_playwright

OUT = Path(sys.argv[1] if len(sys.argv) > 1 else "release/game")
API = "http://127.0.0.1:8088"
H5 = "http://localhost:5180"
BOARD_NAME = "预女猎白"
RUN = os.environ.get("BOT_RUN", "promo")
NAMES = ["月下孤狼", "星轨祭司", "阿岚", "小鱼", "老周", "木木", "七七", "北风", "阿哲", "糖糖", "夜航船", "白桦"]
VIEW = {"width": 390, "height": 844}
VIDEO = {"width": 780, "height": 1688}


def api(path, token=None, method="GET", body=None):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(API + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    with urllib.request.urlopen(req, timeout=20) as resp:
        return json.loads(resp.read().decode())


def login(code, nickname, avatar):
    token = api("/api/login", method="POST", body={"code": code})["data"]["token"]
    api("/api/user/info", token, "PUT", {"nickname": nickname, "avatar": avatar})
    return token


def new_phone(browser, token, video_dir=None):
    options = dict(viewport=VIEW, device_scale_factor=2, is_mobile=True, has_touch=True)
    if video_dir:
        options.update(record_video_dir=str(video_dir), record_video_size=VIDEO)
    context = browser.new_context(**options)
    page = context.new_page()
    page.goto(H5 + "/#/pages/boards/list")
    page.wait_for_load_state("networkidle")
    page.evaluate("t => uni.setStorageSync('auth_token', t)", token)
    return context, page


def open_room(page, room_id):
    page.goto(f"{H5}/#/pages/judge/room?roomId={room_id}")
    page.reload()
    page.wait_for_timeout(2500)


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    shots = OUT / "screenshots"
    shots.mkdir(exist_ok=True)
    videos = OUT / "video-raw"
    videos.mkdir(exist_ok=True)
    timeline = []
    t0 = time.time()

    def mark(label):
        timeline.append({"t": round(time.time() - t0, 2), "label": label})
        print(f"[{timeline[-1]['t']:7.1f}s] {label}", flush=True)

    roles = api("/api/roles?page=1&size=50")["data"]
    portraits = [r["portrait"] for r in roles if r.get("portrait")]
    judge_token = login("promo-judge", "今晚的法官", portraits[0])
    bot_tokens = [login(f"bot-{RUN}-{i}", NAMES[i], portraits[(i + 1) % len(portraits)]) for i in range(12)]

    with sync_playwright() as p:
        browser = p.chromium.launch(executable_path=os.environ.get("PW_CHROME") or None)

        # 法官：建房两步走（录屏）
        judge_ctx, judge = new_phone(browser, judge_token, videos / "judge")
        t0 = time.time()
        mark("judge:create")
        judge.goto(H5 + "/#/pages/judge/create")
        judge.reload()
        judge.wait_for_timeout(2500)
        judge.get_by_text(BOARD_NAME, exact=True).first.click()
        judge.wait_for_timeout(2500)
        mark("judge:config")
        judge.mouse.wheel(0, 700)
        judge.wait_for_timeout(1800)
        judge.mouse.wheel(0, 700)
        judge.wait_for_timeout(1500)
        judge.get_by_text("创建并进入房间").first.click()
        judge.wait_for_url("**/pages/judge/room**", timeout=20000)
        judge.wait_for_timeout(2500)
        room_id = judge.url.split("roomId=")[1].split("&")[0]
        mark("judge:lobby")
        # 本局节奏缩短一点（录视频用）
        api(f"/api/judge/rooms/{room_id}/rules", judge_token, "PUT",
            {"sheriffEnabled": True, "speechSeconds": 60, "sheriffSpeechSeconds": 60, "wolfSeconds": 60, "roleSeconds": 15})

        # 玩家由脚本自己入座、准备（入座时的昵称头像会存进座位；机器人脚本再登录会把账号资料改回默认，所以不能让它来入座）
        for token in bot_tokens:
            api(f"/api/judge/rooms/{room_id}/join", token, "POST")
            judge.wait_for_timeout(500)
        for token in bot_tokens:
            api(f"/api/judge/rooms/{room_id}/ready", token, "POST")
            judge.wait_for_timeout(300)
        judge.wait_for_timeout(3000)
        judge.screenshot(path=str(shots / "20-大厅.png"))
        mark("judge:lobby-full")
        judge.get_by_text("开始游戏").first.click()
        judge.wait_for_timeout(1500)
        mark("game:start")

        # 各角色的手机
        by_role = {}
        for token in bot_tokens:
            me = api(f"/api/judge/rooms/{room_id}", token)["data"]["selfPlayer"]
            by_role.setdefault(me["roleName"], []).append((me["seatNo"], token))
        print({k: [s for s, _ in v] for k, v in by_role.items()}, flush=True)
        phones = {}

        def phone(role, record=False):
            if role not in phones:
                token = by_role[role][0][1]
                ctx, pg = new_phone(browser, token, videos / role if record else None)
                open_room(pg, room_id)
                phones[role] = (ctx, pg)
            return phones[role][1]

        seer = phone("预言家", record=True)
        mark("seer:recording")
        for role in ["女巫", "狼人", "平民", "猎人"]:
            phone(role)

        # 手机都开好了再放机器人行动（不然第一夜狼人那一步在手机打开前就结束了）
        env = dict(os.environ, BOT_RUN=RUN, HOST_TOKEN=judge_token, HOST_DWELL="5")
        bots_log = open(OUT / "bots.log", "w", encoding="utf-8")
        bots = subprocess.Popen(["node", "miniprogram-vue/scripts/judge-bots.mjs", room_id, "12", API],
                                stdout=bots_log, stderr=subprocess.STDOUT, env=env)
        mark("bots:start")

        taken = set()

        def shot(name, page, wait=0):
            if name in taken:
                return
            if wait:
                page.wait_for_timeout(wait)
            page.screenshot(path=str(shots / f"{name}.png"))
            taken.add(name)
            mark(f"shot:{name}")

        last_key = None
        deadline = time.time() + 40 * 60
        while time.time() < deadline:
            snap = api(f"/api/judge/rooms/{room_id}", judge_token)["data"]
            day = snap.get("currentDay")
            phase = snap.get("currentPhase")
            step = snap.get("currentStep") or {}
            key = (snap.get("roomStatus"), day, phase, step.get("index"))
            if key != last_key:
                last_key = key
                mark(f"phase:{snap.get('roomStatus')}:{day}:{phase}:{step.get('roleName') or ''}")
                if snap.get("roomStatus") == "finished":
                    break
                # 夜里每张只截一次；第一夜狼人那一步可能在各部手机打开前就结束了，后面的夜里补
                if phase == "night_action":
                    role = step.get("roleName")
                    if step.get("kind") == "wolf":
                        shot("21-夜晚-狼人", phone("狼人"), 3500)
                        shot("22-夜晚-天黑请闭眼", phone("平民"))
                        shot("23-夜晚-法官视角", judge)
                    elif role == "女巫":
                        shot("24-夜晚-女巫", phone("女巫"), 2500)
                    elif role == "预言家":
                        shot("25-夜晚-预言家", seer, 4000)
                    elif role == "猎人":
                        shot("26-夜晚-猎人", phone("猎人"), 2500)
                elif phase == "sheriff_signup":
                    shot("27-上警报名", phone("平民"), 3000)
                elif phase == "sheriff_speech":
                    shot("28-警上发言", phone("女巫"), 2000)
                elif phase == "sheriff_vote":
                    shot("29-警长投票", phone("平民"), 4000)
                elif phase == "day_announce":
                    shot("30-天亮公布", seer, 2500)
                elif phase == "day_speech":
                    shot("31-白天发言", seer, 2500)
                    if day == 2:
                        log_page = phone("平民")
                        log_page.get_by_text("记录", exact=True).first.click()
                        shot("33-对局记录-按天看", log_page, 1500)
                        log_page.get_by_text("按人看", exact=True).first.click()
                        shot("34-对局记录-按人看", log_page, 1200)
                        open_room(log_page, room_id)
                elif phase == "exile_vote":
                    shot("32-放逐投票", seer, 6000)
                elif phase == "vote_result":
                    shot("35-票型公布", seer, 1500)
                elif phase == "skill_pending":
                    shot("36-遗言", seer, 2000)
            time.sleep(1)

        mark("game:finished")
        for role in ["预言家", "狼人"]:
            pg = phone(role)
            pg.wait_for_timeout(3500)
        shot("40-结算-好人视角", seer)
        shot("41-结算-狼人视角", phone("狼人"))
        seer.get_by_text("对局记录").first.click()
        shot("42-结算-对局记录-按天看", seer, 1500)
        seer.get_by_text("按人看", exact=True).first.click()
        shot("43-结算-对局记录-按人看", seer, 1500)
        seer.wait_for_timeout(2000)
        mark("end")

        bots.terminate()
        bots_log.close()
        for ctx, _ in phones.values():
            ctx.close()
        judge_ctx.close()
        browser.close()

    (OUT / "timeline.json").write_text(json.dumps(timeline, ensure_ascii=False, indent=1), encoding="utf-8")
    print("done", flush=True)


if __name__ == "__main__":
    main()
