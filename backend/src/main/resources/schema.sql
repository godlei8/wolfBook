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
  `judge_support_level` VARCHAR(20) NOT NULL DEFAULT 'manual_only',
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

CREATE TABLE `user_favorite_boards` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `openid` VARCHAR(100) NOT NULL,
  `board_id` INT NOT NULL,
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY `uk_user_favorite_board` (`openid`, `board_id`)
);

CREATE TABLE `user_favorite_posts` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `openid` VARCHAR(100) NOT NULL,
  `post_id` INT NOT NULL,
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY `uk_user_favorite_post` (`openid`, `post_id`)
);

CREATE TABLE `user_note_sessions` (
  `session_id` VARCHAR(64) PRIMARY KEY,
  `version` INT DEFAULT 2,
  `openid` VARCHAR(100) NOT NULL,
  `board_mode` VARCHAR(20) NOT NULL DEFAULT 'library',
  `board_id` INT,
  `board_name` VARCHAR(100) NOT NULL,
  `player_count` TINYINT NOT NULL,
  `status` VARCHAR(20) NOT NULL DEFAULT 'active',
  `current_day` TINYINT DEFAULT 1,
  `current_phase` VARCHAR(30) DEFAULT 'day_speech',
  `result_camp` VARCHAR(20) DEFAULT '',
  `sheriff_seat` TINYINT,
  `players_json` JSON,
  `summary_json` JSON,
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE `user_note_records` (
  `record_id` VARCHAR(64) PRIMARY KEY,
  `session_id` VARCHAR(64) NOT NULL,
  `openid` VARCHAR(100) NOT NULL,
  `record_type` VARCHAR(20) NOT NULL,
  `scene` VARCHAR(20) DEFAULT '',
  `day_no` TINYINT DEFAULT 1,
  `phase` VARCHAR(30) DEFAULT '',
  `actor_seats_json` JSON,
  `target_seats_json` JSON,
  `content` TEXT NOT NULL,
  `player` VARCHAR(255),
  `payload_json` JSON,
  `tags_json` JSON,
  `editable` TINYINT DEFAULT 1,
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE `judge_rooms` (
  `room_id` VARCHAR(64) PRIMARY KEY,
  `board_id` INT NOT NULL,
  `board_name` VARCHAR(100) NOT NULL,
  `player_count` TINYINT NOT NULL,
  `judge_mode` VARCHAR(20) NOT NULL DEFAULT 'observer',
  `judge_support_level` VARCHAR(20) NOT NULL DEFAULT 'manual_only',
  `owner_user_id` VARCHAR(100) NOT NULL,
  `owner_player_id` VARCHAR(64),
  `owner_seat_no` TINYINT,
  `room_status` VARCHAR(20) NOT NULL DEFAULT 'lobby',
  `current_day` TINYINT DEFAULT 1,
  `current_phase` VARCHAR(30) DEFAULT 'lobby',
  `auto_judge_enabled` TINYINT DEFAULT 0,
  `can_rollback` TINYINT DEFAULT 1,
  `winner_camp` VARCHAR(30),
  `latest_announcement` VARCHAR(500),
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE `judge_room_players` (
  `player_id` VARCHAR(64) PRIMARY KEY,
  `room_id` VARCHAR(64) NOT NULL,
  `user_id` VARCHAR(100) NOT NULL,
  `nickname` VARCHAR(50) NOT NULL,
  `avatar` VARCHAR(500),
  `seat_no` TINYINT,
  `is_room_owner` TINYINT DEFAULT 0,
  `is_judge_observer` TINYINT DEFAULT 0,
  `is_playing` TINYINT DEFAULT 1,
  `ready` TINYINT DEFAULT 0,
  `alive` TINYINT DEFAULT 1,
  `role_id` INT,
  `role_name` VARCHAR(50),
  `faction` VARCHAR(30),
  `death_day` TINYINT,
  `death_phase` VARCHAR(30),
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY `uk_judge_room_user` (`room_id`, `user_id`),
  UNIQUE KEY `uk_judge_room_seat` (`room_id`, `seat_no`)
);

CREATE TABLE `judge_action_events` (
  `event_id` VARCHAR(64) PRIMARY KEY,
  `room_id` VARCHAR(64) NOT NULL,
  `day_no` TINYINT DEFAULT 1,
  `phase` VARCHAR(30) NOT NULL,
  `actor_player_id` VARCHAR(64),
  `target_player_ids_json` JSON,
  `action_type` VARCHAR(50) NOT NULL,
  `payload_json` JSON,
  `result_payload_json` JSON,
  `visibility` VARCHAR(20) NOT NULL DEFAULT 'public',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE `posts` (
  `id` INT PRIMARY KEY AUTO_INCREMENT,
  `openid` VARCHAR(100) NOT NULL,
  `post_type` VARCHAR(32) NOT NULL DEFAULT 'general',
  `title` VARCHAR(80) NOT NULL,
  `summary` VARCHAR(255),
  `content` TEXT NOT NULL,
  `images` JSON,
  `board_id` INT,
  `board_name` VARCHAR(100),
  `role_tags` JSON,
  `tag_list` JSON,
  `session_id` VARCHAR(64),
  `quality_score` INT DEFAULT 0,
  `hot_score` DOUBLE DEFAULT 0,
  `view_count` INT DEFAULT 0,
  `like_count` INT DEFAULT 0,
  `comment_count` INT DEFAULT 0,
  `favorite_count` INT DEFAULT 0,
  `status` VARCHAR(20) NOT NULL DEFAULT 'PUBLISHED',
  `featured` TINYINT DEFAULT 0,
  `pinned` TINYINT DEFAULT 0,
  `reject_reason` VARCHAR(255),
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE `comments` (
  `id` INT PRIMARY KEY AUTO_INCREMENT,
  `post_id` INT NOT NULL,
  `openid` VARCHAR(100) NOT NULL,
  `parent_comment_id` INT,
  `reply_to_openid` VARCHAR(100),
  `content` VARCHAR(600) NOT NULL,
  `like_count` INT DEFAULT 0,
  `status` VARCHAR(20) NOT NULL DEFAULT 'VISIBLE',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
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
CREATE INDEX `idx_user_favorite_openid` ON `user_favorite_boards` (`openid`, `create_time`);
CREATE INDEX `idx_user_favorite_posts_openid` ON `user_favorite_posts` (`openid`, `create_time`);
CREATE INDEX `idx_user_note_sessions_openid` ON `user_note_sessions` (`openid`, `update_time`);
CREATE INDEX `idx_user_note_records_session` ON `user_note_records` (`session_id`, `create_time`);
CREATE INDEX `idx_judge_rooms_owner_update` ON `judge_rooms` (`owner_user_id`, `update_time`);
CREATE INDEX `idx_judge_rooms_status_update` ON `judge_rooms` (`room_status`, `update_time`);
CREATE INDEX `idx_judge_room_players_room` ON `judge_room_players` (`room_id`, `seat_no`);
CREATE INDEX `idx_judge_room_players_user` ON `judge_room_players` (`user_id`, `update_time`);
CREATE INDEX `idx_judge_events_room_phase` ON `judge_action_events` (`room_id`, `day_no`, `phase`, `create_time`);
CREATE INDEX `idx_posts_openid_status` ON `posts` (`openid`, `status`);
CREATE INDEX `idx_posts_status_feed` ON `posts` (`status`, `pinned`, `featured`, `create_time`);
CREATE INDEX `idx_posts_board_feed` ON `posts` (`board_id`, `status`, `create_time`);
CREATE INDEX `idx_posts_type_feed` ON `posts` (`post_type`, `status`, `create_time`);
CREATE INDEX `idx_comments_post_status` ON `comments` (`post_id`, `status`, `create_time`);
CREATE INDEX `idx_reports_process_status` ON `reports` (`process_status`);
