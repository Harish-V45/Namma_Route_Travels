package com.nammaroute.model;

/**
 * Supported bus layouts at NammaRoute Travels.
 */
public enum BusType {
    SEATER_2X2("2x2 Seater", 32),
    SLEEPER_2X1("2x1 Sleeper", 24);

    private final String displayName;
    private final int totalSeats;

    BusType(String displayName, int totalSeats) {
        this.displayName = displayName;
        this.totalSeats = totalSeats;
    }

    public String getDisplayName() {
        return displayName;
    }

    public int getTotalSeats() {
        return totalSeats;
    }
}
