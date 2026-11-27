package com.nammaroute.model;

import java.time.LocalDateTime;

/**
 * Represents a passenger waiting in the FIFO queue when a bus is 100% full.
 */
public class WaitlistEntry {
    private final String routeId;
    private final LocalDateTime joinedAt;
    private final Passenger passenger;

    public WaitlistEntry(String routeId, LocalDateTime joinedAt, Passenger passenger) {
        this.routeId = routeId.trim().toUpperCase();
        this.joinedAt = joinedAt;
        this.passenger = passenger;
    }

    public String getRouteId() {
        return routeId;
    }

    public LocalDateTime getJoinedAt() {
        return joinedAt;
    }

    public Passenger getPassenger() {
        return passenger;
    }
}
