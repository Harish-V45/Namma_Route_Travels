package com.nammaroute;

import com.nammaroute.model.Booking;
import com.nammaroute.model.Bus;
import com.nammaroute.model.BusType;
import com.nammaroute.model.Passenger;
import com.nammaroute.service.BookingService;
import com.nammaroute.service.BookingService.CancellationResult;
import com.nammaroute.service.BookingService.ManifestEntry;
import com.nammaroute.storage.CsvStorage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * Self-contained Core Java verification tests for all Version 1.0 features.
 */
public class BookingServiceTest {
    public static void main(String[] args) throws Exception {
        Path tempDir = Files.createTempDirectory("nammaroute-test");
        try {
            runAllTests(tempDir);
            System.out.println("ALL TESTS PASSED!");
        } finally {
            deleteRecursively(tempDir);
        }
    }

    private static void runAllTests(Path dataDir) throws Exception {
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

        // 6. Restart persistence check
        BookingService reloadedService = new BookingService(new CsvStorage(dataDir));
        assertTrue(reloadedService.findBusById("NR-201") != null, "Added bus should persist across restart");
        assertTrue(reloadedService.findBusById("NR-101").findSeat("1A").isBooked(), "1A booking should persist");
        assertTrue(!reloadedService.findBusById("NR-101").findSeat("1B").isBooked(), "1B cancellation should persist");
        assertTrue(reloadedService.findBookingByPnr(booking2.getPnr()) != null, "PNR booking2 should persist");

        // 7. Full cancellation of remaining seat
        CancellationResult fullCancel = reloadedService.cancelBooking(booking1.getPnr(), null);
        assertTrue(fullCancel.fullyCancelled(), "Booking1 should now be fully cancelled");
        assertTrue(fullCancel.refundAmount() == 1080.0, "Refund for remaining seat (1200) should be 90% = 1080.0");
        assertTrue(!reloadedService.findBusById("NR-101").findSeat("1A").isBooked(), "1A should be free after full cancel");
        assertTrue(reloadedService.findBookingByPnr(booking1.getPnr()) == null, "Fully cancelled PNR should be removed");

        // 8. Sleeper berth pricing (Lower = +10%, Upper = base price)
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
