package com.wolfbook.backend.support;

import java.util.Map;

public final class SeedMediaCatalog {

    private static final Map<String, String> BOARD_COVERS = Map.of(
            "wolfking_guard", "https://cos.godlei8.top/wolfbook/seed/default-media/board_wolfking_guard.png",
            "wolfking_dreamer", "https://cos.godlei8.top/wolfbook/seed/default-media/board_wolfking_dreamer.png",
            "nightmare_dreamer", "https://cos.godlei8.top/wolfbook/seed/default-media/board_nightmare_dreamer.png",
            "gargoyle_gravekeeper", "https://cos.godlei8.top/wolfbook/seed/default-media/board_gargoyle_gravekeeper.png",
            "wolfking_magician", "https://cos.godlei8.top/wolfbook/seed/default-media/board_wolfking_magician.png"
    );

    private static final Map<String, String> ROLE_PORTRAITS = Map.of(
            "dreamer", "https://cos.godlei8.top/wolfbook/seed/default-media/role_dreamer_portrait.png",
            "gravekeeper", "https://cos.godlei8.top/wolfbook/seed/default-media/role_gravekeeper_portrait.png",
            "nightmare", "https://cos.godlei8.top/wolfbook/seed/default-media/role_nightmare_portrait.png",
            "bear_tamer", "https://cos.godlei8.top/wolfbook/seed/default-media/role_bear_tamer_portrait.png",
            "magician", "https://cos.godlei8.top/wolfbook/seed/default-media/role_magician_portrait.png"
    );

    private static final Map<String, String> ROLE_ILLUSTRATIONS = Map.of(
            "dreamer", "https://cos.godlei8.top/wolfbook/seed/default-media/role_dreamer_illustration.png",
            "gravekeeper", "https://cos.godlei8.top/wolfbook/seed/default-media/role_gravekeeper_illustration.png",
            "nightmare", "https://cos.godlei8.top/wolfbook/seed/default-media/role_nightmare_illustration.png",
            "bear_tamer", "https://cos.godlei8.top/wolfbook/seed/default-media/role_bear_tamer_illustration.png",
            "magician", "https://cos.godlei8.top/wolfbook/seed/default-media/role_magician_illustration.png"
    );

    private SeedMediaCatalog() {
    }

    public static String boardCover(String key) {
        return BOARD_COVERS.getOrDefault(key, "");
    }

    public static String rolePortrait(String key) {
        return ROLE_PORTRAITS.getOrDefault(key, "");
    }

    public static String roleIllustration(String key) {
        return ROLE_ILLUSTRATIONS.getOrDefault(key, "");
    }
}
