package com.wolfbook.backend.controller;

import org.springframework.test.context.TestPropertySource;

/**
 * 同一套法官流程在快照隔离下再跑一遍：线上 MySQL 默认的 REPEATABLE READ 从事务里第一次读就固定快照，H2 里对应的是 SNAPSHOT；
 * H2 默认的 READ COMMITTED 看不出「惰性推进提交了、本次请求却还读着推进前的房间」这类问题。
 */
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:wolfbook_rr;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
        "spring.datasource.hikari.connection-init-sql=SET SESSION CHARACTERISTICS AS TRANSACTION ISOLATION LEVEL SNAPSHOT"
})
class JudgeFlowRepeatableReadTests extends JudgeFlowIntegrationTests {
}
