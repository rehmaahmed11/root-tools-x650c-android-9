package dev.guestguard;

/** Runs against real JVM classes, not Android stubs, using the production model. */
public final class CompanionTest {
    private static int checks;
    private static void check(boolean ok, String description) {
        if (!ok) throw new AssertionError(description);
        checks++;
    }
    public static void main(String[] args) throws Exception {
        String base = "CURRENT\t0\nMAX\t4\nANDROID\t9\nPROPERTY_MAX\t4\n";
        Snapshot s = Snapshot.parse(base + "USER\t0\towner\t13\tOwner\nUSER\t10\tguest\t104\tWeekend: Guest\nUSER\t11\tprotected\t10\tPersonal\n");
        check(s.current == 0 && s.max == 4, "capacity and current ID");
        check(s.users.size() == 3 && s.guests() == 1, "counts");
        check(s.users.get(1).name.equals("Weekend: Guest"), "names remain display data");
        check(s.users.get(2).switchable(), "regular full users may switch but cannot be deleted");
        check(s.android.equals("9") && s.property.equals("4"), "device information");
        s = Snapshot.parse("CURRENT\t0\nMAX\tunknown\nUSER\t0\tguest\t4\tOwner\n");
        check(!s.users.get(0).guest(), "owner cannot become a deletable guest");
        check(s.max == -1, "unknown is not unlimited");
        for (int protectedFlag : new int[]{1, 2, 8, 32, 2048, 4096, 16384}) {
            s = Snapshot.parse(base + "USER\t0\towner\t13\tOwner\nUSER\t10\tguest\t" + Integer.toHexString(protectedFlag | 4) + "\tConflicting type\n");
            check(!s.users.get(1).guest(), "protected flags override guest");
        }
        s = Snapshot.parse(base + "USER\t0\towner\t13\tOwner\nUSER\t10\tguest\t10\tGuest by name only\n");
        check(!s.users.get(1).guest(), "name does not establish guest type");
        try { Snapshot.parse("CURRENT\t0\nUSER\t10\tguest\t4\tGuest\n"); throw new AssertionError("missing owner accepted"); }
        catch (AssertionError e) { throw e; } catch (Exception expected) { checks++; }
        try { Snapshot.parse("USER\t0\towner\t13\tOwner\n"); throw new AssertionError("missing current accepted"); }
        catch (AssertionError e) { throw e; } catch (Exception expected) { checks++; }
        check(RootClient.quote("Guest").equals("'Guest'"), "plain argument quoting");
        check(RootClient.quote("a'b;$(reboot)").equals("'a'\\''b;$(reboot)'"), "shell metacharacters stay quoted");
        System.out.println("Companion model/quoting: " + checks + " checks passed");
    }
}
