/*
 * Copyright (C) 2024 The AgentVisOS Project
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

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.graphics.Bitmap;
import android.text.format.DateUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Persistence for the conversation-based chat UI and the task lifecycle,
 * backed by SQLite (framework {@link SQLiteOpenHelper}, no external
 * dependencies).
 *
 * One task = one conversation. Key-node screenshots live in their own
 * <code>images</code> table as JPEG BLOBs, referenced by name from message
 * rows — message, metadata and image bytes commit together, so there is no
 * orphan-file bookkeeping. The conversation list is one indexed query;
 * message reads are scoped by conv_id.
 *
 * The active conversation id and the global task state (used by BootReceiver
 * and the settings toggle) live in the shared preferences. The service
 * resolves its conversation at activation time through
 * {@link #getCurrentConvId}.
 *
 * Conversations are never deleted automatically; only per-conversation
 * message counts are bounded (oldest messages of a very long conversation
 * are dropped, their image rows garbage-collected).
 *
 * All methods are safe to call from any thread; reads always hit the
 * database so the UI sees what the service wrote without sharing instances.
 */
public class ChatStore {
    private static final String TAG = "AIPhoneAssistant.ChatStore";

    /** Task lifecycle states (persisted; consumed by UI and BootReceiver). */
    public static final String STATE_IDLE = "idle";
    public static final String STATE_PENDING = "pending";        // task set, not yet activated
    public static final String STATE_EXECUTING = "executing";
    public static final String STATE_PAUSED = "paused";
    public static final String STATE_DONE = "done";
    public static final String STATE_STOPPED = "stopped";        // stopped by disabling the assistant
    public static final String STATE_INTERRUPTED = "interrupted";// stopped by user via interrupt bar
    public static final String STATE_STALLED = "stalled";        // watchdog gave up / repeated errors

    /** Message kinds. */
    public static final int KIND_USER = 0;    // task text sent by the user
    public static final int KIND_ACTION = 1;  // key action node: description + screenshot
    public static final int KIND_STATUS = 2;  // centered status line
    public static final int KIND_RESULT = 3;  // completion summary from the model

    private static final int MAX_MESSAGES_PER_CONV = 200;
    private static final int KEEP_AFTER_TRIM = 150;
    private static final int MAX_RECENT_TASKS = 8;
    private static final int MAX_SCREENSHOT_DIM = 720;
    private static final int SCREENSHOT_JPEG_QUALITY = 80;

    private static final String LEGACY_LOG_NAME = "messages.jsonl";
    private static final String MIGRATED_FLAG = "file_storage_migrated_to_db";

    /** A single chat entry. */
    public static class Message {
        public long id;
        public long ts;
        public int kind;
        public String text;
        public String imagePath; // file name inside the chat dir, null when none

        public boolean hasImage() {
            return imagePath != null && !imagePath.isEmpty();
        }
    }

    /** Conversation metadata for the list screen. */
    public static class Conversation {
        public long id;
        public String task;
        public String state;
        public long startedTs;
        public long updatedTs;
        public String summary; // last appended message text (list preview)
        public boolean archived;
    }

    /** Process-wide database helper (SQLiteOpenHelper is thread-safe). */
    private static class DbHelper extends SQLiteOpenHelper {
        private static volatile DbHelper sInstance;

        static DbHelper get(Context context) {
            if (sInstance == null) {
                synchronized (DbHelper.class) {
                    if (sInstance == null) {
                        sInstance = new DbHelper(context);
                    }
                }
            }
            return sInstance;
        }

        private DbHelper(Context context) {
            super(context.getApplicationContext(), "chat.db", null, 3);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE conversations ("
                    + "id INTEGER PRIMARY KEY, "
                    + "task TEXT NOT NULL, "
                    + "state TEXT NOT NULL, "
                    + "started_ts INTEGER NOT NULL, "
                    + "updated_ts INTEGER NOT NULL, "
                    + "summary TEXT, "
                    + "archived INTEGER NOT NULL DEFAULT 0)");
            db.execSQL("CREATE TABLE messages ("
                    + "conv_id INTEGER NOT NULL, "
                    + "id INTEGER NOT NULL, "
                    + "ts INTEGER NOT NULL, "
                    + "kind INTEGER NOT NULL, "
                    + "text TEXT NOT NULL, "
                    + "image_path TEXT, "
                    + "PRIMARY KEY (conv_id, id))");
            db.execSQL("CREATE INDEX idx_messages_conv ON messages (conv_id, ts)");
            db.execSQL("CREATE INDEX idx_conversations_started ON conversations (started_ts)");
            createImagesTable(db);
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            if (oldVersion < 2) {
                // v2: screenshots moved from files into the images table.
                createImagesTable(db);
            }
            if (oldVersion < 3) {
                // v3: conversation archiving.
                db.execSQL("ALTER TABLE conversations ADD COLUMN "
                        + "archived INTEGER NOT NULL DEFAULT 0");
            }
        }

        private void createImagesTable(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS images ("
                    + "name TEXT PRIMARY KEY, "
                    + "data BLOB NOT NULL, "
                    + "created_ts INTEGER NOT NULL)");
        }
    }

    private final Context mContext;
    private final SQLiteOpenHelper mDb;
    private final File mChatDir;

    public ChatStore(Context context) {
        mContext = context.getApplicationContext();
        mDb = DbHelper.get(mContext);
        mChatDir = new File(mContext.getFilesDir(), "chat");
        mChatDir.mkdirs();
        migrateFileStorageIfNeeded();
        migrateImageFilesIfNeeded();
    }

    // ------------------------------------------------------------------
    // Global state in shared preferences
    // ------------------------------------------------------------------

    public static String getTaskState(Context context) {
        return getPrefs(context).getString("task_state", STATE_IDLE);
    }

    public static void setTaskState(Context context, String state) {
        getPrefs(context).edit().putString("task_state", state).apply();
    }

    /** Convenience: the currently configured task text. */
    public static String getTask(Context context) {
        return getPrefs(context).getString(LLMClientService.KEY_TASK, "").trim();
    }

    public static long getCurrentConvId(Context context) {
        return getPrefs(context).getLong("current_conv_id", 0);
    }

    public static List<String> getRecentTasks(Context context) {
        List<String> result = new ArrayList<>();
        String raw = getPrefs(context).getString("recent_tasks", "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                String t = array.optString(i, "").trim();
                if (!t.isEmpty()) {
                    result.add(t);
                }
            }
        } catch (JSONException e) {
            Log.w(TAG, "Corrupt recent task list, resetting", e);
        }
        return result;
    }

    /** Move the task to the front of the recent list (deduplicated, capped). */
    public static void addRecentTask(Context context, String task) {
        if (task == null || task.trim().isEmpty()) {
            return;
        }
        try {
            List<String> recent = getRecentTasks(context);
            recent.remove(task);
            recent.add(0, task);
            while (recent.size() > MAX_RECENT_TASKS) {
                recent.remove(recent.size() - 1);
            }
            getPrefs(context).edit()
                    .putString("recent_tasks", new JSONArray(recent).toString())
                    .apply();
        } catch (Exception e) {
            Log.w(TAG, "Failed to update recent tasks", e);
        }
    }

    private static SharedPreferences getPrefs(Context context) {
        return LLMClientService.getPrefs(context);
    }

    // ------------------------------------------------------------------
    // Conversations
    // ------------------------------------------------------------------

    /**
     * Create a conversation for a new task: inserts the meta row + the
     * user's message, arms the task (pending) and records it as the current
     * one.
     *
     * @return the new conversation id
     */
    public long createConversation(Context context, String task) {
        long id = System.currentTimeMillis();

        ContentValues conv = new ContentValues();
        conv.put("id", id);
        conv.put("task", task);
        conv.put("state", STATE_PENDING);
        conv.put("started_ts", id);
        conv.put("updated_ts", id);
        conv.put("summary", task);
        mDb.getWritableDatabase()
                .insertWithOnConflict("conversations", null, conv,
                        SQLiteDatabase.CONFLICT_REPLACE);

        insertMessage(id, newMessage(KIND_USER, task, null));

        getPrefs(context).edit()
                .putLong("current_conv_id", id)
                .putString(LLMClientService.KEY_TASK, task)
                .putString("task_state", STATE_PENDING)
                .apply();
        return id;
    }

    /**
     * Close out conversations still marked executing/paused while the
     * service is not running (e.g. after a frozen loop or force-stop):
     * marks them "stopped" so the list does not show ghost badges.
     */
    public static void reconcileStaleStates(Context context, boolean serviceRunning) {
        if (serviceRunning) {
            return;
        }
        getPrefs(context); // ensure prefs exist before touching the DB
        ContentValues values = new ContentValues();
        values.put("state", STATE_STOPPED);
        values.put("updated_ts", System.currentTimeMillis());
        DbHelper.get(context).getWritableDatabase().update(
                "conversations", values,
                "state IN (?, ?)",
                new String[]{STATE_EXECUTING, STATE_PAUSED});
    }

    /** All conversations, newest first. */
    public List<Conversation> listConversations() {
        List<Conversation> result = new ArrayList<>();
        Cursor c = mDb.getReadableDatabase().query(
                "conversations", null, null, null, null, null, "started_ts DESC");
        try {
            while (c.moveToNext()) {
                Conversation conv = new Conversation();
                conv.id = c.getLong(c.getColumnIndexOrThrow("id"));
                conv.task = c.getString(c.getColumnIndexOrThrow("task"));
                conv.state = c.getString(c.getColumnIndexOrThrow("state"));
                conv.startedTs = c.getLong(c.getColumnIndexOrThrow("started_ts"));
                conv.updatedTs = c.getLong(c.getColumnIndexOrThrow("updated_ts"));
                conv.summary = c.getString(c.getColumnIndexOrThrow("summary"));
                conv.archived = c.getInt(c.getColumnIndexOrThrow("archived")) != 0;
                result.add(conv);
            }
        } finally {
            c.close();
        }
        return result;
    }

    public Conversation getConversation(long id) {
        Cursor c = mDb.getReadableDatabase().query(
                "conversations", null, "id=?", new String[]{String.valueOf(id)},
                null, null, null);
        try {
            if (!c.moveToFirst()) {
                return null;
            }
            Conversation conv = new Conversation();
            conv.id = c.getLong(c.getColumnIndexOrThrow("id"));
            conv.task = c.getString(c.getColumnIndexOrThrow("task"));
            conv.state = c.getString(c.getColumnIndexOrThrow("state"));
            conv.startedTs = c.getLong(c.getColumnIndexOrThrow("started_ts"));
            conv.updatedTs = c.getLong(c.getColumnIndexOrThrow("updated_ts"));
            conv.summary = c.getString(c.getColumnIndexOrThrow("summary"));
            conv.archived = c.getInt(c.getColumnIndexOrThrow("archived")) != 0;
            return conv;
        } finally {
            c.close();
        }
    }

    /** Update a conversation's lifecycle state (and optionally its preview). */
    public void updateConversation(long id, String state, String summary) {
        ContentValues values = new ContentValues();
        values.put("state", state);
        values.put("updated_ts", System.currentTimeMillis());
        if (summary != null && !summary.isEmpty()) {
            values.put("summary", summary);
        }
        mDb.getWritableDatabase().update("conversations", values,
                "id=?", new String[]{String.valueOf(id)});
    }

    /** Archive or unarchive a conversation. */
    public void setArchived(long id, boolean archived) {
        ContentValues values = new ContentValues();
        values.put("archived", archived ? 1 : 0);
        mDb.getWritableDatabase().update("conversations", values,
                "id=?", new String[]{String.valueOf(id)});
    }

    /** Localized label for a conversation/task state. */
    public static String stateLabel(Context context, String state) {
        if (STATE_EXECUTING.equals(state)) return context.getString(R.string.conv_state_executing);
        if (STATE_PAUSED.equals(state)) return context.getString(R.string.conv_state_paused);
        if (STATE_DONE.equals(state)) return context.getString(R.string.conv_state_done);
        if (STATE_INTERRUPTED.equals(state)) return context.getString(R.string.conv_state_interrupted);
        if (STATE_STALLED.equals(state)) return context.getString(R.string.conv_state_stalled);
        if (STATE_PENDING.equals(state)) return context.getString(R.string.conv_state_pending);
        return context.getString(R.string.conv_state_stopped);
    }

    /** Relative timestamp for list rows ("5 min ago" style, localized). */
    public static CharSequence relativeTime(long ts) {
        return DateUtils.getRelativeTimeSpanString(ts);
    }

    // ------------------------------------------------------------------
    // Messages (scoped to one conversation)
    // ------------------------------------------------------------------

    /** Snapshot of one conversation's messages, oldest first. Reads from DB. */
    public List<Message> getMessages(long convId) {
        List<Message> messages = new ArrayList<>();
        Cursor c = mDb.getReadableDatabase().query(
                "messages", null, "conv_id=?", new String[]{String.valueOf(convId)},
                null, null, "ts ASC");
        try {
            while (c.moveToNext()) {
                Message m = new Message();
                m.id = c.getLong(c.getColumnIndexOrThrow("id"));
                m.ts = c.getLong(c.getColumnIndexOrThrow("ts"));
                m.kind = c.getInt(c.getColumnIndexOrThrow("kind"));
                m.text = c.getString(c.getColumnIndexOrThrow("text"));
                m.imagePath = c.getString(c.getColumnIndexOrThrow("image_path"));
                messages.add(m);
            }
        } finally {
            c.close();
        }
        return messages;
    }

    /** Append a centered status line. */
    public void appendStatus(long convId, String text) {
        insertMessage(convId, newMessage(KIND_STATUS, text, null));
    }

    /**
     * Append a key action node. The screenshot is the frame the model based
     * its decision on; it is saved as a downscaled JPEG in the chat dir.
     */
    public void appendAction(long convId, String description, Bitmap screenshot) {
        String image = screenshot != null ? saveScreenshot(screenshot) : null;
        insertMessage(convId, newMessage(KIND_ACTION, description, image));
    }

    /** Append the model's completion summary, optionally with a final screenshot. */
    public void appendResult(long convId, String summary, Bitmap finalScreenshot) {
        String image = finalScreenshot != null ? saveScreenshot(finalScreenshot) : null;
        insertMessage(convId, newMessage(KIND_RESULT, summary, image));
    }

    /**
     * Fetch the JPEG bytes of a stored screenshot, or null when unknown.
     * Large-ish (~100 KB); call off the UI thread and cache the decode.
     */
    public byte[] getImageBytes(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        Cursor c = mDb.getReadableDatabase().query(
                "images", new String[]{"data"}, "name=?", new String[]{name},
                null, null, null);
        try {
            return c.moveToFirst() ? c.getBlob(0) : null;
        } finally {
            c.close();
        }
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private Message newMessage(int kind, String text, String imagePath) {
        Message m = new Message();
        m.id = System.currentTimeMillis();
        m.ts = m.id;
        m.kind = kind;
        m.text = text != null ? text : "";
        m.imagePath = imagePath;
        return m;
    }

    /** Insert one message row and bump the conversation's updated time/preview. */
    private void insertMessage(long convId, Message message) {
        ContentValues values = new ContentValues();
        values.put("conv_id", convId);
        values.put("id", message.id);
        values.put("ts", message.ts);
        values.put("kind", message.kind);
        values.put("text", message.text);
        values.put("image_path", message.imagePath);

        SQLiteDatabase db = mDb.getWritableDatabase();
        db.beginTransaction();
        try {
            db.insertWithOnConflict("messages", null, values,
                    SQLiteDatabase.CONFLICT_REPLACE);
            ContentValues conv = new ContentValues();
            conv.put("updated_ts", message.ts);
            if (!message.text.isEmpty()) {
                conv.put("summary", message.text);
            }
            db.update("conversations", conv, "id=?", new String[]{String.valueOf(convId)});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        trimConvIfNeed(convId);
    }

    /**
     * One-time import of the pre-database file storage: the original single
     * JSONL log (oldest installs) and the per-conversation JSONL + meta
     * files. Files are removed after a successful import.
     */
    private void migrateFileStorageIfNeeded() {
        SharedPreferences prefs = getPrefs(mContext);
        if (prefs.getBoolean(MIGRATED_FLAG, false)) {
            return;
        }

        SQLiteDatabase db = mDb.getWritableDatabase();
        db.beginTransaction();
        try {
            File legacy = new File(mChatDir, LEGACY_LOG_NAME);
            if (legacy.exists()) {
                long convId = legacy.lastModified();
                List<Message> messages = readJsonlFile(legacy);
                importConversation(db, convId,
                        findFirstUserText(messages, getTask(mContext)),
                        getTaskState(mContext), messages);
                legacy.delete();
                Log.i(TAG, "Imported legacy chat log as conversation " + convId);
            }

            File[] files = mChatDir.listFiles();
            if (files != null) {
                for (File metaFile : files) {
                    String name = metaFile.getName();
                    if (!name.startsWith("conv_") || !name.endsWith(".meta.json")) {
                        continue;
                    }
                    long convId = parseConvId(name);
                    File jsonl = new File(mChatDir, "conv_" + convId + ".jsonl");
                    List<Message> messages = jsonl.exists()
                            ? readJsonlFile(jsonl) : new ArrayList<Message>();
                    JSONObject meta = readJsonFile(metaFile);
                    if (meta == null) {
                        continue;
                    }
                    importConversation(db, convId,
                            meta.optString("task", ""),
                            meta.optString("state", STATE_DONE), messages);
                    metaFile.delete();
                    if (jsonl.exists()) {
                        jsonl.delete();
                    }
                    Log.i(TAG, "Imported conversation " + convId + " into DB");
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        prefs.edit().putBoolean(MIGRATED_FLAG, true).apply();
    }

    private static long parseConvId(String metaFileName) {
        String s = metaFileName.substring("conv_".length(),
                metaFileName.length() - ".meta.json".length());
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void importConversation(SQLiteDatabase db, long convId, String task,
            String state, List<Message> messages) {
        long started = messages.isEmpty() ? convId : messages.get(0).ts;
        long updated = messages.isEmpty() ? convId : messages.get(messages.size() - 1).ts;
        String summary = messages.isEmpty() ? "" : messages.get(messages.size() - 1).text;

        ContentValues conv = new ContentValues();
        conv.put("id", convId);
        conv.put("task", task);
        conv.put("state", state);
        conv.put("started_ts", started);
        conv.put("updated_ts", updated);
        conv.put("summary", summary);
        db.insertWithOnConflict("conversations", null, conv, SQLiteDatabase.CONFLICT_REPLACE);

        for (Message m : messages) {
            ContentValues values = new ContentValues();
            values.put("conv_id", convId);
            values.put("id", m.id);
            values.put("ts", m.ts);
            values.put("kind", m.kind);
            values.put("text", m.text);
            values.put("image_path", m.imagePath);
            db.insertWithOnConflict("messages", null, values,
                    SQLiteDatabase.CONFLICT_REPLACE);
        }
    }

    private static String findFirstUserText(List<Message> messages, String fallback) {
        for (Message m : messages) {
            if (m.kind == KIND_USER && !m.text.isEmpty()) {
                return m.text;
            }
        }
        return fallback;
    }

    private List<Message> readJsonlFile(File file) {
        List<Message> messages = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                try {
                    JSONObject json = new JSONObject(line);
                    Message m = new Message();
                    m.id = json.optLong("id", 0);
                    m.ts = json.optLong("ts", m.id);
                    m.kind = json.optInt("kind", KIND_STATUS);
                    m.text = json.optString("text", "");
                    m.imagePath = json.has("img") ? json.optString("img") : null;
                    messages.add(m);
                } catch (JSONException e) {
                    Log.w(TAG, "Skipping corrupt chat line");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to read chat log " + file.getName(), e);
        }
        return messages;
    }

    private JSONObject readJsonFile(File file) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return new JSONObject(sb.toString());
        } catch (Exception e) {
            Log.w(TAG, "Skipping corrupt meta file " + file.getName());
            return null;
        }
    }

    /** Keep the per-conversation message count bounded. */
    private void trimConvIfNeed(long convId) {
        SQLiteDatabase db = mDb.getWritableDatabase();
        Cursor c = db.rawQuery(
                "SELECT COUNT(*) FROM messages WHERE conv_id=?",
                new String[]{String.valueOf(convId)});
        int count = 0;
        try {
            count = c.moveToFirst() ? c.getInt(0) : 0;
        } finally {
            c.close();
        }
        if (count <= MAX_MESSAGES_PER_CONV) {
            return;
        }
        db.execSQL("DELETE FROM messages WHERE conv_id=? AND id NOT IN "
                        + "(SELECT id FROM messages WHERE conv_id=? "
                        + "ORDER BY ts DESC LIMIT ?)",
                new Object[]{convId, convId, KEEP_AFTER_TRIM});
        cleanupOrphanImages();
    }

    /** Delete image rows no longer referenced by any message row. */
    private void cleanupOrphanImages() {
        mDb.getWritableDatabase().execSQL(
                "DELETE FROM images WHERE name NOT IN "
                        + "(SELECT DISTINCT image_path FROM messages "
                        + "WHERE image_path IS NOT NULL)");
    }

    /** Save the screenshot into the images table; returns its name. */
    private String saveScreenshot(Bitmap screenshot) {
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            Bitmap toSave = screenshot;
            int maxDim = Math.max(screenshot.getWidth(), screenshot.getHeight());
            if (maxDim > MAX_SCREENSHOT_DIM) {
                float scale = (float) MAX_SCREENSHOT_DIM / maxDim;
                toSave = Bitmap.createScaledBitmap(screenshot,
                        Math.round(screenshot.getWidth() * scale),
                        Math.round(screenshot.getHeight() * scale), true);
            }
            toSave.compress(Bitmap.CompressFormat.JPEG, SCREENSHOT_JPEG_QUALITY, out);
            byte[] bytes = out.toByteArray();
            if (toSave != screenshot) {
                toSave.recycle();
            }

            String name = "img_" + System.currentTimeMillis() + ".jpg";
            ContentValues values = new ContentValues();
            values.put("name", name);
            values.put("data", bytes);
            values.put("created_ts", System.currentTimeMillis());
            mDb.getWritableDatabase()
                    .insertWithOnConflict("images", null, values,
                            SQLiteDatabase.CONFLICT_REPLACE);
            return name;
        } catch (Exception e) {
            Log.e(TAG, "Failed to store chat screenshot", e);
            return null;
        }
    }

    /**
     * One-time import of screenshot JPEG files left by pre-DB versions into
     * the images table. Idempotent: after the import no .jpg files remain.
     */
    private void migrateImageFilesIfNeeded() {
        File[] files = mChatDir.listFiles();
        boolean hasJpg = false;
        if (files != null) {
            for (File f : files) {
                if (f.getName().endsWith(".jpg")) {
                    hasJpg = true;
                    break;
                }
            }
        }
        if (!hasJpg) {
            return;
        }
        int imported = 0;
        for (File f : files) {
            if (!f.getName().endsWith(".jpg")) {
                continue;
            }
            try {
                byte[] bytes = new byte[(int) f.length()];
                try (FileInputStream in = new FileInputStream(f)) {
                    int read = in.read(bytes);
                    if (read != bytes.length) {
                        throw new java.io.IOException("short read");
                    }
                }
                ContentValues values = new ContentValues();
                values.put("name", f.getName());
                values.put("data", bytes);
                values.put("created_ts", f.lastModified());
                mDb.getWritableDatabase()
                        .insertWithOnConflict("images", null, values,
                                SQLiteDatabase.CONFLICT_REPLACE);
                f.delete();
                imported++;
            } catch (Exception e) {
                Log.w(TAG, "Failed to import screenshot " + f.getName(), e);
            }
        }
        Log.i(TAG, "Imported " + imported + " screenshot files into DB");
    }

}
