package com.example.usermanager;

import android.app.AlertDialog;
import android.content.Context;
import android.view.*;
import android.widget.*;
import java.util.List;

public class UserAdapter extends BaseAdapter {

    private final Context context;
    private final List<User> users;
    private final MainActivity activity;

    public UserAdapter(Context context, List<User> users, MainActivity activity) {
        this.context = context;
        this.users = users;
        this.activity = activity;
    }

    @Override public int getCount() { return users.size(); }
    @Override public User getItem(int pos) { return users.get(pos); }
    @Override public long getItemId(int pos) { return pos; }

    @Override
    public View getView(int pos, View convertView, ViewGroup parent) {
        if (convertView == null) {
            convertView = LayoutInflater.from(context)
                    .inflate(R.layout.item_user, parent, false);
        }

        User u = getItem(pos);
        TextView tvName   = convertView.findViewById(R.id.tvName);
        TextView tvId     = convertView.findViewById(R.id.tvId);
        Button   btnSwitch = convertView.findViewById(R.id.btnSwitch);
        Button   btnDelete = convertView.findViewById(R.id.btnDelete);

        tvName.setText(u.name);
        tvId.setText("ID: " + u.id + (u.running ? "  •  active" : ""));

        btnSwitch.setEnabled(!u.running && u.id != 0);
        btnSwitch.setOnClickListener(v -> activity.switchToUser(u.id));

        btnDelete.setEnabled(u.id != 0);
        btnDelete.setOnClickListener(v -> new AlertDialog.Builder(context)
                .setTitle("Remove user")
                .setMessage("Delete " + u.name + "?\nAll its data will be wiped.")
                .setPositiveButton("Remove", (d, w) -> activity.removeUser(u.id))
                .setNegativeButton("Cancel", null)
                .show());

        return convertView;
    }
}