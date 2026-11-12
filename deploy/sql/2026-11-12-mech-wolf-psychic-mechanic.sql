-- 机械狼、通灵师的技能机制（电子法官按机制结算）。按名字匹配，可重复执行。
UPDATE roles SET mechanic = 'mech_wolf' WHERE name = '机械狼' AND (mechanic IS NULL OR mechanic = '');
UPDATE roles SET mechanic = 'psychic' WHERE name = '通灵师' AND (mechanic IS NULL OR mechanic = '');
