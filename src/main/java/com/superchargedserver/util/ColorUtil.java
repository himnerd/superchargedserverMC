package com.superchargedserver.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.regex.Pattern;

public final class ColorUtil {

    public static final String PREFIX = "<gradient:#00E5FF:#7B2FFF><bold>SCS</bold></gradient> <dark_gray>» <gray>";

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private static final Pattern URL_PATTERN = Pattern.compile("https?://[^\\s<>\"]+");
    private static final TextReplacementConfig LINKIFY = TextReplacementConfig.builder()
            .match(URL_PATTERN)
            .replacement((match, builder) -> builder
                    .clickEvent(ClickEvent.openUrl(match.group()))
                    .hoverEvent(HoverEvent.showText(Component.text("Click to open link", NamedTextColor.AQUA)))
                    .decorate(TextDecoration.UNDERLINED))
            .build();

    private ColorUtil() {
    }

    public static Component colorize(String text) {
        if (text == null) return Component.empty();
        Component component;
        if (text.contains("&") || text.contains("§")) {
            component = LegacyComponentSerializer.legacyAmpersand().deserialize(text.replace('§', '&'));
        } else {
            component = MINI_MESSAGE.deserialize(text);
        }
        return component.replaceText(LINKIFY);
    }

    public static String legacy(String text) {
        return LegacyComponentSerializer.legacySection().serialize(colorize(text));
    }

    public static String plain(String text) {
        return PlainTextComponentSerializer.plainText().serialize(colorize(text));
    }
}