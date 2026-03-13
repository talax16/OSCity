package com.oscity.core;

import com.oscity.content.DialogueManager;
import com.oscity.mode.PlayerMode;
import com.oscity.gamification.ProgressTracker;
import com.oscity.journey.Journey;
import com.oscity.journey.JourneyManager;
import com.oscity.mechanics.CalculatorListener;
import com.oscity.mechanics.ChoiceButtonHandler;
import com.oscity.mechanics.JourneyMapManager;
import com.oscity.mechanics.DiskRoomManager;
import com.oscity.mechanics.PageTableManager;
import com.oscity.mechanics.RAMRoomManager;
import com.oscity.mechanics.SwapClockManager;
import com.oscity.mechanics.TLBRoomManager;
import com.oscity.quiz.QuizManager;
import com.oscity.session.JourneyTracker;
import com.oscity.world.LocationRegistry;
import com.oscity.world.RoomRegistry;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;

public class RoomChangeListener implements Listener {

    private final JavaPlugin plugin;
    private final KernelGuardian guardian;
    private final RoomRegistry roomRegistry;
    private final LocationRegistry locationRegistry;
    private final DialogueManager dialogueManager;
    private final JourneyTracker journeyTracker;
    private final CalculatorListener calculatorListener;
    private final ProgressTracker progressTracker;
    private final ChoiceButtonHandler choiceButtonHandler;
    private final SwapClockManager swapClockManager;
    private final TLBRoomManager tlbRoomManager;
    private final PageTableManager pageTableManager;
    private final RAMRoomManager ramRoomManager;
    private final DiskRoomManager diskRoomManager;
    private final JourneyMapManager journeyMapManager;
    private final QuizManager quizManager;

    private String currentRoomTitle = null;
    private boolean guardianSpawned = false;

    public RoomChangeListener(JavaPlugin plugin, KernelGuardian guardian,
                               RoomRegistry roomRegistry, LocationRegistry locationRegistry,
                               DialogueManager dialogueManager, JourneyTracker journeyTracker,
                               CalculatorListener calculatorListener,
                               ProgressTracker progressTracker,
                               ChoiceButtonHandler choiceButtonHandler,
                               SwapClockManager swapClockManager,
                               TLBRoomManager tlbRoomManager,
                               PageTableManager pageTableManager,
                               RAMRoomManager ramRoomManager,
                               DiskRoomManager diskRoomManager,
                               JourneyMapManager journeyMapManager,
                               QuizManager quizManager) {
        this.plugin = plugin;
        this.guardian = guardian;
        this.roomRegistry = roomRegistry;
        this.locationRegistry = locationRegistry;
        this.dialogueManager = dialogueManager;
        this.journeyTracker = journeyTracker;
        this.calculatorListener = calculatorListener;
        this.progressTracker = progressTracker;
        this.choiceButtonHandler = choiceButtonHandler;
        this.swapClockManager = swapClockManager;
        this.tlbRoomManager = tlbRoomManager;
        this.pageTableManager = pageTableManager;
        this.ramRoomManager = ramRoomManager;
        this.diskRoomManager = diskRoomManager;
        this.journeyMapManager = journeyMapManager;
        this.quizManager = quizManager;
    }

    // ── Player join ───────────────────────────────────────────────────────────

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        progressTracker.unloadPlayer(player.getUniqueId());
        quizManager.dropSession(player);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        progressTracker.loadPlayer(event.getPlayer().getUniqueId());
        Player player = event.getPlayer();
        
        // Force spawn at initial terminal (override Bukkit's saved location)
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Location initialSpawn = locationRegistry.get("initialSpawn");
            if (initialSpawn != null) {
                player.teleport(initialSpawn);
            }
        }, 5L);
        
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!guardianSpawned) {
                Location spawnLoc = locationRegistry.get("initialSpawn");
                if (spawnLoc != null) {
                    spawnLoc.add(3, 0, 0);
                    guardian.spawn(spawnLoc, "§6Kernel Guardian");
                    guardianSpawned = true;
                    plugin.getLogger().info("Kernel Guardian spawned on first player join");
                } else {
                    plugin.getLogger().severe("Cannot spawn guardian - initialSpawn not found!");
                    return;
                }
                Bukkit.getScheduler().runTaskLater(plugin, () ->
                    moveGuardianToPlayer(player), 20L);
            } else {
                moveGuardianToPlayer(player);
            }

            // Speak initial terminal dialogue
            journeyTracker.setPhase(player, "terminal_spawn");
            Bukkit.getScheduler().runTaskLater(plugin, () ->
                dialogueManager.speak(player, "rooms.terminal.initial_spawn",
                    journeyTracker.getVars(player)), 40L);
        }, 20L);
    }

    // ── Player move ───────────────────────────────────────────────────────────

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        if (event.getFrom().getBlockX() == event.getTo().getBlockX() &&
            event.getFrom().getBlockY() == event.getTo().getBlockY() &&
            event.getFrom().getBlockZ() == event.getTo().getBlockZ()) {
            return;
        }

        if (!guardian.isSpawned()) return;

        RoomRegistry.Room newRoom = roomRegistry.getRoomAt(event.getTo());
        String newTitle = newRoom != null ? newRoom.title : null;

        // Only act when entering a new room
        if (newTitle != null && !newTitle.equals(currentRoomTitle)) {
            currentRoomTitle = newTitle;
            Player player = event.getPlayer();

            // Move guardian to room NPC position
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (guardian.isSpawned()) {
                    if (newRoom.npcPosition != null) {
                        guardian.moveTo(newRoom.npcPosition);
                    } else {
                        guardian.moveTo(player.getLocation().clone().add(3, 0, 0));
                    }
                }
            }, 5L);

            // Trigger room entry dialogue (small delay so teleport settles)
            Bukkit.getScheduler().runTaskLater(plugin, () ->
                onRoomEntered(player, newTitle), 10L);
        }

        // Leaving a room
        if (newTitle == null && currentRoomTitle != null) {
            currentRoomTitle = null;
        }
    }

    // ── Dialogue helpers ──────────────────────────────────────────────────────

    /**
     * Speak dialogue only if the player is not in ADVENTURER mode.
     * Null mode (pre-journey) and LEARNER both pass through.
     */
    private void speakIfLearner(Player player, String path, Map<String, String> vars) {
        if (journeyTracker.getMode(player) != PlayerMode.ADVENTURER) {
            dialogueManager.speak(player, path, vars);
        }
    }

    // ── Room entry dialogue dispatch ──────────────────────────────────────────

    private void onRoomEntered(Player player, String roomTitle) {
        // Cancel any pending confirmations when leaving those rooms.
        // Path selection only cancels when entering a room other than Learner Mode.
        quizManager.cancelQuizConfirmation(player);
        if (!"Learner Mode".equals(roomTitle)) {
            choiceButtonHandler.cancelTerminalPathSelection(player);
        }

        String phase = journeyTracker.getPhase(player);
        Map<String, String> vars = journeyTracker.getVars(player);

        switch (roomTitle) {
            case "Initial Terminal":
                if ("terminal_spawn".equals(phase)) {
                    dialogueManager.speak(player, "rooms.terminal.initial_spawn", vars);
                }
                break;

            case "Learner Mode":
                if ("terminal_journey_chosen".equals(phase)) {
                    // Journey already chosen — chest is ready
                    dialogueManager.speak(player, "rooms.learner_mode.ready", vars);
                } else if (!choiceButtonHandler.isTerminalPathPending(player)) {
                    // Only greet and start selection if not already mid-selection
                    if (journeyTracker.hasCompletedQuiz(player)) {
                        dialogueManager.speak(player, "rooms.learner_mode.quiz_done", vars);
                    } else {
                        dialogueManager.speak(player, "rooms.learner_mode.no_quiz_warning", vars);
                    }
                    Bukkit.getScheduler().runTaskLater(plugin, () ->
                        choiceButtonHandler.startTerminalPathSelection(player), 80L);
                }
                break;

            case "Adventurer Mode":
                journeyTracker.setPhase(player, "quiz_active");
                quizManager.promptQuizStart(player);
                break;

            case "TLB Room":
                handleTLBEntry(player, phase, vars);   // uses speakIfLearner internally
                break;

            case "Calculator Room":
                handleCalculatorEntry(player, phase, vars);
                break;

            case "Page Table Library - Page Directory":
                if (!"page_directory".equals(phase) && !"correct_floor".equals(phase)) {
                    speakIfLearner(player, "rooms.page_table_library.entrance", vars);
                    Bukkit.getScheduler().runTaskLater(plugin, () ->
                        speakIfLearner(player, "rooms.page_table_library.before_entering", vars), 60L);
                }
                journeyTracker.setPhase(player, "page_directory");
                speakIfLearner(player, "rooms.page_table_library.page_directory", vars);
                choiceButtonHandler.closeDoor("tlbToPt");
                break;

            case "Page Table Library - Page Table 1":
            case "Page Table Library - Page Table 2":
            case "Page Table Library - Page Table 3":
                handlePageTableFloorEntry(player, roomTitle, phase, vars);
                break;

            case "Permission Chamber":
                journeyTracker.setPhase(player, "permission_decision");
                speakIfLearner(player, "rooms.permission_chamber.at_spawn", vars);
                choiceButtonHandler.initPermissionChamberSigns();
                choiceButtonHandler.closeDoor("toPageFaultCorridor");
                
                // Update journey map with PTE info (PTE map stays in inventory)
                journeyMapManager.updateMapAfterPTE(player);
                break;

            case "Page Fault Corridor":
                journeyTracker.setPhase(player, "page_fault_corridor");
                speakIfLearner(player, "rooms.page_fault_corridor.at_enter", vars);
                choiceButtonHandler.closeDoor("toPageFaultCorridor");
                break;

            case "Lazy Allocation Room":
                handleLazyAllocEntry(player, phase, vars);
                break;

            case "COW Room":
                journeyTracker.setPhase(player, "cow_decision");
                speakIfLearner(player, "rooms.cow_room.at_spawn", vars);
                choiceButtonHandler.clearCowToRamSign();
                break;

            case "Lazy Loading Room":
                if ("calculator_from_lazy_loading".equals(phase)) {
                    // Returning from Calculator Room — page index already calculated
                    journeyTracker.setPhase(player, "lazy_loading_returned");
                    choiceButtonHandler.setLoadingSign("Go to Disk", "", "", "");
                    // Page index was set by calculator, just show dialogue
                    speakIfLearner(player, "rooms.lazy_loading_room.after_calculator", vars);
                } else {
                    journeyTracker.setPhase(player, "lazy_loading_entered");
                    speakIfLearner(player, "rooms.lazy_loading_room.at_enter", vars);
                    choiceButtonHandler.setLoadingSign("Go to Calculator", "Room", "", "");
                    // Reveal page size on map
                    journeyTracker.setVar(player, "pageSize", "0x10");
                    journeyMapManager.updateMap(player);
                }
                break;

            case "Disk Room":
                handleDiskEntry(player, phase, vars);
                break;

            case "RAM Room":
                handleRAMEntry(player, phase, vars);
                break;

            case "End Terminal":
                journeyTracker.setPhase(player, "end_terminal");
                dialogueManager.speak(player, "rooms.end_terminal.arrival", vars);
                break;

            case "Swap District":
                journeyTracker.setPhase(player, "swap_entered");
                speakIfLearner(player, "rooms.swap_district.at_spawn", vars);
                // Pre-set victim frame (pfn) and eviction slot so swap_district dialogue works
                Journey swapEntry = journeyTracker.getJourney(player);
                String pfnCow = journeyTracker.getVar(player, "pfnCow");
                JourneyManager.swapEntryVarUpdates(swapEntry, pfnCow)
                    .forEach((k, v) -> journeyTracker.setVar(player, k, v));
                // Start clock algorithm (lights torches, sets signs) after vars are applied
                swapClockManager.startClock(player);
                break;
        }
    }

    // ── Room-specific entry handlers ──────────────────────────────────────────

    private void handleTLBEntry(Player player, String phase, Map<String, String> vars) {
        switch (phase) {
            case "terminal_journey_chosen":
            case "terminal_spawn":
                journeyTracker.setPhase(player, "tlb_spawn");
                speakIfLearner(player, "rooms.tlb_room.at_spawn", vars);
                tlbRoomManager.populate(player);
                break;
            case "calculator_from_tlb":
                journeyTracker.setPhase(player, "tlb_after_calculator");
                speakIfLearner(player, "rooms.tlb_room.after_calculator", vars);
                break;
            case "tlb_after_calculator":
                speakIfLearner(player, "rooms.tlb_room.after_calculator", vars);
                break;
        }
    }

    private void handleCalculatorEntry(Player player, String phase, Map<String, String> vars) {
        String newPhase = null;
        switch (phase) {
            case "tlb_spawn":
            case "tlb_after_calculator":
                newPhase = "calculator_from_tlb";
                journeyTracker.setPhase(player, newPhase);
                speakIfLearner(player, "rooms.calculator_room.from_tlb_spawn", vars);
                break;
            case "calculator_from_tlb":
                speakIfLearner(player, "rooms.calculator_room.from_tlb_spawn", vars);
                break;
            case "lazy_loading_entered":
                newPhase = "calculator_from_lazy_loading";
                journeyTracker.setPhase(player, newPhase);
                speakIfLearner(player, "rooms.calculator_room.from_lazy_loading_spawn", vars);
                break;
        }
        if (newPhase != null || "calculator_from_tlb".equals(phase)) {
            final String p = (newPhase != null) ? newPhase : phase;
            Bukkit.getScheduler().runTaskLater(plugin,
                () -> calculatorListener.onCalculatorRoomEntered(player, p), 15L);
        }
    }

    private void handlePageTableFloorEntry(Player player, String roomTitle, String phase, Map<String, String> vars) {
        String floorNum = roomTitle.substring(roomTitle.lastIndexOf(' ') + 1); // "1", "2", or "3"
        String expectedFloor = journeyTracker.getVar(player, "expectedFloor");

        journeyTracker.setVar(player, "floor", floorNum);

        // Populate the 4 chests on this floor with PTE maps
        try {
            int floor = Integer.parseInt(floorNum);
            pageTableManager.populateFloor(player, floor);
        } catch (NumberFormatException e) {
            plugin.getLogger().warning("[RoomChangeListener] Invalid floor number: " + floorNum);
        }

        if (!expectedFloor.equals("?") && floorNum.equals(expectedFloor)) {
            journeyTracker.setPhase(player, "correct_floor");
            speakIfLearner(player, "rooms.page_table_library.correct_floor", vars);
        } else if (!expectedFloor.equals("?")) {
            journeyTracker.setPhase(player, "wrong_floor");
            speakIfLearner(player, "rooms.page_table_library.wrong_floor", vars);
        } else {
            journeyTracker.setPhase(player, "correct_floor");
            speakIfLearner(player, "rooms.page_table_library.correct_floor", vars);
        }
    }

    private void handleLazyAllocEntry(Player player, String phase, Map<String, String> vars) {
        if ("page_fault_corridor".equals(phase) || "lazy_alloc_decision".equals(phase)) {
            journeyTracker.setPhase(player, "lazy_alloc_decision");
            speakIfLearner(player, "rooms.lazy_allocation_room.at_enter", vars);
            choiceButtonHandler.setLazyAllocDecisionSigns();
        } else if ("ram_after_lazy_alloc".equals(phase) || "ram_continue_to_lazy_alloc".equals(phase)) {
            JourneyManager.lazyAllocSecondVisitVarUpdates(journeyTracker.getJourney(player))
                .forEach((k, v) -> journeyTracker.setVar(player, k, v));
            journeyTracker.setVar(player, "pteReadOnly", "1");
            pageTableManager.updatePteMap(player);
            journeyMapManager.updateMap(player);
            journeyTracker.setPhase(player, "lazy_alloc_cow");
            speakIfLearner(player, "rooms.lazy_allocation_room.second_visit", vars);
            choiceButtonHandler.setLazyAllocCowSigns();
        }
    }

    private void handleDiskEntry(Player player, String phase, Map<String, String> vars) {
        speakIfLearner(player, "rooms.disk_room.at_spawn", vars);
        Journey journey = journeyTracker.getJourney(player);
        if (journey == null) return;

        diskRoomManager.populateDiskChests(player);

        String diskPhase = JourneyManager.diskPhase(journey);
        String diskDialogue = JourneyManager.diskPromptDialogue(journey);
        if (diskPhase != null) {
            journeyTracker.setPhase(player, diskPhase);
            Bukkit.getScheduler().runTaskLater(plugin, () ->
                speakIfLearner(player, diskDialogue, journeyTracker.getVars(player)), 40L);
        }
    }

    private void handleRAMEntry(Player player, String phase, Map<String, String> vars) {
        // Don't speak at_spawn dialogue for phases that have their own dialogue
        if (!"swap_after_eviction".equals(phase) && !"swap_lazy_alloc".equals(phase)
                && !"ram_after_cow".equals(phase)) {
            speakIfLearner(player, "rooms.ram_room.at_spawn", vars);
        }

        // Update frame signs based on journey and phase
        ramRoomManager.updateFrameSigns(player);

        // For Pure COW: place book in frame 0x2 chest after COW allocation
        // Note: Frame 0x2 uses chest3 in config (chest1=frame0, chest2=frame1, chest3=frame2)
        Journey currentJourney = journeyTracker.getJourney(player);
        plugin.getLogger().info("[RoomChange] RAM entry: phase=" + phase + ", journey=" + currentJourney);
        if ("ram_after_cow".equals(phase) && currentJourney == Journey.PURE_COW) {
            plugin.getLogger().info("[RoomChange] Placing book in frame 0x2 chest (chest3) for Pure COW");
            ramRoomManager.placeBookInFrameChest(player, 3);  // Use chest3 for frame 0x2
        }

        switch (phase) {
            case "ram_allow_access":
                choiceButtonHandler.setRamMixSign("CONFIRM", "PROCESS", "MAPPED", "");
                Bukkit.getScheduler().runTaskLater(plugin, () ->
                    speakIfLearner(player, "rooms.ram_room.tlb_miss_no_fault", journeyTracker.getVars(player)), 40L);
                break;
            case "ram_after_lazy_alloc":
                choiceButtonHandler.setRamMixSign("RETRY", "INSTRUCTION", "", "");
                Bukkit.getScheduler().runTaskLater(plugin, () ->
                    speakIfLearner(player, "rooms.ram_room.after_lazy_alloc_first", journeyTracker.getVars(player)), 40L);
                break;
            case "ram_after_cow":
                Journey cowJourney = journeyTracker.getJourney(player);
                if (cowJourney == Journey.LAZY_ALLOCATION) {
                    choiceButtonHandler.setRamMixSign("CONTINUE", "", "", "");
                } else {
                    choiceButtonHandler.setRamMixSign("RETRY", "INSTRUCTION", "", "");
                }
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (cowJourney == Journey.PURE_COW) {
                        speakIfLearner(player, "rooms.ram_room.after_cow_pure", journeyTracker.getVars(player));
                    } else if (cowJourney == Journey.LAZY_ALLOCATION) {
                        speakIfLearner(player, "rooms.ram_room.ram_full_need_swap", journeyTracker.getVars(player));
                    }
                }, 40L);
                break;
            case "disk_lazy_loading":
                choiceButtonHandler.setRamMixSign("Go to Swap", "District", "", "");
                Bukkit.getScheduler().runTaskLater(plugin, () ->
                    speakIfLearner(player, "rooms.ram_room.ram_full_need_swap", journeyTracker.getVars(player)), 40L);
                break;
            case "disk_swap_retrieval":
                choiceButtonHandler.setRamMixSign("RETRY", "INSTRUCTION", "", "");
                Bukkit.getScheduler().runTaskLater(plugin, () ->
                    speakIfLearner(player, "rooms.ram_room.from_disk_swap_out", journeyTracker.getVars(player)), 40L);
                break;
            case "swap_entered":
                Journey swapJ = journeyTracker.getJourney(player);
                String swapPhase = JourneyManager.phaseAfterSwapInRam(swapJ);
                String swapDialogue = JourneyManager.dialogueAfterSwapInRam(swapJ);
                if (swapPhase != null) {
                    journeyTracker.setPhase(player, swapPhase);
                    choiceButtonHandler.setRamMixSign("RETRY", "INSTRUCTION", "", "");
                    Bukkit.getScheduler().runTaskLater(plugin, () ->
                        speakIfLearner(player, swapDialogue, journeyTracker.getVars(player)), 40L);
                }
                break;
            case "swap_after_eviction":
                Journey swapJourney = journeyTracker.getJourney(player);
                plugin.getLogger().info("[RoomChange] swap_after_eviction: journey=" + swapJourney);

                if (swapJourney == Journey.LAZY_LOADING) {
                    journeyTracker.setPhase(player, "swap_lazy_loading");
                    choiceButtonHandler.setRamMixSign("RETRY", "INSTRUCTION", "", "");
                    Bukkit.getScheduler().runTaskLater(plugin, () ->
                        speakIfLearner(player, "rooms.ram_room.after_swap_for_lazy_loading",
                            journeyTracker.getVars(player)), 40L);
                } else if (swapJourney == Journey.LAZY_ALLOCATION) {
                    plugin.getLogger().info("[RoomChange] LAZY_ALLOCATION after swap: placing book in chest7 (frame 0x6)");
                    ramRoomManager.placeBookInFrameChest(player, 7);
                    journeyTracker.setPhase(player, "swap_lazy_alloc");
                    ramRoomManager.updateZeroFrameSignOnly(player);
                    plugin.getLogger().info("[RoomChange] LAZY_ALLOCATION: Phase changed to swap_lazy_alloc, zero frame sign updated");
                    choiceButtonHandler.setRamMixSign("RETRY", "INSTRUCTION", "", "");
                    plugin.getLogger().info("[RoomChange] LAZY_ALLOCATION: Speaking after_swap_for_lazy_alloc dialogue");
                    Bukkit.getScheduler().runTaskLater(plugin, () ->
                        speakIfLearner(player, "rooms.ram_room.after_swap_for_lazy_alloc",
                            journeyTracker.getVars(player)), 40L);
                } else {
                    choiceButtonHandler.setRamMixSign("PUT BOOK", "IN CHEST", "", "");
                }
                break;
        }
    }

    // ── Guardian movement helper ──────────────────────────────────────────────

    private void moveGuardianToPlayer(Player player) {
        Location loc = player.getLocation();
        RoomRegistry.Room room = roomRegistry.getRoomAt(loc);
        if (room != null) {
            currentRoomTitle = room.title;
            if (guardian.isSpawned()) {
                guardian.moveTo(room.npcPosition != null
                    ? room.npcPosition
                    : loc.clone().add(3, 0, 0));
            }
        }
    }
}
