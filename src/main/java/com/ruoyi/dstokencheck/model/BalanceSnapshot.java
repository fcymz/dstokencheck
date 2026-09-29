package com.ruoyi.dstokencheck.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable snapshot of everything the widget needs to render one refresh. */
public class BalanceSnapshot {

    private final List<Wallet> wallets;
    private final String totalAvailableTokenEstimation;
    private final String accountLabel;
    private final long fetchedAtMillis;
    private final String source;

    public BalanceSnapshot(List<Wallet> wallets,
                           String totalAvailableTokenEstimation,
                           String accountLabel,
                           long fetchedAtMillis,
                           String source) {
        this.wallets = Collections.unmodifiableList(new ArrayList<Wallet>(
                wallets == null ? Collections.<Wallet>emptyList() : wallets));
        this.totalAvailableTokenEstimation = totalAvailableTokenEstimation;
        this.accountLabel = accountLabel;
        this.fetchedAtMillis = fetchedAtMillis;
        this.source = source;
    }

    public List<Wallet> getWallets() {
        return wallets;
    }

    public String getTotalAvailableTokenEstimation() {
        return totalAvailableTokenEstimation;
    }

    public String getAccountLabel() {
        return accountLabel;
    }

    public long getFetchedAtMillis() {
        return fetchedAtMillis;
    }

    public String getSource() {
        return source;
    }

    /**
     * Sum of the wallets held in {@link #primaryCurrency()}. Adding balances across different
     * currencies would be meaningless, so the headline figure is scoped to one currency.
     */
    public java.math.BigDecimal totalInPrimaryCurrency() {
        java.math.BigDecimal sum = java.math.BigDecimal.ZERO;
        String primary = primaryCurrency();
        for (Wallet w : wallets) {
            if (w.getCurrency().equalsIgnoreCase(primary)) {
                sum = sum.add(w.getBalance());
            }
        }
        return sum;
    }

    /** The part of {@link #totalInPrimaryCurrency()} that comes from granted/credit balance. */
    public java.math.BigDecimal bonusInPrimaryCurrency() {
        java.math.BigDecimal sum = java.math.BigDecimal.ZERO;
        String primary = primaryCurrency();
        for (Wallet w : wallets) {
            if (w.isBonus() && w.getCurrency().equalsIgnoreCase(primary)) {
                sum = sum.add(w.getBalance());
            }
        }
        return sum;
    }

    /** Wallets in currencies other than the primary one, for the secondary breakdown lines. */
    public java.util.List<Wallet> secondaryWallets() {
        java.util.List<Wallet> out = new java.util.ArrayList<Wallet>();
        String primary = primaryCurrency();
        for (Wallet w : wallets) {
            if (!w.getCurrency().equalsIgnoreCase(primary)) {
                out.add(w);
            }
        }
        return out;
    }

    /** Primary currency, preferring CNY then USD then whatever is first. */
    public String primaryCurrency() {
        for (Wallet w : wallets) {
            if ("CNY".equalsIgnoreCase(w.getCurrency())) {
                return w.getCurrency();
            }
        }
        for (Wallet w : wallets) {
            if ("USD".equalsIgnoreCase(w.getCurrency())) {
                return w.getCurrency();
            }
        }
        return wallets.isEmpty() ? "" : wallets.get(0).getCurrency();
    }

    public String primarySymbol() {
        String cur = primaryCurrency();
        if ("CNY".equalsIgnoreCase(cur)) {
            return "\u00a5";
        }
        if ("USD".equalsIgnoreCase(cur)) {
            return "$";
        }
        return "";
    }
}
