package com.nammaroute.service;

import com.nammaroute.model.Booking;
import com.nammaroute.model.Bus;
import com.nammaroute.model.BusType;
import com.nammaroute.model.Passenger;
import com.nammaroute.model.Seat;
import com.nammaroute.model.TicketItem;
import com.nammaroute.model.WaitlistEntry;
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
 * Core business logic for managing buses, dynamic surge pricing, female co-seat safety,
 * FIFO waitlist auto-promotion, cancellations, and conductor passenger manifests.
 */
public class BookingService {
    public static final int MAX_WAITLIST_PER_BUS = 5;
    public static final double SURGE_OCCUPANCY_THRESHOLD = 70.0;
    public static final double SURGE_MULTIPLIER = 1.20;
    public static final double LOWER_BERTH_MULTIPLIER = 1.10;
    public static final double REFUND_RATE = 0.90;

    private final CsvStorage storage;
    private final List<Bus> buses;
    private final List<Booking> bookings;
    private final List<WaitlistEntry> waitlist;
    private final Random random = new Random();

    public record ManifestEntry(String seatNumber, String pnr, Passenger passenger) {}

    public record SeatFarePreview(String seatNumber, double normalFare, boolean surgeApplied, double finalFare) {}

    public record PromotedBooking(String pnr, String routeId, String seatNumber, Passenger passenger, double fare) {}

    public record CancellationResult(
            String pnr,
            String routeId,
            List<String> cancelledSeats,
            double refundAmount,
            boolean fullyCancelled,
            List<PromotedBooking> promotions
    ) {}

    public BookingService(CsvStorage storage) throws IOException {
        this.storage = storage;
        if (storage.busesFileExists()) {
            this.buses = storage.loadBuses();
            this.bookings = storage.loadBookings(this.buses);
            this.waitlist = storage.loadWaitlist();
        } else {
            this.buses = new ArrayList<>();
            this.bookings = new ArrayList<>();
            this.waitlist = new ArrayList<>();
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
     * Checks the Female Co-Seat Safety Rule for a single passenger & seat within a booking.
     * Group/family bookings (where both partner seats are in allSelectedSeats) are exempt.
     */
    public void validateFemaleCoSeatForPassenger(String routeId, List<String> allSelectedSeats,
                                                 String seatNo, Passenger passenger) {
        Bus bus = findBusById(routeId);
        if (bus == null) {
            throw new IllegalArgumentException("Bus route '" + routeId + "' not found.");
        }
        String partnerSeatNo = bus.getPartnerSeatNumber(seatNo);
        if (partnerSeatNo == null) {
            return;
        }
        // Group/family booking exception: both partner seats are booked together on the same PNR
        if (containsSeatIgnoreCase(allSelectedSeats, partnerSeatNo)) {
            return;
        }

        Passenger existingPartner = getBookedPassengerAtSeat(bus.getRouteId(), partnerSeatNo);
        if (existingPartner != null
                && isFemale(existingPartner)
                && !isFemale(passenger)) {
            throw new IllegalArgumentException(
                    "Sorry, seat " + seatNo.toUpperCase()
                            + " is reserved for a female co-passenger. Please choose another seat."
            );
        }
    }

    /**
     * Returns open seat numbers on the bus that are currently restricted to female passengers.
     */
    public List<String> getFemaleReservedOpenSeats(Bus bus) {
        List<String> reserved = new ArrayList<>();
        for (Seat seat : bus.getSeats()) {
            if (!seat.isBooked() && isSeatReservedForFemale(bus, seat.getSeatNumber())) {
                reserved.add(seat.getSeatNumber());
            }
        }
        return reserved;
    }

    /**
     * Computes exact per-seat fares (including 70%+ surge pricing) before confirming a booking.
     */
    public List<SeatFarePreview> previewSeatFares(String routeId, List<String> seatNumbers) {
        List<String> validSeats = validateSeatsForBooking(routeId, seatNumbers);
        Bus bus = findBusById(routeId);
        int currentBooked = bus.getBookedSeatCount();
        int totalSeats = bus.getSeats().size();

        List<SeatFarePreview> previews = new ArrayList<>();
        for (int i = 0; i < validSeats.size(); i++) {
            Seat seat = bus.findSeat(validSeats.get(i));
            double normalFare = calculateNormalSeatFare(bus, seat);
            boolean surge = isSurgeAtBookedCount(currentBooked + i, totalSeats);
            double finalFare = surge ? roundCurrency(normalFare * SURGE_MULTIPLIER) : normalFare;
            previews.add(new SeatFarePreview(seat.getSeatNumber(), normalFare, surge, finalFare));
        }
        return previews;
    }

    /**
     * Books one or more seats under a single PNR, enforcing safety & surge pricing, and saves to CSV.
     */
    public Booking bookTickets(String routeId, List<String> seatNumbers, List<Passenger> passengers) throws IOException {
        List<String> validSeats = validateSeatsForBooking(routeId, seatNumbers);
        if (passengers == null || passengers.size() != validSeats.size()) {
            throw new IllegalArgumentException("Passenger count must match the number of seats.");
        }

        Bus bus = findBusById(routeId);
        for (int i = 0; i < validSeats.size(); i++) {
            validateFemaleCoSeatForPassenger(bus.getRouteId(), validSeats, validSeats.get(i), passengers.get(i));
        }

        List<SeatFarePreview> farePreviews = previewSeatFares(bus.getRouteId(), validSeats);
        List<TicketItem> items = new ArrayList<>();
        for (int i = 0; i < validSeats.size(); i++) {
            String seatNo = validSeats.get(i);
            Seat seat = bus.findSeat(seatNo);
            double fare = farePreviews.get(i).finalFare();
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
     * Adds a passenger to the FIFO waitlist when the bus is 100% full (max 5 per bus).
     * Returns the 1-based queue position.
     */
    public int joinWaitlist(String routeId, Passenger passenger) throws IOException {
        Bus bus = findBusById(routeId);
        if (bus == null) {
            throw new IllegalArgumentException("Bus route '" + routeId + "' not found.");
        }
        if (bus.getAvailableSeatCount() > 0) {
            throw new IllegalArgumentException("Seats are still available on " + bus.getRouteId() + ". Please book a seat directly.");
        }
        List<WaitlistEntry> routeWaitlist = getWaitlistForBus(bus.getRouteId());
        if (routeWaitlist.size() >= MAX_WAITLIST_PER_BUS) {
            throw new IllegalArgumentException("Waitlist for bus " + bus.getRouteId() + " is full (max " + MAX_WAITLIST_PER_BUS + ").");
        }

        waitlist.add(new WaitlistEntry(bus.getRouteId(), LocalDateTime.now(), passenger));
        save();
        return routeWaitlist.size() + 1;
    }

    public List<WaitlistEntry> getWaitlistForBus(String routeId) {
        Bus bus = findBusById(routeId);
        String targetRoute = (bus != null) ? bus.getRouteId() : routeId.trim().toUpperCase();
        List<WaitlistEntry> entries = new ArrayList<>();
        for (WaitlistEntry entry : waitlist) {
            if (entry.getRouteId().equalsIgnoreCase(targetRoute)) {
                entries.add(entry);
            }
        }
        return Collections.unmodifiableList(entries);
    }

    /**
     * Cancels either all seats under a PNR (when seatsToCancel is null or empty)
     * or only the specified seats from a multi-seat PNR, then auto-promotes eligible
     * passengers from the FIFO waitlist.
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

        // Auto-promote eligible passengers from the FIFO waitlist for each freed seat
        List<PromotedBooking> promotions = new ArrayList<>();
        if (bus != null) {
            for (String freedSeatNo : targetSeats) {
                PromotedBooking promotion = promoteNextEligibleFromWaitlist(bus, freedSeatNo);
                if (promotion != null) {
                    promotions.add(promotion);
                }
            }
        }

        save();
        return new CancellationResult(
                booking.getPnr(),
                booking.getRouteId(),
                targetSeats,
                roundCurrency(refundAmount),
                fullyCancelled,
                promotions
        );
    }

    private PromotedBooking promoteNextEligibleFromWaitlist(Bus bus, String freedSeatNo) {
        Seat seat = bus.findSeat(freedSeatNo);
        if (seat == null || seat.isBooked()) {
            return null;
        }

        boolean femaleOnly = isSeatReservedForFemale(bus, freedSeatNo);
        WaitlistEntry matchedEntry = null;
        for (WaitlistEntry entry : waitlist) {
            if (!entry.getRouteId().equalsIgnoreCase(bus.getRouteId())) {
                continue;
            }
            if (!femaleOnly || isFemale(entry.getPassenger())) {
                matchedEntry = entry;
                break;
            }
        }

        if (matchedEntry == null) {
            return null;
        }

        waitlist.remove(matchedEntry);
        double fare = calculateSeatFare(bus, seat);
        seat.setBooked(true);
        String newPnr = generateUniquePnr();
        TicketItem item = new TicketItem(seat.getSeatNumber(), matchedEntry.getPassenger(), fare);
        Booking promotedBooking = new Booking(newPnr, bus.getRouteId(), LocalDateTime.now(), List.of(item));
        bookings.add(promotedBooking);

        return new PromotedBooking(newPnr, bus.getRouteId(), seat.getSeatNumber(), matchedEntry.getPassenger(), fare);
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
     * Normal fare before dynamic surge: Lower sleeper berths cost 10% extra; Upper berths and Seater seats use base price.
     */
    public double calculateNormalSeatFare(Bus bus, Seat seat) {
        if ("LOWER".equalsIgnoreCase(seat.getCategory())) {
            return roundCurrency(bus.getBasePrice() * LOWER_BERTH_MULTIPLIER);
        }
        return bus.getBasePrice();
    }

    /**
     * Current live seat fare including 70%+ occupancy dynamic surge (+20%).
     */
    public double calculateSeatFare(Bus bus, Seat seat) {
        double normalFare = calculateNormalSeatFare(bus, seat);
        if (bus.isSurgePricingActive()) {
            return roundCurrency(normalFare * SURGE_MULTIPLIER);
        }
        return normalFare;
    }

    /**
     * Refund rule: 90% of the original booked seat fare (10% cancellation fee).
     */
    protected double calculateRefund(double paidFare) {
        return roundCurrency(paidFare * REFUND_RATE);
    }

    private boolean isSeatReservedForFemale(Bus bus, String seatNumber) {
        String partnerSeatNo = bus.getPartnerSeatNumber(seatNumber);
        if (partnerSeatNo == null) {
            return false;
        }
        Passenger partner = getBookedPassengerAtSeat(bus.getRouteId(), partnerSeatNo);
        return partner != null && isFemale(partner);
    }

    private Passenger getBookedPassengerAtSeat(String routeId, String seatNumber) {
        for (Booking booking : bookings) {
            if (booking.getRouteId().equalsIgnoreCase(routeId)) {
                TicketItem item = booking.findItemBySeat(seatNumber);
                if (item != null) {
                    return item.getPassenger();
                }
            }
        }
        return null;
    }

    private static boolean isFemale(Passenger passenger) {
        return passenger != null && "F".equalsIgnoreCase(passenger.getGender().trim());
    }

    private static boolean containsSeatIgnoreCase(List<String> seats, String target) {
        for (String seat : seats) {
            if (seat.equalsIgnoreCase(target)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSurgeAtBookedCount(int bookedCount, int totalSeats) {
        if (totalSeats <= 0) {
            return false;
        }
        return (bookedCount * 100.0) / totalSeats >= SURGE_OCCUPANCY_THRESHOLD;
    }

    private static double roundCurrency(double amount) {
        return Math.round(amount * 100.0) / 100.0;
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
        storage.saveAll(buses, bookings, waitlist);
    }
}
