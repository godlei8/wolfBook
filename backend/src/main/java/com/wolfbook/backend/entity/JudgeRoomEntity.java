package com.wolfbook.backend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("judge_rooms")
public class JudgeRoomEntity {

    @TableId(type = IdType.INPUT)
    private String roomId;
    private Integer boardId;
    private String boardName;
    private Integer playerCount;
    private String judgeMode;
    private String judgeSupportLevel;
    private String ownerUserId;
    private String ownerPlayerId;
    private Integer ownerSeatNo;
    private String roomStatus;
    private Integer currentDay;
    private String currentPhase;
    private Boolean autoJudgeEnabled;
    private Boolean canRollback;
    private String winnerCamp;
    private String latestAnnouncement;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public String getRoomId() {
        return roomId;
    }

    public void setRoomId(String roomId) {
        this.roomId = roomId;
    }

    public Integer getBoardId() {
        return boardId;
    }

    public void setBoardId(Integer boardId) {
        this.boardId = boardId;
    }

    public String getBoardName() {
        return boardName;
    }

    public void setBoardName(String boardName) {
        this.boardName = boardName;
    }

    public Integer getPlayerCount() {
        return playerCount;
    }

    public void setPlayerCount(Integer playerCount) {
        this.playerCount = playerCount;
    }

    public String getJudgeMode() {
        return judgeMode;
    }

    public void setJudgeMode(String judgeMode) {
        this.judgeMode = judgeMode;
    }

    public String getJudgeSupportLevel() {
        return judgeSupportLevel;
    }

    public void setJudgeSupportLevel(String judgeSupportLevel) {
        this.judgeSupportLevel = judgeSupportLevel;
    }

    public String getOwnerUserId() {
        return ownerUserId;
    }

    public void setOwnerUserId(String ownerUserId) {
        this.ownerUserId = ownerUserId;
    }

    public String getOwnerPlayerId() {
        return ownerPlayerId;
    }

    public void setOwnerPlayerId(String ownerPlayerId) {
        this.ownerPlayerId = ownerPlayerId;
    }

    public Integer getOwnerSeatNo() {
        return ownerSeatNo;
    }

    public void setOwnerSeatNo(Integer ownerSeatNo) {
        this.ownerSeatNo = ownerSeatNo;
    }

    public String getRoomStatus() {
        return roomStatus;
    }

    public void setRoomStatus(String roomStatus) {
        this.roomStatus = roomStatus;
    }

    public Integer getCurrentDay() {
        return currentDay;
    }

    public void setCurrentDay(Integer currentDay) {
        this.currentDay = currentDay;
    }

    public String getCurrentPhase() {
        return currentPhase;
    }

    public void setCurrentPhase(String currentPhase) {
        this.currentPhase = currentPhase;
    }

    public Boolean getAutoJudgeEnabled() {
        return autoJudgeEnabled;
    }

    public void setAutoJudgeEnabled(Boolean autoJudgeEnabled) {
        this.autoJudgeEnabled = autoJudgeEnabled;
    }

    public Boolean getCanRollback() {
        return canRollback;
    }

    public void setCanRollback(Boolean canRollback) {
        this.canRollback = canRollback;
    }

    public String getWinnerCamp() {
        return winnerCamp;
    }

    public void setWinnerCamp(String winnerCamp) {
        this.winnerCamp = winnerCamp;
    }

    public String getLatestAnnouncement() {
        return latestAnnouncement;
    }

    public void setLatestAnnouncement(String latestAnnouncement) {
        this.latestAnnouncement = latestAnnouncement;
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
