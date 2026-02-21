package com.oscity.journey.scenarios;

import com.oscity.journey.JourneyScenario;
import java.util.Map;

public class LuckyJourney implements JourneyScenario {

    @Override public String getDisplayName()      { return "Lucky"; }
    @Override public int    getNumber()           { return 1; }
    @Override public boolean isTlbHit()           { return true; }
    @Override public String getPermissionAnswer() { return "allow_access"; }
    @Override public String getPageFaultType()    { return null; }
    @Override public boolean involvesCow()        { return false; }
    @Override public boolean involvesSwap()       { return false; }

    @Override
    public void initVars(Map<String, String> vars) {
        vars.put("va",          "0x2A");
        vars.put("vaBin",       "0010 1010");
        vars.put("process",     "Process 1");
        vars.put("operation",   "read");
        vars.put("instruction", "read 0x2A");
        vars.put("vpn",         "0010");
        vars.put("vpnHex",      "0x2");
        vars.put("offset",      "1010");
        vars.put("offsetHex",   "0xA");
        vars.put("pfn",         "0x3");
        vars.put("expectedFloor", "?");
        vars.put("hex",  "0x2A");
        vars.put("optA", "0010 1010");
        vars.put("optB", "0010 0101");
        vars.put("optC", "0101 0010");
    }
}
