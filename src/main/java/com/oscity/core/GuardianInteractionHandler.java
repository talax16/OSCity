package com.oscity.core;

import com.oscity.config.ConfigManager;
import com.oscity.content.DialogueManager;
import com.oscity.session.JourneyTracker;
import com.oscity.mechanics.HintSystem;
import net.citizensnpcs.api.event.NPCRightClickEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class GuardianInteractionHandler implements Listener {

    private final JavaPlugin plugin;
    private final ConfigManager config;
    private final KernelGuardian guardian;
    private final DialogueManager dialogueManager;
    private final HintSystem hintSystem;
    private final JourneyTracker journeyTracker;

    public GuardianInteractionHandler(JavaPlugin plugin, ConfigManager config,
                                      KernelGuardian guardian,
                                      DialogueManager dialogueManager,
                                      HintSystem hintSystem,
                                      JourneyTracker journeyTracker) {
        this.plugin = plugin;
        this.config = config;
        this.guardian = guardian;
        this.dialogueManager = dialogueManager;
        this.hintSystem = hintSystem;
        this.journeyTracker = journeyTracker;
    }

    @EventHandler
    public void onNPCRightClick(NPCRightClickEvent event) {
        if (!event.getNPC().equals(guardian.getNPC())) return;
        showMainMenu(event.getClicker());
    }

    private void showMainMenu(Player player) {
        player.sendMessage(Component.text("════════════════════════════════", NamedTextColor.GOLD));
        player.sendMessage(Component.text("Kernel Guardian", NamedTextColor.AQUA, TextDecoration.BOLD));
        player.sendMessage(Component.text(""));
        player.sendMessage(Component.text("What would you like to know?", NamedTextColor.YELLOW));
        player.sendMessage(Component.text(""));
        player.sendMessage(Component.text("1. Explain again", NamedTextColor.GREEN)
            .append(Component.text(" - Replay instructions", NamedTextColor.GRAY)));
        player.sendMessage(Component.text("2. I'm lost", NamedTextColor.GOLD)
            .append(Component.text(" - Get a hint", NamedTextColor.GRAY)));
        player.sendMessage(Component.text("3. Explain a concept", NamedTextColor.LIGHT_PURPLE)
            .append(Component.text(" - Learn terminology", NamedTextColor.GRAY)));
        player.sendMessage(Component.text(""));
        player.sendMessage(Component.text("Type a number in chat to choose", NamedTextColor.DARK_GRAY, TextDecoration.ITALIC));
        player.sendMessage(Component.text("════════════════════════════════", NamedTextColor.GOLD));

        // Register pending menu selection — handled by MenuChatListener (below)
        pendingMenu.put(player.getUniqueId(), true);
    }

    // ── Concept list ──────────────────────────────────────────────────────────

    /** {display name, explanations YAML sub-key} */
    private static final List<String[]> CONCEPTS = Arrays.asList(
        new String[]{"TLB Hit",                          "tlb.hit"},
        new String[]{"TLB Miss",                         "tlb.miss"},
        new String[]{"TLB Location",                     "tlb.location"},
        new String[]{"Virtual Page Number (VPN)",        "address.vpn"},
        new String[]{"Page Offset",                      "address.offset"},
        new String[]{"Why Multi-Level Page Tables?",     "page_table.why_multi_level"},
        new String[]{"Page Table Entry (PTE)",           "page_table.pte"},
        new String[]{"Page Fault",                       "permission_chamber.page_fault"},
        new String[]{"Segmentation Fault",               "permission_chamber.segfault"},
        new String[]{"Copy-on-Write (COW)",              "memory.cow_what"},
        new String[]{"Lazy Loading",                     "memory.lazy_loading_what"},
        new String[]{"Swap Space",                       "memory.swap_what"},
        new String[]{"RAM",                              "memory.ram_what"},
        new String[]{"CLOCK Algorithm",                  "memory.clock_algo"}
    );

    // ── Pending menu state ────────────────────────────────────────────────────

    private final Map<UUID, Boolean> pendingMenu = new HashMap<>();
    /** Stores the filtered concept indices shown to each player awaiting a concept choice. */
    private final Map<UUID, List<Integer>> pendingConceptIndices = new HashMap<>();

    @EventHandler
    public void onMenuChat(io.papermc.paper.event.player.AsyncChatEvent event) {
        Player player = event.getPlayer();
        String msg = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
            .plainText().serialize(event.message()).trim();

        if (pendingConceptIndices.containsKey(player.getUniqueId())) {
            event.setCancelled(true);
            List<Integer> indices = pendingConceptIndices.remove(player.getUniqueId());
            org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> handleConceptChoice(player, msg, indices));
            return;
        }

        if (!pendingMenu.containsKey(player.getUniqueId())) return;
        event.setCancelled(true);
        pendingMenu.remove(player.getUniqueId());
        org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> handleMenuChoice(player, msg));
    }

    private void handleMenuChoice(Player player, String choice) {
        switch (choice) {
            case "1":
                replayCurrentDialogue(player);
                break;
            case "2":
                hintSystem.showHint(player);
                break;
            case "3":
                showConceptList(player);
                break;
            default:
                player.sendMessage(Component.text("Please type 1, 2, or 3.", NamedTextColor.RED));
        }
    }

    private void handleConceptChoice(Player player, String input, List<Integer> indices) {
        int choice;
        try {
            choice = Integer.parseInt(input);
        } catch (NumberFormatException e) {
            player.sendMessage(Component.text("Please type a number from the list.", NamedTextColor.RED));
            showConceptList(player);
            return;
        }
        if (choice < 1 || choice > indices.size()) {
            player.sendMessage(Component.text("Please type a number between 1 and " + indices.size() + ".", NamedTextColor.RED));
            showConceptList(player);
            return;
        }
        String key = "explanations." + CONCEPTS.get(indices.get(choice - 1))[1];
        String text = dialogueManager.getString(key, journeyTracker.getVars(player));
        if (text == null) {
            player.sendMessage("§6[Kernel Guardian] §fI don't have an explanation for that yet.");
        } else {
            player.sendMessage("§6[Kernel Guardian] §f" + text);
        }
    }

    private List<Integer> getConceptsForPhase(String phase) {
        switch (phase) {
            case "tlb_spawn":
            case "tlb_after_calculator":
                return Arrays.asList(0, 1, 2, 3, 4);
            case "calculator_from_tlb":
                return Arrays.asList(3, 4);
            case "library_entrance":
            case "page_directory":
                return Arrays.asList(3, 4, 5, 6);
            case "permission_decision":
            case "page_fault_type":
                return Arrays.asList(6, 7, 8);
            case "page_fault_corridor":
                return Arrays.asList(7, 9, 10, 11);
            case "lazy_alloc_decision":
            case "lazy_alloc_cow":
                return Arrays.asList(7, 9);
            case "cow_decision":
                return Arrays.asList(9);
            case "lazy_loading_entered":
                return Arrays.asList(10);
            case "disk_lazy_loading":
            case "disk_swap_retrieval":
                return Arrays.asList(10, 11);
            default:
                if (phase.startsWith("ram_")) return Arrays.asList(9, 10, 12);
                if (phase.startsWith("swap_")) return Arrays.asList(11, 13);
                return Arrays.asList();
        }
    }

    private void replayCurrentDialogue(Player player) {
        String phase = journeyTracker.getPhase(player);
        String dialoguePath = phaseToEntryDialogue(phase);
        if (dialoguePath != null && dialogueManager.hasPath(dialoguePath)) {
            dialogueManager.speak(player, dialoguePath, journeyTracker.getVars(player));
        } else {
            player.sendMessage("§6[Kernel Guardian] §fI have nothing more to add right now.");
        }
    }

    private String phaseToEntryDialogue(String phase) {
        switch (phase) {
            case "terminal_spawn":        return "rooms.terminal.initial_spawn";
            case "tlb_spawn":             return "rooms.tlb_room.at_spawn";
            case "tlb_after_calculator":  return "rooms.tlb_room.after_calculator";
            case "calculator_from_tlb":   return "rooms.calculator_room.from_tlb_spawn";
            case "library_entrance":      return "rooms.page_table_library.entrance";
            case "page_directory":        return "rooms.page_table_library.page_directory";
            case "permission_decision":   return "rooms.permission_chamber.at_spawn";
            case "page_fault_type":       return "rooms.permission_chamber.page_fault_subtype_prompt";
            case "page_fault_corridor":   return "rooms.page_fault_corridor.at_enter";
            case "lazy_alloc_decision":   return "rooms.lazy_allocation_room.at_enter";
            case "lazy_alloc_cow":        return "rooms.lazy_allocation_room.second_visit";
            case "cow_decision":          return "rooms.cow_room.at_spawn";
            case "lazy_loading_entered":  return "rooms.lazy_loading_room.at_enter";
            case "disk_lazy_loading":
            case "disk_swap_retrieval":   return "rooms.disk_room.at_spawn";
            default:
                if (phase.startsWith("ram_")) return "rooms.ram_room.at_spawn";
                if (phase.startsWith("swap_")) return "rooms.swap_district.at_spawn";
                return null;
        }
    }

    private void showConceptList(Player player) {
        String phase = journeyTracker.getPhase(player);
        List<Integer> indices = getConceptsForPhase(phase);

        player.sendMessage(Component.text("════════════════════════════════", NamedTextColor.GOLD));
        player.sendMessage(Component.text("Concepts I can explain here:", NamedTextColor.AQUA, TextDecoration.BOLD));

        if (indices.isEmpty()) {
            player.sendMessage(Component.text("  There are no concepts to explain in this area.", NamedTextColor.GRAY));
            player.sendMessage(Component.text("════════════════════════════════", NamedTextColor.GOLD));
            return;
        }

        player.sendMessage(Component.text(""));
        for (int i = 0; i < indices.size(); i++) {
            String name = CONCEPTS.get(indices.get(i))[0];
            player.sendMessage(Component.text("  " + (i + 1) + ". ", NamedTextColor.GOLD)
                .append(Component.text(name, NamedTextColor.YELLOW)));
        }
        player.sendMessage(Component.text(""));
        player.sendMessage(Component.text("Choose the number of the concept you want to ask about.", NamedTextColor.GRAY, TextDecoration.ITALIC));
        player.sendMessage(Component.text("════════════════════════════════", NamedTextColor.GOLD));
        pendingConceptIndices.put(player.getUniqueId(), indices);
    }
}
