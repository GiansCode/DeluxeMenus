package com.extendedclip.deluxemenus.utils;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Color;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class StringUtils {

    // &#RRGGBB ampersand-hex format
    private static final Pattern AMP_HEX_PATTERN = Pattern.compile("&(#[a-fA-F0-9]{6})");

    // §x§R§R§G§G§B§B — the §-encoded hex format emitted by legacy servers / PlaceholderAPI
    private static final Pattern SECTION_HEX_PATTERN = Pattern.compile(
            "§x§([0-9a-fA-F])§([0-9a-fA-F])§([0-9a-fA-F])§([0-9a-fA-F])§([0-9a-fA-F])§([0-9a-fA-F])");

    // &X and §X named color/formatting codes
    private static final Pattern AMP_CODE_PATTERN = Pattern.compile("&([0-9a-fk-orA-FK-OR])");
    private static final Pattern SECTION_CODE_PATTERN = Pattern.compile("§([0-9a-fk-orA-FK-OR])");

    /** Mapping from legacy color/format code characters to MiniMessage tag names. */
    private static final Map<Character, String> CODE_TO_MINIMESSAGE;

    static {
        Map<Character, String> map = new HashMap<>();
        map.put('0', "black");          map.put('1', "dark_blue");
        map.put('2', "dark_green");     map.put('3', "dark_aqua");
        map.put('4', "dark_red");       map.put('5', "dark_purple");
        map.put('6', "gold");           map.put('7', "gray");
        map.put('8', "dark_gray");      map.put('9', "blue");
        map.put('a', "green");          map.put('b', "aqua");
        map.put('c', "red");            map.put('d', "light_purple");
        map.put('e', "yellow");         map.put('f', "white");
        map.put('k', "obfuscated");     map.put('l', "bold");
        map.put('m', "strikethrough");  map.put('n', "underlined");
        map.put('o', "italic");         map.put('r', "reset");
        CODE_TO_MINIMESSAGE = Collections.unmodifiableMap(map);
    }

    /**
     * Legacy serializer used to convert a {@link Component} back to a {@code §}-coded string for
     * use with Bukkit's legacy ItemMeta / Inventory APIs.  Hex-color support is enabled when the
     * server version supports it (≥ 1.16), producing the {@code §x§R§R§G§G§B§B} format.
     */
    private static final LegacyComponentSerializer LEGACY_SERIALIZER = buildLegacySerializer();

    private static LegacyComponentSerializer buildLegacySerializer() {
        LegacyComponentSerializer.Builder builder = LegacyComponentSerializer.builder();
        if (VersionHelper.IS_HEX_VERSION) {
            builder.hexColors().useUnusualXRepeatedCharacterHexFormat();
        }
        return builder.build();
    }

    /**
     * Converts a raw text string to an Adventure {@link Component}.
     *
     * <p>Supports, in a single unified pass:
     * <ul>
     *   <li><b>MiniMessage tags</b> — {@code <red>}, {@code <gradient:red:blue>},
     *       {@code <#ff0000>}, {@code <bold>}, {@code <hover:show_text:'…'>…</hover>}, etc.
     *   <li><b>Legacy {@code &} codes</b> — {@code &a}, {@code &l}, {@code &r}, …
     *   <li><b>Legacy hex</b> — {@code &#rrggbb} (config-side) and the
     *       {@code §x§R§R§G§G§B§B} format that PlaceholderAPI / other plugins may return.
     *   <li><b>Section-symbol codes</b> — {@code §a}, {@code §l}, … returned by external sources.
     * </ul>
     *
     * @param input The raw string (may contain any mix of the formats above).
     * @return A fully-parsed Adventure {@link Component}.
     */
    @NotNull
    public static Component toComponent(@NotNull String input) {
        // §x§R§R§G§G§B§B → <#RRGGBB>
        input = SECTION_HEX_PATTERN.matcher(input).replaceAll("<#$1$2$3$4$5$6>");
        // &#RRGGBB → <#RRGGBB>
        input = AMP_HEX_PATTERN.matcher(input).replaceAll("<$1>");
        // &X / §X → <minimessage_tag>
        input = legacyCodesToTags(AMP_CODE_PATTERN, input);
        input = legacyCodesToTags(SECTION_CODE_PATTERN, input);
        return MiniMessage.miniMessage().deserialize(input);
    }

    /**
     * Translates color codes and MiniMessage tags in {@code input} to a legacy
     * section-symbol string compatible with {@link org.bukkit.inventory.meta.ItemMeta#setDisplayName},
     * {@link org.bukkit.inventory.meta.ItemMeta#setLore}, and
     * {@link org.bukkit.Bukkit#createInventory(org.bukkit.inventory.InventoryHolder, int, String)}.
     *
     * <p>Fully replaces the old {@code &} / hex-only coloring — all MiniMessage formatting
     * (gradients, per-character colors, click/hover etc.) is parsed first, then serialized to
     * the legacy format understood by Bukkit.
     *
     * @param input The raw text string.
     * @return A {@code §}-coded string ready for Bukkit APIs.
     */
    @NotNull
    public static String color(@NotNull String input) {
        return LEGACY_SERIALIZER.serialize(toComponent(input));
    }

    private static String legacyCodesToTags(Pattern pattern, String input) {
        Matcher m = pattern.matcher(input);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        while (m.find()) {
            sb.append(input, last, m.start());
            char code = Character.toLowerCase(m.group(1).charAt(0));
            String tag = CODE_TO_MINIMESSAGE.get(code);
            sb.append(tag != null ? "<" + tag + ">" : m.group());
            last = m.end();
        }
        sb.append(input, last, input.length());
        return sb.toString();
    }

    @NotNull
    public static String replacePlaceholdersAndArguments(@NotNull String input, final @Nullable Map<String, String> arguments,
                                                         final @Nullable Player player,
                                                         final boolean parsePlaceholdersInsideArguments,
                                                         final boolean parsePlaceholdersAfterArguments) {
        if (player == null) {
            return replaceArguments(input, arguments, null, parsePlaceholdersInsideArguments);
        }

        if (parsePlaceholdersAfterArguments) {
            return replacePlaceholders(replaceArguments(input, arguments, player, parsePlaceholdersInsideArguments), player);
        }

        return replaceArguments(replacePlaceholders(input, player), arguments, player, parsePlaceholdersInsideArguments);
    }

    @NotNull
    public static String replacePlaceholders(final @NotNull String input, final @NotNull Player player) {
        return PlaceholderAPI.setPlaceholders(player, input);
    }

    @NotNull
    public static String replaceArguments(@NotNull String input, final @Nullable Map<String, String> arguments,
                                          final @Nullable Player player, boolean parsePlaceholdersInsideArguments) {
        if (arguments == null || arguments.isEmpty()) {
            return input;
        }

        for (final Map.Entry<String, String> entry : arguments.entrySet()) {
            final String value = player != null && parsePlaceholdersInsideArguments
                    ? replacePlaceholders(entry.getValue(), player)
                    : entry.getValue();
            input = input.replace("{" + entry.getKey() + "}", value);
        }

        return input;
    }

    @Nullable
    public static Color parseRGBColor(@NotNull final String input) {
        final String[] parts = input.split(",");
        try {
            return Color.fromRGB(
                    Integer.parseInt(parts[0].trim()),
                    Integer.parseInt(parts[1].trim()),
                    Integer.parseInt(parts[2].trim())
            );
        } catch (final Exception exception) {
            return null;
        }
    }
}
