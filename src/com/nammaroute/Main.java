package com.nammaroute;

import com.nammaroute.service.BookingService;
import com.nammaroute.storage.CsvStorage;
import com.nammaroute.ui.ConsoleMenu;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Scanner;

/**
 * Entry point for the NammaRoute Travels console application.
 */
public class Main {
    public static void main(String[] args) {
        CsvStorage storage = new CsvStorage(Path.of("data"));
        try {
            BookingService service = new BookingService(storage);
            try (Scanner scanner = new Scanner(System.in)) {
                ConsoleMenu menu = new ConsoleMenu(service, scanner);
                menu.start();
            }
        } catch (IOException e) {
            System.err.println("Failed to load or save application data: " + e.getMessage());
        }
    }
}
