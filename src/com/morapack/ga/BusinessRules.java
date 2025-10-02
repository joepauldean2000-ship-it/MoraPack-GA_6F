package com.morapack.ga;

public final class BusinessRules {
    public int slaIntraHours = 48;
    public int slaInterHours = 72;
    public int whBlockMin = 120;

    public BusinessRules() {
    }

    public BusinessRules(int slaIntra, int slaInter, int whBlock) {
        this.slaIntraHours = slaIntra;
        this.slaInterHours = slaInter;
        this.whBlockMin = whBlock;
    }
}
