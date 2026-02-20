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

    LUCKY(
        "Lucky",
        1,
        true,          // TLB hit
        "allow_access",
        null
    ),
    TLB_MISS_ALLOW(
        "TLB Miss - Allow Access",
        2,
        false,
        "allow_access",
        null
    ),
    TLB_MISS_SEGFAULT(
        "TLB Miss - Segmentation Fault",
        3,
        false,
        "segfault",
        null
    ),
    TLB_MISS_COW(
        "TLB Miss - Protection Fault (COW)",
        4,
        false,
        "protection_fault",
        null
    ),
    LAZY_ALLOCATION(
        "Lazy Allocation",
        5,
        false,
        "page_fault",
        "lazy_allocation"
    ),
    LAZY_LOADING(
        "Lazy Loading",
        6,
        false,
        "page_fault",
        "lazy_loading"
    ),
    SWAPPED_OUT(
        "Swapped-Out Page",
        7,
        false,
        "page_fault",
        "swapped_out"
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
        return this == TLB_MISS_COW || this == LAZY_ALLOCATION;
    }

    /** Returns true if this journey requires visiting the Swap District. */
    public boolean involvesSwap() {
        return this == LAZY_ALLOCATION || this == LAZY_LOADING;
    }
}
