package com.nammaroute.storage;

import com.nammaroute.model.Booking;
import com.nammaroute.model.Bus;
import com.nammaroute.model.BusType;
import com.nammaroute.model.Passenger;
import com.nammaroute.model.Seat;
import com.nammaroute.model.TicketItem;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Handles reading and writing bus and booking data to local CSV files.
 */
public class CsvStorage {
    private static final String BUSES_HEADER = "routeId,origin,destination,departureTime,basePrice,busType";
    private static final String BOOKINGS_HEADER = "pnr,routeId,bookingTime,seatNumber,passengerName,age,gender,fare";

    private final Path busesFile;
    private final Path bookingsFile;

    public CsvStorage(Path dataDirectory) {
        this.busesFile = dataDirectory.resolve("buses.csv");
        this.bookingsFile = dataDirectory.resolve("bookings.csv");
    }

    public boolean busesFileExists() {
        return Files.exists(busesFile);
    }

    public List<Bus> loadBuses() throws IOException {
        List<Bus> buses = new ArrayList<>();
        if (!Files.exists(busesFile)) {
            return buses;
        }

        List<String> lines = Files.readAllLines(busesFile, StandardCharsets.UTF_8);
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty()) {
                continue;
            }
            List<String> fields = parseCsvLine(line);
            if (fields.size() < 6) {
                continue;
            }
            String routeId = fields.get(0);
            String origin = fields.get(1);
            String destination = fields.get(2);
            LocalDateTime departureTime = LocalDateTime.parse(fields.get(3), Bus.DATE_TIME_FORMAT);
            double basePrice = Double.parseDouble(fields.get(4));
            BusType busType = BusType.valueOf(fields.get(5));

            buses.add(new Bus(routeId, origin, destination, departureTime, basePrice, busType));
        }
        return buses;
    }

    public List<Booking> loadBookings(List<Bus> buses) throws IOException {
        List<Booking> bookings = new ArrayList<>();
        if (!Files.exists(bookingsFile)) {
            return bookings;
        }

        Map<String, Bus> busMap = new LinkedHashMap<>();
        for (Bus bus : buses) {
            busMap.put(bus.getRouteId(), bus);
        }

        // Group ticket rows by PNR while preserving file order
        Map<String, List<TicketItem>> itemsByPnr = new LinkedHashMap<>();
        Map<String, String> routeByPnr = new LinkedHashMap<>();
        Map<String, LocalDateTime> timeByPnr = new LinkedHashMap<>();

        List<String> lines = Files.readAllLines(bookingsFile, StandardCharsets.UTF_8);
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty()) {
                continue;
            }
            List<String> fields = parseCsvLine(line);
            if (fields.size() < 8) {
                continue;
            }
            String pnr = fields.get(0).toUpperCase();
            String routeId = fields.get(1).toUpperCase();
            LocalDateTime bookingTime = LocalDateTime.parse(fields.get(2), Bus.DATE_TIME_FORMAT);
            String seatNumber = fields.get(3).toUpperCase();
            String passengerName = fields.get(4);
            int age = Integer.parseInt(fields.get(5));
            String gender = fields.get(6);
            double fare = Double.parseDouble(fields.get(7));

            Passenger passenger = new Passenger(passengerName, age, gender);
            TicketItem item = new TicketItem(seatNumber, passenger, fare);

            itemsByPnr.computeIfAbsent(pnr, k -> new ArrayList<>()).add(item);
            routeByPnr.putIfAbsent(pnr, routeId);
            timeByPnr.putIfAbsent(pnr, bookingTime);

            // Sync booked status on the matching bus seat
            Bus bus = busMap.get(routeId);
            if (bus != null) {
                Seat seat = bus.findSeat(seatNumber);
                if (seat != null) {
                    seat.setBooked(true);
                }
            }
        }

        for (String pnr : itemsByPnr.keySet()) {
            bookings.add(new Booking(
                    pnr,
                    routeByPnr.get(pnr),
                    timeByPnr.get(pnr),
                    itemsByPnr.get(pnr)
            ));
        }
        return bookings;
    }

    public void saveAll(List<Bus> buses, List<Booking> bookings) throws IOException {
        Path parent = busesFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        saveBuses(buses);
        saveBookings(bookings);
    }

    private void saveBuses(List<Bus> buses) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add(BUSES_HEADER);
        for (Bus bus : buses) {
            lines.add(String.join(",",
                    escapeCsv(bus.getRouteId()),
                    escapeCsv(bus.getOrigin()),
                    escapeCsv(bus.getDestination()),
                    escapeCsv(bus.getFormattedDepartureTime()),
                    String.valueOf(bus.getBasePrice()),
                    escapeCsv(bus.getBusType().name())
            ));
        }
        Files.write(busesFile, lines, StandardCharsets.UTF_8);
    }

    private void saveBookings(List<Booking> bookings) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add(BOOKINGS_HEADER);
        for (Booking booking : bookings) {
            String timeStr = booking.getBookingTime().format(Bus.DATE_TIME_FORMAT);
            for (TicketItem item : booking.getItems()) {
                Passenger p = item.getPassenger();
                lines.add(String.join(",",
                        escapeCsv(booking.getPnr()),
                        escapeCsv(booking.getRouteId()),
                        escapeCsv(timeStr),
                        escapeCsv(item.getSeatNumber()),
                        escapeCsv(p.getName()),
                        String.valueOf(p.getAge()),
                        escapeCsv(p.getGender()),
                        String.valueOf(item.getFare())
                ));
            }
        }
        Files.write(bookingsFile, lines, StandardCharsets.UTF_8);
    }

    private static String escapeCsv(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private static List<String> parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    current.append(ch);
                }
            } else {
                if (ch == '"') {
                    inQuotes = true;
                } else if (ch == ',') {
                    fields.add(current.toString());
                    current.setLength(0);
                } else {
                    current.append(ch);
                }
            }
        }
        fields.add(current.toString());
        return fields;
    }
}
