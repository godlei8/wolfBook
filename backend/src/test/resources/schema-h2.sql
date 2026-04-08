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
