package com.nammaroute.model;

/**
 * Links a single booked seat to its passenger and fare within a booking.
 */
public class TicketItem {
    private final String seatNumber;
    private final Passenger passenger;
    private final double fare;

    public TicketItem(String seatNumber, Passenger passenger, double fare) {
        this.seatNumber = seatNumber.trim().toUpperCase();
        this.passenger = passenger;
        this.fare = fare;
    }

    public String getSeatNumber() {
        return seatNumber;
    }

    public Passenger getPassenger() {
        return passenger;
    }

    public double getFare() {
        return fare;
    }
}
