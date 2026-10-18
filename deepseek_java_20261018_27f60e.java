package com.example.usermanager;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import java.io.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private ListView listView;
    private Button btnRefresh, btnCreate;
    private UserAdapter adapter;
    private final List<User> users = new ArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        listView  = findViewById(R.id.listView);
        btnRefresh = findViewById(R.id.btnRefresh);
        btnCreate  = findViewById(R.id.btnCreate);

        adapter = new UserAdapter(this, users, this);
        listView.setAdapter(adapter);

        btnRefresh.setOnClickListener(v -> refreshUsers());
        btnCreate.setOnClickListener(v -> createGuest());

        refreshUsers();
    }

    // ---------- Actions ----------

    private void refreshUsers() {
        executor.execute(() -> {
            String out = runRootCommand("pm list users");
            List<User> parsed = parseUsers(out);
            handler.post(() -> {
                users.clear();
                users.addAll(parsed);
                adapter.notifyDataSetChanged();
            });
        });
    }

    private void createGuest() {
        executor.execute(() -> {
            String out = runRootCommand("pm create-user --guest GuestUser");
            handler.post(() -> {
                Toast.makeText(this, out.trim(), Toast.LENGTH_LONG).show();
                refreshUsers();
            });
        });
    }

    public void switchToUser(int userId) {
        executor.execute(() -> {
            String out = runRootCommand("am switch-user " + userId);
            handler.post(() -> {
                if (!out.trim().isEmpty()) {
                    Toast.makeText(this, out.trim(), Toast.LENGTH_SHORT).show();
                }
                refreshUsers();
            });
        });
    }

    public void removeUser(int userId) {
        executor.execute(() -> {
            runRootCommand("am switch-user 0");   // must be on owner first
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            String out = runRootCommand("pm remove-user " + userId);
            handler.post(() -> {
                Toast.makeText(this, "Removed: " + out.trim(), Toast.LENGTH_SHORT).show();
                refreshUsers();
            });
        });
    }

    // ---------- Root runner ----------

    private String runRootCommand(String command) {
        StringBuilder sb = new StringBuilder();
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", command});
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) sb.append(line).append("\n");
            reader.close();

            BufferedReader err = new BufferedReader(
                    new InputStreamReader(p.getErrorStream()));
            while ((line = err.readLine()) != null) sb.append(line).append("\n");
            err.close();

            p.waitFor();
        } catch (Exception e) {
            sb.append("ERROR: ").append(e.getMessage());
        }
        return sb.toString();
    }

    // ---------- Parser ----------

    private List<User> parseUsers(String output) {
        List<User> result = new ArrayList<>();
        for (String line : output.split("\n")) {
            line = line.trim();
            if (!line.startsWith("UserInfo{")) continue;
            try {
                int start = line.indexOf('{') + 1;
                int end   = line.indexOf('}');
                String inner = line.substring(start, end);
                String[] parts = inner.split(":", 3);
                int id = Integer.parseInt(parts[0]);
                String name = parts[1];
                boolean running = line.contains("running");
                result.add(new User(id, name, running));
            } catch (Exception ignored) {}
        }
        return result;
    }
}