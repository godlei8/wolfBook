CREATE TABLE `boards` (
  `id` INT PRIMARY KEY AUTO_INCREMENT,
  `name` VARCHAR(100) NOT NULL,
  `player_count` TINYINT NOT NULL,
  `difficulty` VARCHAR(20) NOT NULL,
  `tags` JSON DEFAULT NULL,
  `cover_image` VARCHAR(500),
  `card_description` VARCHAR(255),
  `brief_config` VARCHAR(500) NOT NULL,
  `special_rules` JSON,
  `tips` JSON,
  `faqs` JSON,
  `win_condition` VARCHAR(100) DEFAULT '屠边',
  `rule_type` VARCHAR(50) DEFAULT '标准板',
  `status` TINYINT DEFAULT 1,
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE `roles` (
  `id` INT PRIMARY KEY AUTO_INCREMENT,
  `name` VARCHAR(50) NOT NULL UNIQUE,
  `alias` VARCHAR(50),
  `faction` VARCHAR(30) NOT NULL,
  `role_type` VARCHAR(30) NOT NULL,
  `camp` VARCHAR(60) NOT NULL,
  `skill` TEXT NOT NULL,
  `background` TEXT,
  `faqs` JSON,
  `portrait` VARCHAR(500),
  `full_illustration` VARCHAR(500),
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE `board_roles` (
  `board_id` INT NOT NULL,
  `role_id` INT NOT NULL,
  `count` TINYINT DEFAULT 1,
  PRIMARY KEY (`board_id`, `role_id`)
);

CREATE TABLE `users` (
  `openid` VARCHAR(100) PRIMARY KEY,
  `nickname` VARCHAR(50),
  `avatar` VARCHAR(500),
  `status` TINYINT DEFAULT 1,
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE `posts` (
  `id` INT PRIMARY KEY AUTO_INCREMENT,
  `openid` VARCHAR(100) NOT NULL,
  `content` TEXT NOT NULL,
  `images` JSON,
  `like_count` INT DEFAULT 0,
  `comment_count` INT DEFAULT 0,
  `status` TINYINT DEFAULT 1,
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE `comments` (
  `id` INT PRIMARY KEY AUTO_INCREMENT,
  `post_id` INT NOT NULL,
  `openid` VARCHAR(100) NOT NULL,
  `content` VARCHAR(200) NOT NULL,
  `like_count` INT DEFAULT 0,
  `status` TINYINT DEFAULT 1,
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE `likes` (
  `id` INT PRIMARY KEY AUTO_INCREMENT,
  `target_type` VARCHAR(20) NOT NULL,
  `target_id` INT NOT NULL,
  `openid` VARCHAR(100) NOT NULL,
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY `uk_like` (`target_type`, `target_id`, `openid`)
);

CREATE TABLE `reports` (
  `id` INT PRIMARY KEY AUTO_INCREMENT,
  `target_type` VARCHAR(20) NOT NULL,
  `target_id` INT NOT NULL,
  `openid` VARCHAR(100),
  `reason` VARCHAR(200),
  `process_status` VARCHAR(20) DEFAULT 'OPEN',
  `process_by` VARCHAR(100),
  `process_time` DATETIME,
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE `admin_users` (
  `id` INT PRIMARY KEY AUTO_INCREMENT,
  `username` VARCHAR(50) NOT NULL UNIQUE,
  `password` VARCHAR(255) NOT NULL,
  `display_name` VARCHAR(100) NOT NULL,
  `status` TINYINT DEFAULT 1,
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX `idx_boards_status` ON `boards` (`status`);
CREATE INDEX `idx_roles_faction_type` ON `roles` (`faction`, `role_type`);
CREATE INDEX `idx_posts_openid_status` ON `posts` (`openid`, `status`);
CREATE INDEX `idx_comments_post_status` ON `comments` (`post_id`, `status`);
CREATE INDEX `idx_reports_process_status` ON `reports` (`process_status`);
