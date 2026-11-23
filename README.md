# WF Chat

Chat replacement. Depends on GemRender. Client-only or client + server.

## Nodes
| node | loader |
|---|---|
| 1.20.1 | Forge |
| 1.20.1-fabric | Fabric |
| 1.21.1 | NeoForge |
| 1.21.1-fabric | Fabric |
| 26.1 | NeoForge |
| 26.1-fabric | Fabric |

`./gradlew :<node>:build`

## Channels
- Defaults: `system` (command feedback), `global` (player chat), `local` (senders in the client's loaded chunks).
- Buttons sit between history and input. Click = only that channel; shift-click = merge. Selected = green. Right-click closes a dynamic channel.
- One log, entries carry every channel they belong to: merged views list an entry once; prefix = most specific selected channel (`priority`).
- Typed text goes to the target channel's `Sender` (last clicked). `/`-lines bypass it.
- Local sending needs the WF Chat server (`/wfchat local`); client-only falls back to global.
- Server detected by the `wfchat` command root.

## Plugin API (`com.wf.wfchat.api`)
```java
WfChatApi.register(new WfChatPlugin() {
    public String id() { return "mymod"; }
    public void register(ChatRegistry r) {
        r.channel(ChannelSpec.builder("mymod:team").title("Team").color(0x55FF55)
                .order(40).priority(5).sender(Sender.command("t")).build());
        r.classifier((msg, sink) -> { if (isTeam(msg)) { sink.add("mymod:team"); sink.claim(); } });
        r.channelCommand(cmd);          // commands that open dynamic channels (/msg is the default one)
        r.vanity((uuid, name, tags) -> tags.add(":crown: VIP", 0xFFAA00));
        r.glyph("crown", "mymod:badges", '');
    }
});
```
Server: `WfChatServer.channel(id, recipients)` backs `/wfchat channel <id> <msg>`; `WfChatServer.stamp(id, line)` files any outgoing line into a channel on WF Chat clients, unchanged for others.

## Glyphs
Resource packs: `assets/<ns>/wfchat/glyphs.json` = `{"glyphs": {"crown": {"font": "ns:badges", "char": ""}}}`, font = ordinary bitmap font definition. `:crown:` in tag text expands to it.

## Vanity tags
`config/wfchat-vanity.toml`: `[tags]` table, key = `"*"`, name or UUID, value = string list (`["[Admin]", ":crown:"]`). Providers from plugins stack on top.

## Emoji
- Wire format matches Emojicord: `<:name:BASE64(id)>` (decimal ids also accepted), `:name:`, raw Unicode.
- Names/aliases: GitHub gemoji list; images: Twemoji. Both fetched once, cached in `wfchat/emoji/`, URLs overridable.
- `config/wfchat-emoji.toml` (written on first run): `enabled`, `animate`, `dictionary`, `images`, `[server_emoji]` limits, `[custom]` `pepe = "<discord id>"`, animated `dance = "a:<id>"`. Typed `:pepe:` is sent as `<:pepe:BASE64>` / `<a:dance:BASE64>`.
- Rendering: one placeholder char of font `wfchat:emoji` (9 px advance, U+E000..U+E7FF) per emoji; images drawn by Canvas.
- Typing `:ab` pops up suggestions: Tab/click completes, Up/Down move, Esc dismisses.
- Animated: GIF (Discord `<a:..>`, custom `a:`, server emoji). Frames box-filtered to <=36 px, one stacked texture; `animate=false` => first frame.
- Gap: no emoji picker grid; animated PNG/WebP unsupported.

### Server emoji
- Server: `config/wfchat/emoji/<name>.png|gif`, `config/wfchat-server.toml` `[emoji]` limits (defaults: 128 KiB, 8..128 px, square, 128 frames, 256 files). `/wfchat emoji list|reload` (reload = op 3).
- Verified at load by `core/EmojiFile`: extension == magic bytes, name `[a-z0-9_]{2,32}`, size, px, square, PNG chunk CRCs + pixel stream length, GIF block structure + frame count; animated PNG, trailing data, unknown critical chunks => rejected (logged). PNG ancillary / GIF comment+app extensions stripped; canonical bytes hashed (SHA-256).
- Transfer: no custom packets. Client sends `/wfchat emoji manifest|get`; replies are empty chat lines whose insertion carries `wfchat:em=...` (`core/EmojiWire`), consumed client-side, never shown. Cache `wfchat/emoji/server/<sha>.<ext>`.
- Client re-verifies everything with its own `[server_emoji]` limits and requires SHA-256 == manifest. Typed `:name:` stays plain text; clients without the mod see the text.
- Gap: server cache never pruned.

## Styling
`config/wfchat-chat.toml` `[style]` (`enabled`, `colors`, `formatting`, `spoilers`). Received player messages: `**bold**` `~~strike~~` `__underline__` `||spoiler||` (click to reveal) `&#RRGGBB`/`&#RGB` colour until `&r`. `\` escapes. Markers pair inside one text run. Strike/underline drawn by WF Chat (Canvas has none). Spoiler click not tied to `styleAt` => works on 26.1.
