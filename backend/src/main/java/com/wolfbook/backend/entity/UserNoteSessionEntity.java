package com.wolfbook.backend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("user_note_sessions")
public class UserNoteSessionEntity {

    @TableId(type = IdType.INPUT)
    private String sessionId;
    private Integer version;
    private String openid;
    private String boardMode;
    private Integer boardId;
    private String boardName;
    private Integer playerCount;
    private String status;
    private Integer currentDay;
    private String currentPhase;
    private String resultCamp;
    private Integer sheriffSeat;
    private String playersJson;
    private String summaryJson;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getOpenid() {
        return openid;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public void setOpenid(String openid) {
        this.openid = openid;
    }

    public String getBoardMode() {
        return boardMode;
    }

    public void setBoardMode(String boardMode) {
        this.boardMode = boardMode;
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

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
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

    public String getResultCamp() {
        return resultCamp;
    }

    public void setResultCamp(String resultCamp) {
        this.resultCamp = resultCamp;
    }

    public Integer getSheriffSeat() {
        return sheriffSeat;
    }

    public void setSheriffSeat(Integer sheriffSeat) {
        this.sheriffSeat = sheriffSeat;
    }

    public String getPlayersJson() {
        return playersJson;
    }

    public void setPlayersJson(String playersJson) {
        this.playersJson = playersJson;
    }

    public String getSummaryJson() {
        return summaryJson;
    }

    public void setSummaryJson(String summaryJson) {
        this.summaryJson = summaryJson;
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
