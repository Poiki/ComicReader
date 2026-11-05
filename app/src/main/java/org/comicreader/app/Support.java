package org.comicreader.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;

final class Support {
    private Support() {}

    static void show(Activity activity) {
        new AlertDialog.Builder(activity)
                .setTitle(R.string.support_title)
                .setMessage(R.string.support_message)
                .setNegativeButton(R.string.action_close, null)
                .setPositiveButton(
                        R.string.support_open,
                        (dialog, which) -> {
                            try {
                                activity.startActivity(
                                        new Intent(
                                                Intent.ACTION_VIEW,
                                                Uri.parse("https://ko-fi.com/A0A71AC06Z")));
                            } catch (ActivityNotFoundException error) {
                                Ui.toast(activity, activity.getString(R.string.support_no_browser));
                            }
                        })
                .show();
    }
}
