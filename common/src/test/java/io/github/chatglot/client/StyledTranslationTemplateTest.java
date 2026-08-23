package io.github.chatglot.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.junit.jupiter.api.Test;

class StyledTranslationTemplateTest {

    @Test
    void restoresPrExampleWithThirtyStyledSegments() {
        Component original = createPrExample();
        StyledTranslationTemplate template = StyledTranslationTemplate.create(
            original,
            original.getString(),
            false
        );
        String gasResponse = template.markedText().replace("▬", "-");
        Component restored = template.apply(gasResponse);

        assertEquals("-".repeat(30), restored.getString());
        assertFalse(restored.getString().contains("[[CGT_"));
    }

    @Test
    void restoresMarkerWhenTranslationInsertsWhitespaceInsideItsName() {
        Component original = createPrExample();
        StyledTranslationTemplate template = StyledTranslationTemplate.create(
            original,
            original.getString(),
            false
        );
        String gasResponse = template.markedText()
            .replace("▬", "-")
            .replace("[[/CGT_22]]", "[[/CG T_22]]")
            .replace("]][[CGT_", "]] [[CGT_");
        Component restored = template.apply(gasResponse);

        assertEquals("-".repeat(30), restored.getString());
        assertFalse(restored.getString().contains("CG T_22"));
    }

    @Test
    void removesWhitespaceInsertedInsideDecorativeSegments() {
        Component original = createPrExample();
        StyledTranslationTemplate template = StyledTranslationTemplate.create(
            original,
            original.getString(),
            false
        );
        String gasResponse = template.markedText()
            .replace("[[CGT_4]]▬[[/CGT_4]]", "[[CGT_4]] ▬[[/CGT_4]]")
            .replace("[[CGT_15]]▬[[/CGT_15]]", "[[CGT_15]]▬ [[/CGT_15]]")
            .replace("[[CGT_26]]▬[[/CGT_26]]", "[[CGT_26]] ▬ [[/CGT_26]]");

        Component restored = template.apply(gasResponse);

        assertEquals("▬".repeat(30), restored.getString());
    }

    @Test
    void preservesBoundaryWhitespaceThatExistsInDecorativeSourceSegment() {
        Component original = Component.literal(" ▬ ").withStyle(ChatFormatting.AQUA);
        StyledTranslationTemplate template = StyledTranslationTemplate.create(
            original,
            original.getString(),
            false
        );

        Component restored = template.apply("[[CGT_0]] ▬ [[/CGT_0]]");

        assertEquals(" ▬ ", restored.getString());
    }

    @Test
    void preservesWhitespaceInsertedBetweenTranslatedWords() {
        Component original = Component.empty()
            .append(Component.literal("こんにちは").withStyle(ChatFormatting.RED))
            .append(Component.literal("世界").withStyle(ChatFormatting.BLUE));
        StyledTranslationTemplate template = StyledTranslationTemplate.create(
            original,
            original.getString(),
            false
        );

        Component restored = template.apply(
            "[[CGT_0]]Hello[[/CGT_0]] [[CGT_1]]world[[/CGT_1]]"
        );

        assertEquals("Hello world", restored.getString());
    }

    private static Component createPrExample() {
        ChatFormatting[] colors = {
            ChatFormatting.WHITE,
            ChatFormatting.RED,
            ChatFormatting.GREEN,
            ChatFormatting.BLUE,
            ChatFormatting.YELLOW,
            ChatFormatting.AQUA,
            ChatFormatting.LIGHT_PURPLE,
            ChatFormatting.GOLD,
            ChatFormatting.DARK_RED,
            ChatFormatting.DARK_GREEN,
            ChatFormatting.DARK_BLUE,
            ChatFormatting.DARK_AQUA,
            ChatFormatting.DARK_PURPLE
        };
        MutableComponent original = Component.empty();
        for (int index = 0; index < 30; index++) {
            ChatFormatting color = colors[index % colors.length];
            int decorationGroup = index / colors.length;
            original.append(Component.literal("▬").withStyle(style -> {
                style = style.withColor(color);
                if (decorationGroup == 1) {
                    style = style.withBold(true);
                } else if (decorationGroup == 2) {
                    style = style.withItalic(true);
                }
                return style;
            }));
        }
        return original;
    }
}
