-- 电子法官二期：角色的技能机制。引擎按 mechanic 结算，不再看角色名。
-- 列由后端启动时的 ContentSchemaInitializer 补（roles.mechanic、judge_room_players.mechanic），补 roles.mechanic 时顺带做同样的回填。
-- 这份脚本只做回填，可重复执行，给手动核对用；先启动新版后端再跑。
UPDATE roles SET mechanic = 'guard' WHERE name = '守卫' AND mechanic IS NULL;
UPDATE roles SET mechanic = 'wolf' WHERE name = '狼人' AND mechanic IS NULL;
UPDATE roles SET mechanic = 'witch' WHERE name = '女巫' AND mechanic IS NULL;
UPDATE roles SET mechanic = 'seer' WHERE name = '预言家' AND mechanic IS NULL;
UPDATE roles SET mechanic = 'hunter' WHERE name = '猎人' AND mechanic IS NULL;
UPDATE roles SET mechanic = 'idiot' WHERE name = '白痴' AND mechanic IS NULL;
UPDATE roles SET mechanic = 'villager' WHERE name = '平民' AND mechanic IS NULL;
