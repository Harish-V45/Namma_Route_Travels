package com.nammaroute.model;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Represents a scheduled bus service with its route, timing, fare, and seats.
 */
public class Bus {
    public static final DateTimeFormatter DATE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final String routeId;
    private final String origin;
    private final String destination;
    private final LocalDateTime departureTime;
    private final double basePrice;
    private final BusType busType;
    private final List<Seat> seats;

    public Bus(String routeId, String origin, String destination,
               LocalDateTime departureTime, double basePrice, BusType busType) {
        this.routeId = routeId.trim().toUpperCase();
        this.origin = origin.trim();
        this.destination = destination.trim();
        this.departureTime = departureTime;
        this.basePrice = basePrice;
        this.busType = busType;
        this.seats = createDefaultSeats(busType);
    }

    private List<Seat> createDefaultSeats(BusType type) {
        List<Seat> generated = new ArrayList<>();
        if (type == BusType.SEATER_2X2) {
            // 8 rows, 4 seats per row (A, B | C, D) = 32 seats
            String[] cols = {"A", "B", "C", "D"};
            for (int row = 1; row <= 8; row++) {
                for (String col : cols) {
                    generated.add(new Seat(row + col, "SEAT", false));
                }
            }
        } else {
            // 2x1 Sleeper: 12 Lower berths (L1-L12) and 12 Upper berths (U1-U12)
            for (int i = 1; i <= 12; i++) {
                generated.add(new Seat("L" + i, "LOWER", false));
            }
            for (int i = 1; i <= 12; i++) {
                generated.add(new Seat("U" + i, "UPPER", false));
            }
        }
        return generated;
    }

    public String getRouteId() {
        return routeId;
    }

    public String getOrigin() {
        return origin;
    }

    public String getDestination() {
        return destination;
    }

    public LocalDateTime getDepartureTime() {
        return departureTime;
    }

    public String getFormattedDepartureTime() {
        return departureTime.format(DATE_TIME_FORMAT);
    }

    public double getBasePrice() {
        return basePrice;
    }

    public BusType getBusType() {
        return busType;
    }

    public List<Seat> getSeats() {
        return Collections.unmodifiableList(seats);
    }

    public Seat findSeat(String seatNumber) {
        if (seatNumber == null) {
            return null;
        }
        String normalized = seatNumber.trim().toUpperCase();
        for (Seat seat : seats) {
            if (seat.getSeatNumber().equalsIgnoreCase(normalized)) {
                return seat;
            }
        }
        return null;
    }

    public int getSeatOrderIndex(String seatNumber) {
        for (int i = 0; i < seats.size(); i++) {
            if (seats.get(i).getSeatNumber().equalsIgnoreCase(seatNumber)) {
                return i;
            }
        }
        return Integer.MAX_VALUE;
    }

    public int getAvailableSeatCount() {
        int count = 0;
        for (Seat seat : seats) {
            if (!seat.isBooked()) {
                count++;
            }
        }
        return count;
    }

    public int getBookedSeatCount() {
        return seats.size() - getAvailableSeatCount();
    }

    public double getOccupancyPercentage() {
        if (seats.isEmpty()) {
            return 0.0;
        }
        return (getBookedSeatCount() * 100.0) / seats.size();
    }

    public boolean isSurgePricingActive() {
        return getOccupancyPercentage() >= 70.0;
    }

    /**
     * Returns the adjacent seat number in a 2-seat pair, or null if the seat is a solo berth.
     */
    public String getPartnerSeatNumber(String seatNumber) {
        Seat seat = findSeat(seatNumber);
        if (seat == null) {
            return null;
        }
        String normalized = seat.getSeatNumber();
        if (busType == BusType.SEATER_2X2) {
            String row = normalized.substring(0, normalized.length() - 1);
            char col = normalized.charAt(normalized.length() - 1);
            return switch (col) {
                case 'A' -> row + "B";
                case 'B' -> row + "A";
                case 'C' -> row + "D";
                case 'D' -> row + "C";
                default -> null;
            };
        } else {
            // 2x1 Sleeper: 1&2, 4&5, 7&8, 10&11 are pairs; 3, 6, 9, 12 are single berths
            String deck = normalized.substring(0, 1);
            int num = Integer.parseInt(normalized.substring(1));
            int mod = num % 3;
            if (mod == 1) {
                return deck + (num + 1);
            } else if (mod == 2) {
                return deck + (num - 1);
            }
            return null;
        }
    }
}
