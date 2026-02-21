package com.oscity.journey.scenarios;

import com.oscity.journey.JourneyScenario;
import java.util.Map;

public class SwappedOutPage implements JourneyScenario {

    @Override public String getDisplayName()      { return "Swapped-Out Page"; }
    @Override public int    getNumber()           { return 4; }
    @Override public boolean isTlbHit()           { return false; }
    @Override public String getPermissionAnswer() { return "page_fault"; }
    @Override public String getPageFaultType()    { return "swapped_out"; }
    @Override public boolean involvesCow()        { return false; }
    @Override public boolean involvesSwap()       { return false; }

    @Override
    public void initVars(Map<String, String> vars) {
        vars.put("va",          "0x7B");
        vars.put("vaBin",       "0111 1011");
        vars.put("process",     "Process 4");
        vars.put("operation",   "read");
        vars.put("instruction", "read 0x7B");
        vars.put("vpn",         "0111");
        vars.put("vpnHex",      "0x7");
        vars.put("offset",      "1011");
        vars.put("offsetHex",   "0xB");
        vars.put("pfn",         "0x2");   // PFN after loading from swap
        vars.put("slot",        "0");     // Swap retrieval slot
        vars.put("expectedFloor", "1");
        vars.put("hex",  "0x7B");
        vars.put("optA", "0111 1011");
        vars.put("optB", "0111 1101");
        vars.put("optC", "1011 0111");
    }
}
