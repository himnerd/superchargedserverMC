package com.superchargedserver.wiki;

import com.superchargedserver.SuperChargedServer;
import com.superchargedserver.inventory.InventoryButton;
import com.superchargedserver.inventory.InventoryGUI;
import com.superchargedserver.util.ColorUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class WikiManager {

    private final SuperChargedServer plugin;
    private final List<WikiEntry> entries = new ArrayList<>();
    private String guiTitle;
    private int guiSize;
    private Material fillerMaterial;

    public record WikiEntry(String displayName, String url, Material icon, int customModelData, List<Component> lore) {}

    public WikiManager(SuperChargedServer plugin) {
        this.plugin = plugin;
    }

    public void load() {
        entries.clear();
        FileConfiguration config = plugin.getConfigManager().wikis();
        guiTitle = config.getString("gui.title", "<gradient:#00E5FF:#7B2FFF>Server Wikis");
        guiSize = Math.min(54, Math.max(9, config.getInt("gui.size", 27) / 9 * 9));
        String filler = config.getString("gui.filler-material", "GRAY_STAINED_GLASS_PANE");
        try {
            fillerMaterial = Material.valueOf(filler);
        } catch (IllegalArgumentException e) {
            fillerMaterial = Material.GRAY_STAINED_GLASS_PANE;
        }

        ConfigurationSection custom = config.getConfigurationSection("custom-wikis");
        if (custom != null) {
            for (String key : custom.getKeys(false)) {
                ConfigurationSection sec = custom.getConfigurationSection(key);
                if (sec == null) continue;
                String perm = sec.getString("permission", "");
                List<Component> lore = new ArrayList<>();
                for (String line : sec.getStringList("lore")) {
                    lore.add(ColorUtil.colorize(line));
                }
                Material mat;
                try {
                    mat = Material.valueOf(sec.getString("icon", "BOOK"));
                } catch (IllegalArgumentException e) {
                    mat = Material.BOOK;
                }
                entries.add(new WikiEntry(
                        sec.getString("display-name", key),
                        sec.getString("url", ""),
                        mat,
                        sec.getInt("custom-model-data", 0),
                        lore));
            }
        }
        plugin.getLogger().info("Loaded " + entries.size() + " wiki entries.");
    }

    public void openGUI(Player player, int page) {
        int perPage = guiSize - 9;
        int totalPages = Math.max(1, (int) Math.ceil((double) entries.size() / perPage));
        int actualPage = Math.max(0, Math.min(page, totalPages - 1));

        WikiGUI gui = new WikiGUI(actualPage, totalPages);
        plugin.getGuiManager().openGUI(gui, player);
    }

    private class WikiGUI extends InventoryGUI {
        private final int page;
        private final int totalPages;

        WikiGUI(int page, int totalPages) {
            this.page = page;
            this.totalPages = totalPages;
        }

        @Override
        protected Inventory createInventory() {
            return Bukkit.createInventory(null, guiSize, ColorUtil.colorize(guiTitle));
        }

        @Override
        public void decorate(Player player) {
            int start = page * (guiSize - 9);
            int end = Math.min(start + guiSize - 9, entries.size());
            for (int i = start, slot = 0; i < end; i++, slot++) {
                WikiEntry e = entries.get(i);
                ItemStack item = new ItemStack(e.icon());
                ItemMeta meta = item.getItemMeta();
                meta.displayName(ColorUtil.colorize(e.displayName()));
                meta.setCustomModelData(e.customModelData() > 0 ? e.customModelData() : null);
                meta.lore(e.lore());
                item.setItemMeta(meta);

                String url = e.url();
                addButton(slot, new InventoryButton()
                        .creator(p -> item)
                        .consumer(event -> {
                            Player clicker = (Player) event.getWhoClicked();
                            clicker.closeInventory();
                            clicker.sendMessage(ColorUtil.colorize(
                                    "<gray>Wiki link: <aqua>" + url));
                        }));
            }

            // Navigation row at the bottom
            if (page > 0) {
                ItemStack prev = new ItemStack(Material.ARROW);
                ItemMeta pMeta = prev.getItemMeta();
                pMeta.displayName(ColorUtil.colorize("<yellow><bold>← Previous Page"));
                prev.setItemMeta(pMeta);
                addButton(guiSize - 9, new InventoryButton()
                        .creator(p -> prev)
                        .consumer(event -> {
                            Player clicker = (Player) event.getWhoClicked();
                            openGUI(clicker, page - 1);
                        }));
            }
            if (page < totalPages - 1) {
                ItemStack next = new ItemStack(Material.ARROW);
                ItemMeta nMeta = next.getItemMeta();
                nMeta.displayName(ColorUtil.colorize("<yellow><bold>Next Page →"));
                next.setItemMeta(nMeta);
                addButton(guiSize - 1, new InventoryButton()
                        .creator(p -> next)
                        .consumer(event -> {
                            Player clicker = (Player) event.getWhoClicked();
                            openGUI(clicker, page + 1);
                        }));
            }

            // Page indicator
            ItemStack info = new ItemStack(Material.PAPER);
            ItemMeta iMeta = info.getItemMeta();
            iMeta.displayName(ColorUtil.colorize("<gray>Page <white>" + (page + 1) + "/" + totalPages));
            info.setItemMeta(iMeta);
            addButton(guiSize - 5, new InventoryButton()
                    .creator(p -> info)
                    .consumer(event -> {}));

            // Fill remaining slots
            ItemStack filler = new ItemStack(fillerMaterial);
            ItemMeta fMeta = filler.getItemMeta();
            fMeta.displayName(Component.empty());
            filler.setItemMeta(fMeta);
            for (int slot = 0; slot < guiSize; slot++) {
                if (getInventory().getItem(slot) == null) {
                    addButton(slot, new InventoryButton()
                            .creator(p -> filler)
                            .consumer(event -> {}));
                }
            }
            super.decorate(player);
        }
    }
}