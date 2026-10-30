package com.wolfbook.backend.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("roles")
public class RoleEntity {

    @TableId(type = IdType.AUTO)
    private Integer id;
    private String name;
    // 后台允许清空的可选字段：updateById 默认跳过 null，这里改成总是写入，清空才会落库
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String alias;
    // 称号（世界观里的头衔，比如「看星的人」）
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String title;
    // 人物关系 JSON：[{roleId, label}]
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String relations;
    private String faction;
    private String roleType;
    private String camp;
    private String skill;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String background;
    private String faqs;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String portrait;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String fullIllustration;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String themeColor;
    /** 电子法官的技能机制（service/judge/Mechanic 的 code），为空表示引擎不认识、只播报不结算。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String mechanic;
    private LocalDateTime createTime;

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getAlias() {
        return alias;
    }

    public void setAlias(String alias) {
        this.alias = alias;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getRelations() {
        return relations;
    }

    public void setRelations(String relations) {
        this.relations = relations;
    }

    public String getFaction() {
        return faction;
    }

    public void setFaction(String faction) {
        this.faction = faction;
    }

    public String getRoleType() {
        return roleType;
    }

    public void setRoleType(String roleType) {
        this.roleType = roleType;
    }

    public String getCamp() {
        return camp;
    }

    public void setCamp(String camp) {
        this.camp = camp;
    }

    public String getSkill() {
        return skill;
    }

    public void setSkill(String skill) {
        this.skill = skill;
    }

    public String getBackground() {
        return background;
    }

    public void setBackground(String background) {
        this.background = background;
    }

    public String getFaqs() {
        return faqs;
    }

    public void setFaqs(String faqs) {
        this.faqs = faqs;
    }

    public String getPortrait() {
        return portrait;
    }

    public void setPortrait(String portrait) {
        this.portrait = portrait;
    }

    public String getFullIllustration() {
        return fullIllustration;
    }

    public void setFullIllustration(String fullIllustration) {
        this.fullIllustration = fullIllustration;
    }

    public String getThemeColor() {
        return themeColor;
    }

    public void setThemeColor(String themeColor) {
        this.themeColor = themeColor;
    }

    public String getMechanic() {
        return mechanic;
    }

    public void setMechanic(String mechanic) {
        this.mechanic = mechanic;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
}
