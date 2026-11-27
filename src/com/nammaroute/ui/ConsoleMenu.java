package com.nammaroute.ui;

import com.nammaroute.model.Booking;
import com.nammaroute.model.Bus;
import com.nammaroute.model.BusType;
import com.nammaroute.model.Passenger;
import com.nammaroute.model.Seat;
import com.nammaroute.model.TicketItem;
import com.nammaroute.service.BookingService;
import com.nammaroute.service.BookingService.CancellationResult;
import com.nammaroute.service.BookingService.ManifestEntry;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

/**
 * Handles all console input/output and user menus for NammaRoute Travels.
 */
public class ConsoleMenu {
    private final BookingService service;
    private final Scanner scanner;

    public ConsoleMenu(BookingService service, Scanner scanner) {
        this.service = service;
        this.scanner = scanner;
    }

    public void start() {
        System.out.println("==========================================");
        System.out.println("   Welcome to NammaRoute Travels (v1.0)   ");
        System.out.println("==========================================");

        boolean running = true;
        while (running) {
            printMainMenu();
            int choice = readIntInRange("Choose an option (0-6): ", 0, 6);
            System.out.println();

            switch (choice) {
                case 1 -> handleViewBuses();
                case 2 -> handleAddBus();
                case 3 -> handleViewSeatMap();
                case 4 -> handleBookTickets();
                case 5 -> handleCancelTickets();
                case 6 -> handlePassengerManifest();
                case 0 -> {
                    running = false;
                    System.out.println("Thank you for using NammaRoute Travels. Goodbye!");
                }
                default -> System.out.println("Invalid option. Please try again.");
            }
            System.out.println();
        }
    }

    private void printMainMenu() {
        System.out.println("--------------- MAIN MENU ----------------");
        System.out.println("1. View All Bus Services");
        System.out.println("2. Add New Bus Service");
        System.out.println("3. View Visual Seat Map");
        System.out.println("4. Book Tickets");
        System.out.println("5. Cancel Tickets");
        System.out.println("6. Conductor Passenger Manifest");
        System.out.println("0. Exit");
        System.out.println("------------------------------------------");
    }

    private void handleViewBuses() {
        List<Bus> buses = service.getAllBuses();
        if (buses.isEmpty()) {
            System.out.println("No bus services available yet.");
            return;
        }

        System.out.println("=== AVAILABLE BUS SERVICES ===");
        System.out.printf("%-8s | %-24s | %-16s | %-12s | %-9s | %s%n",
                "Route ID", "Route", "Departure", "Bus Type", "Fare", "Seats Free");
        System.out.println("-".repeat(92));
        for (Bus bus : buses) {
            String route = bus.getOrigin() + " -> " + bus.getDestination();
            String seatsFree = bus.getAvailableSeatCount() + " / " + bus.getBusType().getTotalSeats();
            System.out.printf("%-8s | %-24s | %-16s | %-12s | Rs.%-6.0f | %s%n",
                    bus.getRouteId(),
                    route,
                    bus.getFormattedDepartureTime(),
                    bus.getBusType().getDisplayName(),
                    bus.getBasePrice(),
                    seatsFree);
        }
    }

    private void handleAddBus() {
        System.out.println("=== ADD NEW BUS SERVICE ===");
        String routeId = readNonEmptyString("Enter Route ID (e.g., NR-104): ").toUpperCase();
        if (service.findBusById(routeId) != null) {
            System.out.println("Error: Route ID '" + routeId + "' already exists.");
            return;
        }

        String origin = readNonEmptyString("Enter Origin City (e.g., Bengaluru): ");
        String destination = readNonEmptyString("Enter Destination City (e.g., Mangaluru): ");
        LocalDateTime departureTime = readDateTime("Enter Departure Date & Time (yyyy-MM-dd HH:mm): ");
        double basePrice = readPositiveDouble("Enter Base Ticket Price (Rs.): ");

        System.out.println("Select Bus Type:");
        System.out.println("1. 2x2 Seater (32 seats)");
        System.out.println("2. 2x1 Sleeper (24 berths: 12 Lower + 12 Upper)");
        int typeChoice = readIntInRange("Choice (1 or 2): ", 1, 2);
        BusType busType = (typeChoice == 1) ? BusType.SEATER_2X2 : BusType.SLEEPER_2X1;

        try {
            Bus bus = service.addBus(routeId, origin, destination, departureTime, basePrice, busType);
            System.out.println("Success! Added bus " + bus.getRouteId() + " (" +
                    bus.getOrigin() + " to " + bus.getDestination() + ").");
        } catch (IllegalArgumentException | IOException e) {
            System.out.println("Could not add bus: " + e.getMessage());
        }
    }

    private void handleViewSeatMap() {
        String routeId = readNonEmptyString("Enter Bus Route ID: ");
        Bus bus = service.findBusById(routeId);
        if (bus == null) {
            System.out.println("Error: Bus route '" + routeId + "' not found.");
            return;
        }
        printSeatMap(bus);
    }

    private void printSeatMap(Bus bus) {
        System.out.println();
        System.out.printf("--- Bus %s: %s to %s (%s) ---%n",
                bus.getRouteId(), bus.getOrigin(), bus.getDestination(), bus.getFormattedDepartureTime());
        System.out.printf("Type: %s | Fare: Rs.%.2f | Available: %d / %d%n",
                bus.getBusType().getDisplayName(),
                bus.getBasePrice(),
                bus.getAvailableSeatCount(),
                bus.getBusType().getTotalSeats());
        System.out.println("Available: [Seat No] | Booked: [XX]");
        System.out.println();

        List<Seat> seats = bus.getSeats();
        if (bus.getBusType() == BusType.SEATER_2X2) {
            // 8 rows, 4 seats per row
            for (int row = 0; row < 8; row++) {
                int base = row * 4;
                System.out.printf("Row %d: %s %s   %s %s%n",
                        row + 1,
                        formatSeatCell(seats.get(base), 2),
                        formatSeatCell(seats.get(base + 1), 2),
                        formatSeatCell(seats.get(base + 2), 2),
                        formatSeatCell(seats.get(base + 3), 2));
            }
        } else {
            // 12 Lower berths (0..11) and 12 Upper berths (12..23), 4 rows of 2x1 per deck
            double lowerFare = service.calculateSeatFare(bus, seats.get(0));
            double upperFare = service.calculateSeatFare(bus, seats.get(12));
            System.out.printf("Lower Deck (Rs.%.2f - 10%% extra):%n", lowerFare);
            printSleeperDeck(seats, 0);
            System.out.println();
            System.out.printf("Upper Deck (Rs.%.2f - Base fare):%n", upperFare);
            printSleeperDeck(seats, 12);
        }
    }

    private void printSleeperDeck(List<Seat> seats, int offset) {
        for (int row = 0; row < 4; row++) {
            int base = offset + (row * 3);
            System.out.printf("Row %d: %s %s   %s%n",
                    row + 1,
                    formatSeatCell(seats.get(base), 3),
                    formatSeatCell(seats.get(base + 1), 3),
                    formatSeatCell(seats.get(base + 2), 3));
        }
    }

    private String formatSeatCell(Seat seat, int width) {
        String label = seat.isBooked() ? "XX" : seat.getSeatNumber();
        return String.format("[%-" + width + "s]", label);
    }

    private void handleBookTickets() {
        System.out.println("=== BOOK TICKETS ===");
        String routeId = readNonEmptyString("Enter Bus Route ID: ");
        Bus bus = service.findBusById(routeId);
        if (bus == null) {
            System.out.println("Error: Bus route '" + routeId + "' not found.");
            return;
        }

        printSeatMap(bus);
        if (bus.getAvailableSeatCount() == 0) {
            System.out.println("Sorry, this bus is completely sold out.");
            return;
        }

        System.out.println();
        String seatInput = readNonEmptyString("Enter Seat Numbers separated by commas (e.g., 1A, 1B): ");
        List<String> requestedSeats = parseSeatList(seatInput);

        List<String> validatedSeats;
        try {
            validatedSeats = service.validateSeatsForBooking(bus.getRouteId(), requestedSeats);
        } catch (IllegalArgumentException e) {
            System.out.println("Booking stopped: " + e.getMessage());
            return;
        }

        List<Passenger> passengers = new ArrayList<>();
        for (String seatNo : validatedSeats) {
            System.out.println();
            System.out.println("Enter details for Seat " + seatNo + ":");
            String name = readNonEmptyString("  Passenger Name: ");
            int age = readIntInRange("  Passenger Age (1-120): ", 1, 120);
            String gender = readGender("  Passenger Gender (M / F / Other): ");
            passengers.add(new Passenger(name, age, gender));
        }

        try {
            Booking booking = service.bookTickets(bus.getRouteId(), validatedSeats, passengers);
            printBookingReceipt(bus, booking);
        } catch (IllegalArgumentException | IOException e) {
            System.out.println("Booking failed: " + e.getMessage());
        }
    }

    private void printBookingReceipt(Bus bus, Booking booking) {
        System.out.println();
        System.out.println("==========================================");
        System.out.println("             BOOKING RECEIPT              ");
        System.out.println("==========================================");
        System.out.println("PNR Number  : " + booking.getPnr());
        System.out.println("Route       : " + bus.getRouteId() + " (" + bus.getOrigin() + " to " + bus.getDestination() + ")");
        System.out.println("Departure   : " + bus.getFormattedDepartureTime());
        System.out.println("Bus Type    : " + bus.getBusType().getDisplayName());
        System.out.println("----------------------------------------------------");
        System.out.printf("%-6s | %-20s | %-3s | %-6s | %s%n", "Seat", "Passenger Name", "Age", "Gender", "Fare");
        System.out.println("----------------------------------------------------");
        for (TicketItem item : booking.getItems()) {
            Passenger p = item.getPassenger();
            System.out.printf("%-6s | %-20s | %-3d | %-6s | Rs.%.2f%n",
                    item.getSeatNumber(), p.getName(), p.getAge(), p.getGender(), item.getFare());
        }
        System.out.println("----------------------------------------------------");
        System.out.printf("Total Amount Paid: Rs.%.2f%n", booking.getTotalAmount());
        System.out.println("====================================================");
    }

    private void handleCancelTickets() {
        System.out.println("=== CANCEL TICKETS ===");
        String pnr = readNonEmptyString("Enter PNR Number (e.g., PNR-84920): ");
        Booking booking = service.findBookingByPnr(pnr);
        if (booking == null) {
            System.out.println("Error: No booking found with PNR '" + pnr.toUpperCase() + "'.");
            return;
        }

        System.out.println();
        System.out.println("Booking Found - PNR: " + booking.getPnr() + " | Route: " + booking.getRouteId());
        for (TicketItem item : booking.getItems()) {
            Passenger p = item.getPassenger();
            System.out.printf("  Seat %-4s -> %s (%d, %s) | Fare: Rs.%.2f%n",
                    item.getSeatNumber(), p.getName(), p.getAge(), p.getGender(), item.getFare());
        }

        List<String> seatsToCancel = null;
        if (booking.getItems().size() > 1) {
            System.out.println();
            System.out.println("1. Cancel ALL seats under this PNR");
            System.out.println("2. Cancel SPECIFIC seats only");
            int choice = readIntInRange("Choose an option (1 or 2): ", 1, 2);
            if (choice == 2) {
                String seatInput = readNonEmptyString("Enter Seat Numbers to cancel (comma-separated): ");
                seatsToCancel = parseSeatList(seatInput);
            }
        } else {
            String confirm = readNonEmptyString("Confirm cancellation of seat "
                    + booking.getItems().get(0).getSeatNumber() + "? (Y/N): ");
            if (!confirm.equalsIgnoreCase("Y") && !confirm.equalsIgnoreCase("YES")) {
                System.out.println("Cancellation aborted.");
                return;
            }
        }

        try {
            CancellationResult result = service.cancelBooking(booking.getPnr(), seatsToCancel);
            System.out.println();
            System.out.println("------------------------------------------");
            System.out.println("Cancellation Confirmed!");
            System.out.println("PNR             : " + result.pnr());
            System.out.println("Route           : " + result.routeId());
            System.out.println("Cancelled Seats : " + String.join(", ", result.cancelledSeats()));
            System.out.printf("Refund Amount   : Rs.%.2f (90%% of original fare)%n", result.refundAmount());
            System.out.println("Booking Status  : " + (result.fullyCancelled() ? "Fully Cancelled" : "Partially Active"));
            System.out.println("------------------------------------------");
        } catch (IllegalArgumentException | IOException e) {
            System.out.println("Cancellation failed: " + e.getMessage());
        }
    }

    private void handlePassengerManifest() {
        String routeId = readNonEmptyString("Enter Bus Route ID: ");
        Bus bus = service.findBusById(routeId);
        if (bus == null) {
            System.out.println("Error: Bus route '" + routeId + "' not found.");
            return;
        }

        List<ManifestEntry> manifest = service.getManifestForBus(bus.getRouteId());
        System.out.println();
        System.out.printf("=== PASSENGER MANIFEST: %s (%s -> %s) ===%n",
                bus.getRouteId(), bus.getOrigin(), bus.getDestination());
        System.out.printf("%-4s | %-9s | %-20s | %-3s | %-6s%n",
                "Seat", "PNR", "Passenger Name", "Age", "Gender");
        System.out.println("-".repeat(54));

        if (manifest.isEmpty()) {
            System.out.println("No passengers booked on this bus yet.");
        } else {
            for (ManifestEntry entry : manifest) {
                Passenger p = entry.passenger();
                System.out.printf("%-4s | %-9s | %-20s | %-3d | %-6s%n",
                        entry.seatNumber(),
                        entry.pnr(),
                        p.getName(),
                        p.getAge(),
                        p.getGender());
            }
        }
        System.out.println("-".repeat(54));
        System.out.printf("Total Booked: %d | Available: %d%n",
                bus.getBookedSeatCount(), bus.getAvailableSeatCount());
    }

    private List<String> parseSeatList(String rawInput) {
        String[] parts = rawInput.split(",");
        List<String> seats = new ArrayList<>();
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                seats.add(trimmed);
            }
        }
        return seats;
    }

    private String readNonEmptyString(String prompt) {
        while (true) {
            System.out.print(prompt);
            String input = scanner.nextLine().trim();
            if (!input.isEmpty()) {
                return input;
            }
            System.out.println("Input cannot be empty. Please try again.");
        }
    }

    private int readIntInRange(String prompt, int min, int max) {
        while (true) {
            System.out.print(prompt);
            String input = scanner.nextLine().trim();
            try {
                int value = Integer.parseInt(input);
                if (value >= min && value <= max) {
                    return value;
                }
                System.out.printf("Please enter a number between %d and %d.%n", min, max);
            } catch (NumberFormatException e) {
                System.out.println("Invalid number. Please enter digits only.");
            }
        }
    }

    private double readPositiveDouble(String prompt) {
        while (true) {
            System.out.print(prompt);
            String input = scanner.nextLine().trim();
            try {
                double value = Double.parseDouble(input);
                if (value > 0) {
                    return value;
                }
                System.out.println("Amount must be greater than zero.");
            } catch (NumberFormatException e) {
                System.out.println("Invalid amount. Please enter a valid number.");
            }
        }
    }

    private LocalDateTime readDateTime(String prompt) {
        while (true) {
            System.out.print(prompt);
            String input = scanner.nextLine().trim();
            try {
                return LocalDateTime.parse(input, Bus.DATE_TIME_FORMAT);
            } catch (DateTimeParseException e) {
                System.out.println("Invalid date/time format. Use yyyy-MM-dd HH:mm (e.g., 2026-11-27 21:30).");
            }
        }
    }

    private String readGender(String prompt) {
        while (true) {
            System.out.print(prompt);
            String input = scanner.nextLine().trim().toUpperCase();
            switch (input) {
                case "M", "MALE" -> {
                    return "M";
                }
                case "F", "FEMALE" -> {
                    return "F";
                }
                case "O", "OTHER" -> {
                    return "Other";
                }
                default -> System.out.println("Please enter M, F, or Other.");
            }
        }
    }
}
