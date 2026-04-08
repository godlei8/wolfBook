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

CREATE TABLE `assistant_config` (
  `id` INT PRIMARY KEY AUTO_INCREMENT,
  `base_config` JSON,
  `prompt_config` JSON,
  `retrieval_config` JSON,
  `search_config` JSON,
  `safety_config` JSON,
  `ui_config` JSON,
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE `assistant_documents` (
  `id` INT PRIMARY KEY AUTO_INCREMENT,
  `name` VARCHAR(150) NOT NULL,
  `file_name` VARCHAR(255),
  `source_type` VARCHAR(30) NOT NULL,
  `source_key` VARCHAR(100) NOT NULL,
  `source_id` VARCHAR(100),
  `file_path` VARCHAR(500),
  `summary` VARCHAR(500),
  `content_text` LONGTEXT,
  `metadata_json` JSON,
  `chunk_count` INT DEFAULT 0,
  `processing_status` VARCHAR(20) DEFAULT 'READY',
  `review_status` VARCHAR(20) DEFAULT 'PENDING',
  `publish_version_id` INT,
  `last_error` VARCHAR(500),
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY `uk_assistant_documents_source_key` (`source_key`)
);

CREATE TABLE `assistant_publish_versions` (
  `id` INT PRIMARY KEY AUTO_INCREMENT,
  `version_name` VARCHAR(100) NOT NULL,
  `notes` VARCHAR(500),
  `document_ids` JSON,
  `is_current` TINYINT DEFAULT 0,
  `published_by` VARCHAR(100),
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE `assistant_sessions` (
  `session_id` VARCHAR(64) PRIMARY KEY,
  `openid` VARCHAR(100) NOT NULL,
  `title` VARCHAR(120),
  `scene` VARCHAR(50),
  `page_context` JSON,
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE `assistant_messages` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `session_id` VARCHAR(64) NOT NULL,
  `role` VARCHAR(20) NOT NULL,
  `content` LONGTEXT NOT NULL,
  `answer_type` VARCHAR(40),
  `citations` JSON,
  `recommended_boards` JSON,
  `suggested_questions` JSON,
  `used_web_search` TINYINT DEFAULT 0,
  `trace_id` VARCHAR(64),
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE `assistant_query_logs` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `openid` VARCHAR(100),
  `session_id` VARCHAR(64),
  `user_message` VARCHAR(1000),
  `answer_type` VARCHAR(40),
  `hit_sources` JSON,
  `used_web_search` TINYINT DEFAULT 0,
  `latency_ms` BIGINT DEFAULT 0,
  `success` TINYINT DEFAULT 1,
  `failure_type` VARCHAR(50),
  `trace_id` VARCHAR(64),
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX `idx_boards_status` ON `boards` (`status`);
CREATE INDEX `idx_roles_faction_type` ON `roles` (`faction`, `role_type`);
CREATE INDEX `idx_posts_openid_status` ON `posts` (`openid`, `status`);
CREATE INDEX `idx_comments_post_status` ON `comments` (`post_id`, `status`);
CREATE INDEX `idx_reports_process_status` ON `reports` (`process_status`);
CREATE INDEX `idx_assistant_documents_publish` ON `assistant_documents` (`publish_version_id`, `review_status`);
CREATE INDEX `idx_assistant_sessions_openid` ON `assistant_sessions` (`openid`, `update_time`);
CREATE INDEX `idx_assistant_messages_session` ON `assistant_messages` (`session_id`, `create_time`);
CREATE INDEX `idx_assistant_logs_openid` ON `assistant_query_logs` (`openid`, `create_time`);
