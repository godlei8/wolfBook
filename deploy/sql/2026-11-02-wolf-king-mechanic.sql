-- 电子法官三期 A：狼王机制和「狼王守卫」板子。可以重复执行。
-- 先启动新版后端（ContentSchemaInitializer 会补 roles.mechanic 列，补列时已按名字回填黑狼王）再跑；
-- 这份脚本给已经补过列的库（本地开发库）用，也方便手动核对。
-- 生产库已有「狼王守卫」板子（id 8），第二段按名字判断，不会动它。

-- 1. 黑狼王用狼王机制
UPDATE roles SET mechanic = 'wolf_king' WHERE name = '黑狼王' AND mechanic IS NULL;

-- 2. 没有「狼王守卫」时插一个：预言家、女巫、猎人、守卫、黑狼王各 1，狼人 3，平民 4，12 人屠边
INSERT INTO boards (name, player_count, difficulty, tags, cover_image, card_description, brief_config, special_rules, tips, faqs,
                    win_condition, rule_type, judge_support_level, status)
SELECT '狼王守卫', 12, '进阶', JSON_ARRAY('经典', '标准'), 'https://picsum.photos/seed/board8/900/600', NULL,
       '预言家x1 女巫x1 猎人x1 守卫x1 黑狼王x1 狼人x3 平民x4',
       JSON_ARRAY('狼王出局时可以开枪带走一人，被毒不能开枪。', '守卫可以守自己，不能连续两晚守同一人。'),
       JSON_ARRAY('狼王开枪的时机往往决定轮次，好人要提前想好怎么出狼王。'),
       JSON_ARRAY(JSON_OBJECT('question', '狼王被毒了还能开枪吗？', 'answer', '不能，被毒、被法官点杀都不能开枪。')),
       '屠边', '标准板', 'full', 1
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM boards WHERE name = '狼王守卫');

SET @board := (SELECT MIN(id) FROM boards WHERE name = '狼王守卫');
INSERT INTO board_roles (board_id, role_id, count)
SELECT @board, r.id, x.cnt
FROM (SELECT '预言家' AS name, 1 AS cnt UNION ALL SELECT '女巫', 1 UNION ALL SELECT '猎人', 1 UNION ALL SELECT '守卫', 1
      UNION ALL SELECT '黑狼王', 1 UNION ALL SELECT '狼人', 3 UNION ALL SELECT '平民', 4) x
JOIN roles r ON r.name = x.name
WHERE NOT EXISTS (SELECT 1 FROM board_roles WHERE board_id = @board);
