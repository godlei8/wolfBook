package com.wolfbook.backend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("judge_room_players")
public class JudgeRoomPlayerEntity {

    @TableId(type = IdType.INPUT)
    private String playerId;
    private String roomId;
    private String userId;
    private String nickname;
    private String avatar;
    private Integer seatNo;
    @TableField("is_room_owner")
    private Boolean roomOwner;
    @TableField("is_judge_observer")
    private Boolean judgeObserver;
    @TableField("is_playing")
    private Boolean playing;
    private Boolean ready;
    private Boolean alive;
    private Integer roleId;
    private String roleName;
    private String faction;
    private Integer deathDay;
    private String deathPhase;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public String getPlayerId() {
        return playerId;
    }

    public void setPlayerId(String playerId) {
        this.playerId = playerId;
    }

    public String getRoomId() {
        return roomId;
    }

    public void setRoomId(String roomId) {
        this.roomId = roomId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public String getAvatar() {
        return avatar;
    }

    public void setAvatar(String avatar) {
        this.avatar = avatar;
    }

    public Integer getSeatNo() {
        return seatNo;
    }

    public void setSeatNo(Integer seatNo) {
        this.seatNo = seatNo;
    }

    public Boolean getRoomOwner() {
        return roomOwner;
    }

    public void setRoomOwner(Boolean roomOwner) {
        this.roomOwner = roomOwner;
    }

    public Boolean getJudgeObserver() {
        return judgeObserver;
    }

    public void setJudgeObserver(Boolean judgeObserver) {
        this.judgeObserver = judgeObserver;
    }

    public Boolean getPlaying() {
        return playing;
    }

    public void setPlaying(Boolean playing) {
        this.playing = playing;
    }

    public Boolean getReady() {
        return ready;
    }

    public void setReady(Boolean ready) {
        this.ready = ready;
    }

    public Boolean getAlive() {
        return alive;
    }

    public void setAlive(Boolean alive) {
        this.alive = alive;
    }

    public Integer getRoleId() {
        return roleId;
    }

    public void setRoleId(Integer roleId) {
        this.roleId = roleId;
    }

    public String getRoleName() {
        return roleName;
    }

    public void setRoleName(String roleName) {
        this.roleName = roleName;
    }

    public String getFaction() {
        return faction;
    }

    public void setFaction(String faction) {
        this.faction = faction;
    }

    public Integer getDeathDay() {
        return deathDay;
    }

    public void setDeathDay(Integer deathDay) {
        this.deathDay = deathDay;
    }

    public String getDeathPhase() {
        return deathPhase;
    }

    public void setDeathPhase(String deathPhase) {
        this.deathPhase = deathPhase;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }
}
