package com.nammaroute.service;

import com.nammaroute.model.Booking;
import com.nammaroute.model.Bus;
import com.nammaroute.model.BusType;
import com.nammaroute.model.Passenger;
import com.nammaroute.model.Seat;
import com.nammaroute.model.TicketItem;
import com.nammaroute.storage.CsvStorage;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Core business logic for managing buses, seat availability, bookings,
 * cancellations, and conductor passenger manifests.
 */
public class BookingService {
    private final CsvStorage storage;
    private final List<Bus> buses;
    private final List<Booking> bookings;
    private final Random random = new Random();

    public record ManifestEntry(String seatNumber, String pnr, Passenger passenger) {}

    public record CancellationResult(
            String pnr,
            String routeId,
            List<String> cancelledSeats,
            double refundAmount,
            boolean fullyCancelled
    ) {}

    public BookingService(CsvStorage storage) throws IOException {
        this.storage = storage;
        if (storage.busesFileExists()) {
            this.buses = storage.loadBuses();
            this.bookings = storage.loadBookings(this.buses);
        } else {
            this.buses = new ArrayList<>();
            this.bookings = new ArrayList<>();
            seedSampleBuses();
            save();
        }
    }

    private void seedSampleBuses() {
        buses.add(new Bus(
                "NR-101",
                "Bengaluru",
                "Goa",
                LocalDateTime.of(2026, 11, 27, 21, 30),
                1200.0,
                BusType.SEATER_2X2
        ));
        buses.add(new Bus(
                "NR-102",
                "Bengaluru",
                "Hyderabad",
                LocalDateTime.of(2026, 11, 27, 22, 15),
                1450.0,
                BusType.SLEEPER_2X1
        ));
        buses.add(new Bus(
                "NR-103",
                "Bengaluru",
                "Chennai",
                LocalDateTime.of(2026, 11, 28, 21, 0),
                950.0,
                BusType.SEATER_2X2
        ));
    }

    public List<Bus> getAllBuses() {
        return Collections.unmodifiableList(buses);
    }

    public Bus findBusById(String routeId) {
        String target = normalizeCode(routeId);
        if (target.isEmpty()) {
            return null;
        }
        for (Bus bus : buses) {
            if (normalizeCode(bus.getRouteId()).equals(target)) {
                return bus;
            }
        }
        return null;
    }

    public Booking findBookingByPnr(String pnr) {
        String target = normalizeCode(pnr);
        if (target.isEmpty()) {
            return null;
        }
        for (Booking booking : bookings) {
            if (normalizeCode(booking.getPnr()).equals(target)) {
                return booking;
            }
        }
        return null;
    }

    private static String normalizeCode(String code) {
        if (code == null) {
            return "";
        }
        return code.replaceAll("[\\s-]+", "").toUpperCase();
    }

    public Bus addBus(String routeId, String origin, String destination,
                      LocalDateTime departureTime, double basePrice, BusType busType) throws IOException {
        String normalizedId = routeId.trim().toUpperCase();
        if (normalizedId.isEmpty()) {
            throw new IllegalArgumentException("Route ID cannot be empty.");
        }
        if (findBusById(normalizedId) != null) {
            throw new IllegalArgumentException("A bus with Route ID '" + normalizedId + "' already exists.");
        }
        if (origin.trim().isEmpty() || destination.trim().isEmpty()) {
            throw new IllegalArgumentException("Origin and destination cannot be empty.");
        }
        if (basePrice <= 0) {
            throw new IllegalArgumentException("Base ticket price must be greater than zero.");
        }

        Bus bus = new Bus(normalizedId, origin, destination, departureTime, basePrice, busType);
        buses.add(bus);
        save();
        return bus;
    }

    /**
     * Validates requested seats before collecting passenger details.
     * Returns a normalized list of seat numbers if all seats are valid and available.
     */
    public List<String> validateSeatsForBooking(String routeId, List<String> requestedSeats) {
        Bus bus = findBusById(routeId);
        if (bus == null) {
            throw new IllegalArgumentException("Bus route '" + routeId + "' not found.");
        }
        if (requestedSeats == null || requestedSeats.isEmpty()) {
            throw new IllegalArgumentException("Please enter at least one seat number.");
        }

        Set<String> uniqueSeats = new LinkedHashSet<>();
        for (String rawSeat : requestedSeats) {
            String seatNo = rawSeat.trim().toUpperCase();
            if (seatNo.isEmpty()) {
                continue;
            }
            if (!uniqueSeats.add(seatNo)) {
                throw new IllegalArgumentException("Duplicate seat number entered: " + seatNo);
            }
            Seat seat = bus.findSeat(seatNo);
            if (seat == null) {
                throw new IllegalArgumentException("Invalid seat number '" + seatNo + "' for bus " + bus.getRouteId() + ".");
            }
            if (seat.isBooked()) {
                throw new IllegalArgumentException("Seat '" + seatNo + "' is already booked.");
            }
        }

        if (uniqueSeats.isEmpty()) {
            throw new IllegalArgumentException("Please enter at least one valid seat number.");
        }
        return new ArrayList<>(uniqueSeats);
    }

    /**
     * Books one or more seats under a single PNR and saves changes to CSV.
     */
    public Booking bookTickets(String routeId, List<String> seatNumbers, List<Passenger> passengers) throws IOException {
        List<String> validSeats = validateSeatsForBooking(routeId, seatNumbers);
        if (passengers == null || passengers.size() != validSeats.size()) {
            throw new IllegalArgumentException("Passenger count must match the number of seats.");
        }

        Bus bus = findBusById(routeId);
        List<TicketItem> items = new ArrayList<>();
        for (int i = 0; i < validSeats.size(); i++) {
            String seatNo = validSeats.get(i);
            Seat seat = bus.findSeat(seatNo);
            double fare = calculateSeatFare(bus, seat);
            seat.setBooked(true);
            items.add(new TicketItem(seatNo, passengers.get(i), fare));
        }

        String pnr = generateUniquePnr();
        Booking booking = new Booking(pnr, bus.getRouteId(), LocalDateTime.now(), items);
        bookings.add(booking);
        save();
        return booking;
    }

    /**
     * Cancels either all seats under a PNR (when seatsToCancel is null or empty)
     * or only the specified seats from a multi-seat PNR.
     */
    public CancellationResult cancelBooking(String pnr, List<String> seatsToCancel) throws IOException {
        Booking booking = findBookingByPnr(pnr);
        if (booking == null) {
            throw new IllegalArgumentException("No booking found with PNR '" + pnr + "'.");
        }

        Bus bus = findBusById(booking.getRouteId());
        List<String> targetSeats;

        if (seatsToCancel == null || seatsToCancel.isEmpty()) {
            targetSeats = new ArrayList<>();
            for (TicketItem item : booking.getItems()) {
                targetSeats.add(item.getSeatNumber());
            }
        } else {
            Set<String> uniqueTargets = new LinkedHashSet<>();
            for (String rawSeat : seatsToCancel) {
                String seatNo = rawSeat.trim().toUpperCase();
                if (seatNo.isEmpty()) {
                    continue;
                }
                if (booking.findItemBySeat(seatNo) == null) {
                    throw new IllegalArgumentException("Seat '" + seatNo + "' is not part of PNR " + booking.getPnr() + ".");
                }
                uniqueTargets.add(seatNo);
            }
            if (uniqueTargets.isEmpty()) {
                throw new IllegalArgumentException("No valid seats specified for cancellation.");
            }
            targetSeats = new ArrayList<>(uniqueTargets);
        }

        double refundAmount = 0.0;
        for (String seatNo : targetSeats) {
            TicketItem item = booking.findItemBySeat(seatNo);
            if (item != null) {
                refundAmount += calculateRefund(item.getFare());
                booking.removeSeat(seatNo);
            }
            if (bus != null) {
                Seat seat = bus.findSeat(seatNo);
                if (seat != null) {
                    seat.setBooked(false);
                }
            }
        }

        boolean fullyCancelled = booking.isEmpty();
        if (fullyCancelled) {
            bookings.remove(booking);
        }

        save();
        return new CancellationResult(
                booking.getPnr(),
                booking.getRouteId(),
                targetSeats,
                refundAmount,
                fullyCancelled
        );
    }

    /**
     * Returns all booked passengers for a route, sorted by seat number order.
     */
    public List<ManifestEntry> getManifestForBus(String routeId) {
        Bus bus = findBusById(routeId);
        if (bus == null) {
            throw new IllegalArgumentException("Bus route '" + routeId + "' not found.");
        }

        List<ManifestEntry> entries = new ArrayList<>();
        for (Booking booking : bookings) {
            if (booking.getRouteId().equalsIgnoreCase(bus.getRouteId())) {
                for (TicketItem item : booking.getItems()) {
                    entries.add(new ManifestEntry(item.getSeatNumber(), booking.getPnr(), item.getPassenger()));
                }
            }
        }

        entries.sort(Comparator.comparingInt(entry -> bus.getSeatOrderIndex(entry.seatNumber())));
        return entries;
    }

    /**
     * Pricing rule: Lower sleeper berths cost 10% extra; Upper berths and Seater seats use the base price.
     */
    public double calculateSeatFare(Bus bus, Seat seat) {
        if ("LOWER".equalsIgnoreCase(seat.getCategory())) {
            return Math.round(bus.getBasePrice() * 1.10 * 100.0) / 100.0;
        }
        return bus.getBasePrice();
    }

    /**
     * Refund rule: 90% of the original booked seat fare (10% cancellation fee).
     */
    protected double calculateRefund(double paidFare) {
        return Math.round(paidFare * 0.90 * 100.0) / 100.0;
    }

    private String generateUniquePnr() {
        String pnr;
        do {
            int number = 10000 + random.nextInt(90000);
            pnr = "PNR-" + number;
        } while (findBookingByPnr(pnr) != null);
        return pnr;
    }

    private void save() throws IOException {
        storage.saveAll(buses, bookings);
    }
}
