package com.wolfbook.backend.domain;

/** 角色的一条人物关系：对方是谁（roleId），一句话说明关系（label，比如「心上人」「宿敌」）。 */
public record RoleRelation(Integer roleId, String label) {
}
