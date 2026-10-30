package org.comicreader.app;

import android.app.*;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;

final class Ui {
    static final int ACCENT = 0xff00b0f0;

    static int accent(Activity activity) {
        return switch (activity.getSharedPreferences("appearance", 0).getString("accent", "blue")) {
            case "orange" -> 0xffff9000;
            case "teal" -> 0xff16b8a6;
            default -> ACCENT;
        };
    }

    static int accentText(Activity activity) {
        int color = accent(activity);
        return dark(activity)
                ? color
                : Color.rgb(
                        Color.red(color) * 2 / 3,
                        Color.green(color) * 2 / 3,
                        Color.blue(color) * 2 / 3);
    }

    static String theme(Activity activity) {
        return activity.getSharedPreferences("appearance", 0).getString("theme", "oled");
    }

    static boolean dark(Activity activity) {
        return !theme(activity).equals("light");
    }

    static int background(Activity activity) {
        return !dark(activity)
                ? Color.WHITE
                : theme(activity).equals("oled") ? Color.BLACK : 0xff121212;
    }

    static int foreground(Activity activity) {
        return dark(activity) ? 0xffeeeeee : 0xff111111;
    }

    static int secondary(Activity activity) {
        return dark(activity) ? 0xffbbbbbb : 0xff646464;
    }

    static int surface(Activity activity) {
        return dark(activity)
                ? theme(activity).equals("oled") ? 0xff0d1012 : 0xff202428
                : 0xfff0f3f6;
    }

    static int header(Activity activity) {
        return background(activity);
    }

    static void applyTheme(Activity activity) {
        activity.setTheme(
                switch (theme(activity)) {
                    case "dark" -> R.style.AppThemeDark;
                    case "oled" -> R.style.AppThemeOled;
                    default -> R.style.AppTheme;
                });
        activity.getTheme()
                .applyStyle(
                        switch (activity.getSharedPreferences("appearance", 0)
                                .getString("accent", "blue")) {
                            case "orange" -> R.style.AccentOrange;
                            case "teal" -> R.style.AccentTeal;
                            default -> R.style.AccentBlue;
                        },
                        true);
    }

    static void bars(Activity activity) {
        activity.getWindow().setStatusBarColor(background(activity));
        activity.getWindow().setNavigationBarColor(background(activity));
        activity.getWindow()
                .getDecorView()
                .setSystemUiVisibility(
                        dark(activity)
                                ? 0
                                : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                                        | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    }

    static int dp(Activity activity, int value) {
        return (int) (value * activity.getResources().getDisplayMetrics().density + 0.5f);
    }

    static LinearLayout column(Activity activity) {
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(background(activity));
        int pad = dp(activity, 12);
        layout.setPadding(pad, pad, pad, pad);
        return layout;
    }

    static TextView text(Activity activity, String value, int size) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(foreground(activity));
        int pad = dp(activity, 8);
        view.setPadding(pad, pad, pad, pad);
        return view;
    }

    static Button button(Activity activity, String value, Runnable action) {
        Button button = new Button(activity);
        button.setText(value);
        button.setAllCaps(false);
        button.setMinHeight(dp(activity, 48));
        button.setOnClickListener(view -> action.run());
        return button;
    }

    static EditText input(Activity activity, String hint) {
        EditText input = new EditText(activity);
        input.setHint(hint);
        input.setSingleLine(true);
        input.setTextSize(16);
        input.setTextColor(foreground(activity));
        input.setHintTextColor(secondary(activity));
        return input;
    }

    static Button icon(Activity activity, int drawable, String description, Runnable action) {
        Button button = button(activity, "", action);
        button.setContentDescription(description);
        button.setTooltipText(description);
        button.setPadding(dp(activity, 12), dp(activity, 12), dp(activity, 12), dp(activity, 12));
        android.graphics.drawable.Drawable symbol = activity.getDrawable(drawable).mutate();
        symbol.setTint(foreground(activity));
        symbol.setBounds(0, 0, dp(activity, 24), dp(activity, 24));
        button.setCompoundDrawables(symbol, null, null, null);
        button.setBackgroundResource(android.R.drawable.list_selector_background);
        button.setMinimumWidth(dp(activity, 48));
        button.setMinWidth(0);
        return button;
    }

    static GradientDrawable background(int color, int radius) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(radius);
        return background;
    }

    static android.graphics.drawable.Drawable selectable(Activity activity, int color, int radius) {
        return new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(
                        (accent(activity) & 0x00ffffff) | 0x33000000),
                background(color, dp(activity, radius)),
                background(Color.WHITE, dp(activity, radius)));
    }

    static LinearLayout destination(
            Activity activity,
            int icon,
            String label,
            boolean selected,
            boolean black,
            Runnable action) {
        LinearLayout item = new LinearLayout(activity);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.setMinimumHeight(dp(activity, 64));
        item.setMinimumWidth(dp(activity, 48));
        int color =
                selected
                        ? black ? accent(activity) : accentText(activity)
                        : black ? 0xffdddddd : secondary(activity);
        ImageView symbol = new ImageView(activity);
        symbol.setImageResource(icon);
        symbol.setColorFilter(color);
        symbol.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        item.addView(symbol, new LinearLayout.LayoutParams(dp(activity, 24), dp(activity, 24)));
        TextView title = text(activity, label, 12);
        title.setPadding(0, dp(activity, 4), 0, 0);
        title.setTextColor(color);
        title.setMaxLines(1);
        title.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        item.addView(title);
        item.setContentDescription(label);
        item.setSelected(selected);
        item.setFocusable(true);
        item.setClickable(true);
        item.setBackground(selectable(activity, Color.TRANSPARENT, 12));
        item.setOnClickListener(view -> action.run());
        return item;
    }

    static void insets(View root) {
        int left = root.getPaddingLeft(),
                top = root.getPaddingTop(),
                right = root.getPaddingRight(),
                bottom = root.getPaddingBottom();
        root.setOnApplyWindowInsetsListener(
                (view, insets) -> {
                    view.setPadding(
                            left + insets.getSystemWindowInsetLeft(),
                            top + insets.getSystemWindowInsetTop(),
                            right + insets.getSystemWindowInsetRight(),
                            bottom + insets.getSystemWindowInsetBottom());
                    return insets;
                });
    }

    static String message(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    static void error(Activity activity, Throwable error) {
        int translated =
                switch (message(error)) {
                    case "Cannot save the comic",
                            "Cannot create the library",
                            "Cannot create the library; check free device storage" ->
                            R.string.error_storage;
                    case "The MEGA transfer quota is exhausted" -> R.string.error_mega_quota;
                    case "The MEGA session expired; sign out and sign in again" ->
                            R.string.error_mega_session;
                    case "MEGA denied access; check your credentials or permissions" ->
                            R.string.error_mega_access;
                    case "MEGA took too long to finish the previous operation; try again" ->
                            R.string.error_mega_busy_timeout;
                    default -> 0;
                };
        if (!activity.isFinishing() && !activity.isDestroyed())
            new AlertDialog.Builder(activity)
                    .setTitle(activity.getString(R.string.error_title))
                    .setMessage(translated == 0 ? message(error) : activity.getString(translated))
                    .setPositiveButton(activity.getString(R.string.action_ok), null)
                    .show();
    }

    static void toast(Activity activity, String value) {
        Toast.makeText(activity, value, Toast.LENGTH_SHORT).show();
    }
}
