package com.nammaroute;

import com.nammaroute.model.Booking;
import com.nammaroute.model.Bus;
import com.nammaroute.model.BusType;
import com.nammaroute.model.Passenger;
import com.nammaroute.model.WaitlistEntry;
import com.nammaroute.service.BookingService;
import com.nammaroute.service.BookingService.CancellationResult;
import com.nammaroute.service.BookingService.ManifestEntry;
import com.nammaroute.service.BookingService.SeatFarePreview;
import com.nammaroute.storage.CsvStorage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Self-contained Core Java verification tests for Version 1.0 and Version 2.0 features.
 */
public class BookingServiceTest {
    public static void main(String[] args) throws Exception {
        Path tempDir = Files.createTempDirectory("nammaroute-test");
        try {
            runV1AndV2Tests(tempDir);
            System.out.println("ALL TESTS PASSED!");
        } finally {
            deleteRecursively(tempDir);
        }
    }

    private static void runV1AndV2Tests(Path dataDir) throws Exception {
        CsvStorage storage = new CsvStorage(dataDir);
        BookingService service = new BookingService(storage);

        // 1. Seeded buses check and flexible Route ID lookup
        assertTrue(service.getAllBuses().size() == 3, "Expected 3 seeded buses");
        assertTrue(service.findBusById("nr 101") != null, "Space in Route ID should match NR-101");
        assertTrue(service.findBusById("nr101") != null, "Missing hyphen in Route ID should match NR-101");

        // 2. Add Bus (Feature 1)
        Bus newBus = service.addBus(
                "NR-201",
                "Bengaluru",
                "Kochi",
                LocalDateTime.of(2026, 12, 1, 20, 45),
                1350.0,
                BusType.SLEEPER_2X1
        );
        assertTrue(newBus.getBusType().getTotalSeats() == 24, "Sleeper should have 24 berths");
        assertThrows(() -> {
            try {
                service.addBus("NR-201", "A", "B", LocalDateTime.now(), 500, BusType.SEATER_2X2);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, "Duplicate Route ID must fail");

        // 3. Seat validation & booking (Feature 2 & 3)
        assertThrows(() -> service.validateSeatsForBooking("NR-101", List.of("99Z")),
                "Invalid seat 99Z must fail");
        assertThrows(() -> service.validateSeatsForBooking("NR-101", List.of("1A", "1A")),
                "Duplicate seat in same request must fail");

        Booking booking1 = service.bookTickets(
                "NR-101",
                List.of("1A", "1B"),
                List.of(new Passenger("Ananya Rao", 26, "F"), new Passenger("Karthik Rao", 29, "M"))
        );
        assertTrue(booking1.getPnr().startsWith("PNR-"), "PNR must start with PNR-");
        assertTrue(service.findBookingByPnr(booking1.getPnr().replace("-", " ").toLowerCase()) != null,
                "Space/lowercase in PNR should still match");
        assertTrue(booking1.getTotalAmount() == 2400.0, "Total amount should be 2400.0");
        assertTrue(service.findBusById("NR-101").findSeat("1A").isBooked(), "1A should be marked booked");

        assertThrows(() -> service.validateSeatsForBooking("NR-101", List.of("1A")),
                "Already booked seat 1A must fail");

        Booking booking2 = service.bookTickets(
                "NR-101",
                List.of("2B"),
                List.of(new Passenger("Rahul Verma", 34, "M"))
        );

        // 4. Passenger manifest sorted by seat order (Feature 5)
        List<ManifestEntry> manifest = service.getManifestForBus("NR-101");
        assertTrue(manifest.size() == 3, "Manifest should have 3 passengers");
        assertTrue(manifest.get(0).seatNumber().equals("1A"), "First seat in manifest should be 1A");
        assertTrue(manifest.get(1).seatNumber().equals("1B"), "Second seat in manifest should be 1B");
        assertTrue(manifest.get(2).seatNumber().equals("2B"), "Third seat in manifest should be 2B");

        // 5. Partial cancellation (Feature 4 - 90% refund)
        CancellationResult partialCancel = service.cancelBooking(booking1.getPnr(), List.of("1B"));
        assertTrue(!partialCancel.fullyCancelled(), "Should be partially cancelled");
        assertTrue(partialCancel.refundAmount() == 1080.0, "Refund for 1 seat (1200) should be 90% = 1080.0");
        assertTrue(!service.findBusById("NR-101").findSeat("1B").isBooked(), "1B should be free again");
        assertTrue(service.findBusById("NR-101").findSeat("1A").isBooked(), "1A should remain booked");

        // 6. V2 Change 2: Female Co-Seat Safety Rule (1A is Female, so 1B must block Male, allow Female)
        try {
            service.bookTickets("NR-101", List.of("1B"), List.of(new Passenger("Suresh", 30, "M")));
            throw new AssertionError("Male passenger should be blocked next to solo female 1A");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().equals("Sorry, seat 1B is reserved for a female co-passenger. Please choose another seat."),
                    "Unexpected safety error message: " + e.getMessage());
        }
        Booking femalePairBooking = service.bookTickets("NR-101", List.of("1B"), List.of(new Passenger("Divya", 25, "F")));
        assertTrue(femalePairBooking.getTotalAmount() == 1200.0, "Female co-passenger should be allowed");

        // Group/Family booking exception: husband and wife booking 1C and 1D together on same PNR
        Booking familyBooking = service.bookTickets(
                "NR-101",
                List.of("1C", "1D"),
                List.of(new Passenger("Sita", 30, "F"), new Passenger("Ram", 32, "M"))
        );
        assertTrue(familyBooking.getItems().size() == 2, "Group/family booking on same PNR should succeed");

        // 7. Restart persistence check
        BookingService reloadedService = new BookingService(new CsvStorage(dataDir));
        assertTrue(reloadedService.findBusById("NR-201") != null, "Added bus should persist across restart");
        assertTrue(reloadedService.findBusById("NR-101").findSeat("1A").isBooked(), "1A booking should persist");
        assertTrue(reloadedService.findBookingByPnr(booking2.getPnr()) != null, "PNR booking2 should persist");

        // 8. V2 Change 1: Dynamic Surge Pricing (70% Occupancy Rule)
        // NR-103 has 32 seats, base fare 950.0. 70% of 32 = 22.4 -> 23 seats booked triggers surge.
        List<String> first22Seats = new ArrayList<>();
        List<Passenger> first22Passengers = new ArrayList<>();
        Bus nr103 = reloadedService.findBusById("NR-103");
        for (int i = 0; i < 22; i++) {
            first22Seats.add(nr103.getSeats().get(i).getSeatNumber());
            first22Passengers.add(new Passenger("Pax" + i, 25, "M"));
        }
        reloadedService.bookTickets("NR-103", first22Seats, first22Passengers);
        assertTrue(!nr103.isSurgePricingActive(), "22/32 (68.75%) should not trigger surge yet");

        // 23rd seat sells at normal 950.0, pushing occupancy to 23/32 (71.875%)
        String seat23 = nr103.getSeats().get(22).getSeatNumber();
        Booking b23 = reloadedService.bookTickets("NR-103", List.of(seat23), List.of(new Passenger("Pax22", 28, "M")));
        assertTrue(b23.getTotalAmount() == 950.0, "23rd seat booked at 68.75% should still be normal 950.0");
        assertTrue(nr103.isSurgePricingActive(), "23/32 (71.875%) must activate 70% surge");

        // 24th seat previews and books at +20% surge (950 * 1.20 = 1140.0)
        String seat24 = nr103.getSeats().get(23).getSeatNumber();
        List<SeatFarePreview> surgePreview = reloadedService.previewSeatFares("NR-103", List.of(seat24));
        assertTrue(surgePreview.get(0).surgeApplied(), "Preview should show surge applied");
        assertTrue(surgePreview.get(0).finalFare() == 1140.0, "Surge fare should be 1140.0");
        Booking b24 = reloadedService.bookTickets("NR-103", List.of(seat24), List.of(new Passenger("Pax23", 29, "M")));
        assertTrue(b24.getTotalAmount() == 1140.0, "24th seat should be charged 1140.0");

        // Drop below 70% again -> normal price resumes, while earlier bookings keep original fare
        reloadedService.cancelBooking(b24.getPnr(), null);
        reloadedService.cancelBooking(b23.getPnr(), null);
        assertTrue(!nr103.isSurgePricingActive(), "22/32 should drop surge back below 70%");
        Booking bNormalAgain = reloadedService.bookTickets("NR-103", List.of(seat23), List.of(new Passenger("Pax22New", 28, "M")));
        assertTrue(bNormalAgain.getTotalAmount() == 950.0, "Price should return to normal 950.0 below 70%");

        // 9. Sleeper berth pricing (Lower = +10%, Upper = base price)
        Booking sleeperBooking = reloadedService.bookTickets(
                "NR-102",
                List.of("L1", "U1"),
                List.of(new Passenger("Meera Nair", 31, "F"), new Passenger("Arjun Nair", 33, "M"))
        );
        assertTrue(sleeperBooking.findItemBySeat("L1").getFare() == 1595.0,
                "Lower berth on 1450 base fare should be 1595.0 (+10%)");
        assertTrue(sleeperBooking.findItemBySeat("U1").getFare() == 1450.0,
                "Upper berth on 1450 base fare should be 1450.0 (base)");
        assertTrue(sleeperBooking.getTotalAmount() == 3045.0,
                "Total for L1 + U1 should be 3045.0");

        CancellationResult sleeperCancel = reloadedService.cancelBooking(sleeperBooking.getPnr(), List.of("L1"));
        assertTrue(sleeperCancel.refundAmount() == 1435.5,
                "90% refund of 1595.0 lower berth should be 1435.5");

        // 10. V2 Change 3: Waitlist FIFO & Smart Female Auto-Promotion + Persistence
        // Fill all 24 berths on NR-201 (2x1 Sleeper). Leave L1 booked by Female, L2 booked by Female, L3 by Male.
        Bus nr201 = reloadedService.findBusById("NR-201");
        Booking l1Booking = reloadedService.bookTickets("NR-201", List.of("L1"), List.of(new Passenger("Lakshmi", 27, "F")));
        Booking l2Booking = reloadedService.bookTickets("NR-201", List.of("L2"), List.of(new Passenger("Kavya", 28, "F")));
        Booking l3Booking = reloadedService.bookTickets("NR-201", List.of("L3"), List.of(new Passenger("Manoj", 30, "M")));
        assertTrue(l1Booking.getItems().size() == 1, "L1 booked");

        List<String> remainingBerths = new ArrayList<>();
        List<Passenger> remainingPax = new ArrayList<>();
        for (int i = 3; i < 24; i++) {
            remainingBerths.add(nr201.getSeats().get(i).getSeatNumber());
            remainingPax.add(new Passenger("SleeperPax" + i, 30, "M"));
        }
        reloadedService.bookTickets("NR-201", remainingBerths, remainingPax);
        assertTrue(nr201.getAvailableSeatCount() == 0, "NR-201 must be 100% full");

        // Join Waitlist (FIFO: #1 Vikram (M), #2 Priya (F), #3 Rohan (M))
        int pos1 = reloadedService.joinWaitlist("NR-201", new Passenger("Vikram", 26, "M"));
        int pos2 = reloadedService.joinWaitlist("NR-201", new Passenger("Priya", 24, "F"));
        int pos3 = reloadedService.joinWaitlist("NR-201", new Passenger("Rohan", 31, "M"));
        reloadedService.joinWaitlist("NR-201", new Passenger("Deepak", 35, "M"));
        reloadedService.joinWaitlist("NR-201", new Passenger("Neha", 29, "F"));
        assertTrue(pos1 == 1 && pos2 == 2 && pos3 == 3, "Positions should be FIFO 1, 2, 3");

        assertThrows(() -> {
            try {
                reloadedService.joinWaitlist("NR-201", new Passenger("Extra6", 22, "M"));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, "6th waitlist passenger should be rejected (max 5)");

        // Restart check for waitlist persistence
        BookingService afterRestart = new BookingService(new CsvStorage(dataDir));
        List<WaitlistEntry> persistedWl = afterRestart.getWaitlistForBus("NR-201");
        assertTrue(persistedWl.size() == 5, "Waitlist of 5 should persist across restart");
        assertTrue(persistedWl.get(0).getPassenger().getName().equals("Vikram"), "Vikram must still be #1");

        // Cancel L2 (partner L1 is solo Female!): Vikram (M) must be SKIPPED and stay #1, Priya (F) promoted!
        CancellationResult cancelL2 = afterRestart.cancelBooking(l2Booking.getPnr(), null);
        assertTrue(cancelL2.promotions().size() == 1, "1 passenger should be promoted to L2");
        assertTrue(cancelL2.promotions().get(0).passenger().getName().equals("Priya"),
                "Priya (F) should be promoted over Vikram (M) due to female co-seat rule");
        assertTrue(cancelL2.promotions().get(0).seatNumber().equals("L2"), "Priya should get L2");
        assertTrue(afterRestart.getWaitlistForBus("NR-201").get(0).getPassenger().getName().equals("Vikram"),
                "Skipped Vikram (M) must remain at front (#1) of waitlist");

        // Cancel L3 (single berth, no partner restriction): Vikram (M, #1) should now be promoted!
        CancellationResult cancelL3 = afterRestart.cancelBooking(l3Booking.getPnr(), null);
        assertTrue(cancelL3.promotions().size() == 1, "Vikram should be promoted to L3");
        assertTrue(cancelL3.promotions().get(0).passenger().getName().equals("Vikram"),
                "Vikram (#1) should be promoted to solo berth L3");
        assertTrue(afterRestart.getWaitlistForBus("NR-201").size() == 3, "Waitlist should now have 3 remaining");
        assertTrue(afterRestart.getWaitlistForBus("NR-201").get(0).getPassenger().getName().equals("Rohan"),
                "Rohan should now be #1 in waitlist");
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError("Assertion failed: " + message);
        }
    }

    private static void assertThrows(Runnable action, String message) {
        try {
            action.run();
        } catch (RuntimeException e) {
            return;
        }
        throw new AssertionError("Expected exception was not thrown: " + message);
    }

    private static void deleteRecursively(Path dir) throws Exception {
        if (Files.exists(dir)) {
            Files.walk(dir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (Exception ignored) {
                        }
                    });
        }
    }
}
