package com.oscity.core;

import com.oscity.config.ConfigManager;
import com.oscity.content.DialogueManager;
import com.oscity.mode.PlayerMode;
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
        PlayerMode mode = journeyTracker.getMode(player);
        boolean isAdventurer = (mode == PlayerMode.ADVENTURER);

        player.sendMessage(Component.text(config.getMessage("ui.guardian_separator"), NamedTextColor.GOLD));
        player.sendMessage(Component.text(config.getMessage("ui.guardian_menu.title"), NamedTextColor.AQUA, TextDecoration.BOLD));
        player.sendMessage(Component.text(""));
        player.sendMessage(Component.text(config.getMessage("ui.guardian_menu.what_would_you_know"), NamedTextColor.YELLOW));
        player.sendMessage(Component.text(""));
        if (isAdventurer) {
            player.sendMessage(Component.text(config.getMessage("ui.guardian_menu.option1_brief"), NamedTextColor.GREEN)
                .append(Component.text(config.getMessage("ui.guardian_menu.option1_brief_desc"), NamedTextColor.GRAY)));
        } else {
            player.sendMessage(Component.text(config.getMessage("ui.guardian_menu.option1_explain"), NamedTextColor.GREEN)
                .append(Component.text(config.getMessage("ui.guardian_menu.option1_explain_desc"), NamedTextColor.GRAY)));
        }
        if (isAdventurer) {
            player.sendMessage(Component.text(config.getMessage("ui.guardian_menu.option2_guide"), NamedTextColor.GOLD)
                .append(Component.text(config.getMessage("ui.guardian_menu.option2_guide_desc"), NamedTextColor.GRAY)));
        } else {
            player.sendMessage(Component.text(config.getMessage("ui.guardian_menu.option2_lost"), NamedTextColor.GOLD)
                .append(Component.text(config.getMessage("ui.guardian_menu.option2_lost_desc"), NamedTextColor.GRAY)));
        }
        player.sendMessage(Component.text(config.getMessage("ui.guardian_menu.option3"), NamedTextColor.LIGHT_PURPLE)
            .append(Component.text(config.getMessage("ui.guardian_menu.option3_desc"), NamedTextColor.GRAY)));
        if (isAdventurer) {
            player.sendMessage(Component.text(config.getMessage("ui.guardian_menu.option4_enable"), NamedTextColor.AQUA)
                .append(Component.text(config.getMessage("ui.guardian_menu.option4_enable_desc"), NamedTextColor.GRAY)));
        } else {
            player.sendMessage(Component.text(config.getMessage("ui.guardian_menu.option4_disable"), NamedTextColor.GRAY)
                .append(Component.text(config.getMessage("ui.guardian_menu.option4_disable_desc"), NamedTextColor.GRAY)));
        }
        player.sendMessage(Component.text(""));
        player.sendMessage(Component.text(config.getMessage("ui.guardian_menu.prompt"), NamedTextColor.DARK_GRAY, TextDecoration.ITALIC));
        player.sendMessage(Component.text(config.getMessage("ui.guardian_separator"), NamedTextColor.GOLD));

        pendingMenu.put(player.getUniqueId(), true);
    }

    // ── Concept list ──────────────────────────────────────────────────────────

    /** {display name, explanations YAML sub-key} */
    private static final List<String[]> CONCEPTS = Arrays.asList(
        new String[]{"TLB Hit",                          "tlb.hit"},                              // 0
        new String[]{"TLB Miss",                         "tlb.miss"},                             // 1
        new String[]{"TLB Location",                     "tlb.location"},                         // 2
        new String[]{"Virtual Page Number (VPN)",        "address.vpn"},                          // 3
        new String[]{"Page Offset",                      "address.offset"},                       // 4
        new String[]{"Why Multi-Level Page Tables?",     "page_table.why_multi_level"},           // 5
        new String[]{"Page Table Entry (PTE)",           "page_table.pte"},                       // 6
        new String[]{"Page Fault",                       "permission_chamber.page_fault"},        // 7
        new String[]{"Segmentation Fault",               "permission_chamber.segfault"},          // 8
        new String[]{"Copy-on-Write (COW)",              "memory.cow_what"},                      // 9
        new String[]{"Lazy Loading",                     "memory.lazy_loading_what"},             // 10
        new String[]{"Swap Space",                       "memory.swap_what"},                     // 11
        new String[]{"RAM",                              "memory.ram_what"},                      // 12
        new String[]{"CLOCK Algorithm",                  "memory.clock_algo"},                    // 13
        new String[]{"Why TLB is Fast",                  "tlb.importance"},                       // 14
        new String[]{"Binary Conversion",                "address.hex_to_bin"},                   // 15
        new String[]{"Page Table Base Register (PTBR)", "page_table.ptbr"},                      // 16
        new String[]{"Process Isolation",                "page_table.isolation"},                 // 17
        new String[]{"VPN Split (DIR / TABLE)",          "page_table.vpn_split"},                 // 18
        new String[]{"Page Directory Range",             "page_table.dir_range"},                 // 19
        new String[]{"Allow Access",                     "permission_chamber.allow_access"},      // 20
        new String[]{"Protection Fault",                 "permission_chamber.protection_fault"},  // 21
        new String[]{"Lazy Allocation (fault type)",     "permission_chamber.lazy_allocation"},   // 22
        new String[]{"Lazy Loading (fault type)",        "permission_chamber.lazy_loading"},      // 23
        new String[]{"Swapped Out (fault type)",         "permission_chamber.swapped_out"},       // 24
        new String[]{"What is Lazy Allocation?",         "memory.lazy_allocation_what"},          // 25
        new String[]{"Why Lazy Allocation?",             "memory.lazy_allocation_why"},           // 26
        new String[]{"Zero Page Optimisation",           "memory.zero_page_readonly"},            // 27
        new String[]{"Page Index",                       "memory.page_index"},                    // 28
        new String[]{"Swap vs File-Backed Pages",        "memory.swap_vs_file"},                  // 29
        new String[]{"Why PFN Matters",                  "memory.pfn_why"}                        // 30
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
        PlayerMode mode = journeyTracker.getMode(player);
        boolean isAdventurer = (mode == PlayerMode.ADVENTURER);
        plugin.getLogger().info("[Guardian] " + player.getName() + " chose menu option '" + choice
            + "' | phase=" + journeyTracker.getPhase(player) + " | mode=" + mode);

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
            case "4":
                if (isAdventurer) {
                    journeyTracker.setMode(player, PlayerMode.LEARNER);
                    dialogueManager.speakInstant(player, "guardian.meta.guidance_enabled", null);
                } else {
                    journeyTracker.setMode(player, PlayerMode.ADVENTURER);
                    dialogueManager.speakInstant(player, "guardian.meta.guidance_disabled", null);
                }
                break;
            default:
                player.sendMessage(Component.text(config.getMessage("errors.guardian.type_1_to_4"), NamedTextColor.RED));
        }
    }

    private void handleConceptChoice(Player player, String input, List<Integer> indices) {
        int choice;
        try {
            choice = Integer.parseInt(input);
        } catch (NumberFormatException e) {
            player.sendMessage(Component.text(config.getMessage("errors.guardian.type_from_list"), NamedTextColor.RED));
            showConceptList(player);
            return;
        }
        if (choice < 1 || choice > indices.size()) {
            player.sendMessage(Component.text(config.getMessage("errors.guardian.type_range", "{max}", String.valueOf(indices.size())), NamedTextColor.RED));
            showConceptList(player);
            return;
        }
        String conceptName = CONCEPTS.get(indices.get(choice - 1))[0];
        String key = "explanations." + CONCEPTS.get(indices.get(choice - 1))[1];
        plugin.getLogger().info("[Guardian] " + player.getName() + " requested explanation: '" + conceptName
            + "' (key=" + key + ") | phase=" + journeyTracker.getPhase(player));
        String text = dialogueManager.getString(key, journeyTracker.getVars(player));
        if (text == null) {
            plugin.getLogger().warning("[Guardian] No explanation found at: " + key);
            dialogueManager.speakInstant(player, "guardian.meta.no_explanation", null);
        } else {
            player.sendMessage("§6[Kernel Guardian] §f" + text);
            ((com.oscity.OSCity) plugin).getAchievementManager().onExplanationRequested(player);
        }
    }

    private List<Integer> getConceptsForPhase(String phase) {
        switch (phase) {
            case "tlb_spawn":
            case "tlb_after_calculator":
                return Arrays.asList(0, 1, 2, 3, 4, 14, 30);
            case "calculator_from_tlb":
                return Arrays.asList(3, 4, 15);
            case "calculator_from_lazy_loading":
                return Arrays.asList(3, 4, 28);
            case "library_entrance":
            case "page_directory":
            case "correct_floor":
                return Arrays.asList(3, 4, 5, 6, 16, 17, 18, 19);
            case "permission_decision":
            case "page_fault_type":
                return Arrays.asList(6, 7, 8, 20, 21, 22, 23, 24);
            case "page_fault_corridor":
                return Arrays.asList(7, 9, 10, 11);
            case "lazy_alloc_decision":
            case "lazy_alloc_cow":
                return Arrays.asList(7, 9, 25, 26);
            case "cow_decision":
                return Arrays.asList(9, 27);
            case "lazy_loading_entered":
                return Arrays.asList(10, 28);
            case "disk_lazy_loading":
                return Arrays.asList(10, 11, 28, 29);
            case "disk_swap_retrieval":
                return Arrays.asList(10, 11, 29);
            default:
                if (phase.startsWith("ram_")) return Arrays.asList(9, 10, 12, 27, 30);
                if (phase.startsWith("swap_")) return Arrays.asList(11, 13);
                return Arrays.asList();
        }
    }

    private static final java.util.Set<String> NO_FREEZE_DIALOGUE_PREFIXES = new java.util.HashSet<>(Arrays.asList(
        "rooms.terminal.", "rooms.departure_gate.", "rooms.end_terminal."
    ));

    private void replayCurrentDialogue(Player player) {
        String phase = journeyTracker.getPhase(player);
        String dialoguePath = phaseToEntryDialogue(phase);
        if (dialoguePath != null && dialogueManager.hasPath(dialoguePath)) {
            boolean noFreeze = NO_FREEZE_DIALOGUE_PREFIXES.stream().anyMatch(dialoguePath::startsWith);
            if (noFreeze) {
                dialogueManager.speakDelayed(player, dialoguePath, journeyTracker.getVars(player));
            } else {
                dialogueManager.speak(player, dialoguePath, journeyTracker.getVars(player));
            }
        } else {
            dialogueManager.speakInstant(player, "guardian.meta.nothing_to_add", null);
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
        StringBuilder conceptNames = new StringBuilder();
        for (int i : indices) {
            if (conceptNames.length() > 0) conceptNames.append(", ");
            conceptNames.append(CONCEPTS.get(i)[0]);
        }
        plugin.getLogger().info("[Guardian] " + player.getName() + " opened concept list | phase=" + phase
            + " | available=[" + conceptNames + "]");

        player.sendMessage(Component.text(config.getMessage("ui.guardian_separator"), NamedTextColor.GOLD));
        player.sendMessage(Component.text(config.getMessage("ui.concepts.title"), NamedTextColor.AQUA, TextDecoration.BOLD));

        if (indices.isEmpty()) {
            player.sendMessage(Component.text(config.getMessage("ui.concepts.empty"), NamedTextColor.GRAY));
            player.sendMessage(Component.text(config.getMessage("ui.guardian_separator"), NamedTextColor.GOLD));
            return;
        }

        player.sendMessage(Component.text(""));
        for (int i = 0; i < indices.size(); i++) {
            String name = CONCEPTS.get(indices.get(i))[0];
            player.sendMessage(Component.text("  " + (i + 1) + ". ", NamedTextColor.GOLD)
                .append(Component.text(name, NamedTextColor.YELLOW)));
        }
        player.sendMessage(Component.text(""));
        player.sendMessage(Component.text(config.getMessage("ui.concepts.choose_prompt"), NamedTextColor.GRAY, TextDecoration.ITALIC));
        player.sendMessage(Component.text(config.getMessage("ui.guardian_separator"), NamedTextColor.GOLD));
        pendingConceptIndices.put(player.getUniqueId(), indices);
    }
}
