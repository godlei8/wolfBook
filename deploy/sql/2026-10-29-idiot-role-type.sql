-- 白痴是神职（屠边时翻牌的白痴算存活神职）。电子法官的胜负判定按 role_type 区分神职 / 平民。
UPDATE roles SET role_type = '神职' WHERE name = '白痴' AND role_type = '平民';
