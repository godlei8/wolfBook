"""Capture phone-sized PNG screenshots of the mini-program H5 build for release material.

Usage: python scripts/release-shots.py <out_dir> [base_url]
Needs the H5 dev server (npm run dev:h5, default http://localhost:5180) and a backend on 8088.
Opens its own browser profile, so it never touches anyone's login.
"""
import sys
from pathlib import Path

from playwright.sync_api import sync_playwright

OUT = Path(sys.argv[1] if len(sys.argv) > 1 else "release/screenshots")
BASE = sys.argv[2] if len(sys.argv) > 2 else "http://localhost:5180"

# (file name, route, scroll px before the shot)
SHOTS = [
    ("01-板子首页", "pages/boards/list", 0),
    ("02-板子详情", "pages/boards/detail?id=5", 0),
    ("03-板子详情-规则", "pages/boards/detail?id=5", 900),
    ("04-角色图鉴", "pages/roles/list", 0),
    ("05-角色详情", "pages/roles/detail?id=12", 0),
    ("06-夜镇编年史", "pages/world/index", 0),
    ("07-开局", "pages/judge/index", 0),
    ("08-建房-选板子", "pages/judge/create", 0),
    ("09-建房-配置房间", "pages/judge/create?boardId=5", 0),
    ("10-建房-板子配置", "pages/judge/create?boardId=5", 700),
]


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    only = set(sys.argv[3].split(",")) if len(sys.argv) > 3 else None
    with sync_playwright() as p:
        # 本机装的 Chromium 版本和 Python 包要的不一致时，用环境变量 PW_CHROME 指定 chrome.exe
        import os
        browser = p.chromium.launch(executable_path=os.environ.get("PW_CHROME") or None)
        page = browser.new_page(viewport={"width": 390, "height": 844}, device_scale_factor=2, is_mobile=True, has_touch=True)
        for name, route, scroll in SHOTS:
            if only and name not in only:
                continue
            page.goto(f"{BASE}/#/{route}")
            # H5 里只改 hash 不会重新走 onLoad（同一页面带不同参数），每张都整页刷新
            page.reload()
            page.wait_for_load_state("networkidle")
            page.wait_for_timeout(1500)
            if scroll:
                page.mouse.wheel(0, scroll)
                page.wait_for_timeout(800)
            path = OUT / f"{name}.png"
            page.screenshot(path=str(path))
            print("saved", path)
        browser.close()


if __name__ == "__main__":
    main()
