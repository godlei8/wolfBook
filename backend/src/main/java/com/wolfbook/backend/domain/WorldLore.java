package com.wolfbook.backend.domain;

import java.util.List;

/**
 * 世界观（夜镇编年史），整份存在 world_lore 表的一行 JSON 里。
 * factions 是三方势力（camp 取 good / wolf / third），events 是编年史事件，roleIds 是事件里出场的角色。
 */
public record WorldLore(
        String townName,
        String tagline,
        String intro,
        String coverImage,
        List<Faction> factions,
        List<Event> events
) {

    public record Faction(String name, String camp, String description) {
    }

    public record Event(String era, String title, String content, List<Integer> roleIds) {
    }

    public static WorldLore empty() {
        return new WorldLore(null, null, null, null, List.of(), List.of());
    }

    /** 读出来的旧数据可能缺字段，这里把 null 列表补成空列表。 */
    public WorldLore normalized() {
        return new WorldLore(
                townName,
                tagline,
                intro,
                coverImage,
                factions == null ? List.of() : factions,
                events == null ? List.of() : events.stream()
                        .map(event -> new Event(event.era(), event.title(), event.content(), event.roleIds() == null ? List.of() : event.roleIds()))
                        .toList()
        );
    }
}
