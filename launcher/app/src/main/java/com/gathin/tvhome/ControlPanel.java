package com.gathin.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.media.tv.TvInputManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * appletv_control_center: the glass panel OK on the status capsule opens, in the manner of tvOS's Control Center. It
 * slides in from the right over a blurred, darker snapshot of the screen (made once, by the caller), holds the owner's
 * initial and name, the time and date, a grid of tiles (Standby, Sleep timer, Network, Sound, then Subtitles and
 * Settings) around the inputs list, and a row for the launcher's own settings. A tile shows only when it does
 * something on this TV (ControlCenter). It is a PopupWindow, so it keeps the focus and Back closes it.
 */
final class ControlPanel implements MainActivity.InputSink {
    /** What only Home can do: list its inputs, open the network pill's screen, its settings, the time it shows. */
    interface Host {
        void listInputs(MainActivity.InputSink sink);
        void openNetwork();
        void openLauncherSettings();
        boolean libraryOn();
        void openLibrary();
        void open(Intent intent);
        Intent systemIntent(String standardAction);
        CharSequence time();
    }

    /** The network as the pill reads it: its name (or "Ethernet", "Offline"), and whether it is Ethernet, and up. */
    static final class Network {
        final String name;
        final boolean ethernet, online;

        Network(String name, boolean ethernet, boolean online) {
            this.name = name;
            this.ethernet = ethernet;
            this.online = online;
        }
    }

    private static final String TAG = "ControlCenter";
    private static final int INK_DARK = 0xFF1C1C1E;

    private final Activity activity;
    private final Host host;
    private final float density;
    private final boolean animations, reducedMotion;
    private final Handler handler = new Handler(Looper.getMainLooper());
    final PopupWindow popup;
    private final LinearLayout inputRows;
    private TextView clock, timerSub;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, 15_000);
        }
    };

    ControlPanel(Activity activity, Host host, Bitmap backdrop, String greetingName, Network network,
                 boolean animations, boolean reducedMotion, Runnable onClosed) {
        this.activity = activity;
        this.host = host;
        this.density = activity.getResources().getDisplayMetrics().density;
        this.animations = animations;
        this.reducedMotion = reducedMotion;

        FrameLayout screen = new FrameLayout(activity);
        ImageView behind = new ImageView(activity);
        behind.setScaleType(ImageView.ScaleType.FIT_XY);
        if (backdrop != null) behind.setImageBitmap(backdrop);
        else behind.setImageDrawable(new ColorDrawable(0xE6000000));
        behind.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        screen.addView(behind, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        panel.setPadding(pad, dp(18), pad, dp(16));
        panel.setBackground(glass(ControlCenter.PANEL_RADIUS, 0x24FFFFFF, 0x40FFFFFF));
        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(dp(ControlCenter.PANEL_W), ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        panelLp.topMargin = dp(ControlCenter.PANEL_TOP);
        panelLp.setMarginEnd(dp(ControlCenter.PANEL_RIGHT));
        screen.addView(panel, panelLp);

        panel.addView(header(greetingName));

        // Which tiles this TV can use: Standby and the timer need a key helper; the settings tiles a screen that opens.
        int sdk = Build.VERSION.SDK_INT;
        boolean helper = SleepTimer.helperBound();
        Intent sound = new Intent(Settings.ACTION_SOUND_SETTINGS);
        Intent captions = new Intent(Settings.ACTION_CAPTIONING_SETTINGS);
        boolean soundOk = ControlCenter.settingsOffered(resolves(sound));
        boolean captionsOk = ControlCenter.settingsOffered(resolves(captions));
        // Without a helper, Standby opens the TV's power options: Google TV's Energy modes, else its settings.
        Intent energy = new Intent().setClassName("com.android.tv.settings", "com.android.tv.settings.device.eco.EnergyModesActivity");
        if (!resolves(energy)) energy = host.systemIntent(Settings.ACTION_SETTINGS);
        Intent power = energy;
        Log.d(TAG, "helper=" + helper + " sound=" + soundOk + " captions=" + captionsOk + " power=" + power.getComponent());
        List<View> tiles = new ArrayList<>();
        boolean sleeps = ControlCenter.canSleep(helper, sdk);
        tiles.add(tile(R.drawable.ic_power, activity.getString(R.string.cc_standby),
                activity.getString(sleeps ? R.string.cc_standby_sub : R.string.cc_standby_options), 0,
                () -> {
                    dismiss();
                    if (!SleepTimer.sleepNow()) host.open(power);
                }));
        if (ControlCenter.timerOffered(helper, sdk)) {
            View timer = tile(R.drawable.ic_timer, activity.getString(R.string.cc_timer), "", 0, null);
            timerSub = timer.findViewWithTag("sub");
            timer.setOnClickListener(v -> {
                SleepTimer.set(activity, ControlCenter.nextTimer(SleepTimer.chosen(activity)));
                refresh();
            });
            tiles.add(timer);
        }
        // "Wi-Fi" over the network's name (where Android lets the launcher read it), "Ethernet", or offline.
        String wifi = activity.getString(R.string.wifi);
        String netTitle = !network.online ? activity.getString(R.string.cc_network)
                : network.ethernet ? activity.getString(R.string.ethernet) : wifi;
        String netSub = !network.online ? activity.getString(R.string.offline)
                : network.ethernet || TextUtils.isEmpty(network.name) || network.name.equals(wifi)
                ? activity.getString(R.string.connected) : network.name;
        tiles.add(tile(network.ethernet ? R.drawable.ic_ethernet : R.drawable.ic_wifi, netTitle, netSub,
                network.online ? 0xFF0A84FF : 0, () -> {
                    dismiss();
                    host.openNetwork();
                }));
        if (soundOk) {
            tiles.add(tile(R.drawable.ic_volume, activity.getString(R.string.cc_sound), activity.getString(R.string.cc_sound_sub), 0,
                    () -> {
                        dismiss();
                        host.open(sound);
                    }));
        }
        if (captionsOk) {
            tiles.add(tile(R.drawable.ic_subtitles, activity.getString(R.string.cc_subtitles), activity.getString(R.string.cc_subtitles_sub), 0,
                    () -> {
                        dismiss();
                        host.open(captions);
                    }));
        }
        tiles.add(tile(R.drawable.ic_settings, activity.getString(R.string.cc_settings), activity.getString(R.string.cc_settings_sub), 0,
                () -> {
                    dismiss();
                    host.open(host.systemIntent(Settings.ACTION_SETTINGS));
                }));
        // Four tiles above the inputs, as in tvOS's two rows, and the rest below them.
        addGrid(panel, tiles.subList(0, Math.min(4, tiles.size())));

        // The inputs, each a row, in the order and with the names the Inputs menu gives them.
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(6), dp(6), dp(6), dp(6));
        box.setBackground(glass(ControlCenter.TILE_RADIUS, 0x1AFFFFFF, 0x29FFFFFF));
        inputRows = new LinearLayout(activity);
        inputRows.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(activity);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(inputRows);
        box.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        host.listInputs(this);
        if (inputRows.getChildCount() > 0) {
            LinearLayout.LayoutParams boxLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            boxLp.topMargin = dp(ControlCenter.GAP);
            panel.addView(box, boxLp);
        }

        if (tiles.size() > 4) addGrid(panel, tiles.subList(4, tiles.size()));
        // The launcher's own settings, and its App library (the way in once its pill is hidden), side by side.
        LinearLayout extras = new LinearLayout(activity);
        extras.setOrientation(LinearLayout.HORIZONTAL);
        extras.addView(row(R.drawable.ic_home_settings_pill, activity.getString(R.string.cc_launcher_settings), "", () -> {
            dismiss();
            host.openLauncherSettings();
        }), new LinearLayout.LayoutParams(0, dp(34), 1.6f));
        if (host.libraryOn()) {
            LinearLayout.LayoutParams libLp = new LinearLayout.LayoutParams(0, dp(34), 1f);
            libLp.setMarginStart(dp(ControlCenter.GAP));
            extras.addView(row(R.drawable.ic_apps, activity.getString(R.string.cc_library), "", () -> {
                dismiss();
                host.openLibrary();
            }), libLp);
        }
        LinearLayout.LayoutParams extrasLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        extrasLp.topMargin = dp(8);
        panel.addView(extras, extrasLp);

        // At most PANEL_MAX_H tall: a long inputs list scrolls in its box.
        panel.measure(View.MeasureSpec.makeMeasureSpec(dp(ControlCenter.PANEL_W), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int over = panel.getMeasuredHeight() - dp(ControlCenter.PANEL_MAX_H);
        if (over > 0) scroll.getLayoutParams().height = Math.max(dp(40), scroll.getMeasuredHeight() - over);

        popup = new PopupWindow(screen, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // needed for Back to dismiss
        popup.setAnimationStyle(0);
        popup.setClippingEnabled(false);
        popup.setOnDismissListener(() -> {
            handler.removeCallbacks(tick);
            onClosed.run();
        });
        refresh();

        // In: the snapshot fades up and the panel slides in from the right edge, 220 ms, eased out.
        int ms = !animations ? 0 : reducedMotion ? Motion.REDUCED_MS : ControlCenter.SLIDE_MS;
        behind.setAlpha(0f);
        behind.animate().alpha(1f).setDuration(ms).start();
        panel.setTranslationX(reducedMotion || !animations ? 0f : dp(ControlCenter.PANEL_W + ControlCenter.PANEL_RIGHT));
        panel.setAlpha(reducedMotion ? 0f : 1f);
        panel.animate().translationX(0f).alpha(1f).setDuration(ms).setInterpolator(new DecelerateInterpolator(1.6f)).start();
    }

    void show(View parent) {
        popup.showAtLocation(parent, Gravity.NO_GRAVITY, 0, 0);
        handler.postDelayed(tick, 15_000);
        // Focus on the first tile, as tvOS starts there.
        ViewGroup content = (ViewGroup) popup.getContentView();
        View first = content.findViewWithTag("tile");
        if (first != null) first.requestFocus();
    }

    void dismiss() {
        popup.dismiss();
    }

    private boolean resolves(Intent intent) {
        try {
            return activity.getPackageManager().resolveActivity(intent, 0) != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** The clock and the timer's time left; every 15 s while open. */
    private void refresh() {
        if (clock != null) clock.setText(host.time());
        if (timerSub == null) return;
        int left = ControlCenter.minutesLeft(SleepTimer.end(activity), System.currentTimeMillis());
        if (left == 0) {
            timerSub.setText(R.string.cc_timer_off);
        } else {
            int[] hm = ControlCenter.hoursMinutes(left);
            timerSub.setText(hm[0] == 0 ? activity.getString(R.string.cc_timer_left_min, hm[1])
                    : activity.getString(R.string.cc_timer_left_hours, hm[0], hm[1]));
        }
    }

    // ---- views -----------------------------------------------------------------------------------

    /** The owner's initial in the capsule's orange-to-pink disc and name on the left; the time and date on the right. */
    private View header(String greetingName) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView avatar = new TextView(activity);
        GradientDrawable disc = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFFFF9F0A, 0xFFFF375F});
        disc.setShape(GradientDrawable.OVAL);
        avatar.setBackground(disc);
        avatar.setGravity(Gravity.CENTER);
        avatar.setTextColor(Color.WHITE);
        avatar.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f);
        avatar.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        String letter = AppleTv.initial(greetingName);
        avatar.setText(letter);
        if (letter.isEmpty()) {
            avatar.setCompoundDrawablesWithIntrinsicBounds(0, R.drawable.ic_person, 0, 0);
            avatar.setPadding(0, dp(10), 0, 0);
        }
        row.addView(avatar, new LinearLayout.LayoutParams(dp(42), dp(42)));
        TextView name = text(TextUtils.isEmpty(greetingName) ? "" : greetingName.trim(), 17f, "sans-serif", Typeface.BOLD, Color.WHITE);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        nameLp.setMarginStart(dp(10));
        row.addView(name, nameLp);

        LinearLayout when = new LinearLayout(activity);
        when.setOrientation(LinearLayout.VERTICAL);
        when.setGravity(Gravity.END);
        clock = text("", 26f, "sans-serif", Typeface.BOLD, Color.WHITE);
        clock.setLetterSpacing(-0.02f);
        when.addView(clock);
        String date = DateUtils.formatDateTime(activity, System.currentTimeMillis(),
                DateUtils.FORMAT_SHOW_WEEKDAY | DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_NO_YEAR);
        when.addView(text(date, 11f, "sans-serif-medium", Typeface.NORMAL, 0xB3FFFFFF));
        row.addView(when);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(16);
        row.setLayoutParams(lp);
        return row;
    }

    /** Tiles two to a row, 10dp apart. */
    private void addGrid(LinearLayout panel, List<View> tiles) {
        for (int i = 0; i < tiles.size(); i += 2) {
            LinearLayout line = new LinearLayout(activity);
            line.setOrientation(LinearLayout.HORIZONTAL);
            boolean alone = i + 1 >= tiles.size();
            for (int j = i; j < Math.min(tiles.size(), i + 2); j++) {
                // A tile alone on its row takes the whole width.
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(alone ? ViewGroup.LayoutParams.MATCH_PARENT
                        : dp(ControlCenter.TILE_W), dp(ControlCenter.TILE_H));
                if (j > i) lp.setMarginStart(dp(ControlCenter.GAP));
                line.addView(tiles.get(j), lp);
            }
            LinearLayout.LayoutParams lineLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (panel.getChildCount() > 1) lineLp.topMargin = dp(ControlCenter.GAP);
            panel.addView(line, lineLp);
        }
    }

    /**
     * A tile: its glyph in a disc (accent, when not 0, fills the disc: the network's blue), a title and a line under it.
     * Glass at rest; focused, opaque white with dark glyph and text, as the status capsule.
     */
    private View tile(int icon, String title, String sub, int accent, Runnable action) {
        LinearLayout tile = new LinearLayout(activity);
        tile.setTag("tile");
        tile.setOrientation(LinearLayout.HORIZONTAL);
        tile.setGravity(Gravity.CENTER_VERTICAL);
        tile.setPadding(dp(12), 0, dp(10), 0);
        tile.setFocusable(true);
        tile.setClickable(true);
        tile.setBackground(states(ControlCenter.TILE_RADIUS, 0x1FFFFFFF, 0x29FFFFFF));
        ImageView glyph = new ImageView(activity);
        int inset = dp(6);
        glyph.setPadding(inset, inset, inset, inset);
        GradientDrawable disc = new GradientDrawable();
        disc.setShape(GradientDrawable.OVAL);
        disc.setColor(accent != 0 ? accent : 0x33FFFFFF);
        glyph.setBackground(disc);
        glyph.setImageResource(icon);
        tile.addView(glyph, new LinearLayout.LayoutParams(dp(ControlCenter.TILE_ICON), dp(ControlCenter.TILE_ICON)));
        LinearLayout lines = new LinearLayout(activity);
        lines.setOrientation(LinearLayout.VERTICAL);
        TextView t = text(title, 13f, "sans-serif", Typeface.BOLD, Color.WHITE);
        TextView s = text(sub, 11f, "sans-serif-medium", Typeface.NORMAL, 0xB3FFFFFF);
        s.setTag("sub");
        lines.addView(t);
        lines.addView(s);
        LinearLayout.LayoutParams linesLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        linesLp.setMarginStart(dp(9));
        tile.addView(lines, linesLp);
        tile.setOnFocusChangeListener((v, focused) -> {
            t.setTextColor(focused ? INK_DARK : Color.WHITE);
            s.setTextColor(focused ? 0x991C1C1E : 0xB3FFFFFF);
            glyph.setImageTintList(ColorStateList.valueOf(focused && accent == 0 ? INK_DARK : Color.WHITE));
            disc.setColor(accent != 0 ? accent : focused ? 0x1A000000 : 0x33FFFFFF);
            float scale = focused ? 1.04f : 1f;
            if (animations) v.animate().scaleX(scale).scaleY(scale).setDuration(reducedMotion ? Motion.REDUCED_MS : 140).start();
        });
        if (action != null) tile.setOnClickListener(v -> action.run());
        return tile;
    }

    /** An inputs-list row (and the launcher settings row): the port in bold, the device's name after it. */
    private View row(int icon, String title, String detail, Runnable action) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), 0, dp(10), 0);
        row.setFocusable(true);
        row.setClickable(true);
        row.setBackground(states(12, 0, 0));
        ImageView glyph = new ImageView(activity);
        glyph.setImageResource(icon);
        row.addView(glyph, new LinearLayout.LayoutParams(dp(18), dp(18)));
        TextView t = text(title, 13f, "sans-serif", Typeface.BOLD, Color.WHITE);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tLp.setMarginStart(dp(10));
        row.addView(t, tLp);
        TextView d = text(detail, 12f, "sans-serif-medium", Typeface.NORMAL, 0x99FFFFFF);
        LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        dLp.setMarginStart(dp(9));
        row.addView(d, dLp);
        row.setOnFocusChangeListener((v, focused) -> {
            t.setTextColor(focused ? INK_DARK : Color.WHITE);
            d.setTextColor(focused ? 0x991C1C1E : 0x99FFFFFF);
            glyph.setImageTintList(ColorStateList.valueOf(focused ? INK_DARK : Color.WHITE));
        });
        glyph.setImageTintList(ColorStateList.valueOf(Color.WHITE));
        row.setOnClickListener(v -> action.run());
        return row;
    }

    // ---- MainActivity.InputSink: the inputs list fills the box ------------------------------------

    @Override
    public View inputRow(int icon, String title, int state, Runnable action) {
        String[] lines = ControlCenter.inputLines(title);
        String detail = lines[1];
        if (detail.isEmpty() && state == TvInputManager.INPUT_STATE_DISCONNECTED) detail = activity.getString(R.string.not_connected);
        View row = row(icon, lines[0], detail, () -> {
            dismiss();
            action.run();
        });
        if (state == TvInputManager.INPUT_STATE_CONNECTED) {
            // Something is on this port: the green dot the Inputs menu shows.
            View dot = new View(activity);
            dot.setBackgroundResource(R.drawable.status_dot);
            LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(7), dp(7));
            dotLp.setMarginEnd(dp(4));
            ((LinearLayout) row).addView(dot, dotLp);
        }
        // A port with nothing on it stays reachable, dimmed, as tvOS greys an input that is off.
        if (state == TvInputManager.INPUT_STATE_DISCONNECTED) row.setAlpha(0.55f);
        inputRows.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34)));
        return row;
    }

    @Override
    public View inputSeparator() {
        View line = new View(activity);
        line.setBackgroundColor(0x1FFFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, (int) density));
        lp.setMargins(dp(10), dp(4), dp(10), dp(4));
        inputRows.addView(line, lp);
        return line;
    }

    @Override
    public int inputCount() {
        return inputRows.getChildCount();
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private TextView text(String s, float sp, String family, int style, int color) {
        TextView t = new TextView(activity);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(Typeface.create(family, style));
        t.setTextColor(color);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setIncludeFontPadding(false);
        return t;
    }

    /** White fill and rim at rest (fill 0: none), opaque white when focused. */
    private StateListDrawable states(int radius, int fill, int rim) {
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, glass(radius, 0xF2FFFFFF, 0));
        bg.addState(new int[0], glass(radius, fill, rim));
        return bg;
    }

    private GradientDrawable glass(int radius, int fill, int rim) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(radius));
        g.setColor(fill);
        if (rim != 0) g.setStroke(Math.max(1, Math.round(density)), rim);
        return g;
    }

    private int dp(float v) {
        return Math.round(v * density);
    }
}
