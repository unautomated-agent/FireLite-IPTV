package dev.firetvlite.iptv;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

/** Vollbild-Wiedergabe. Hoch/Runter wechselt den Sender, OK zeigt die Infoleiste, Menue = Favorit. */
public class PlayerActivity extends Activity {

    private static final long OVERLAY_MS = 4000;
    private static final int MAX_RETRIES = 3;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private Prefs prefs;
    private ExoPlayer player;
    private SurfaceView surface;
    private TextView overlay;
    private TextView message;
    private ProgressBar spinner;
    private int retries;

    private final Runnable hideOverlay = new Runnable() {
        @Override
        public void run() {
            overlay.setVisibility(View.GONE);
        }
    };

    private final Runnable retryPlayback = new Runnable() {
        @Override
        public void run() {
            if (player != null) {
                player.seekToDefaultPosition();
                player.prepare();
            }
        }
    };

    private final Player.Listener listener = new Player.Listener() {
        @Override
        public void onPlaybackStateChanged(int state) {
            spinner.setVisibility(state == Player.STATE_BUFFERING ? View.VISIBLE : View.GONE);
            if (state == Player.STATE_READY) {
                retries = 0;
                message.setVisibility(View.GONE);
            }
        }

        @Override
        public void onPlayerError(PlaybackException error) {
            spinner.setVisibility(View.GONE);
            if (retries < MAX_RETRIES) {
                retries++;
                showMessage("Verbindungsproblem \u2013 neuer Versuch " + retries + " von " + MAX_RETRIES);
                ui.postDelayed(retryPlayback, 1500L * retries);
            } else {
                showMessage("Dieser Sender l\u00E4uft nicht (" + error.getErrorCodeName() + ").\n"
                        + "Hoch/Runter: anderer Sender   Zur\u00FCck: Liste");
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Store.zapList == null || Store.zapList.isEmpty()) {
            finish();
            return;
        }
        prefs = new Prefs(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        surface = new SurfaceView(this);
        root.addView(surface, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        overlay = new TextView(this);
        overlay.setTextColor(Color.WHITE);
        overlay.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        overlay.setBackgroundColor(0xCC000000);
        overlay.setPadding(dp(32), dp(16), dp(32), dp(16));
        overlay.setVisibility(View.GONE);
        root.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        message = new TextView(this);
        message.setTextColor(Color.WHITE);
        message.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        message.setGravity(Gravity.CENTER);
        message.setBackgroundColor(0xB0000000);
        message.setPadding(dp(32), dp(20), dp(32), dp(20));
        message.setVisibility(View.GONE);
        root.addView(message, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        spinner = new ProgressBar(this);
        spinner.setVisibility(View.GONE);
        root.addView(spinner, new FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER));

        setContentView(root);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (Store.zapList != null && !Store.zapList.isEmpty()) {
            startPlayer();
        }
    }

    @Override
    protected void onStop() {
        stopPlayer();
        super.onStop();
    }

    private void startPlayer() {
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(prefs.userAgent())
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(20000);
        DefaultRenderersFactory renderers = new DefaultRenderersFactory(this)
                .setEnableDecoderFallback(true);
        player = new ExoPlayer.Builder(this, renderers)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(http))
                .build();
        player.setAudioAttributes(AudioAttributes.DEFAULT, true);
        player.setVideoSurfaceView(surface);
        player.addListener(listener);
        playCurrent();
    }

    private void stopPlayer() {
        ui.removeCallbacks(retryPlayback);
        ui.removeCallbacks(hideOverlay);
        if (player != null) {
            player.removeListener(listener);
            player.release();
            player = null;
        }
    }

    private Channel current() {
        return Store.zapList.get(Store.zapIndex);
    }

    private void playCurrent() {
        retries = 0;
        ui.removeCallbacks(retryPlayback);
        message.setVisibility(View.GONE);
        player.setMediaItem(MediaItem.fromUri(current().url));
        player.prepare();
        player.play();
        showOverlay();
    }

    private void zap(int delta) {
        int size = Store.zapList.size();
        Store.zapIndex = ((Store.zapIndex + delta) % size + size) % size;
        playCurrent();
    }

    private void showOverlay() {
        Channel channel = current();
        String star = prefs.isFavorite(channel.url) ? "\u2605  " : "";
        overlay.setText((Store.zapIndex + 1) + " / " + Store.zapList.size() + "     " + star + channel.name
                + "\n" + channel.group);
        overlay.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideOverlay);
        ui.postDelayed(hideOverlay, OVERLAY_MS);
    }

    private void showMessage(String text) {
        message.setText(text);
        message.setVisibility(View.VISIBLE);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (player == null) {
            return super.onKeyDown(keyCode, event);
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_CHANNEL_UP:
                if (event.getRepeatCount() == 0) {
                    zap(-1);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                if (event.getRepeatCount() == 0) {
                    zap(1);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                showOverlay();
                return true;
            case KeyEvent.KEYCODE_MENU:
                if (event.getRepeatCount() == 0) {
                    prefs.toggleFavorite(current().url);
                    showOverlay();
                }
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                if (player.getPlayWhenReady()) {
                    player.pause();
                } else {
                    player.play();
                }
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                player.play();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                player.pause();
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }
}
