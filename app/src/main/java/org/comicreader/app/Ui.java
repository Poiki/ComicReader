package org.comicreader.app;

import android.app.*;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;

final class Ui {
    static final int ACCENT = Color.rgb(255, 144, 0);
    static String theme(Activity activity) { return activity.getSharedPreferences("appearance", 0).getString("theme", "light"); }
    static boolean dark(Activity activity) { return !theme(activity).equals("light"); }
    static int background(Activity activity) { return !dark(activity) ? Color.WHITE : theme(activity).equals("oled") ? Color.BLACK : 0xff121212; }
    static int foreground(Activity activity) { return dark(activity) ? 0xffeeeeee : 0xff111111; }
    static int secondary(Activity activity) { return dark(activity) ? 0xffbbbbbb : 0xff646464; }
    static int surface(Activity activity) { return dark(activity) ? theme(activity).equals("oled") ? Color.BLACK : 0xff242424 : 0xffeeeeee; }
    static int header(Activity activity) { return dark(activity) ? surface(activity) : 0xff424242; }
    static int controls(Activity activity) { return dark(activity) ? surface(activity) : 0xfffffbef; }
    static void applyTheme(Activity activity) {
        activity.setTheme(switch (theme(activity)) { case "dark" -> R.style.AppThemeDark; case "oled" -> R.style.AppThemeOled; default -> R.style.AppTheme; });
    }
    static void bars(Activity activity) {
        activity.getWindow().setStatusBarColor(background(activity)); activity.getWindow().setNavigationBarColor(background(activity));
        activity.getWindow().getDecorView().setSystemUiVisibility(dark(activity) ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    }
    static int dp(Activity activity, int value) { return (int) (value * activity.getResources().getDisplayMetrics().density + 0.5f); }
    static LinearLayout column(Activity activity) {
        LinearLayout layout = new LinearLayout(activity); layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(background(activity));
        int pad = dp(activity, 12); layout.setPadding(pad, pad, pad, pad);
        return layout;
    }
    static TextView text(Activity activity, String value, int size) {
        TextView view = new TextView(activity); view.setText(value); view.setTextSize(size); view.setTextColor(foreground(activity));
        int pad = dp(activity, 8); view.setPadding(pad, pad, pad, pad); return view;
    }
    static Button button(Activity activity, String value, Runnable action) {
        Button button = new Button(activity); button.setText(value); button.setAllCaps(false);
        button.setMinHeight(dp(activity, 48)); button.setOnClickListener(view -> action.run()); return button;
    }
    static EditText input(Activity activity, String hint) {
        EditText input = new EditText(activity); input.setHint(hint); input.setSingleLine(true); input.setTextSize(16);
        input.setTextColor(foreground(activity)); input.setHintTextColor(secondary(activity)); return input;
    }
    static Button icon(Activity activity, int drawable, String description, Runnable action) {
        Button button = button(activity, "", action); button.setContentDescription(description); button.setTooltipText(description);
        button.setPadding(dp(activity, 12), dp(activity, 12), dp(activity, 12), dp(activity, 12));
        android.graphics.drawable.Drawable symbol = activity.getDrawable(drawable).mutate(); symbol.setTint(foreground(activity));
        symbol.setBounds(0, 0, dp(activity, 24), dp(activity, 24)); button.setCompoundDrawables(symbol, null, null, null);
        button.setBackgroundResource(android.R.drawable.list_selector_background); button.setMinimumWidth(dp(activity, 48));
        button.setMinWidth(0); return button;
    }
    static GradientDrawable background(int color, int radius) {
        GradientDrawable background = new GradientDrawable(); background.setColor(color); background.setCornerRadius(radius); return background;
    }
    static void insets(View root) {
        int left = root.getPaddingLeft(), top = root.getPaddingTop(), right = root.getPaddingRight(), bottom = root.getPaddingBottom();
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(left + insets.getSystemWindowInsetLeft(), top + insets.getSystemWindowInsetTop(),
                    right + insets.getSystemWindowInsetRight(), bottom + insets.getSystemWindowInsetBottom());
            return insets;
        });
    }
    static String message(Throwable error) {
        String message = error.getMessage(); return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
    static void error(Activity activity, Throwable error) {
        if (!activity.isFinishing() && !activity.isDestroyed())
            new AlertDialog.Builder(activity).setTitle("No se ha completado la operación")
                    .setMessage(message(error)).setPositiveButton("Aceptar", null).show();
    }
    static void toast(Activity activity, String value) { Toast.makeText(activity, value, Toast.LENGTH_SHORT).show(); }
}
