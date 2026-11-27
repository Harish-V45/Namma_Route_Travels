# NammaRoute Travels (Core Java)

A simple, menu-driven Core Java 17 console application for managing intercity bus routes, visual seat maps, ticket bookings, cancellations, and conductor passenger manifests with local CSV file persistence.

## Project Structure

```
src/com/nammaroute/
├── Main.java                     # Application entry point
├── model/
│   ├── Bus.java                  # Bus route details and seat layout generator
│   ├── BusType.java              # 2x2 Seater (32 seats) and 2x1 Sleeper (24 berths)
│   ├── Seat.java                 # Individual seat / berth state
│   ├── Passenger.java            # Passenger name, age, and gender
│   ├── TicketItem.java           # Seat + passenger + fare entry inside a booking
│   └── Booking.java              # PNR booking transaction
├── storage/
│   └── CsvStorage.java           # Reads/writes data/buses.csv and data/bookings.csv
├── service/
│   └── BookingService.java       # Core booking, cancellation, and manifest rules
└── ui/
    └── ConsoleMenu.java          # Console menus, input validation, and output formatting
```

## How to Compile and Run

From the project root directory:

```powershell
mkdir out -Force
javac -d out (Get-ChildItem -Recurse -Filter *.java src | ForEach-Object { $_.FullName })
java -cp out com.nammaroute.Main
```

Or with Command Prompt (`cmd`):

```bat
mkdir out
dir /s /B src\*.java > sources.txt
javac -d out @sources.txt
java -cp out com.nammaroute.Main
```

## Data Persistence

All bus services and ticket bookings are automatically saved to:
- `data/buses.csv`
- `data/bookings.csv`

On first startup (when `data/buses.csv` does not exist), three sample routes (`NR-101`, `NR-102`, `NR-103`) are created automatically. Any new buses, bookings, or cancellations are immediately saved and restored on restart.
