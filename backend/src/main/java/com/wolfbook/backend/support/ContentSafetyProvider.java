package com.wolfbook.backend.support;

import java.util.List;

public interface ContentSafetyProvider {

    void ensureSafeText(String text);

    void ensureSafeImages(List<String> images);
}
