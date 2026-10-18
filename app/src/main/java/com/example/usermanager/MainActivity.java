package com.example.usermanager;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Android multi-user controls; requested shell operations run off the UI thread. */
public class MainActivity extends Activity {
    private static final int INK = Color.rgb(29, 43, 37);
    private static final int MUTED = Color.rgb(112, 127, 119);
    private static final int GREEN = Color.rgb(23, 139, 94);
    private static final int BG = Color.rgb(245, 248, 246);
    private static final int CARD = Color.WHITE;
    private static final int LINE = Color.rgb(230, 237, 232);
    private final RootShell rootShell = new RootShell();
    private boolean actionRunning;
    private boolean loadingUsers;
    private boolean usersLoaded;
    private String usersLoadError;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<DeviceUser> users = new ArrayList<>();
    private FrameLayout host;
    private LinearLayout page;
    private TextView homeUsersLabel;
    private boolean showingUsers;
    private int currentUser = -1;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        // Open the interface immediately. The real user-list command is issued
        // only when the user opens Guest controls, with no separate root preflight.
        renderHome();
    }

    private void renderHome() {
        showingUsers = false;
        buildPage();
        addToolbar();
        addSpacer(34);
        addLabel("DEVICE USERS", 11, GREEN, true, 2);
        addText("Your device,\nyour space.", 34, INK, true, 0);
        addText("A simple home for managing Android profiles.", 15, MUTED, false, 12);

        LinearLayout hero = card(22);
        hero.setBackground(roundDrawable(Color.rgb(27, 57, 45), 24));
        hero.setPadding(dp(22), dp(20), dp(22), dp(20));
        TextView heroOver = text("MULTI-USER TOOLS", 11, Color.rgb(176, 224, 198), true);
        hero.addView(heroOver);
        TextView heroTitle = text("Manage your profiles", 21, Color.WHITE, true);
        LinearLayout.LayoutParams htp = params(-1, -2); htp.topMargin = dp(8); hero.addView(heroTitle, htp);
        TextView heroInfo = text("Create a GuestUser profile, switch between users, and manage device accounts.",
                14, Color.rgb(211, 226, 217), false);
        heroInfo.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams hip = params(-1, -2); hip.topMargin = dp(6); hero.addView(heroInfo, hip);
        LinearLayout.LayoutParams hp = params(-1, -2); hp.topMargin = dp(26); page.addView(hero, hp);

        TextView section = text("TOOLS", 11, MUTED, true);
        LinearLayout.LayoutParams sp = params(-1, -2); sp.topMargin = dp(30); page.addView(section, sp);
        LinearLayout manage = card(18);
        manage.setBackground(roundDrawable(CARD, 20));
        manage.setClickable(true); manage.setFocusable(true);
        manage.setOnClickListener(v -> openUserManager());
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
        TextView icon = text("01", 13, GREEN, true); icon.setGravity(Gravity.CENTER);
        icon.setBackground(roundDrawable(Color.rgb(231, 246, 237), 15));
        row.addView(icon, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout labels = new LinearLayout(this); labels.setOrientation(LinearLayout.VERTICAL);
        TextView title = text("Guest user manager", 17, INK, true);
        TextView subtitle = text(homeUsersSummary(), 13, MUTED, false);
        homeUsersLabel = subtitle;
        labels.addView(title); LinearLayout.LayoutParams subp = params(-1, -2); subp.topMargin = dp(4); labels.addView(subtitle, subp);
        LinearLayout.LayoutParams labelp = new LinearLayout.LayoutParams(0, -2, 1); labelp.leftMargin = dp(14); row.addView(labels, labelp);
        TextView arrow = text("›", 30, MUTED, false); row.addView(arrow);
        manage.addView(row);
        LinearLayout.LayoutParams mp = params(-1, -2); mp.topMargin = dp(12); page.addView(manage, mp);
        TextView hint = text("List users, create GuestUser, switch profiles, and safely remove a guest account.", 13, MUTED, false);
        hint.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams hintp = params(-1, -2); hintp.topMargin = dp(12); page.addView(hint, hintp);
        addFooter();
    }

    private String homeUsersSummary() {
        if (loadingUsers) return "Loading device users…";
        if (usersLoaded) return users.size() + (users.size() == 1 ? " device user" : " device users");
        if (usersLoadError != null) return "Could not load users · tap to open";
        return "Create, switch, and remove GuestUser";
    }

    private void openUserManager() {
        renderUsers();
        refreshUsers(false);
    }

    private void renderUsers() {
        showingUsers = true;
        buildPage();
        addToolbar();
        LinearLayout head = new LinearLayout(this); head.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = text("‹", 30, INK, false); back.setGravity(Gravity.CENTER);
        head.addView(back, new LinearLayout.LayoutParams(dp(42), dp(48)));
        back.setOnClickListener(v -> renderHome());
        LinearLayout heading = new LinearLayout(this); heading.setOrientation(LinearLayout.VERTICAL);
        TextView title = text("Guest controls", 27, INK, true);
        String profileSummary = loadingUsers ? "Loading device users…"
                : usersLoaded ? users.size() + (users.size() == 1 ? " profile on this device" : " profiles on this device")
                : "Device user management";
        TextView subtitle = text(profileSummary, 13, MUTED, false);
        heading.addView(title); LinearLayout.LayoutParams sub = params(-1, -2); sub.topMargin = dp(3); heading.addView(subtitle, sub);
        head.addView(heading);
        LinearLayout.LayoutParams headp = params(-1, -2); headp.topMargin = dp(24); page.addView(head, headp);

        DeviceUser guest = findGuest();
        Button toggle = button("⇄    Toggle Owner / Guest", true);
        LinearLayout.LayoutParams togglep = params(-1, 54); togglep.topMargin = dp(22); page.addView(toggle, togglep);
        toggle.setEnabled(usersLoaded && !loadingUsers && guest != null);
        toggle.setAlpha(toggle.isEnabled() ? 1f : .45f);
        toggle.setOnClickListener(v -> toggleGuest());

        Button create = button(guest == null ? "＋    Create GuestUser" : "✓    GuestUser exists · ID " + guest.id, false);
        LinearLayout.LayoutParams createp = params(-1, 52); createp.topMargin = dp(10); page.addView(create, createp);
        create.setEnabled(usersLoaded && !loadingUsers && guest == null);
        create.setAlpha(create.isEnabled() ? 1f : .55f);
        create.setOnClickListener(v -> createGuest());

        LinearLayout listCard = card(0); listCard.setBackground(roundDrawable(CARD, 20));
        LinearLayout.LayoutParams listp = params(-1, -2); listp.topMargin = dp(20); page.addView(listCard, listp);
        if (users.isEmpty()) {
            String emptyMessage = loadingUsers ? "Loading device users…"
                    : usersLoadError != null ? usersLoadError
                    : usersLoaded ? "No users were returned by Android.\nTry refreshing the list."
                    : "User list has not been loaded yet.";
            TextView empty = text(emptyMessage, 14, MUTED, false);
            empty.setGravity(Gravity.CENTER); empty.setPadding(dp(20), dp(34), dp(20), dp(34)); listCard.addView(empty);
        } else {
            for (int i = 0; i < users.size(); i++) {
                DeviceUser user = users.get(i);
                listCard.addView(userRow(user));
                if (i < users.size() - 1) {
                    View divider = new View(this); divider.setBackgroundColor(LINE);
                    LinearLayout.LayoutParams dpv = params(-1, 1); dpv.leftMargin = dp(18); dpv.rightMargin = dp(18); listCard.addView(divider, dpv);
                }
            }
        }
        TextView note = text("Long-press any profile for actions. The owner profile is protected and can never be deleted.", 13, MUTED, false);
        note.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams notep = params(-1, -2); notep.topMargin = dp(16); page.addView(note, notep);
        TextView refresh = text(loadingUsers ? "Refreshing user list…" : "↻   Refresh user list", 14, GREEN, true);
        refresh.setGravity(Gravity.CENTER); refresh.setPadding(0, dp(18), 0, dp(18));
        refresh.setEnabled(!loadingUsers); refresh.setAlpha(loadingUsers ? .55f : 1f);
        LinearLayout.LayoutParams refreshp = params(-1, -2); refreshp.topMargin = dp(4); page.addView(refresh, refreshp);
        refresh.setOnClickListener(v -> refreshUsers(true));
        addFooter();
    }

    private View userRow(DeviceUser user) {
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(17), dp(15), dp(16), dp(15)); row.setMinimumHeight(dp(78));
        TextView avatar = text(initials(user.name), 15, GREEN, true); avatar.setGravity(Gravity.CENTER);
        avatar.setBackground(roundDrawable(Color.rgb(232, 246, 237), 18));
        row.addView(avatar, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout details = new LinearLayout(this); details.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(user.name, 16, INK, true);
        LinearLayout.LayoutParams namep = params(-1, -2); details.addView(name, namep);
        String identity = user.id == 0 ? "Owner · ID 0" : "User · ID " + user.id;
        if (user.id == currentUser) identity += "  ·  CURRENT";
        else if (user.running) identity += "  ·  RUNNING";
        TextView meta = text(identity, 11, user.id == currentUser ? GREEN : MUTED, true);
        LinearLayout.LayoutParams metap = params(-1, -2); metap.topMargin = dp(5); details.addView(meta, metap);
        LinearLayout.LayoutParams detailp = new LinearLayout.LayoutParams(0, -2, 1); detailp.leftMargin = dp(13); row.addView(details, detailp);
        TextView menu = text("•••", 17, MUTED, true); menu.setGravity(Gravity.CENTER);
        row.addView(menu, new LinearLayout.LayoutParams(dp(36), dp(44)));
        View.OnLongClickListener actions = v -> { showUserActions(user); return true; };
        row.setOnLongClickListener(actions); name.setOnLongClickListener(actions); avatar.setOnLongClickListener(actions);
        row.setOnClickListener(v -> showUserActions(user));
        menu.setOnClickListener(v -> showUserActions(user));
        row.setBackground(ripple(roundDrawable(Color.TRANSPARENT, 0)));
        return row;
    }

    private void showUserActions(DeviceUser user) {
        boolean guest = "GuestUser".equals(user.name);
        String[] actions = guest
                ? new String[]{user.id == currentUser ? "Currently active" : "Switch to GuestUser", "Delete GuestUser"}
                : new String[]{user.id == currentUser ? "Currently active" : "Switch to this user"};
        new AlertDialog.Builder(this)
                .setTitle(user.name + (user.id == 0 ? "  ·  Owner" : guest ? "  ·  Guest" : ""))
                .setItems(actions, (dialog, which) -> {
                    if (which == 0) {
                        if (user.id != currentUser) confirmSwitch(user);
                    } else if (guest) confirmDelete(user);
                }).setNegativeButton("Cancel", null).show();
    }

    private void confirmSwitch(DeviceUser user) {
        new AlertDialog.Builder(this).setTitle("Switch user?")
                .setMessage("Switch Android to “" + user.name + "”? Your device will move to that user’s profile.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Switch", (d, w) -> executeAction("Switching to " + user.name + "…", "am switch-user " + user.id, true))
                .show();
    }

    private void confirmDelete(DeviceUser user) {
        if (user.id == 0) { showMessage("The owner profile is protected and cannot be deleted."); return; }
        new AlertDialog.Builder(this).setTitle("Delete “" + user.name + "”?")
                .setMessage("This permanently removes this Android user and its local data. This cannot be undone.")
                .setNegativeButton("Keep user", null)
                .setPositiveButton("Delete", (d, w) -> {
                    String command = "pm remove-user --guest " + user.id;
                    if (user.id == currentUser) command = "am switch-user 0 && sleep 2 && " + command;
                    executeAction("Deleting user…", command, false);
                }).show();
    }

    private DeviceUser findGuest() {
        for (DeviceUser user : users) if ("GuestUser".equals(user.name)) return user;
        return null;
    }

    private void createGuest() {
        if (findGuest() != null) { showMessage("GuestUser already exists."); return; }
        new AlertDialog.Builder(this).setTitle("Create GuestUser?")
                .setMessage("Android will create a separate guest profile with its own apps and data.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Create guest", (d, w) ->
                        executeAction("Creating GuestUser…", "pm create-user --guest GuestUser", false))
                .show();
    }

    private void toggleGuest() {
        DeviceUser guest = findGuest();
        if (guest == null) { showMessage("No GuestUser exists. Create it first."); return; }
        String destination = currentUser == 0 ? "GuestUser" : "Owner";
        new AlertDialog.Builder(this).setTitle("Switch to " + destination + "?")
                .setMessage("This uses the same automatic toggle as the working Termux script.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Switch", (d, w) -> executeAction("Switching to " + destination + "…",
                        "if [ $(am get-current-user) -eq 0 ]; then am switch-user " + guest.id
                                + "; else am switch-user 0; fi", true))
                .show();
    }

    private void showRenameDialog(DeviceUser user) {
        if (user.id == 0) { showMessage("The owner profile name is managed by Android and cannot be changed here."); return; }
        EditText input = nameInput(user.name);
        new AlertDialog.Builder(this).setTitle("Rename user")
                .setView(dialogInput(input)).setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (!validName(name)) { showMessage("Choose a name from 1 to 32 characters (letters, numbers, spaces, _ . or -)."); return; }
                    executeAction("Renaming user…", "pm rename-user " + user.id + " " + shellQuote(name), false);
                }).show();
    }

    private void executeAction(String pendingMessage, String command, boolean switchAction) {
        if (actionRunning) { showMessage("An operation is already in progress."); return; }
        actionRunning = true;
        Toast.makeText(this, pendingMessage, Toast.LENGTH_SHORT).show();
        worker.execute(() -> {
            RootShell.Result result = runRoot(command, 25);
            main.post(() -> {
                actionRunning = false;
                if (isFinishing() || isDestroyed()) return;
                if (result.commandStarted && result.exitCode == 0 && !result.timedOut
                        && !Pattern.compile("(?im)^\\s*(error:|failure|exception|java\\.)").matcher(result.output).find()) {
                    if (switchAction) showMessage("Switch request sent to Android.");
                    else showMessage(successMessage(command, result.output));
                    refreshUsers(false);
                } else {
                    String reason = result.timedOut ? "The command timed out. Refresh before retrying: Android may still complete it."
                            : cleanError(result.output);
                    showError("Could not complete action", reason);
                    // Do not silently issue a second privileged command after a failure.
                    // Let the user explicitly refresh if they want another command sent.
                }
            });
        });
    }

    private String successMessage(String command, String output) {
        if (command.startsWith("pm create-user")) return output.contains("Success") ? output.trim() : "User created.";
        if (command.startsWith("pm rename-user")) return "User renamed.";
        if (command.contains("pm remove-user")) return output.toLowerCase(Locale.ROOT).contains("success") ? output.trim() : "User removed.";
        return "Done.";
    }

    private void refreshUsers(boolean announce) {
        if (loadingUsers) {
            if (announce) showMessage("The user list is already refreshing.");
            return;
        }
        loadingUsers = true;
        usersLoadError = null;
        if (showingUsers) renderUsers();
        else if (homeUsersLabel != null) homeUsersLabel.setText(homeUsersSummary());

        worker.execute(() -> {
            // Run the requested operation immediately through su; no separate `id` preflight.
            RootShell.Result list = runRoot("pm list users", 25);
            RootShell.Result current = list.commandStarted && list.exitCode == 0 && !list.timedOut
                    ? runRoot("am get-current-user", 25) : null;
            List<DeviceUser> parsed = parseUsers(list.output);
            int active = -1;
            if (current != null && current.commandStarted && current.exitCode == 0 && !current.timedOut) {
                Matcher m = Pattern.compile("(?m)^\\s*(\\d+)\\s*$").matcher(current.output);
                if (m.find()) try { active = Integer.parseInt(m.group(1)); } catch (Exception ignored) {}
            }
            final int activeId = active;
            main.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                loadingUsers = false;
                boolean listSucceeded = list.commandStarted && list.exitCode == 0 && !list.timedOut;
                if (!listSucceeded) {
                    users.clear();
                    currentUser = -1;
                    usersLoaded = false;
                    usersLoadError = cleanError(list.output);
                } else {
                    users.clear();
                    users.addAll(parsed);
                    currentUser = activeId;
                    usersLoaded = true;
                    usersLoadError = null;
                }
                if (showingUsers) renderUsers();
                else if (homeUsersLabel != null) homeUsersLabel.setText(homeUsersSummary());

                if (!listSucceeded && announce) showError("Unable to read users", usersLoadError);
                else if (listSucceeded && announce) showMessage("User list refreshed.");
            });
        });
    }

    private List<DeviceUser> parseUsers(String output) {
        List<DeviceUser> result = new ArrayList<>();
        Pattern pattern = Pattern.compile("UserInfo\\{(\\d+):([^}:]*)(?::[^}]*)?}([^\\n]*)");
        Matcher matcher = pattern.matcher(output);
        while (matcher.find()) {
            try {
                int id = Integer.parseInt(matcher.group(1));
                String name = matcher.group(2).trim();
                boolean running = matcher.group(3).toLowerCase(Locale.ROOT).contains("running");
                result.add(new DeviceUser(id, name.isEmpty() ? (id == 0 ? "Owner" : "User " + id) : name, running));
            } catch (Exception ignored) { }
        }
        return result;
    }

    private void buildPage() {
        host = new FrameLayout(this); host.setBackgroundColor(BG);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false);
        page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(22), dp(12), dp(22), dp(26));
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2)); host.addView(scroll);
        setContentView(host);
    }

    private void addToolbar() {
        LinearLayout bar = new LinearLayout(this); bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView mark = text("R", 17, Color.WHITE, true); mark.setGravity(Gravity.CENTER);
        mark.setBackground(roundDrawable(Color.rgb(28, 78, 54), 13));
        bar.addView(mark, new LinearLayout.LayoutParams(dp(38), dp(38)));
        TextView appName = text("ROOT TOOLS", 13, INK, true);
        LinearLayout.LayoutParams an = new LinearLayout.LayoutParams(0, -2, 1); an.leftMargin = dp(10); bar.addView(appName, an);
        page.addView(bar, params(-1, 46));
    }

    private void addFooter() {
        TextView footer = text("BUILT FOR ROOTED ANDROID", 10, Color.rgb(153, 165, 158), true);
        footer.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams fp = params(-1, -2); fp.topMargin = dp(38); page.addView(footer, fp);
    }

    private void addSpacer(int height) { View spacer = new View(this); page.addView(spacer, params(1, height)); }
    private LinearLayout card(int padding) { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); l.setPadding(dp(padding), dp(padding), dp(padding), dp(padding)); return l; }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color);
        t.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL)); t.setIncludeFontPadding(true); return t;
    }
    private void addLabel(String value, int size, int color, boolean bold, int margin) {
        TextView t = text(value, size, color, bold); LinearLayout.LayoutParams p = params(-1, -2); p.bottomMargin = dp(margin); page.addView(t, p);
    }
    private void addText(String value, int size, int color, boolean bold, int bottom) {
        TextView t = text(value, size, color, bold); LinearLayout.LayoutParams p = params(-1, -2); p.bottomMargin = dp(bottom); page.addView(t, p);
    }

    private Button button(String label, boolean primary) {
        Button b = new Button(this); b.setText(label); b.setAllCaps(false); b.setTextSize(15); b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setTextColor(primary ? Color.WHITE : GREEN); b.setBackground(roundDrawable(primary ? GREEN : Color.rgb(232, 246, 237), 15));
        b.setElevation(0); return b;
    }
    private EditText nameInput(String hint) {
        EditText input = new EditText(this); input.setSingleLine(true); input.setTextSize(16); input.setHint(hint);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(32)});
        input.setPadding(dp(14), dp(12), dp(14), dp(12));
        input.setBackground(roundDrawable(Color.rgb(245, 248, 246), 12)); return input;
    }
    private View dialogInput(EditText input) {
        LinearLayout wrap = new LinearLayout(this); wrap.setPadding(dp(24), dp(10), dp(24), dp(10)); wrap.addView(input, params(-1, 52)); return wrap;
    }
    private boolean validName(String name) { return name.matches("[A-Za-z0-9 _.-]{1,32}") && !name.trim().isEmpty(); }
    private String shellQuote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }
    private String initials(String name) { String clean = name.trim(); return clean.isEmpty() ? "U" : clean.substring(0, 1).toUpperCase(Locale.ROOT); }
    private String cleanError(String output) {
        String value = output == null ? "" : output.trim();
        if (value.isEmpty()) return "The command returned no output. Check the operation and try again.";
        if (value.length() > 280) value = value.substring(0, 280) + "…";
        return value;
    }
    private void showMessage(String value) { Toast.makeText(this, value, Toast.LENGTH_LONG).show(); }
    private void showError(String title, String message) { new AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("OK", null).show(); }
    private int dp(float value) { return (int) (value * getResources().getDisplayMetrics().density + .5f); }
    private LinearLayout.LayoutParams params(int width, int height) { return new LinearLayout.LayoutParams(width < 0 ? width : dp(width), height < 0 ? height : dp(height)); }

    private GradientDrawable roundDrawable(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radiusDp)); return d;
    }
    private android.graphics.drawable.Drawable ripple(GradientDrawable base) {
        android.content.res.ColorStateList color = android.content.res.ColorStateList.valueOf(Color.rgb(225, 239, 229));
        return new android.graphics.drawable.RippleDrawable(color, base, null);
    }

    private RootShell.Result runRoot(String command, int timeoutSeconds) {
        return rootShell.run(command, timeoutSeconds);
    }

    @Override protected void onDestroy() {
        super.onDestroy(); worker.shutdownNow();
    }

    private static final class DeviceUser {
        final int id; final String name; final boolean running;
        DeviceUser(int id, String name, boolean running) { this.id = id; this.name = name; this.running = running; }
    }
}
