package com.nammaroute.model;

/**
 * Represents a single seat or sleeper berth on a bus.
 */
public class Seat {
    private final String seatNumber;
    private final String category; // "SEAT", "LOWER", or "UPPER"
    private boolean booked;

    public Seat(String seatNumber, String category, boolean booked) {
        this.seatNumber = seatNumber;
        this.category = category;
        this.booked = booked;
    }

    public String getSeatNumber() {
        return seatNumber;
    }

    public String getCategory() {
        return category;
    }

    public boolean isBooked() {
        return booked;
    }

    public void setBooked(boolean booked) {
        this.booked = booked;
    }
}
