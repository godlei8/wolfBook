-- 角色新立绘（第二批）：头像 512x512 webp，立绘 900x1200 webp，主题色 = 立绘底色。按角色名匹配，可以重复执行。
-- 摄梦人、守墓人、梦魇之影的原图先用 design/prompts/remove_watermark.py 去了水印。
-- 图来自 design/art/avatars、design/art/cards（design/prompts/crop_roles.py 裁出），已传到 COS。
START TRANSACTION;
UPDATE roles SET portrait = 'https://cos.godlei8.top/wolfbook/admin/assets/2026/11/05/5740804e035d4acf95f10a13d01f4a27.webp', full_illustration = 'https://cos.godlei8.top/wolfbook/admin/assets/2026/11/05/3cc16788ca8c44a59ef58b7cf61336b0.webp', theme_color = '#7E4B30' WHERE name = '机械狼';
UPDATE roles SET portrait = 'https://cos.godlei8.top/wolfbook/admin/assets/2026/11/05/3a6c87ace8c94727a8178a447494d882.webp', full_illustration = 'https://cos.godlei8.top/wolfbook/admin/assets/2026/11/05/77fe51fbee7949fa9b3e2df38a51e6d1.webp', theme_color = '#71757D' WHERE name = '石像鬼';
UPDATE roles SET portrait = 'https://cos.godlei8.top/wolfbook/admin/assets/2026/11/05/3894c0eb7509495797013ede80692b6a.webp', full_illustration = 'https://cos.godlei8.top/wolfbook/admin/assets/2026/11/05/daec59247b254fbd8c029e6b93d5fd46.webp', theme_color = '#18738D' WHERE name = '通灵师';
UPDATE roles SET portrait = 'https://cos.godlei8.top/wolfbook/admin/assets/2026/11/05/3ee25d895a8840aebe3157201f3e4dff.webp', full_illustration = 'https://cos.godlei8.top/wolfbook/admin/assets/2026/11/05/ef207a37e0e74a3fbfda493f36bc9c39.webp', theme_color = '#2F43C4' WHERE name = '魔术师';
UPDATE roles SET portrait = 'https://cos.godlei8.top/wolfbook/admin/assets/2026/11/05/597a62b63fa84007a9b87e3284f09cd1.webp', full_illustration = 'https://cos.godlei8.top/wolfbook/admin/assets/2026/11/05/0d8c385c9033459eae8f81660ce7414c.webp', theme_color = '#A790C8' WHERE name = '摄梦人';
UPDATE roles SET portrait = 'https://cos.godlei8.top/wolfbook/admin/assets/2026/11/05/e4fa8d2fa33143289551ce67ea2efb98.webp', full_illustration = 'https://cos.godlei8.top/wolfbook/admin/assets/2026/11/05/c1e39e529c184dbf8a31e791126bbb0a.webp', theme_color = '#5D8763' WHERE name = '守墓人';
UPDATE roles SET portrait = 'https://cos.godlei8.top/wolfbook/admin/assets/2026/11/05/7223604bcf3b418383b6931e19ed7c0c.webp', full_illustration = 'https://cos.godlei8.top/wolfbook/admin/assets/2026/11/05/1c963164fd54487c9b9e5e412b7e6fb8.webp', theme_color = '#261144' WHERE name = '梦魇之影';
COMMIT;
