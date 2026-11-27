package com.nammaroute.model;

/**
 * Represents a passenger travelling on a booked seat.
 */
public class Passenger {
    private final String name;
    private final int age;
    private final String gender;

    public Passenger(String name, int age, String gender) {
        this.name = name.trim();
        this.age = age;
        this.gender = gender.trim();
    }

    public String getName() {
        return name;
    }

    public int getAge() {
        return age;
    }

    public String getGender() {
        return gender;
    }
}
