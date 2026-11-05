"""把 docs/design/world-lore.md 转成可重复执行的迁移 SQL。

用法：python scripts/world-lore-sql.py deploy/sql/2026-11-05-world-lore-v2.sql

- 「四、规则对照表」同时写进 backend/src/main/resources/prompts/world-rules.md，AI 助手的世界观资料（WORLD:1）会带上它。

- 角色按名字找 id，只改 alias（真名）、title（称号）、background（身世）、relations（人物关系），文档里没有的角色不动。
- 「一、世界」除三方势力外的几段拼成小镇简介（每段前一行是小标题），三方势力和编年史整份重灌进 world_lore id = 1。
- 关系里写到自己（比如平民的「彼此」）的跳过；写到库里没有的角色时 SQL 里那一条会变成 NULL 角色，先跑本脚本的自检。
- 删除角色和板子不在这里，写在迁移文件头部（见 DELETE_SQL）。
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DOC = ROOT / "docs" / "design" / "world-lore.md"
RULES_OUT = ROOT / "backend" / "src" / "main" / "resources" / "prompts" / "world-rules.md"

REMOVED_ROLES = ["织梦人", "驯熊师", "老酒鬼", "诡术师", "爱神"]
REMOVED_BOARDS = ["黑狼王织梦人", "爱神混情局"]
CAMPS = {"好人": "good", "狼人": "wolf", "第三方": "third"}
INTRO_MAX = 3000
LABEL_MAX = 40


def strip_md(text: str) -> str:
    return re.sub(r"\*\*(.+?)\*\*", r"\1", text).strip()


def sql(text) -> str:
    if text is None:
        return "NULL"
    return "'" + text.replace("\\", "\\\\").replace("'", "''") + "'"


def split_top(text: str, sep: str = "、") -> list[str]:
    """按顿号切，但不切括号（含「」）里面的。"""
    parts, depth, buf = [], 0, ""
    for ch in text:
        if ch in "（(「":
            depth += 1
        elif ch in "）)」":
            depth -= 1
        if ch == sep and depth == 0:
            parts.append(buf)
            buf = ""
        else:
            buf += ch
    parts.append(buf)
    return [p.strip() for p in parts if p.strip()]


def sections(lines: list[str], level: str) -> dict[str, list[str]]:
    result, current = {}, None
    for line in lines:
        if line.startswith(level + " "):
            current = line[len(level) + 1:].strip()
            result[current] = []
        elif current is not None:
            result[current].append(line)
    return result


def parse():
    lines = DOC.read_text(encoding="utf-8").splitlines()
    top = sections(lines, "##")
    world = sections(top["一、世界"], "###")

    intro_parts = []
    for title, body in world.items():
        if title == "三方势力":
            continue
        paragraphs = [strip_md(line) for line in body if line.strip()]
        intro_parts.append("【" + title + "】")
        intro_parts.extend(paragraphs)
    intro = "\n".join(intro_parts)

    factions = []
    for line in world["三方势力"]:
        m = re.match(r"- \*\*(.+?)\*\*（(.+?)(?:阵营)?）：(.+)", line.strip())
        if m:
            name, camp, desc = m.groups()
            camp = camp.removesuffix("阵营")
            factions.append((name, CAMPS[camp], strip_md(desc)))

    events = []
    for line in top["二、编年史"]:
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if len(cells) != 3 or cells[0] in ("时间", "---"):
            continue
        era, body, roles = cells
        m = re.match(r"\*\*(.+?)\*\*。?(.*)", body)
        title, content = m.group(1), strip_md(m.group(2))
        names = [] if roles in ("—", "-", "") else split_top(roles)
        events.append((era, title, content, names))

    roles = []
    role_lines = top["三、角色"]
    i = 0
    while i < len(role_lines):
        m = re.match(r"\*\*(.+?) · (.+?)\*\*｜(.+?)｜(.+)", role_lines[i].strip())
        if not m:
            i += 1
            continue
        name, alias, title, _faction = m.groups()
        background = strip_md(role_lines[i + 1])
        relation_line = role_lines[i + 2].strip()
        assert relation_line.startswith("关系："), name
        relations = []
        for item in split_top(relation_line[3:]):
            rm = re.match(r"(.+?)（(.+)）$", item)
            assert rm, (name, item)
            target, label = rm.groups()
            if target == "彼此" or target == name:
                continue
            relations.append((target, label))
        roles.append((name, alias, title, background, relations))
        i += 3
    return intro, factions, events, roles


def parse_rules() -> list[tuple[str, str]]:
    top = sections(DOC.read_text(encoding="utf-8").splitlines(), "##")
    rules = []
    for line in top["四、规则对照表"]:
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if len(cells) != 2 or cells[0] in ("规则", "---"):
            continue
        rules.append((strip_md(cells[0]), strip_md(cells[1])))
    return rules


def build_rules(rules) -> str:
    head = [
        "<!-- 由 scripts/world-lore-sql.py 从 docs/design/world-lore.md「四、规则对照表」生成，不要手改。 -->",
        "<!-- AI 助手的世界观资料（WORLD:1）会把每一行原样带上；规则本身以 docs/judge/house-rules/ 为准。 -->",
    ]
    return "\n".join(head + [f"{rule}：{reason}" for rule, reason in rules]) + "\n"


def check(intro, factions, events, roles):
    names = {r[0] for r in roles}
    errors = []
    if len(intro) > INTRO_MAX:
        errors.append(f"小镇简介 {len(intro)} 字，超过 {INTRO_MAX}")
    for era, title, _content, event_roles in events:
        for n in event_roles:
            if n not in names:
                errors.append(f"编年史「{title}」出场角色 {n} 不在文档角色里")
    for name, _alias, _title, background, relations in roles:
        if name in REMOVED_ROLES:
            errors.append(f"{name} 已删除，文档里却还有")
        if len(relations) > 8:
            errors.append(f"{name} 关系 {len(relations)} 条，超过 8")
        seen = set()
        for target, label in relations:
            if target not in names:
                errors.append(f"{name} 的关系写到了文档里没有的 {target}")
            if target in seen:
                errors.append(f"{name} 对 {target} 写了两条关系")
            seen.add(target)
            if len(label) > LABEL_MAX:
                errors.append(f"{name} → {target} 的关系 {len(label)} 字，超过 {LABEL_MAX}")
    return errors


def build(intro, factions, events, roles) -> str:
    names = sorted({r[0] for r in roles})
    var = {n: f"@r{i}" for i, n in enumerate(names)}
    out = [
        "-- 夜镇编年史 2026-11-05 版：按 docs/design/world-lore.md 重新同步角色身世、人物关系和世界观，",
        "-- 并删掉织梦人、驯熊师、老酒鬼、诡术师、爱神五个角色，以及用到其中角色的两个板子（黑狼王织梦人、爱神混情局）。",
        "-- 由 scripts/world-lore-sql.py 生成，不要手改；文档改了重新生成。按名字找 id，可以重复执行。",
        "-- 跑完之后 AI 助手的资料不会自己重建：到后台随便保存一次板子（或发布一次资料）。",
        "SET NAMES utf8mb4;",
        "",
        "-- 两个板子：连同阵容、收藏一起删（帖子、笔记、法官房间里关联的板子 id 置空 / 保留，线上核对过都没有）",
        f"DELETE FROM board_roles WHERE board_id IN (SELECT id FROM (SELECT id FROM boards WHERE name IN ({', '.join(sql(b) for b in REMOVED_BOARDS)})) b);",
        f"DELETE FROM user_favorite_boards WHERE board_id IN (SELECT id FROM (SELECT id FROM boards WHERE name IN ({', '.join(sql(b) for b in REMOVED_BOARDS)})) b);",
        f"UPDATE posts SET board_id = NULL WHERE board_id IN (SELECT id FROM (SELECT id FROM boards WHERE name IN ({', '.join(sql(b) for b in REMOVED_BOARDS)})) b);",
        f"DELETE FROM boards WHERE name IN ({', '.join(sql(b) for b in REMOVED_BOARDS)});",
        "",
        "-- 五个角色：先清掉其他板子阵容里的引用（线上核对过没有），再删角色",
        f"DELETE FROM board_roles WHERE role_id IN (SELECT id FROM (SELECT id FROM roles WHERE name IN ({', '.join(sql(r) for r in REMOVED_ROLES)})) r);",
        f"DELETE FROM roles WHERE name IN ({', '.join(sql(r) for r in REMOVED_ROLES)});",
        "",
    ]
    for n in names:
        out.append(f"SET {var[n]} := (SELECT id FROM roles WHERE name = {sql(n)});")
    out.append("")
    for name, alias, title, background, relations in roles:
        rel = ", ".join(f"JSON_OBJECT('roleId', {var[t]}, 'label', {sql(l)})" for t, l in relations)
        out.append(
            f"UPDATE roles SET alias = {sql(alias)}, title = {sql(title)}, background = {sql(background)}, "
            f"relations = JSON_ARRAY({rel}) WHERE name = {sql(name)};"
        )
    out.append("")
    out.append("SET @cover := (SELECT NULLIF(JSON_UNQUOTE(JSON_EXTRACT(content, '$.coverImage')), 'null') FROM world_lore WHERE id = 1);")
    faction_sql = ", ".join(
        f"JSON_OBJECT('name', {sql(n)}, 'camp', {sql(c)}, 'description', {sql(d)})" for n, c, d in factions
    )
    event_sql = ", ".join(
        f"JSON_OBJECT('era', {sql(era)}, 'title', {sql(title)}, 'content', {sql(content)}, 'roleIds', JSON_ARRAY({', '.join(var[r] for r in rs)}))"
        for era, title, content, rs in events
    )
    out.append(
        "INSERT INTO world_lore (id, content, update_time) VALUES (1, JSON_OBJECT("
        f"'townName', '灰铃镇', 'tagline', '银钟响过，不出家门。', 'intro', {sql(intro)}, 'coverImage', "
        "@cover, "
        f"'factions', JSON_ARRAY({faction_sql}), 'events', JSON_ARRAY({event_sql})), NOW())"
    )
    out.append("  ON DUPLICATE KEY UPDATE content = VALUES(content), update_time = NOW();")
    return "\n".join(out) + "\n"


def main():
    intro, factions, events, roles = parse()
    errors = check(intro, factions, events, roles)
    print(f"简介 {len(intro)} 字，势力 {len(factions)}，事件 {len(events)}，角色 {len(roles)}，"
          f"关系 {sum(len(r[4]) for r in roles)} 条，最长关系 {max(len(l) for r in roles for _, l in r[4])} 字")
    if errors:
        print("\n".join(errors))
        sys.exit(1)
    rules = parse_rules()
    if not rules:
        print("没读到规则对照表")
        sys.exit(1)
    if len(sys.argv) > 1:
        Path(sys.argv[1]).write_text(build(intro, factions, events, roles), encoding="utf-8")
        RULES_OUT.write_text(build_rules(rules), encoding="utf-8")
        print("写入", sys.argv[1], "和", RULES_OUT.relative_to(ROOT), f"（{len(rules)} 条规则）")


if __name__ == "__main__":
    main()
