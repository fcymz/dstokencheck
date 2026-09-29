package com.ruoyi.dstokencheck.model;

import java.math.BigDecimal;

/** A single currency wallet reported by the DeepSeek platform. */
public class Wallet {
    private final String currency;
    private final BigDecimal balance;
    private final BigDecimal tokenEstimation;
    private final boolean bonus;

    public Wallet(String currency, BigDecimal balance, BigDecimal tokenEstimation, boolean bonus) {
        this.currency = currency == null ? "" : currency;
        this.balance = balance == null ? BigDecimal.ZERO : balance;
        this.tokenEstimation = tokenEstimation;
        this.bonus = bonus;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public BigDecimal getTokenEstimation() {
        return tokenEstimation;
    }

    /** True when this is granted/credit balance rather than topped-up balance. */
    public boolean isBonus() {
        return bonus;
    }

    public String getSymbol() {
        if ("CNY".equalsIgnoreCase(currency)) {
            return "\u00a5";
        }
        if ("USD".equalsIgnoreCase(currency)) {
            return "$";
        }
        return "";
    }

    @Override
    public String toString() {
        return currency + " " + balance;
    }
}
