package dev.firetvlite.iptv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Startbildschirm: links die Gruppen, rechts die Sender der gewaehlten Gruppe. */
public class MainActivity extends Activity {

    private static final int BG = 0xFF101418;
    private static final int PANEL = 0xFF161C22;
    private static final int TEXT = 0xFFECEFF1;
    private static final int MUTED = 0xFF90A4AE;
    private static final int ACCENT = 0xFF4FC3F7;
    private static final int SELECTOR = 0x554FC3F7;

    private static final int ROW_SETTINGS = 0;
    private static final int ROW_FAVORITES = 1;
    private static final int ROW_ALL = 2;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private Prefs prefs;
    private File cacheFile;

    private TextView status;
    private ListView categoryList;
    private ListView channelList;
    private CategoryAdapter categoryAdapter;
    private ChannelAdapter channelAdapter;

    /** Beschriftungen und Inhalte der linken Liste. Eintrag null = "Einstellungen". */
    private final ArrayList<String> categoryLabels = new ArrayList<>();
    private final ArrayList<ArrayList<Channel>> categoryData = new ArrayList<>();
    private int activeCategory = -1;
    private ArrayList<Channel> shown = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        cacheFile = new File(getFilesDir(), "playlist.m3u");
        buildUi();

        boolean started = handleIntent(getIntent());
        if (!started) {
            if (prefs.url().isEmpty()) {
                buildCategories(null);
                showSettings(true);
            } else {
                load(false);
            }
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Im Player kann ein Favorit geaendert worden sein.
        if (Store.playlist != null) {
            refreshFavorites();
        }
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    /**
     * Setzt die Playlist-URL per Befehl vom PC, z. B.:
     * adb shell am start -n dev.firetvlite.iptv/.MainActivity --es url "http://..."
     */
    private boolean handleIntent(Intent intent) {
        if (intent == null) {
            return false;
        }
        String url = intent.getStringExtra("url");
        if (url == null || url.trim().isEmpty()) {
            return false;
        }
        String ua = intent.getStringExtra("ua");
        prefs.save(url.trim(), ua == null || ua.trim().isEmpty() ? prefs.userAgent() : ua.trim());
        load(true);
        return true;
    }

    // ---------------------------------------------------------------- Aufbau der Oberflaeche

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(32), dp(20), dp(32), dp(16));

        status = new TextView(this);
        status.setTextColor(MUTED);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        status.setPadding(0, 0, 0, dp(10));
        root.addView(status, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        categoryList = newList();
        channelList = newList();
        categoryAdapter = new CategoryAdapter();
        channelAdapter = new ChannelAdapter();
        categoryList.setAdapter(categoryAdapter);
        channelList.setAdapter(channelAdapter);
        categoryList.setNextFocusRightId(channelList.getId());
        channelList.setNextFocusLeftId(categoryList.getId());

        row.addView(categoryList, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 2f);
        right.leftMargin = dp(16);
        row.addView(channelList, right);

        root.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView hint = new TextView(this);
        hint.setTextColor(MUTED);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        hint.setPadding(0, dp(10), 0, 0);
        hint.setText("OK: abspielen    Men\u00FC-Taste: Favorit (bei Sendern) / Einstellungen (bei Gruppen)    Im Bild: Hoch/Runter = Sender wechseln");
        root.addView(hint, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);

        categoryList.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                showCategory(position);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        categoryList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position == ROW_SETTINGS) {
                    showSettings(false);
                } else {
                    channelList.requestFocus();
                }
            }
        });
        channelList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                play(position);
            }
        });
    }

    private ListView newList() {
        ListView list = new ListView(this);
        list.setId(View.generateViewId());
        list.setBackgroundColor(PANEL);
        list.setSelector(new ColorDrawable(SELECTOR));
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setFocusable(true);
        return list;
    }

    private TextView newRow() {
        TextView row = new TextView(this);
        row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        row.setPadding(dp(16), dp(12), dp(16), dp(12));
        row.setSingleLine(true);
        row.setEllipsize(TextUtils.TruncateAt.END);
        return row;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    // ---------------------------------------------------------------- Playlist laden

    private void load(final boolean forceDownload) {
        final String url = prefs.url();
        final String userAgent = prefs.userAgent();
        setStatus("Lade Playlist \u2026");
        io.execute(new Runnable() {
            @Override
            public void run() {
                Playlist result = null;
                String error = null;
                boolean offline = false;
                try {
                    if (forceDownload || !cacheFile.exists()) {
                        try {
                            PlaylistLoader.download(url, userAgent, cacheFile);
                        } catch (IOException e) {
                            if (!cacheFile.exists()) {
                                throw e;
                            }
                            offline = true; // es gibt noch die zuletzt gespeicherte Liste
                        }
                    }
                    result = PlaylistLoader.parseFile(cacheFile);
                    if (result.all.isEmpty()) {
                        result = null;
                        cacheFile.delete();
                        error = "Keine Sender gefunden. Ist das wirklich eine M3U-Playlist?";
                    }
                } catch (IOException e) {
                    error = "Fehler beim Laden: " + e.getMessage();
                } catch (OutOfMemoryError e) {
                    result = null;
                    error = "Die Playlist ist zu gro\u00DF f\u00FCr den Speicher.";
                }
                final Playlist playlist = result;
                final String message = error;
                final boolean wasOffline = offline;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (playlist == null) {
                            Store.playlist = null;
                            buildCategories(null);
                            setStatus(message + "  (Men\u00FC-Taste \u2192 Einstellungen)");
                        } else {
                            Store.playlist = playlist;
                            buildCategories(playlist);
                            setStatus(playlist.all.size() + " Sender in " + playlist.groups.size() + " Gruppen"
                                    + (wasOffline ? "  \u00B7  Offline: zuletzt gespeicherte Liste" : ""));
                        }
                    }
                });
            }
        });
    }

    private void setStatus(String text) {
        status.setText(text);
    }

    // ---------------------------------------------------------------- Gruppen und Sender

    private void buildCategories(Playlist playlist) {
        categoryLabels.clear();
        categoryData.clear();
        categoryLabels.add("Einstellungen");
        categoryData.add(null);
        if (playlist != null) {
            ArrayList<Channel> favorites = collectFavorites(playlist);
            categoryLabels.add("\u2605  Favoriten (" + favorites.size() + ")");
            categoryData.add(favorites);
            categoryLabels.add("Alle (" + playlist.all.size() + ")");
            categoryData.add(playlist.all);
            for (Map.Entry<String, ArrayList<Channel>> entry : playlist.groups.entrySet()) {
                categoryLabels.add(entry.getKey() + " (" + entry.getValue().size() + ")");
                categoryData.add(entry.getValue());
            }
        }
        activeCategory = -1;
        categoryAdapter.notifyDataSetChanged();
        int start = playlist == null ? ROW_SETTINGS : ROW_ALL;
        categoryList.setSelection(start);
        showCategory(start);
        categoryList.requestFocus();
    }

    private void showCategory(int position) {
        if (position < 0 || position >= categoryData.size() || position == activeCategory) {
            return;
        }
        activeCategory = position;
        ArrayList<Channel> list = categoryData.get(position);
        shown = list == null ? new ArrayList<Channel>() : list;
        categoryAdapter.notifyDataSetChanged();
        channelAdapter.notifyDataSetChanged();
        channelList.setSelection(0);
    }

    private ArrayList<Channel> collectFavorites(Playlist playlist) {
        ArrayList<Channel> favorites = new ArrayList<>();
        for (Channel channel : playlist.all) {
            if (prefs.isFavorite(channel.url)) {
                favorites.add(channel);
            }
        }
        return favorites;
    }

    private void refreshFavorites() {
        Playlist playlist = Store.playlist;
        if (playlist == null || categoryData.size() <= ROW_FAVORITES) {
            return;
        }
        ArrayList<Channel> favorites = collectFavorites(playlist);
        categoryData.set(ROW_FAVORITES, favorites);
        categoryLabels.set(ROW_FAVORITES, "\u2605  Favoriten (" + favorites.size() + ")");
        if (activeCategory == ROW_FAVORITES) {
            int selected = channelList.getSelectedItemPosition();
            shown = favorites;
            channelAdapter.notifyDataSetChanged();
            channelList.setSelection(Math.max(0, Math.min(selected, favorites.size() - 1)));
        }
        categoryAdapter.notifyDataSetChanged();
        channelAdapter.notifyDataSetChanged();
    }

    private void toggleFavorite(Channel channel) {
        boolean added = prefs.toggleFavorite(channel.url);
        Toast.makeText(this, (added ? "Favorit: " : "Kein Favorit mehr: ") + channel.name,
                Toast.LENGTH_SHORT).show();
        refreshFavorites();
    }

    private void play(int position) {
        if (position < 0 || position >= shown.size()) {
            return;
        }
        Store.zapList = shown;
        Store.zapIndex = position;
        startActivity(new Intent(this, PlayerActivity.class));
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            if (channelList.hasFocus()) {
                int position = channelList.getSelectedItemPosition();
                if (position >= 0 && position < shown.size()) {
                    toggleFavorite(shown.get(position));
                }
            } else {
                showSettings(false);
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ---------------------------------------------------------------- Einstellungen

    private void showSettings(final boolean firstRun) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(24), dp(16), dp(24), dp(8));

        form.addView(newLabel("Playlist-URL (M3U)"));
        final EditText urlField = newField(prefs.url(), "http://\u2026/playlist.m3u");
        urlField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        form.addView(urlField);

        form.addView(newLabel("User-Agent (nur \u00E4ndern, wenn dein Anbieter es verlangt)"));
        final EditText agentField = newField(prefs.userAgent(), Prefs.DEFAULT_USER_AGENT);
        form.addView(agentField);

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(firstRun ? "Willkommen! Playlist einrichten" : "Einstellungen")
                .setView(form)
                .setCancelable(!firstRun)
                .setPositiveButton("Speichern & laden", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String url = urlField.getText().toString().trim();
                        String agent = agentField.getText().toString().trim();
                        if (!url.startsWith("http://") && !url.startsWith("https://")) {
                            Toast.makeText(MainActivity.this,
                                    "Die URL muss mit http:// oder https:// beginnen.",
                                    Toast.LENGTH_LONG).show();
                            showSettings(firstRun);
                            return;
                        }
                        prefs.save(url, agent.isEmpty() ? Prefs.DEFAULT_USER_AGENT : agent);
                        load(true);
                    }
                });
        if (!firstRun) {
            builder.setNeutralButton("Neu laden", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    if (prefs.url().isEmpty()) {
                        showSettings(true);
                    } else {
                        load(true);
                    }
                }
            });
            builder.setNegativeButton("Abbrechen", null);
        }
        builder.create().show();
    }

    private TextView newLabel(String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        label.setPadding(0, dp(12), 0, dp(4));
        return label;
    }

    private EditText newField(String value, String hint) {
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setText(value);
        field.setHint(hint);
        field.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        field.setMinWidth(dp(520));
        return field;
    }

    // ---------------------------------------------------------------- Adapter

    private final class CategoryAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return categoryLabels.size();
        }

        @Override
        public Object getItem(int position) {
            return categoryLabels.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TextView row = convertView instanceof TextView ? (TextView) convertView : newRow();
            row.setText(categoryLabels.get(position));
            row.setTextColor(position == activeCategory ? ACCENT : TEXT);
            return row;
        }
    }

    private final class ChannelAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return shown.size();
        }

        @Override
        public Object getItem(int position) {
            return shown.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TextView row = convertView instanceof TextView ? (TextView) convertView : newRow();
            Channel channel = shown.get(position);
            row.setText((prefs.isFavorite(channel.url) ? "\u2605  " : "") + channel.name);
            row.setTextColor(Color.WHITE);
            return row;
        }
    }
}
