package dev.guestguard;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.StatFs;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Offline native UI. No WebView, network permission, exported command receiver or background root service. */
public final class MainActivity extends Activity {
    private static final int BG = Color.rgb(11, 19, 29), PANEL = Color.rgb(22, 35, 49), INK = Color.rgb(234, 242, 249), MUTED = Color.rgb(158, 178, 197), MINT = Color.rgb(104, 226, 197), RED = Color.rgb(255, 149, 154);
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private LinearLayout page;
    private Snapshot snapshot;
    private boolean busy, loaded;
    private String error = "", lastResult = "", tab = "Overview";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        if (android.os.Process.myUid() / 100000 != 0) {
            new AlertDialog.Builder(this).setTitle("Owner-only management")
                .setMessage("Open Guest Guard in owner user 0. Guest profiles cannot manage other users with this app.")
                .setPositiveButton("Close", new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) { finish(); } }).setCancelable(false).show();
            return;
        }
        render();
    }
    @Override protected void onResume() {
        super.onResume();
        if (loaded && !busy) refresh();
    }
    @Override protected void onDestroy() { worker.shutdown(); super.onDestroy(); }
    private int dp(int n) { return (int)(n * getResources().getDisplayMetrics().density + .5f); }
    private GradientDrawable shape(int color, int radius) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d;
    }
    private TextView text(String value, int size, int color) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color); t.setPadding(0, dp(5), 0, dp(5));
        return t;
    }
    private void heading(LinearLayout parent, String title) {
        TextView t = text(title, 21, INK); t.setTypeface(Typeface.DEFAULT, Typeface.BOLD); parent.addView(t);
    }
    private LinearLayout card(String eyebrow, String title) {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(18), dp(16), dp(18), dp(16)); box.setBackground(shape(PANEL, 18));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.bottomMargin = dp(14); page.addView(box, lp);
        box.addView(text(eyebrow.toUpperCase(java.util.Locale.ROOT), 11, MINT)); heading(box, title); return box;
    }
    private Button button(LinearLayout parent, String label, int color, boolean enabled, final Runnable action) {
        Button b = new Button(this); b.setText(label); b.setAllCaps(false); b.setTextSize(14); b.setTextColor(color); b.setMinHeight(dp(48));
        b.setEnabled(enabled && !busy); b.setAlpha(b.isEnabled() ? 1f : .4f); b.setBackground(shape(BG, 10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(8); parent.addView(b, lp);
        b.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { action.run(); } }); return b;
    }
    private void render() {
        if (isFinishing() || isDestroyed()) return;
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(BG);
        page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL); page.setPadding(dp(20), dp(16), dp(20), dp(30)); scroll.addView(page); setContentView(scroll);
        page.addView(text("GUEST GUARD   /   MAGISK MODULE", 11, MINT));
        TextView title = text("Your users.\nSafely managed.", 32, INK); title.setTypeface(Typeface.DEFAULT, Typeface.BOLD); page.addView(title);
        page.addView(text("Owner protected. Guests in your control.", 14, MUTED));
        LinearLayout nav = new LinearLayout(this);
        for (final String name : new String[]{"Overview", "Users", "Safety"}) {
            Button b = new Button(this); b.setText(name); b.setAllCaps(false); b.setTextSize(12); b.setTextColor(tab.equals(name) ? MINT : MUTED); b.setBackgroundColor(BG);
            nav.addView(b, new LinearLayout.LayoutParams(0, dp(56), 1)); b.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { tab = name; render(); } });
        }
        page.addView(nav);
        if (busy) page.addView(text("Working… approve the Magisk root prompt if shown.", 14, MINT));
        if (!error.isEmpty()) {
            LinearLayout c = card("Request failed • state may have changed", "Check before retrying"); c.addView(text(error, 14, RED));
            button(c, "Copy diagnostic", MUTED, true, new Runnable() { public void run() { copy(error); } });
        }
        if (!lastResult.isEmpty()) { LinearLayout c = card("Last request", "System response"); c.addView(text(lastResult, 14, INK)); }
        if ("Safety".equals(tab)) safety();
        else if (snapshot == null) {
            LinearLayout c = card("Root access", "Ready when you are");
            c.addView(text("The module uses your existing Magisk root. Tap Connect, then grant Guest Guard in Magisk. Nothing changes just by opening this app.", 15, MUTED));
            button(c, "Connect & load users", MINT, true, new Runnable() { public void run() { refresh(); } });
        } else if ("Users".equals(tab)) users(); else overview();
        if (snapshot != null || !error.isEmpty()) button(page, "Refresh live status", MINT, true, new Runnable() { public void run() { refresh(); } });
        page.addView(text("v2.0.0  •  Offline  •  Android 9+\nUser data stays on your device.", 11, MUTED));
    }
    private void overview() {
        Snapshot s = snapshot;
        String currentName = "User " + s.current;
        for (Snapshot.User u : s.users) if (u.id == s.current) currentName = u.name;
        LinearLayout c = card("Active session", currentName);
        c.addView(text("User ID " + s.current + (s.current == 0 ? "  ·  Owner / protected" : "  ·  Return to owner to manage guests"), 14, MINT));
        c = card("Capacity", s.users.size() + " existing  /  " + (s.max > 0 ? s.max + " reported limit" : "limit unknown"));
        c.addView(text(s.guests() + " verified guest(s) · " + (s.users.size() - s.guests()) + " protected account(s)", 14, INK));
        c.addView(text(s.max > 0 ? "Nominal remaining slots: " + Math.max(0, s.max - s.users.size()) + ". Not a guarantee: profiles, guest quotas, restrictions and pending removals use different Android rules." : "This ROM did not expose a valid maximum. Unknown does not mean unlimited.", 14, MUTED));
        c.addView(text("Unlimited users: not supported by this module. Android/OEM enforces the real capacity; many Android 9 builds allow only one guest at a time.", 14, MUTED));
        button(c, "Create a guest", MINT, s.current == 0, new Runnable() { public void run() { createGuest(); } });
        c = card("Device", "Android " + s.android);
        StatFs fs = new StatFs(getFilesDir().getAbsolutePath());
        c.addView(text(String.format(java.util.Locale.US, "%.1f GiB available on app data storage", fs.getAvailableBytes() / 1073741824.0), 14, INK));
        c.addView(text("fw.max_users property: " + s.property + "\nInformational only. This module does not modify system properties, SELinux, user files or the boot image.", 13, MUTED));
        button(c, "Copy status report", MUTED, true, new Runnable() { public void run() { copy(report()); } });
    }
    private void users() {
        for (final Snapshot.User u : snapshot.users) {
            final boolean active = u.id == snapshot.current;
            LinearLayout c = card("ID " + u.id + "  ·  " + (active ? "ACTIVE  ·  " : "") + (u.id == 0 ? "OWNER" : u.guest() ? "VERIFIED GUEST" : "PROTECTED"), u.name);
            c.addView(text("Android flags: 0x" + u.flags, 12, MUTED));
            if (u.id == 0) c.addView(text("Permanent safety lock: owner cannot be deleted or renamed by this module.", 14, MINT));
            else if (!u.guest()) c.addView(text("Protected from deletion. This account or profile cannot be removed or renamed here.", 14, MUTED));
            if (u.switchable()) button(c, active ? "Currently active" : "Switch to " + u.name, MINT, !active, new Runnable() { public void run() { new AlertDialog.Builder(MainActivity.this)
                .setTitle("Switch user?").setMessage("Save your work first. Android may lock the screen. You may need this user's PIN. Return to owner from the lock screen or Quick Settings user switcher.")
                .setNegativeButton("Cancel", null).setPositiveButton("Switch", new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) { request("switch", Integer.toString(u.id)); } }).show(); } });
            if (u.guest()) button(c, "Delete guest permanently…", RED, !active && snapshot.current == 0, new Runnable() { public void run() { deleteGuest(u); } });
        }
        button(page, "Create a guest", MINT, snapshot.current == 0, new Runnable() { public void run() { createGuest(); } });
    }
    private void safety() {
        LinearLayout c = card("Built-in safeguards", "Owner stays safe");
        c.addView(text("• User 0 can never be deleted here.\n• Only positively identified guests can be deleted.\n• Regular users, administrators and managed profiles are protected.\n• Active guests cannot be deleted.\n• Creation and deletion require the owner session.\n• Every delete requires typed confirmation.\n• Commands are never automatically retried.\n• Unknown or malformed user data blocks changes.", 15, INK));
        c = card("Recommended", "Keep a way back");
        c.addView(text("Know your owner PIN before switching. Back up guest files before deletion: Android removes the guest's apps and data permanently. Avoid simultaneous changes from Settings or other root tools. Another root app can bypass this module's protections.", 15, MUTED));
        c = card("Compatibility & recovery", "No risky limit tweaks");
        c.addView(text("No unlimited-user override, no auto-switch at boot, no bulk delete, no owner rename, no credential reset. This module cannot unlock another user's screen. Removing the module does not delete any Android user. Remove the companion manually from owner Settings if no longer needed.", 15, MUTED));
        button(c, "Open Android user settings", MINT, true, new Runnable() { public void run() {
            try { startActivity(new Intent("android.settings.USER_SETTINGS")); }
            catch (Exception e) { try { startActivity(new Intent(Settings.ACTION_SETTINGS)); } catch (Exception ignored) { Toast.makeText(MainActivity.this, "Settings unavailable", Toast.LENGTH_LONG).show(); } }
        } });
        c.addView(text("Android Settings is external and has its own controls; Guest Guard's deletion restrictions do not apply inside Settings.", 12, MUTED));
    }
    private void createGuest() {
        final EditText name = new EditText(this); name.setSingleLine(true); name.setInputType(InputType.TYPE_CLASS_TEXT); name.setHint("Guest"); name.setText("Guest");
        final AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Create guest")
            .setMessage("1–32 characters: English letters, numbers, spaces, dot, dash or underscore. Creation does not switch users. Android may reject this if its guest quota is full.")
            .setView(name).setNegativeButton("Cancel", null).setPositiveButton("Create", null).create();
        dialog.setOnShowListener(new DialogInterface.OnShowListener() { public void onShow(DialogInterface d) { dialog.getButton(-1).setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            String value = name.getText().toString().trim();
            if (!value.matches("[A-Za-z0-9 ._][A-Za-z0-9 ._-]{0,31}")) { name.setError("Invalid guest name (1–32 characters; no leading dash)"); return; }
            dialog.dismiss(); request("create-guest", value);
        } }); } }); dialog.show();
    }
    private void deleteGuest(final Snapshot.User u) {
        if (u.id == 0 || !u.guest() || u.id == snapshot.current) return;
        final EditText confirm = new EditText(this); confirm.setSingleLine(true); confirm.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        final String token = "DELETE-GUEST-" + u.id;
        final AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Erase guest “" + u.name + "”?")
            .setMessage("Permanently deletes apps and data for guest ID " + u.id + ". This cannot be undone.\n\nType " + token + " to confirm.")
            .setView(confirm).setNegativeButton("Keep guest", null).setPositiveButton("Delete permanently", null).create();
        dialog.setOnShowListener(new DialogInterface.OnShowListener() { public void onShow(DialogInterface d) {
            final Button delete = dialog.getButton(-1); delete.setTextColor(RED); delete.setEnabled(false);
            confirm.addTextChangedListener(new TextWatcher() {
                public void beforeTextChanged(CharSequence s, int st, int count, int after) { }
                public void onTextChanged(CharSequence s, int st, int before, int count) { delete.setEnabled(token.contentEquals(s)); }
                public void afterTextChanged(Editable e) { }
            });
            delete.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { if (!token.equals(confirm.getText().toString())) return; dialog.dismiss(); request("delete-guest", Integer.toString(u.id), token); } });
        } }); dialog.show();
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
    }
    private void refresh() { request("status"); }
    private void request(final String... args) {
        if (busy) return;
        busy = true; error = ""; loaded = true; render();
        worker.execute(new Runnable() { public void run() {
            String problem = "", result = ""; Snapshot next = null;
            try {
                String output = RootClient.run(args);
                if ("status".equals(args[0])) next = Snapshot.parse(output);
                else { result = output; next = Snapshot.parse(RootClient.run("status")); }
            } catch (Exception e) { problem = e.getMessage() == null ? e.toString() : e.getMessage(); }
            final String message = problem, response = result; final Snapshot data = next;
            runOnUiThread(new Runnable() { public void run() {
                if (isFinishing() || isDestroyed()) return;
                busy = false; snapshot = data; error = message; lastResult = response; render();
            } });
        } });
    }
    private String report() {
        if (snapshot == null) return error;
        StringBuilder b = new StringBuilder("Guest Guard 2.0.0\nAndroid: " + snapshot.android + "\nCurrent ID: " + snapshot.current + "\nReported max: " + (snapshot.max > 0 ? snapshot.max : "unknown") + "\nTotal: " + snapshot.users.size() + "\nGuests: " + snapshot.guests() + "\nUnlimited: not supported\n");
        for (Snapshot.User u : snapshot.users) b.append("ID ").append(u.id).append(" / ").append(u.type).append(" / flags 0x").append(u.flags).append('\n');
        return b.toString();
    }
    private void copy(String value) { ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Guest Guard report", value)); Toast.makeText(this, "Copied (user names omitted from status report)", Toast.LENGTH_SHORT).show(); }
}
