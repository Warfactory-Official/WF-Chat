package com.wf.wfchat;

import static org.assertj.core.api.Assertions.assertThat;

import com.wf.wfchat.client.ChatConfig;
import com.wf.wfchat.client.StyleText;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import org.junit.jupiter.api.Test;

class StyleTextTest {
    private record Run(String text, Style style) {
    }

    private static List<Run> runs(String in) {
        Component out = StyleText.apply(Component.translatable("chat.type.text", "Bob", in), new ChatConfig());
        Object raw = ((net.minecraft.network.chat.contents.TranslatableContents) out.getContents()).getArgs()[1];
        Component arg = raw instanceof Component c ? c : Component.literal(String.valueOf(raw));
        List<Run> list = new ArrayList<>();
        arg.visit((s, t) -> {
            list.add(new Run(t, s));
            return Optional.empty();
        }, Style.EMPTY);
        return list;
    }

    @Test
    void formats() {
        var r = runs("a **b** ~~c~~ __d__ e");
        assertThat(r).extracting(Run::text).containsExactly("a ", "b", " ", "c", " ", "d", " e");
        assertThat(r.get(1).style.isBold()).isTrue();
        assertThat(r.get(3).style.isStrikethrough()).isTrue();
        assertThat(r.get(5).style.isUnderlined()).isTrue();
    }

    @Test
    void hexColour() {
        var r = runs("&#ff8800hot&r cold &#fa0x");
        assertThat(r.get(0).text).isEqualTo("hot");
        assertThat(r.get(0).style.getColor().getValue()).isEqualTo(0xFF8800);
        assertThat(r.get(1).style.getColor()).isNull();
        assertThat(r.get(2).style.getColor().getValue()).isEqualTo(0xFFAA00);
    }

    @Test
    void spoiler() {
        var r = runs("x ||secret|| y");
        assertThat(StyleText.spoilerId(r.get(1).style)).isNotZero();
        assertThat(StyleText.spoilerId(r.get(0).style)).isZero();
        assertThat(r.get(1).text).isEqualTo("secret");
    }

    @Test
    void unmatchedAndEscaped() {
        assertThat(runs("2 ** 3")).extracting(Run::text).containsExactly("2 ** 3");
        assertThat(runs("\\**no**").stream().map(Run::text).reduce("", String::concat)).isEqualTo("**no**");
        assertThat(runs(":a__b: **x**").get(0).text).isEqualTo(":a__b: ");
    }
}
