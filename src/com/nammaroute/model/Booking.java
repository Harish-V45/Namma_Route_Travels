package com.nammaroute.model;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Represents a ticket booking transaction identified by a unique PNR.
 */
public class Booking {
    private final String pnr;
    private final String routeId;
    private final LocalDateTime bookingTime;
    private final List<TicketItem> items;

    public Booking(String pnr, String routeId, LocalDateTime bookingTime, List<TicketItem> items) {
        this.pnr = pnr.trim().toUpperCase();
        this.routeId = routeId.trim().toUpperCase();
        this.bookingTime = bookingTime;
        this.items = new ArrayList<>(items);
    }

    public String getPnr() {
        return pnr;
    }

    public String getRouteId() {
        return routeId;
    }

    public LocalDateTime getBookingTime() {
        return bookingTime;
    }

    public List<TicketItem> getItems() {
        return Collections.unmodifiableList(items);
    }

    public double getTotalAmount() {
        double total = 0;
        for (TicketItem item : items) {
            total += item.getFare();
        }
        return total;
    }

    public TicketItem findItemBySeat(String seatNumber) {
        if (seatNumber == null) {
            return null;
        }
        for (TicketItem item : items) {
            if (item.getSeatNumber().equalsIgnoreCase(seatNumber.trim())) {
                return item;
            }
        }
        return null;
    }

    public boolean removeSeat(String seatNumber) {
        return items.removeIf(item -> item.getSeatNumber().equalsIgnoreCase(seatNumber.trim()));
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }
}
