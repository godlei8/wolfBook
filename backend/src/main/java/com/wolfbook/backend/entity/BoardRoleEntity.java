package com.wolfbook.backend.entity;

import com.baomidou.mybatisplus.annotation.TableName;

@TableName("board_roles")
public class BoardRoleEntity {

    private Integer boardId;
    private Integer roleId;
    private Integer count;

    public Integer getBoardId() {
        return boardId;
    }

    public void setBoardId(Integer boardId) {
        this.boardId = boardId;
    }

    public Integer getRoleId() {
        return roleId;
    }

    public void setRoleId(Integer roleId) {
        this.roleId = roleId;
    }

    public Integer getCount() {
        return count;
    }

    public void setCount(Integer count) {
        this.count = count;
    }
}
