package com.wf.wfchat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.wf.wfchat.core.Toml;
import org.junit.jupiter.api.Test;

class TomlTest {
    @Test
    void parses() throws Exception {
        Toml.Table t = Toml.parse("""
                # c
                a = true
                n = 1_000
                s = "x # y\\n" # trailing
                l = 'lit'
                [t.u]
                list = [
                  "a", # one
                  "b",
                ]
                "weird key" = 0x10
                """);
        assertThat(t.bool("a", false)).isTrue();
        assertThat(t.integer("n", 0)).isEqualTo(1000);
        assertThat(t.string("s", "")).isEqualTo("x # y\n");
        assertThat(t.string("l", "")).isEqualTo("lit");
        assertThat(t.table("t").table("u").strings("list")).containsExactly("a", "b");
        assertThat(t.table("t").table("u").integer("weird key", 0)).isEqualTo(16);
        assertThat(t.integer("n", 0, 10, 5)).isEqualTo(10);
    }

    @Test
    void failsFast() {
        assertThatThrownBy(() -> Toml.parse("a = ")).isInstanceOf(Toml.ParseException.class);
        assertThatThrownBy(() -> Toml.parse("a = 1\na = 2")).hasMessageContaining("duplicate");
        assertThatThrownBy(() -> Toml.parse("[[x]]")).isInstanceOf(Toml.ParseException.class);
        assertThatThrownBy(() -> Toml.parse("a = {b=1}")).isInstanceOf(Toml.ParseException.class);
        assertThatThrownBy(() -> Toml.parse("a = \"open")).isInstanceOf(Toml.ParseException.class);
    }
}
