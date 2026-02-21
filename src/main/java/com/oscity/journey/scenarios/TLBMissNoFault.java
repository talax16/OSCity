package com.oscity.journey.scenarios;

import com.oscity.journey.JourneyScenario;
import java.util.Map;

public class TLBMissNoFault implements JourneyScenario {

    @Override public String getDisplayName()      { return "TLB Miss - No Fault"; }
    @Override public int    getNumber()           { return 2; }
    @Override public boolean isTlbHit()           { return false; }
    @Override public String getPermissionAnswer() { return "allow_access"; }
    @Override public String getPageFaultType()    { return null; }
    @Override public boolean involvesCow()        { return false; }
    @Override public boolean involvesSwap()       { return false; }

    @Override
    public void initVars(Map<String, String> vars) {
        vars.put("va",          "0x5C");
        vars.put("vaBin",       "0101 1100");
        vars.put("process",     "Process 2");
        vars.put("operation",   "read");
        vars.put("instruction", "read 0x5C");
        vars.put("vpn",         "0101");
        vars.put("vpnHex",      "0x5");
        vars.put("offset",      "1100");
        vars.put("offsetHex",   "0xC");
        vars.put("pfn",         "0x3");
        vars.put("expectedFloor", "1");
        vars.put("hex",  "0x5C");
        vars.put("optA", "0101 1100");
        vars.put("optB", "0101 0011");
        vars.put("optC", "1100 0101");
    }
}
