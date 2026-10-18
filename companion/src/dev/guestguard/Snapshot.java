package dev.guestguard;

import java.util.ArrayList;
import java.util.List;

final class Snapshot {
    static final class User {
        int id;
        String type, flags, name;
        boolean guest() { return "guest".equals(type); }
        boolean switchable() { return id == 0 || (Integer.parseInt(flags, 16) & 6240) == 0; }
    }
    int current = -1;
    int max = -1;
    String android = "Unknown", property = "Not set";
    final List<User> users = new ArrayList<User>();
    int guests() { int n = 0; for (User u : users) if (u.guest()) n++; return n; }
    static Snapshot parse(String text) throws Exception {
        Snapshot s = new Snapshot();
        for (String line : text.split("\n")) {
            String[] p = line.split("\t", 5);
            if (p.length < 2) continue;
            if ("CURRENT".equals(p[0])) s.current = Integer.parseInt(p[1]);
            else if ("MAX".equals(p[0]) && !"unknown".equals(p[1])) s.max = Integer.parseInt(p[1]);
            else if ("ANDROID".equals(p[0])) s.android = p[1];
            else if ("PROPERTY_MAX".equals(p[0])) s.property = p[1];
            else if ("USER".equals(p[0]) && p.length == 5) {
                User u = new User();
                u.id = Integer.parseInt(p[1]); u.type = p[2]; u.flags = p[3]; u.name = p[4];
                // Defense in depth; backend remains the authority at execution time.
                int flags = Integer.parseInt(u.flags, 16);
                if (u.id == 0) u.type = "owner";
                else if ((flags & 4) == 0 || (flags & 22571) != 0) u.type = "protected";
                s.users.add(u);
            }
        }
        boolean owner = false;
        for (User u : s.users) if (u.id == 0) owner = true;
        if (s.current < 0 || !owner) throw new Exception("Incomplete response. User actions are disabled; refresh after checking the module installation.");
        return s;
    }
}
