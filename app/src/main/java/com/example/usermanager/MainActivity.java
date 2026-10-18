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
import android.view.ViewGroup;
import android.view.animation.AlphaAnimation;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Root-gated Android multi-user controls. All privileged shell work is performed off the UI thread. */
public class MainActivity extends Activity {
    private static final int INK = Color.rgb(29, 43, 37);
    private static final int MUTED = Color.rgb(112, 127, 119);
    private static final int GREEN = Color.rgb(23, 139, 94);
    private static final int BG = Color.rgb(245, 248, 246);
    private static final int CARD = Color.WHITE;
    private static final int LINE = Color.rgb(230, 237, 232);
    private static final String ROOT_OK = "root_granted";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<DeviceUser> users = new ArrayList<>();
    private FrameLayout host;
    private LinearLayout page;
    private TextView rootBadge;
    private TextView homeUsersLabel;
    private ProgressBar rootProgress;
    private boolean rootReady;
    private boolean showingUsers;
    private int currentUser = -1;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        rootReady = getPreferences(MODE_PRIVATE).getBoolean(ROOT_OK, false);
        renderHome();
        if (rootReady) {
            updateRootBadge("ROOT READY", true, false);
            refreshUsers(false);
        } else {
            updateRootBadge("CHECKING ROOT", false, true);
            checkRootFirstTime();
        }
    }

    private void checkRootFirstTime() {
        worker.execute(() -> {
            RootResult result = runRoot("id", 25);
            boolean ok = result.exitCode == 0 && result.output.contains("uid=0");
            main.post(() -> {
                if (isFinishing()) return;
                if (ok) {
                    rootReady = true;
                    getPreferences(MODE_PRIVATE).edit().putBoolean(ROOT_OK, true).apply();
                    renderHome();
                    showRootPass();
                    refreshUsers(false);
                } else {
                    updateRootBadge(result.timedOut ? "ROOT TIMEOUT" : "ROOT NEEDED", false, false);
                    showMessage(result.timedOut
                            ? "Root approval took too long. Tap Check root to try again."
                            : "Root access was not granted. Tap Check root when you are ready.");
                }
            });
        });
    }

    private void retryRoot() {
        updateRootBadge("CHECKING ROOT", false, true);
        checkRootFirstTime();
    }

    private void renderHome() {
        showingUsers = false;
        buildPage();
        addToolbar();
        addSpacer(34);
        addLabel("ROOTED AND READY", 11, GREEN, true, 2);
        addText("Your device,\nyour space.", 34, INK, true, 0);
        addText("A simple home for powerful Android tools.", 15, MUTED, false, 12);

        LinearLayout hero = card(22);
        hero.setBackground(roundDrawable(Color.rgb(27, 57, 45), 24));
        hero.setPadding(dp(22), dp(20), dp(22), dp(20));
        TextView heroOver = text("ROOT ACCESS", 11, Color.rgb(176, 224, 198), true);
        hero.addView(heroOver);
        TextView heroTitle = text(rootReady ? "Root is ready" : "Unlock root tools", 21, Color.WHITE, true);
        LinearLayout.LayoutParams htp = params(-1, -2); htp.topMargin = dp(8); hero.addView(heroTitle, htp);
        TextView heroInfo = text(rootReady
                ? "Superuser permission is available for this session."
                : "We’ll request superuser access the first time it’s needed. Allow the prompt within 25 seconds.",
                14, Color.rgb(211, 226, 217), false);
        heroInfo.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams hip = params(-1, -2); hip.topMargin = dp(6); hero.addView(heroInfo, hip);
        LinearLayout status = new LinearLayout(this); status.setGravity(Gravity.CENTER_VERTICAL);
        if (rootProgress != null && rootProgress.getParent() != null) ((ViewGroup) rootProgress.getParent()).removeView(rootProgress);
        rootProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleSmall);
        rootProgress.setIndeterminate(true); rootProgress.setVisibility(rootReady ? View.GONE : View.VISIBLE);
        LinearLayout.LayoutParams rp = params(18, 18); rp.topMargin = dp(17); status.addView(rootProgress, rp);
        TextView inlineStatus = text(rootReady ? "Superuser enabled" : "Waiting for superuser response", 12,
                Color.rgb(212, 231, 218), true);
        LinearLayout.LayoutParams isp = params(-2, -2); isp.leftMargin = dp(9); isp.topMargin = dp(17); status.addView(inlineStatus, isp);
        hero.addView(status);
        if (!rootReady) {
            Button check = button("Check root", true);
            LinearLayout.LayoutParams cp = params(-1, 48); cp.topMargin = dp(18); hero.addView(check, cp);
            check.setOnClickListener(v -> retryRoot());
        }
        LinearLayout.LayoutParams hp = params(-1, -2); hp.topMargin = dp(26); page.addView(hero, hp);

        TextView section = text("TOOLS", 11, MUTED, true);
        LinearLayout.LayoutParams sp = params(-1, -2); sp.topMargin = dp(30); page.addView(section, sp);
        LinearLayout manage = card(18);
        manage.setBackground(roundDrawable(CARD, 20));
        manage.setClickable(true); manage.setFocusable(true);
        manage.setOnClickListener(v -> renderUsers());
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
        TextView icon = text("01", 13, GREEN, true); icon.setGravity(Gravity.CENTER);
        icon.setBackground(roundDrawable(Color.rgb(231, 246, 237), 15));
        row.addView(icon, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout labels = new LinearLayout(this); labels.setOrientation(LinearLayout.VERTICAL);
        TextView title = text("User management", 17, INK, true);
        TextView subtitle = text(rootReady ? users.size() + " device users" : "Create and switch device users", 13, MUTED, false);
        homeUsersLabel = subtitle;
        labels.addView(title); LinearLayout.LayoutParams subp = params(-1, -2); subp.topMargin = dp(4); labels.addView(subtitle, subp);
        LinearLayout.LayoutParams labelp = new LinearLayout.LayoutParams(0, -2, 1); labelp.leftMargin = dp(14); row.addView(labels, labelp);
        TextView arrow = text("›", 30, MUTED, false); row.addView(arrow);
        manage.addView(row);
        LinearLayout.LayoutParams mp = params(-1, -2); mp.topMargin = dp(12); page.addView(manage, mp);
        TextView hint = text("Tap to see every profile on this device. Hold a user to switch, rename, or remove it.", 13, MUTED, false);
        hint.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams hintp = params(-1, -2); hintp.topMargin = dp(12); page.addView(hint, hintp);
        addFooter();
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
        TextView title = text("Your users", 27, INK, true);
        TextView subtitle = text(users.size() + (users.size() == 1 ? " profile on this device" : " profiles on this device"), 13, MUTED, false);
        heading.addView(title); LinearLayout.LayoutParams sub = params(-1, -2); sub.topMargin = dp(3); heading.addView(subtitle, sub);
        head.addView(heading);
        LinearLayout.LayoutParams headp = params(-1, -2); headp.topMargin = dp(24); page.addView(head, headp);

        Button create = button("＋    Create a user", true);
        LinearLayout.LayoutParams createp = params(-1, 54); createp.topMargin = dp(22); page.addView(create, createp);
        create.setOnClickListener(v -> showCreateDialog());

        LinearLayout listCard = card(0); listCard.setBackground(roundDrawable(CARD, 20));
        LinearLayout.LayoutParams listp = params(-1, -2); listp.topMargin = dp(20); page.addView(listCard, listp);
        if (users.isEmpty()) {
            TextView empty = text(rootReady ? "No users were returned by Android.\nTry refreshing the list." : "Waiting for root access to read users…", 14, MUTED, false);
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
        TextView refresh = text("↻   Refresh user list", 14, GREEN, true);
        refresh.setGravity(Gravity.CENTER); refresh.setPadding(0, dp(18), 0, dp(18));
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
        String[] actions = user.id == 0
                ? new String[]{user.id == currentUser ? "Owner · current user" : "Switch to owner"}
                : new String[]{user.id == currentUser ? "Currently active" : "Switch to this user", "Rename user", "Delete user"};
        new AlertDialog.Builder(this)
                .setTitle(user.name + (user.id == 0 ? "  ·  Owner" : ""))
                .setItems(actions, (dialog, which) -> {
                    if (user.id == 0) {
                        if (user.id != currentUser) confirmSwitch(user);
                    } else if (which == 0) {
                        if (user.id != currentUser) confirmSwitch(user);
                    } else if (which == 1) showRenameDialog(user);
                    else confirmDelete(user);
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
                    String command = "pm remove-user " + user.id;
                    if (user.id == currentUser) command = "am switch-user 0 && sleep 2 && " + command;
                    executeAction("Deleting user…", command, false);
                }).show();
    }

    private void showCreateDialog() {
        EditText input = nameInput("e.g. Work profile");
        new AlertDialog.Builder(this).setTitle("Create a user")
                .setMessage("Add a separate Android user with its own apps and data.")
                .setView(dialogInput(input))
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Create", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (!validName(name)) { showMessage("Choose a name from 1 to 32 characters (letters, numbers, spaces, _ . or -). "); return; }
                    executeAction("Creating “" + name + "”…", "pm create-user " + shellQuote(name), false);
                }).show();
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
        if (!rootReady) { showMessage("Root access is required. Tap Check root on the home screen first."); return; }
        Toast.makeText(this, pendingMessage, Toast.LENGTH_SHORT).show();
        worker.execute(() -> {
            RootResult result = runRoot(command, 25);
            main.post(() -> {
                if (isFinishing()) return;
                if (result.exitCode == 0 && !result.timedOut) {
                    if (switchAction) {
                        showMessage("Switch request sent to Android.");
                        refreshUsers(false);
                    } else {
                        showMessage(successMessage(command, result.output));
                        refreshUsers(false);
                    }
                } else {
                    String reason = result.timedOut ? "The root command timed out after 25 seconds."
                            : cleanError(result.output);
                    showError("Could not complete action", reason);
                    refreshUsers(false);
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
        if (!rootReady) { if (announce) retryRoot(); return; }
        worker.execute(() -> {
            RootResult list = runRoot("pm list users", 25);
            RootResult current = runRoot("am get-current-user", 25);
            List<DeviceUser> parsed = parseUsers(list.output);
            int active = -1;
            Matcher m = Pattern.compile("(?m)^\\s*(\\d+)\\s*$").matcher(current.output);
            if (m.find()) try { active = Integer.parseInt(m.group(1)); } catch (Exception ignored) {}
            final int activeId = active;
            main.post(() -> {
                if (isFinishing()) return;
                if (list.exitCode != 0) {
                    if (parsed.isEmpty()) showError("Unable to read users", cleanError(list.output));
                    return;
                }
                users.clear(); users.addAll(parsed); currentUser = activeId;
                if (showingUsers) renderUsers();
                else if (homeUsersLabel != null) homeUsersLabel.setText(users.size() + " device users");
                if (announce) showMessage("User list refreshed.");
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

    private void updateRootBadge(String label, boolean ready, boolean checking) {
        if (rootBadge == null) return;
        rootBadge.setText((ready ? "●  " : checking ? "◌  " : "○  ") + label);
        rootBadge.setTextColor(ready ? GREEN : checking ? Color.rgb(172, 117, 30) : MUTED);
        rootBadge.setBackground(roundDrawable(ready ? Color.rgb(228, 246, 235) : Color.rgb(238, 241, 239), 30));
        if (rootProgress != null) rootProgress.setVisibility(checking ? View.VISIBLE : View.GONE);
    }

    private void showRootPass() {
        TextView pass = text("✓   ROOT ACCESS GRANTED", 14, Color.WHITE, true);
        pass.setGravity(Gravity.CENTER); pass.setPadding(dp(22), dp(14), dp(22), dp(14));
        pass.setBackground(roundDrawable(Color.rgb(22, 145, 91), 28));
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        p.topMargin = dp(22); host.addView(pass, p); pass.setElevation(dp(12));
        AlphaAnimation fadeIn = new AlphaAnimation(0f, 1f); fadeIn.setDuration(260); pass.startAnimation(fadeIn);
        main.postDelayed(() -> {
            if (pass.getParent() == null) return;
            AlphaAnimation fadeOut = new AlphaAnimation(1f, 0f); fadeOut.setDuration(350);
            fadeOut.setAnimationListener(new android.view.animation.Animation.AnimationListener() {
                @Override public void onAnimationStart(android.view.animation.Animation animation) { }
                @Override public void onAnimationEnd(android.view.animation.Animation animation) { if (pass.getParent() != null) host.removeView(pass); }
                @Override public void onAnimationRepeat(android.view.animation.Animation animation) { }
            });
            pass.startAnimation(fadeOut);
        }, 2200);
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
        rootBadge = text("", 10, GREEN, true); rootBadge.setPadding(dp(11), dp(8), dp(11), dp(8));
        bar.addView(rootBadge); updateRootBadge(rootReady ? "ROOT READY" : "ROOT NEEDED", rootReady, false);
        page.addView(bar, params(-1, 46));
        rootBadge.setOnClickListener(v -> { if (!rootReady) retryRoot(); else showMessage("Root access is enabled."); });
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
        if (value.isEmpty()) return "The root command failed. Check that your root manager granted access.";
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

    private RootResult runRoot(String command, int timeoutSeconds) {
        Process process = null;
        try {
            process = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
            boolean ended = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!ended) {
                process.destroy();
                if (!process.waitFor(300, TimeUnit.MILLISECONDS)) process.destroyForcibly();
                return new RootResult("", -1, true);
            }
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line; while ((line = reader.readLine()) != null) output.append(line).append('\n');
            }
            return new RootResult(output.toString(), process.exitValue(), false);
        } catch (Exception e) {
            if (process != null) process.destroyForcibly();
            return new RootResult(e.getMessage() == null ? "Unable to start su" : e.getMessage(), -1, false);
        }
    }

    @Override protected void onDestroy() {
        super.onDestroy(); worker.shutdownNow();
    }

    private static final class DeviceUser {
        final int id; final String name; final boolean running;
        DeviceUser(int id, String name, boolean running) { this.id = id; this.name = name; this.running = running; }
    }
    private static final class RootResult {
        final String output; final int exitCode; final boolean timedOut;
        RootResult(String output, int exitCode, boolean timedOut) { this.output = output; this.exitCode = exitCode; this.timedOut = timedOut; }
    }
}
