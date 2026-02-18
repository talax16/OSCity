package com.oscity.mechanics;

import com.oscity.session.SessionManager;
import com.oscity.persistence.SQLiteStudyDatabase;
import org.bukkit.entity.Player;

public class HintSystem {

    private SessionManager sessionManager;

    public HintSystem(SessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    /**
     * Called when player clicks "I'm lost" (Learner mode only)
     */
    public void showHint(Player player, String currentRoom) {
        String hint = getHintForRoom(currentRoom);
        player.sendMessage("Hint: " + hint);

        sessionManager.recordHintUsed();
        SQLiteStudyDatabase.logHintUsed(sessionManager.getSessionId(), currentRoom);
    }

    private String getHintForRoom(String room) {
        switch (room) {
            case "TLB_TOWER":
                return "The offset is the same in VA and PA";
            case "PERMISSION_CHAMBER":
                return "Check the PTE for the present bit";
            default:
                return "Look around for clues";
        }
    }
}
