"""把 docs/judge/house-rules/ 的规则写进板子文件、小程序的通用规则，并生成更新板子规则详情的迁移 SQL。

用法：python scripts/judge-rules-sql.py [deploy/sql/<日期>-board-rule-detail.sql]

- 规则只有一个来源：角色规则写在 roles/<角色>.md，通用流程写在 00-通用流程.md，板子文件自己只写玩法概述、阵容、
  胜负、夜间顺序和板子配置。板子文件里「角色规则」一节是从角色文件抄过来给人看的，每次运行都会重写，不要手改。
- 通用规则（胜负、警长竞选、白天发言、放逐投票、遗言、狼人自爆）从 00-通用流程.md 抄进小程序的
  src/utils/judge/common-rules.data.js，小程序里点「通用规则」弹窗看的就是它；每次运行都会重写，不要手改。
- 带了 SQL 路径时再生成迁移：按板子名找板子、按角色名找角色，把玩法概述、角色细则写进 boards.rule_detail
  （JSON_SET，原来的夜间顺序、规则章节和电子法官规则不动），并清掉旧的 special_rules（和现在的规则对不上）。可重复执行。
  小程序的板子详情页、建房页和 AI 助手资料都读这份数据；跑完 SQL 要在后台保存一次板子，让 AI 资料重建。
- 只给引擎已经对齐的板子生成 SQL（见 SQL_BOARDS）；其余板子还有没做的机制或待拍板，先只更新文件。
"""
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RULES = ROOT / "docs" / "judge" / "house-rules"
COMMON_OUT = ROOT / "miniprogram-vue" / "src" / "utils" / "judge" / "common-rules.data.js"
BEGIN = "<!-- 角色规则：由 scripts/judge-rules-sql.py 从 roles/ 生成，改规则请改角色文件再重新生成 -->"
END = "<!-- 角色规则结束 -->"

# 规则文件里的板子 → 库里的板子名；只列引擎已经对齐的
SQL_BOARDS = {"01": "预女猎白", "03": "狼王守卫", "08": "机械狼通灵师", "24": "狼美人骑士"}
# 规则文件里的角色名 → 库里的角色名（不一样的才写）
DB_ROLE = {"狼王": "黑狼王", "机械狼人": "机械狼"}
# 通用流程里哪几节进小程序的「通用规则」弹窗：标题 → 00 里的小节标题（按开头匹配）
SECTIONS = [
    ("胜负", "## 10. 胜负判定"),
    ("警长竞选", "## 4. 警长竞选"),
    ("白天发言", "## 6. 白天发言"),
    ("放逐投票", "## 7. 放逐投票"),
    ("遗言", "### 遗言"),
    ("狼人自爆", "### 狼人自爆"),
]


def plain(text: str) -> str:
    """去掉 markdown 记号和只对文档有意义的引用。"""
    text = re.sub(r"\[([^\]]+)\]\([^)]+\)", r"\1", text)
    text = re.sub(r"\*\*(.+?)\*\*", r"\1", text)
    text = re.sub(r"（\d{4}-\d{2}-\d{2} 用户[^）]*）", "", text)
    text = re.sub(r"（见第 \d+ 节[^）]*）", "", text)
    return text.strip()


def bullets(lines: list[str]) -> list[str]:
    """一节里的条目：- 开头和 1. 开头的行；加粗的小标题（单独一段）会成为它下面条目的前缀。"""
    items, prefix = [], ""
    for line in lines:
        stripped = line.strip()
        heading = re.fullmatch(r"\*\*(.+?)\*\*(（.*）)?", stripped)
        if heading:
            name = heading.group(1)
            prefix = "学到" if "学到" in name else name
            continue
        match = re.match(r"(?:- |\d+\. )(.+)", stripped)
        if not match:
            continue
        item = plain(match.group(1))
        if prefix == "学到":
            item = "学到" + item
        elif prefix and item.startswith(("不开", "开")):
            item = prefix + item
        items.append(item)
    return items


def section(lines: list[str], heading: str) -> list[str]:
    """以 heading 开头的那一节的正文行（到下一个同级或更高级标题为止）。"""
    level = len(heading) - len(heading.lstrip("#"))
    out, inside = [], False
    for line in lines:
        if not inside and line.strip().startswith(heading.strip()):
            inside = True
            continue
        if inside and re.match(r"#{1,%d} " % level, line):
            break
        if inside:
            out.append(line)
    return out


def parse_role(path: Path) -> dict:
    lines = path.read_text(encoding="utf-8").splitlines()
    camp = next((plain(line).split("：", 1)[1] for line in lines if line.startswith("- **阵营**")), "")
    return {"name": path.stem, "camp": camp, "items": bullets(section(lines, "## 规则"))}


def parse_board(path: Path) -> dict:
    lines = path.read_text(encoding="utf-8").splitlines()
    lineup_line = next((line.strip() for line in section(lines, "## 阵容") if line.strip()), "")
    lineup = [re.sub(r"\s*×\d+$", "", part).strip() for part in lineup_line.split("、") if part.strip()]
    overview = " ".join(line.strip() for line in section(lines, "## 玩法概述") if line.strip())
    return {"no": path.name[:2], "path": path, "lines": lines, "lineup": lineup, "overview": plain(overview)}


def role_block(board: dict, roles: dict) -> list[str]:
    out = ["## 角色规则", "", BEGIN, ""]
    for name in board["lineup"]:
        role = roles.get(name)
        if not role:
            out += [f"**{name}**", "", "- 还没有角色文件。", ""]
            continue
        out += [f"**{name}**（{role['camp']}）", ""]
        out += [f"- {item}" for item in role["items"]]
        out.append("")
    out += [END, ""]
    return out


def rewrite_board(board: dict, roles: dict) -> bool:
    text = "\n".join(board["lines"]) + "\n"
    block = "\n".join(role_block(board, roles))
    if "## 角色规则" in text:
        start = text.index("## 角色规则")
        end = text.index(END, start) + len(END) + 1
        new = text[:start] + block + text[end:].lstrip("\n")
    else:
        anchor = text.index("## 板子配置")
        new = text[:anchor] + block + "\n" + text[anchor:]
    new = re.sub(r"\n{3,}", "\n\n", new)
    changed = new != text
    if changed:
        board["path"].write_text(new, encoding="utf-8", newline="\n")
    return changed


def write_common(common: list[tuple[str, list[str]]]) -> None:
    """通用规则写成小程序里的一份数据（JSON 写法的 JS 数组）。"""
    data = [{"title": title, "items": items} for title, items in common if items]
    COMMON_OUT.write_text(
        "// 由 scripts/judge-rules-sql.py 从 docs/judge/house-rules/00-通用流程.md 生成，不要手改；改规则去改规则文件再重新生成。\n"
        "export const COMMON_RULES = " + json.dumps(data, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
        newline="\n",
    )


def sql(text: str) -> str:
    return "'" + text.replace("\\", "\\\\").replace("'", "''") + "'"


def json_array(items: list[str]) -> str:
    return "JSON_ARRAY(" + ", ".join(sql(item) for item in items) + ")"


def board_sql(board: dict, db_name: str, roles: dict) -> str:
    role_rules = []
    for name in board["lineup"]:
        role = roles.get(name)
        if not role or not role["items"]:
            continue
        role_rules.append(
            "JSON_OBJECT('roleId', (SELECT id FROM roles WHERE name = %s LIMIT 1), 'items', %s)"
            % (sql(DB_ROLE.get(name, name)), json_array(role["items"]))
        )
    return (
        "UPDATE boards SET\n"
        "  rule_detail = JSON_SET(COALESCE(rule_detail, JSON_OBJECT()),\n"
        "    '$.overview', %s,\n"
        "    '$.roleRules', JSON_ARRAY(\n      %s\n    )\n"
        "  ),\n"
        "  special_rules = JSON_ARRAY()\n"
        "WHERE name = %s;\n" % (sql(board["overview"]), ",\n      ".join(role_rules), sql(db_name))
    )


def main() -> None:
    roles = {path.stem: parse_role(path) for path in sorted((RULES / "roles").glob("*.md")) if path.stem != "README"}
    boards = [parse_board(path) for path in sorted(RULES.glob("[0-9][0-9]-*.md")) if not path.name.startswith("00")]
    for board in boards:
        missing = [name for name in board["lineup"] if name not in roles]
        if missing:
            print(f"{board['path'].name}: 没有角色文件 {missing}")
        print(f"{board['path'].name}: {'已更新' if rewrite_board(board, roles) else '没有变化'}")
    flow = (RULES / "00-通用流程.md").read_text(encoding="utf-8").splitlines()
    write_common([(title, bullets(section(flow, heading))) for title, heading in SECTIONS])
    print(f"通用规则: {COMMON_OUT.relative_to(ROOT)}")
    if len(sys.argv) < 2:
        return
    out = [
        "-- 由 scripts/judge-rules-sql.py 从 docs/judge/house-rules/ 生成，可重复执行。",
        "-- 把玩法概述、角色细则写进板子的规则详情；夜间顺序、规则章节和电子法官规则不动；清掉旧的 special_rules。",
        "-- 跑完在后台保存一次板子，让 AI 助手资料重建。",
        "",
    ]
    for board in boards:
        db_name = SQL_BOARDS.get(board["no"])
        if db_name:
            out.append(board_sql(board, db_name, roles))
    target = Path(sys.argv[1])
    target.write_text("\n".join(out), encoding="utf-8", newline="\n")
    print(f"SQL: {target}（{len(SQL_BOARDS)} 个板子）")


if __name__ == "__main__":
    main()
