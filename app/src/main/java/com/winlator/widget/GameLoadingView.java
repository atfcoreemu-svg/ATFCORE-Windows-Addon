package com.winlator.widget;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.view.Gravity;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.winlator.R;

/**
 * ATFCORE: covers the screen while a game started from ATFCORE loads (Wine, then the game's
 * own start-up), so the player sees progress instead of a bare cursor. Removed when the game's
 * window first draws; after a long wait it offers to leave.
 */
public class GameLoadingView extends FrameLayout {
    private static final long SLOW_MS = 90_000, PEEK_MS = 15_000;
    private final long started = SystemClock.uptimeMillis();
    private final TextView elapsed, hint;
    private final Button leave, peek;
    private boolean done;

    public GameLoadingView(Context context, String title, Runnable onLeave) {
        super(context);
        setBackgroundColor(0xFF100B12);
        setClickable(true);
        float d = getResources().getDisplayMetrics().density;
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = (int) (24 * d);
        column.setPadding(pad, pad, pad, pad);

        ProgressBar spinner = new ProgressBar(context);
        spinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(0xFFFFAA7D));
        column.addView(spinner, new LinearLayout.LayoutParams((int) (56 * d), (int) (56 * d)));

        TextView name = text(context, title == null || title.isEmpty() ? context.getString(R.string.atfcore_loading_game)
                : context.getString(R.string.atfcore_loading_title, title), 22, 0xFFFFF1EA);
        name.setTypeface(Typeface.create("serif", Typeface.NORMAL));
        column.addView(name, margins(16 * d));
        elapsed = text(context, "0:00", 14, 0xFFFFAA7D);
        elapsed.setTypeface(Typeface.MONOSPACE);
        column.addView(elapsed, margins(6 * d));
        hint = text(context, context.getString(R.string.atfcore_loading_hint), 14, 0xFFCFBBC8);
        column.addView(hint, margins(14 * d));

        leave = new Button(context);
        leave.setText(R.string.atfcore_loading_leave);
        leave.setAllCaps(false);
        leave.setTextColor(0xFFFFF1EA);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(0xFF201722);
        shape.setStroke((int) d, 0xFF554052);
        shape.setCornerRadius(10 * d);
        leave.setBackground(shape);
        leave.setVisibility(GONE);
        leave.setOnClickListener(v -> onLeave.run());
        column.addView(leave, margins(18 * d));

        // After a while the player can look behind this screen (a dialog may be waiting there).
        peek = new Button(context);
        peek.setText(R.string.atfcore_loading_show);
        peek.setAllCaps(false);
        peek.setTextColor(0xFF23121B);
        GradientDrawable copper = new GradientDrawable();
        copper.setColor(0xFFFFAA7D);
        copper.setCornerRadius(10 * d);
        peek.setBackground(copper);
        peek.setVisibility(GONE);
        peek.setOnClickListener(v -> finish());
        column.addView(peek, margins(18 * d));

        addView(column, new LayoutParams(-1, -2, Gravity.CENTER));
        tick();
    }

    private static TextView text(Context context, String value, int sp, int color) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER);
        return view;
    }

    private static LinearLayout.LayoutParams margins(float top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.topMargin = (int) top;
        return params;
    }

    private void tick() {
        if (done) return;
        long ms = SystemClock.uptimeMillis() - started;
        long s = ms / 1000;
        elapsed.setText(s / 60 + ":" + String.format("%02d", s % 60));
        if (ms >= PEEK_MS && peek.getVisibility() != VISIBLE) peek.setVisibility(VISIBLE);
        if (ms >= SLOW_MS && leave.getVisibility() != VISIBLE) {
            hint.setText(R.string.atfcore_loading_slow);
            leave.setVisibility(VISIBLE);
        }
        postDelayed(this::tick, 1000);
    }

    /** The game drew its window: fade out and go away. */
    public void finish() {
        if (done) return;
        done = true;
        animate().alpha(0f).setDuration(300).withEndAction(() -> {
            if (getParent() instanceof android.view.ViewGroup) ((android.view.ViewGroup) getParent()).removeView(this);
        }).start();
    }
}
