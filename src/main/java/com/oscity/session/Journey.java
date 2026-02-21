package com.oscity.session;

/**
 * The seven learning journeys through OSCity.
 * Each encodes which answer is correct at the TLB and Permission Chamber
 * so ChoiceButtonHandler can validate player choices without a hardcoded switch.
 *
 * permissionAnswer  – correct button key at Permission Chamber round 1
 * pageFaultType     – correct button key at Permission Chamber round 2 (null if not a page-fault journey)
 */
public enum Journey {

    // Ordered easiest → hardest (number = unlock order)
    LUCKY(
        "Lucky",
        1,
        true,          // TLB hit
        "allow_access",
        null
    ),
    TLB_MISS_ALLOW(
        "TLB Miss - No Fault",
        2,
        false,
        "allow_access",
        null
    ),
    PERMISSION_VIOLATION(
        "Permission Violation",
        3,
        false,
        "segfault",
        null
    ),
    SWAPPED_OUT(
        "Swapped-Out Page",
        4,
        false,
        "page_fault",
        "swapped_out"
    ),
    PURE_COW(
        "Pure COW",
        5,
        false,
        "protection_fault",
        null
    ),
    LAZY_LOADING(
        "Lazy Loading",
        6,
        false,
        "page_fault",
        "lazy_loading"
    ),
    LAZY_ALLOCATION(
        "Lazy Allocation",
        7,
        false,
        "page_fault",
        "lazy_allocation"
    );

    public final String displayName;
    public final int number;
    public final boolean isTlbHit;
    public final String permissionAnswer;   // correct choice at Permission Chamber
    public final String pageFaultType;      // correct page-fault subtype (or null)

    Journey(String displayName, int number, boolean isTlbHit,
            String permissionAnswer, String pageFaultType) {
        this.displayName = displayName;
        this.number = number;
        this.isTlbHit = isTlbHit;
        this.permissionAnswer = permissionAnswer;
        this.pageFaultType = pageFaultType;
    }

    public static Journey fromNumber(int n) {
        for (Journey j : values()) {
            if (j.number == n) return j;
        }
        return null;
    }

    public static Journey random() {
        Journey[] vals = values();
        return vals[(int) (Math.random() * vals.length)];
    }

    /** Returns true if this journey eventually passes through the COW room. */
    public boolean involvesCow() {
        return this == PURE_COW || this == LAZY_ALLOCATION;
    }

    /** Returns true if this journey requires visiting the Swap District. */
    public boolean involvesSwap() {
        return this == LAZY_ALLOCATION || this == LAZY_LOADING;
    }

    /**
     * Populate all journey-specific placeholder variables into the player's vars map.
     * Called once when the player selects this journey.
     *
     * Key vars set:
     *   va, vaBin, process, operation, instruction
     *   vpn (binary), vpnHex, offset (binary), offsetHex
     *   pfn         – primary/initial PFN used throughout most dialogue
     *   pfnCow      – new private frame PFN after COW (journeys 5 & 7 only)
     *   slot        – swap retrieval slot (journey 4 only)
     *   file        – file name (journey 6 only)
     *   pageIndex   – page index (journey 6 only)
     *   diskBlock   – disk block letter (journey 6 only)
     *   expectedFloor – page-table floor to visit ("?" for TLB-hit journey)
     *   hex, optA/B/C – calculator hex→binary quiz vars (visit 1)
     */
    public void initVars(java.util.Map<String, String> vars) {
        switch (this) {

            case LUCKY:
                vars.put("va", "0x2A");
                vars.put("vaBin", "0010 1010");
                vars.put("process", "Process 1");
                vars.put("operation", "read");
                vars.put("instruction", "read 0x2A");
                vars.put("vpn", "0010");
                vars.put("vpnHex", "0x2");
                vars.put("offset", "1010");
                vars.put("offsetHex", "0xA");
                vars.put("pfn", "0x3");
                vars.put("expectedFloor", "?");
                vars.put("hex", "0x2A");
                vars.put("optA", "0010 1010");
                vars.put("optB", "0010 0101");
                vars.put("optC", "0101 0010");
                break;

            case TLB_MISS_ALLOW:
                vars.put("va", "0x5C");
                vars.put("vaBin", "0101 1100");
                vars.put("process", "Process 2");
                vars.put("operation", "read");
                vars.put("instruction", "read 0x5C");
                vars.put("vpn", "0101");
                vars.put("vpnHex", "0x5");
                vars.put("offset", "1100");
                vars.put("offsetHex", "0xC");
                vars.put("pfn", "0x3");
                vars.put("expectedFloor", "1");
                vars.put("hex", "0x5C");
                vars.put("optA", "0101 1100");
                vars.put("optB", "0101 0011");
                vars.put("optC", "1100 0101");
                break;

            case PERMISSION_VIOLATION:
                vars.put("va", "0xFF");
                vars.put("vaBin", "1111 1111");
                vars.put("process", "Process 3");
                vars.put("operation", "read");
                vars.put("instruction", "read 0xFF");
                vars.put("vpn", "1111");
                vars.put("vpnHex", "0xF");
                vars.put("offset", "1111");
                vars.put("offsetHex", "0xF");
                vars.put("pfn", "N/A");
                vars.put("expectedFloor", "3");
                vars.put("hex", "0xFF");
                vars.put("optA", "1111 1111");
                vars.put("optB", "1111 0000");
                vars.put("optC", "0000 1111");
                break;

            case SWAPPED_OUT:
                vars.put("va", "0x7B");
                vars.put("vaBin", "0111 1011");
                vars.put("process", "Process 4");
                vars.put("operation", "read");
                vars.put("instruction", "read 0x7B");
                vars.put("vpn", "0111");
                vars.put("vpnHex", "0x7");
                vars.put("offset", "1011");
                vars.put("offsetHex", "0xB");
                vars.put("pfn", "0x2");          // PFN after loading from swap
                vars.put("slot", "0");            // Swap retrieval slot
                vars.put("expectedFloor", "1");
                vars.put("hex", "0x7B");
                vars.put("optA", "0111 1011");
                vars.put("optB", "0111 1101");
                vars.put("optC", "1011 0111");
                break;

            case PURE_COW:
                // VA=0x64: VPN=0110 (0x6), OFFSET=0100 (0x4), DIR=01, TABLE=10 → Floor 1
                vars.put("va", "0x64");
                vars.put("vaBin", "0110 0100");
                vars.put("process", "Process 5");
                vars.put("operation", "write");
                vars.put("instruction", "write 0x64 hello");
                vars.put("vpn", "0110");
                vars.put("vpnHex", "0x6");
                vars.put("offset", "0100");
                vars.put("offsetHex", "0x4");
                vars.put("pfn", "0x9");           // Zero frame (initial, shared)
                vars.put("pfnCow", "0x2");        // New private frame after COW
                vars.put("expectedFloor", "1");   // DIR=01 → Floor 1
                vars.put("hex", "0x64");
                vars.put("optA", "0110 0100");
                vars.put("optB", "0110 0010");
                vars.put("optC", "0100 0110");
                break;

            case LAZY_LOADING:
                vars.put("va", "0x8E");
                vars.put("vaBin", "1000 1110");
                vars.put("process", "Process 6");
                vars.put("operation", "load");
                vars.put("instruction", "load treasure_map.bin 0x8E");
                vars.put("vpn", "1000");
                vars.put("vpnHex", "0x8");
                vars.put("offset", "1110");
                vars.put("offsetHex", "0xE");
                vars.put("pfn", "0x5");           // Final PFN after swap + disk load
                vars.put("file", "treasure_map.bin");
                vars.put("pageIndex", "0");
                vars.put("diskBlock", "C");
                vars.put("expectedFloor", "2");
                vars.put("hex", "0x8E");
                vars.put("optA", "1000 1110");
                vars.put("optB", "1000 1101");
                vars.put("optC", "0111 1110");
                // Page-index quiz vars (calculator visit 2)
                vars.put("optA_pg", "0");
                vars.put("optB_pg", "1");
                vars.put("optC_pg", "14");
                break;

            case LAZY_ALLOCATION:
                vars.put("va", "0x45");
                vars.put("vaBin", "0100 0101");
                vars.put("process", "Process 7");
                vars.put("operation", "read");
                vars.put("instruction", "read 0x45");
                vars.put("vpn", "0100");
                vars.put("vpnHex", "0x4");
                vars.put("offset", "0101");
                vars.put("offsetHex", "0x5");
                vars.put("pfn", "0x9");           // Zero frame (phase 1)
                vars.put("pfnCow", "0x6");        // New private frame (phase 2, after COW+swap)
                vars.put("expectedFloor", "1");
                vars.put("hex", "0x45");
                vars.put("optA", "0100 0101");
                vars.put("optB", "0100 0100");
                vars.put("optC", "0101 0100");
                break;
        }
    }
}
