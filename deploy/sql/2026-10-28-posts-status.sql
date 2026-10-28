-- 生产库的 posts 是早期结构：status 为 VARCHAR（'PUBLISHED' / 'OFFLINE'，默认 'PUBLISHED'），
-- 现在的代码按 TINYINT 1 = 在线 / 0 = 下线读写（见 schema.sql、AdminService.updatePostStatus），
-- 不转换的话旧帖子在小程序里一条都查不出来。2026-10-28 已在生产执行。
-- 新代码已经写进去的 '1' 也要算在线，否则重复执行会把它们下线
UPDATE posts SET status = IF(status IN ('PUBLISHED', '1'), '1', '0');
ALTER TABLE posts MODIFY `status` TINYINT DEFAULT 1;

-- 早期帖子没有分类，按代码的默认值归到「讨论」，否则按分类筛选时漏掉
UPDATE posts SET category = '讨论' WHERE category IS NULL OR category = '';
