-- 狼美人、骑士的技能机制（电子法官按机制结算，规则见 docs/judge/house-rules/roles/狼美人.md、骑士.md）。按名字匹配，可重复执行。
-- 跑完后阵容里的角色都有机制的板子（如狼美人骑士）自动变成「全自动」，可以开无法官。
UPDATE roles SET mechanic = 'wolf_beauty' WHERE name = '狼美人' AND (mechanic IS NULL OR mechanic = '');
UPDATE roles SET mechanic = 'knight' WHERE name = '骑士' AND (mechanic IS NULL OR mechanic = '');
