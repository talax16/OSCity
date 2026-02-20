package com.oscity.core;

import com.oscity.content.DialogueManager;
import com.oscity.session.Journey;
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
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;

public class RoomChangeListener implements Listener {

    private final JavaPlugin plugin;
    private final KernelGuardian guardian;
    private final RoomRegistry roomRegistry;
    private final LocationRegistry locationRegistry;
    private final DialogueManager dialogueManager;
    private final JourneyTracker journeyTracker;

    private String currentRoomTitle = null;
    private boolean guardianSpawned = false;

    public RoomChangeListener(JavaPlugin plugin, KernelGuardian guardian,
                               RoomRegistry roomRegistry, LocationRegistry locationRegistry,
                               DialogueManager dialogueManager, JourneyTracker journeyTracker) {
        this.plugin = plugin;
        this.guardian = guardian;
        this.roomRegistry = roomRegistry;
        this.locationRegistry = locationRegistry;
        this.dialogueManager = dialogueManager;
        this.journeyTracker = journeyTracker;
    }

    // ── Player join ───────────────────────────────────────────────────────────

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
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
                    moveGuardianToPlayer(event.getPlayer()), 20L);
            } else {
                moveGuardianToPlayer(event.getPlayer());
            }

            // Speak initial terminal dialogue
            Player player = event.getPlayer();
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

    // ── Room entry dialogue dispatch ──────────────────────────────────────────

    private void onRoomEntered(Player player, String roomTitle) {
        String phase = journeyTracker.getPhase(player);
        Map<String, String> vars = journeyTracker.getVars(player);

        switch (roomTitle) {
            case "Terminal":
                dialogueManager.speak(player, "rooms.terminal.initial_spawn", vars);
                journeyTracker.setPhase(player, "terminal_spawn");
                break;

            case "Learner Mode":
                dialogueManager.speak(player, "rooms.terminal.enter_learner", vars);
                break;

            case "Adventurer Mode":
                dialogueManager.speak(player, "rooms.terminal.enter_adventurer", vars);
                // Put player into journey selection mode
                journeyTracker.setPhase(player, "adventurer_select");
                break;

            case "TLB Room":
                handleTLBEntry(player, phase, vars);
                break;

            case "Calculator Room":
                handleCalculatorEntry(player, phase, vars);
                break;

            case "Page Table Library - Page Directory":
                if (!"page_directory".equals(phase) && !"correct_floor".equals(phase)) {
                    dialogueManager.speak(player, "rooms.page_table_library.entrance", vars);
                    Bukkit.getScheduler().runTaskLater(plugin, () ->
                        dialogueManager.speak(player, "rooms.page_table_library.before_entering", vars), 60L);
                }
                journeyTracker.setPhase(player, "page_directory");
                dialogueManager.speak(player, "rooms.page_table_library.page_directory", vars);
                break;

            case "Page Table Library - Page Table 1":
            case "Page Table Library - Page Table 2":
            case "Page Table Library - Page Table 3":
                handlePageTableFloorEntry(player, roomTitle, phase, vars);
                break;

            case "Permission Chamber":
                journeyTracker.setPhase(player, "permission_decision");
                dialogueManager.speak(player, "rooms.permission_chamber.at_spawn", vars);
                break;

            case "Page Fault Corridor":
                journeyTracker.setPhase(player, "page_fault_corridor");
                dialogueManager.speak(player, "rooms.page_fault_corridor.at_enter", vars);
                break;

            case "Lazy Allocation Room":
                handleLazyAllocEntry(player, phase, vars);
                break;

            case "COW Room":
                journeyTracker.setPhase(player, "cow_decision");
                dialogueManager.speak(player, "rooms.cow_room.at_spawn", vars);
                break;

            case "Lazy Loading Room":
                journeyTracker.setPhase(player, "lazy_loading_entered");
                dialogueManager.speak(player, "rooms.lazy_loading_room.at_enter", vars);
                break;

            case "Disk Room":
                handleDiskEntry(player, phase, vars);
                break;

            case "RAM Room":
                handleRAMEntry(player, phase, vars);
                break;

            case "Swap District":
                journeyTracker.setPhase(player, "swap_entered");
                dialogueManager.speak(player, "rooms.swap_district.at_spawn", vars);
                break;
        }
    }

    // ── Room-specific entry handlers ──────────────────────────────────────────

    private void handleTLBEntry(Player player, String phase, Map<String, String> vars) {
        switch (phase) {
            case "terminal_journey_chosen":
            case "terminal_spawn":
                journeyTracker.setPhase(player, "tlb_spawn");
                dialogueManager.speak(player, "rooms.tlb_room.at_spawn", vars);
                break;
            case "calculator_from_tlb":
                journeyTracker.setPhase(player, "tlb_after_calculator");
                dialogueManager.speak(player, "rooms.tlb_room.after_calculator", vars);
                break;
            // Already in TLB progression — no repeat dialogue
        }
    }

    private void handleCalculatorEntry(Player player, String phase, Map<String, String> vars) {
        switch (phase) {
            case "tlb_spawn":
            case "tlb_after_calculator":
                journeyTracker.setPhase(player, "calculator_from_tlb");
                dialogueManager.speak(player, "rooms.calculator_room.from_tlb_spawn", vars);
                break;
            case "lazy_loading_entered":
                journeyTracker.setPhase(player, "calculator_from_lazy_loading");
                dialogueManager.speak(player, "rooms.calculator_room.from_lazy_loading_spawn", vars);
                break;
        }
    }

    private void handlePageTableFloorEntry(Player player, String roomTitle, String phase, Map<String, String> vars) {
        String floorNum = roomTitle.substring(roomTitle.lastIndexOf(' ') + 1); // "1", "2", or "3"
        String expectedFloor = journeyTracker.getVar(player, "expectedFloor");

        journeyTracker.setVar(player, "floor", floorNum);

        if (!expectedFloor.equals("?") && floorNum.equals(expectedFloor)) {
            journeyTracker.setPhase(player, "correct_floor");
            dialogueManager.speak(player, "rooms.page_table_library.correct_floor", vars);
        } else if (!expectedFloor.equals("?")) {
            journeyTracker.setPhase(player, "wrong_floor");
            dialogueManager.speak(player, "rooms.page_table_library.wrong_floor", vars);
        } else {
            // expectedFloor not set yet — show neutral floor dialogue
            journeyTracker.setPhase(player, "correct_floor");
            dialogueManager.speak(player, "rooms.page_table_library.correct_floor", vars);
        }
    }

    private void handleLazyAllocEntry(Player player, String phase, Map<String, String> vars) {
        if ("page_fault_corridor".equals(phase) || "lazy_alloc_decision".equals(phase)) {
            journeyTracker.setPhase(player, "lazy_alloc_decision");
            dialogueManager.speak(player, "rooms.lazy_allocation_room.at_enter", vars);
        } else if ("ram_after_lazy_alloc".equals(phase)) {
            journeyTracker.setPhase(player, "lazy_alloc_cow");
            dialogueManager.speak(player, "rooms.lazy_allocation_room.second_visit", vars);
        }
    }

    private void handleDiskEntry(Player player, String phase, Map<String, String> vars) {
        dialogueManager.speak(player, "rooms.disk_room.at_spawn", vars);
        Journey journey = journeyTracker.getJourney(player);
        if (journey == null) return;

        if (journey == Journey.LAZY_LOADING) {
            journeyTracker.setPhase(player, "disk_lazy_loading");
            Bukkit.getScheduler().runTaskLater(plugin, () ->
                dialogueManager.speak(player, "rooms.disk_room.lazy_loading_prompt", vars), 40L);
        } else if (journey == Journey.SWAPPED_OUT) {
            journeyTracker.setPhase(player, "disk_swap_retrieval");
            Bukkit.getScheduler().runTaskLater(plugin, () ->
                dialogueManager.speak(player, "rooms.disk_room.swap_retrieval_prompt", vars), 40L);
        }
    }

    private void handleRAMEntry(Player player, String phase, Map<String, String> vars) {
        dialogueManager.speak(player, "rooms.ram_room.at_spawn", vars);

        switch (phase) {
            case "ram_allow_access":
                Bukkit.getScheduler().runTaskLater(plugin, () ->
                    dialogueManager.speak(player, "rooms.ram_room.tlb_miss_no_fault", vars), 40L);
                break;
            case "ram_after_lazy_alloc":
                Bukkit.getScheduler().runTaskLater(plugin, () ->
                    dialogueManager.speak(player, "rooms.ram_room.after_lazy_alloc_first", vars), 40L);
                break;
            case "ram_after_cow":
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    Journey j = journeyTracker.getJourney(player);
                    if (j == Journey.TLB_MISS_COW) {
                        dialogueManager.speak(player, "rooms.ram_room.after_cow_pure", vars);
                    } else if (j == Journey.LAZY_ALLOCATION) {
                        dialogueManager.speak(player, "rooms.ram_room.ram_full_need_swap", vars);
                    }
                }, 40L);
                break;
            case "disk_lazy_loading":
            case "disk_swap_retrieval":
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    Journey j = journeyTracker.getJourney(player);
                    if (j == Journey.SWAPPED_OUT) {
                        dialogueManager.speak(player, "rooms.ram_room.from_disk_swap_out", vars);
                    } else {
                        dialogueManager.speak(player, "rooms.ram_room.ram_full_need_swap", vars);
                    }
                }, 40L);
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
