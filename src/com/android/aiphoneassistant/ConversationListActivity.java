/*
 * Copyright (C) 2024 The LineageOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.aiphoneassistant;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * Launcher surface: the list of task conversations, newest first. One task =
 * one conversation; composing here always starts a NEW conversation and
 * activates the assistant. Tapping a row opens that conversation's
 * transcript. Conversations older than a week are grouped into collapsed
 * sections ("older than a week" / "older than a month"); tapping a section
 * header expands it (the state is remembered). While a task executes the
 * phone is busy: composing is disabled. Nothing is ever auto-deleted.
 */
public class ConversationListActivity extends Activity {
    private static final String TAG = "AIPhoneAssistant.ConvList";

    private static final int NOTIFICATIONS_REQUEST_CODE = 1;

    // Age buckets for the list. Recent conversations are shown flat;
    // anything older lands in a collapsed group.
    private static final long GROUP1_THRESHOLD_MS = 7L * 24 * 3600 * 1000;  // "a week ago"
    private static final long GROUP2_THRESHOLD_MS = 30L * 24 * 3600 * 1000; // "a month ago"

    private static final String GROUP_KEY_WEEK = "week";
    private static final String GROUP_KEY_MONTH = "month";

    private ChatStore mStore;
    private ConversationAdapter mAdapter;

    private ListView mList;
    private TextView mEmptyView;
    private TextView mSubtitle;
    private EditText mInput;
    private Button mSend;
    private View mChipsScroll;
    private LinearLayout mChipsRow;

    private List<ChatStore.Conversation> mConversations;
    private List<Row> mRows;

    private BroadcastReceiver mStateReceiver;

    /** One flat entry of the list: either a conversation or a group header. */
    private static class Row {
        static final int CONV = 0;
        static final int HEADER = 1;

        int type;
        ChatStore.Conversation conv;
        String groupKey;
        String headerTitle;
        int count;
        boolean expanded;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        mStore = new ChatStore(this);
        setContentView(R.layout.activity_conversations);

        mList = findViewById(R.id.conv_list);
        mEmptyView = findViewById(R.id.conv_empty);
        mSubtitle = findViewById(R.id.conv_list_subtitle);
        mInput = findViewById(R.id.conv_input);
        mSend = findViewById(R.id.conv_send);
        mChipsScroll = findViewById(R.id.conv_chips_scroll);
        mChipsRow = findViewById(R.id.conv_chips_row);

        mAdapter = new ConversationAdapter();
        mList.setAdapter(mAdapter);
        mList.setEmptyView(mEmptyView);

        mSend.setOnClickListener(v -> sendTask(mInput.getText().toString()));
        mInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                sendTask(mInput.getText().toString());
                return true;
            }
            return false;
        });
        findViewById(R.id.conv_list_settings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));

        // Edge-to-edge on targetSdk 35: keep header/composer clear of system
        // bars, the cutout AND the IME — otherwise the send button stays
        // hidden under the keyboard.
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        View root = findViewById(android.R.id.content);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(
                    android.view.WindowInsets.Type.systemBars()
                            | android.view.WindowInsets.Type.displayCutout()
                            | android.view.WindowInsets.Type.ime());
            v.setPadding(0, bars.top, 0, bars.bottom);
            return insets;
        });

        requestPostNotificationsIfNeeded();
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerStateReceiver();
        refresh();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mStateReceiver != null) {
            unregisterReceiver(mStateReceiver);
            mStateReceiver = null;
        }
    }

    private void registerStateReceiver() {
        mStateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                refresh();
            }
        };
        IntentFilter filter = new IntentFilter(AIPhoneAssistantService.BROADCAST_STATE_CHANGED);
        registerReceiver(mStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
    }

    private void refresh() {
        mConversations = mStore.listConversations();
        rebuildRows();
        mAdapter.notifyDataSetChanged();
        updateHeaderAndInput();
        renderChips();
    }

    /** Flat rows for the adapter: recent conversations + collapsed groups. */
    private void rebuildRows() {
        List<Row> rows = new java.util.ArrayList<>();
        if (mConversations == null) {
            mRows = rows;
            return;
        }
        long now = System.currentTimeMillis();
        List<ChatStore.Conversation> recent = new java.util.ArrayList<>();
        List<ChatStore.Conversation> week = new java.util.ArrayList<>();
        List<ChatStore.Conversation> month = new java.util.ArrayList<>();
        for (ChatStore.Conversation c : mConversations) {
            long age = now - c.startedTs;
            if (age < GROUP1_THRESHOLD_MS) {
                recent.add(c);
            } else if (age < GROUP2_THRESHOLD_MS) {
                week.add(c);
            } else {
                month.add(c);
            }
        }

        for (ChatStore.Conversation c : recent) {
            Row r = new Row();
            r.type = Row.CONV;
            r.conv = c;
            rows.add(r);
        }
        appendGroup(rows, GROUP_KEY_WEEK, R.string.group_week_ago, week);
        appendGroup(rows, GROUP_KEY_MONTH, R.string.group_month_ago, month);
        mRows = rows;
    }

    private void appendGroup(List<Row> rows, String key, int labelRes,
            List<ChatStore.Conversation> conversations) {
        if (conversations.isEmpty()) {
            return;
        }
        Row header = new Row();
        header.type = Row.HEADER;
        header.groupKey = key;
        header.count = conversations.size();
        header.expanded = getPreferences(Context.MODE_PRIVATE)
                .getBoolean("grp_exp_" + key, false);
        header.headerTitle = getString(labelRes) + " (" + header.count + ")";
        rows.add(header);
        if (header.expanded) {
            for (ChatStore.Conversation c : conversations) {
                Row r = new Row();
                r.type = Row.CONV;
                r.conv = c;
                rows.add(r);
            }
        }
    }

    private void toggleGroup(Row header) {
        String prefKey = "grp_exp_" + header.groupKey;
        boolean nowExpanded = !getPreferences(Context.MODE_PRIVATE)
                .getBoolean(prefKey, false);
        getPreferences(Context.MODE_PRIVATE).edit()
                .putBoolean(prefKey, nowExpanded).apply();
        rebuildRows();
        mAdapter.notifyDataSetChanged();
    }

    private boolean isExecuting() {
        String state = ChatStore.getTaskState(this);
        return AIPhoneAssistantService.isRunning()
                && (ChatStore.STATE_EXECUTING.equals(state)
                        || ChatStore.STATE_PAUSED.equals(state));
    }

    private void updateHeaderAndInput() {
        String state = ChatStore.getTaskState(this);
        String task = ChatStore.getTask(this);
        if (ChatStore.STATE_PAUSED.equals(state) && isExecuting()) {
            mSubtitle.setText(R.string.chat_subtitle_paused);
        } else if (isExecuting()) {
            mSubtitle.setText(getString(R.string.chat_subtitle_executing_format,
                    shorten(task, 28)));
        } else {
            mSubtitle.setText(R.string.chat_subtitle_idle);
        }

        boolean executing = isExecuting();
        mInput.setEnabled(!executing);
        mInput.setHint(executing
                ? R.string.chat_input_disabled : R.string.chat_input_hint);
        mInput.setAlpha(executing ? 0.5f : 1f);
        mSend.setEnabled(!executing);
    }

    private void renderChips() {
        mChipsRow.removeAllViews();
        float density = getResources().getDisplayMetrics().density;
        for (String task : ChatStore.getRecentTasks(this)) {
            TextView chip = new TextView(this);
            chip.setText(task);
            chip.setTextSize(13);
            chip.setMaxLines(1);
            chip.setMaxWidth((int) (220 * density));
            chip.setEllipsize(android.text.TextUtils.TruncateAt.END);
            chip.setBackgroundResource(R.drawable.bg_chip);
            chip.setPadding((int) (14 * density), (int) (7 * density),
                    (int) (14 * density), (int) (7 * density));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            params.setMarginEnd((int) (8 * density));
            chip.setLayoutParams(params);
            chip.setOnClickListener(v -> sendTask(task));
            mChipsRow.addView(chip);
        }
        mChipsScroll.setVisibility(
                mChipsRow.getChildCount() == 0 || isExecuting() ? View.GONE : View.VISIBLE);
    }

    /** Compose a new task: creates a new conversation and activates it. */
    private void sendTask(String text) {
        String task = text != null ? text.trim() : "";
        if (task.isEmpty()) {
            return;
        }
        if (isExecuting()) {
            Toast.makeText(this, R.string.chat_input_disabled, Toast.LENGTH_SHORT).show();
            return;
        }

        long convId = mStore.createConversation(this, task);
        ChatStore.addRecentTask(this, task);
        // Sending a task implies the assistant is allowed to run (boot, etc.).
        SharedPreferences prefs = LLMClientService.getPrefs(this);
        prefs.edit().putBoolean(LLMClientService.KEY_ENABLED, true).apply();

        Intent service = new Intent(this, AIPhoneAssistantService.class);
        service.setAction(AIPhoneAssistantService.ACTION_ACTIVATE);
        startForegroundService(service);

        mInput.setText("");
        startActivity(ChatActivity.intent(this, convId));
        refresh();
    }

    private static String shorten(String s, int max) {
        String flat = s != null ? s.replace('\n', ' ').trim() : "";
        return flat.length() <= max ? flat : flat.substring(0, max) + "…";
    }

    private void requestPostNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    NOTIFICATIONS_REQUEST_CODE);
        }
    }

    // ------------------------------------------------------------------
    // Conversation list adapter (with collapsible age groups)
    // ------------------------------------------------------------------

    private class ConversationAdapter extends android.widget.BaseAdapter {
        @Override
        public int getCount() {
            return mRows != null ? mRows.size() : 0;
        }

        @Override
        public Row getItem(int position) {
            return mRows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public int getViewTypeCount() {
            return 2;
        }

        @Override
        public int getItemViewType(int position) {
            return mRows.get(position).type;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Row row = getItem(position);
            View view = convertView;
            if (row.type == Row.HEADER) {
                TextView title;
                TextView arrow;
                if (view == null) {
                    view = LayoutInflater.from(ConversationListActivity.this).inflate(
                            R.layout.item_group_header, parent, false);
                }
                title = view.findViewById(R.id.group_title);
                arrow = view.findViewById(R.id.group_arrow);
                title.setText(row.headerTitle);
                arrow.setText(row.expanded ? "▾" : "▸");
                view.setOnClickListener(v -> toggleGroup(row));
                return view;
            }

            ChatStore.Conversation conv = row.conv;
            TextView title;
            TextView state;
            TextView summary;
            TextView time;
            if (view == null) {
                view = LayoutInflater.from(ConversationListActivity.this).inflate(
                        R.layout.item_conversation, parent, false);
            }
            title = view.findViewById(R.id.conv_row_title);
            state = view.findViewById(R.id.conv_row_state);
            summary = view.findViewById(R.id.conv_row_summary);
            time = view.findViewById(R.id.conv_row_time);

            title.setText(conv.task);
            state.setText(ChatStore.stateLabel(ConversationListActivity.this, conv.state));
            state.setTextColor(stateColor(conv.state));
            summary.setText(conv.summary != null ? conv.summary : "");
            time.setText(ChatStore.relativeTime(conv.updatedTs));
            view.setOnClickListener(v ->
                    startActivity(ChatActivity.intent(ConversationListActivity.this, conv.id)));
            return view;
        }

        private int stateColor(String state) {
            if (ChatStore.STATE_EXECUTING.equals(state)) return 0xFF1976D2;
            if (ChatStore.STATE_PAUSED.equals(state)) return 0xFFE65100;
            if (ChatStore.STATE_DONE.equals(state)) return 0xFF2E7D32;
            if (ChatStore.STATE_STALLED.equals(state)) return 0xFFD93025;
            return 0xFF8A8A8A;
        }
    }
}
