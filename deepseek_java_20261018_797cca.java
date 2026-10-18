package com.example.usermanager;

public class User {
    public final int id;
    public final String name;
    public final boolean running;

    public User(int id, String name, boolean running) {
        this.id = id;
        this.name = name;
        this.running = running;
    }
}