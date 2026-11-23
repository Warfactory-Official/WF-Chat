package com.wf.wfchat;

import static org.assertj.core.api.Assertions.assertThat;

import com.wf.wfchat.core.BridgeRule;
import com.wf.wfchat.core.Toml;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class BridgeRuleTest {
    @Test
    void defaultMatchesDiscordTags() throws Exception {
        Toml.Table t = Toml.parse("[bridge.discord]\npattern = '" + BridgeRule.DEFAULT_PATTERN + "'\n");
        List<String> errors = new ArrayList<>();
        BridgeRule r = BridgeRule.parse(t, errors).get(0);
        assertThat(errors).isEmpty();
        assertThat(r.id()).isEqualTo("bridge:discord");
        assertThat(r.pin()).isTrue();
        assertThat(r.matches("[Discord] Bob » hi")).isTrue();
        assertThat(r.matches("[Discord | Admin] Bob » hi")).isTrue();
        assertThat(r.matches("<Bob> [Discord] hi")).isFalse();
        assertThat(r.matches("[Discordant] x")).isFalse();
    }

    @Test
    void reportsBadRules() throws Exception {
        Toml.Table t = Toml.parse("""
                [bridge.a]
                pattern = '('
                [bridge.b]
                pattern = 'x'
                mode = "weird"
                [bridge.c]
                title = "none"
                [bridge.d]
                pattern = 'ok'
                mode = "own"
                color = "#ff0000"
                """);
        List<String> errors = new ArrayList<>();
        List<BridgeRule> rules = BridgeRule.parse(t, errors);
        assertThat(errors).hasSize(3);
        assertThat(rules).hasSize(1);
        assertThat(rules.get(0).pin()).isFalse();
        assertThat(rules.get(0).color()).isEqualTo(0xFF0000);
    }
}
