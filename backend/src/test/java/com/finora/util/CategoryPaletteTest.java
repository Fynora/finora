package com.finora.util;

import com.finora.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CategoryPaletteTest {

    /** A default category whose icon or colour is outside the palette would be refused the first
     *  time its owner edited it, and render as the fallback icon on both clients. */
    @Test
    void everyDefaultCategoryDrawsFromThePalette() {
        @SuppressWarnings("unchecked")
        Map<String, String[]> defaults =
                (Map<String, String[]>) ReflectionTestUtils.getField(AuthService.class, "DEFAULT_CATEGORIES");

        assertThat(defaults).containsKey("Personal Care");
        defaults.forEach((name, iconAndColor) -> {
            assertThat(CategoryPalette.isValidIcon(iconAndColor[0])).as(name + " icon").isTrue();
            assertThat(CategoryPalette.isValidColor(iconAndColor[1])).as(name + " colour").isTrue();
        });
    }
}
