CREATE TABLE boards (
  id INT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(100) NOT NULL,
  player_count TINYINT NOT NULL,
  difficulty VARCHAR(20) NOT NULL,
  tags CLOB,
  cover_image VARCHAR(500),
  card_description VARCHAR(255),
  brief_config VARCHAR(500) NOT NULL,
  special_rules CLOB,
  tips CLOB,
  faqs CLOB,
  win_condition VARCHAR(100) DEFAULT '屠边',
  rule_type VARCHAR(50) DEFAULT '标准板',
  status TINYINT DEFAULT 1,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE roles (
  id INT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(50) NOT NULL UNIQUE,
  alias VARCHAR(50),
  faction VARCHAR(30) NOT NULL,
  role_type VARCHAR(30) NOT NULL,
  camp VARCHAR(60) NOT NULL,
  skill CLOB NOT NULL,
  background CLOB,
  faqs CLOB,
  portrait VARCHAR(500),
  full_illustration VARCHAR(500),
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE board_roles (
  board_id INT NOT NULL,
  role_id INT NOT NULL,
  count TINYINT DEFAULT 1,
  PRIMARY KEY (board_id, role_id)
);

CREATE TABLE users (
  openid VARCHAR(100) PRIMARY KEY,
  nickname VARCHAR(50),
  avatar VARCHAR(500),
  status TINYINT DEFAULT 1,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE posts (
  id INT PRIMARY KEY AUTO_INCREMENT,
  openid VARCHAR(100) NOT NULL,
  content CLOB NOT NULL,
  images CLOB,
  like_count INT DEFAULT 0,
  comment_count INT DEFAULT 0,
  status TINYINT DEFAULT 1,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE comments (
  id INT PRIMARY KEY AUTO_INCREMENT,
  post_id INT NOT NULL,
  openid VARCHAR(100) NOT NULL,
  content VARCHAR(200) NOT NULL,
  like_count INT DEFAULT 0,
  status TINYINT DEFAULT 1,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE likes (
  id INT PRIMARY KEY AUTO_INCREMENT,
  target_type VARCHAR(20) NOT NULL,
  target_id INT NOT NULL,
  openid VARCHAR(100) NOT NULL,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_like UNIQUE (target_type, target_id, openid)
);

CREATE TABLE reports (
  id INT PRIMARY KEY AUTO_INCREMENT,
  target_type VARCHAR(20) NOT NULL,
  target_id INT NOT NULL,
  openid VARCHAR(100),
  reason VARCHAR(200),
  process_status VARCHAR(20) DEFAULT 'OPEN',
  process_by VARCHAR(100),
  process_time TIMESTAMP,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE admin_users (
  id INT PRIMARY KEY AUTO_INCREMENT,
  username VARCHAR(50) NOT NULL UNIQUE,
  password VARCHAR(255) NOT NULL,
  display_name VARCHAR(100) NOT NULL,
  status TINYINT DEFAULT 1,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE assistant_config (
  id INT PRIMARY KEY AUTO_INCREMENT,
  base_config CLOB,
  prompt_config CLOB,
  retrieval_config CLOB,
  search_config CLOB,
  safety_config CLOB,
  ui_config CLOB,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE assistant_documents (
  id INT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(150) NOT NULL,
  file_name VARCHAR(255),
  source_type VARCHAR(30) NOT NULL,
  source_key VARCHAR(100) NOT NULL,
  source_id VARCHAR(100),
  file_path VARCHAR(500),
  summary VARCHAR(500),
  content_text CLOB,
  metadata_json CLOB,
  chunk_count INT DEFAULT 0,
  processing_status VARCHAR(20) DEFAULT 'READY',
  review_status VARCHAR(20) DEFAULT 'PENDING',
  publish_version_id INT,
  last_error VARCHAR(500),
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_assistant_documents_source_key UNIQUE (source_key)
);

CREATE TABLE assistant_publish_versions (
  id INT PRIMARY KEY AUTO_INCREMENT,
  version_name VARCHAR(100) NOT NULL,
  notes VARCHAR(500),
  document_ids CLOB,
  is_current TINYINT DEFAULT 0,
  published_by VARCHAR(100),
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE assistant_sessions (
  session_id VARCHAR(64) PRIMARY KEY,
  openid VARCHAR(100) NOT NULL,
  title VARCHAR(120),
  scene VARCHAR(50),
  page_context CLOB,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE assistant_messages (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  session_id VARCHAR(64) NOT NULL,
  role VARCHAR(20) NOT NULL,
  content CLOB NOT NULL,
  answer_type VARCHAR(40),
  citations CLOB,
  recommended_boards CLOB,
  suggested_questions CLOB,
  used_web_search TINYINT DEFAULT 0,
  trace_id VARCHAR(64),
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE assistant_query_logs (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  openid VARCHAR(100),
  session_id VARCHAR(64),
  user_message VARCHAR(1000),
  answer_type VARCHAR(40),
  hit_sources CLOB,
  used_web_search TINYINT DEFAULT 0,
  latency_ms BIGINT DEFAULT 0,
  success TINYINT DEFAULT 1,
  failure_type VARCHAR(50),
  trace_id VARCHAR(64),
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
