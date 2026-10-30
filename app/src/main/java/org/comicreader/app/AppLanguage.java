package org.comicreader.app;

import android.app.Activity;
import android.app.LocaleManager;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;
import android.os.LocaleList;

import java.util.Locale;

final class AppLanguage {
    static Context attach(Context base) {
        if (Build.VERSION.SDK_INT >= 33) return base;
        String tag = base.getSharedPreferences("appearance", 0).getString("language", "");
        if (tag.isEmpty()) return base;
        Configuration configuration = new Configuration(base.getResources().getConfiguration());
        configuration.setLocale(Locale.forLanguageTag(tag));
        return base.createConfigurationContext(configuration);
    }

    static String selected(Context context) {
        return Build.VERSION.SDK_INT >= 33
                ? context.getSystemService(LocaleManager.class)
                        .getApplicationLocales()
                        .toLanguageTags()
                : context.getSharedPreferences("appearance", 0).getString("language", "");
    }

    static void select(Activity activity, String tag) {
        if (!tag.isEmpty() && !tag.equals("en") && !tag.equals("es"))
            throw new IllegalArgumentException("Unsupported app language");
        if (Build.VERSION.SDK_INT >= 33) {
            activity.getSystemService(LocaleManager.class)
                    .setApplicationLocales(LocaleList.forLanguageTags(tag));
        } else {
            activity.getSharedPreferences("appearance", 0)
                    .edit()
                    .putString("language", tag)
                    .apply();
            activity.recreate();
        }
    }
}
