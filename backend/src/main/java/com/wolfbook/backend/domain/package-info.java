/**
 * 前端友好的领域模型。
 *
 * <p>domain 包里的 record/class 是服务层对外返回的业务视图，字段更贴近页面展示；
 * entity 包则是数据库表映射，两者通过 {@code support.DomainConverter} 等代码互相转换。</p>
 */
package com.wolfbook.backend.domain;
