package com.feurstagram.extension;

import android.app.Dialog;
import android.app.DownloadManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

/**
 * Checks GitHub for a newer Feurstagram release once per process and, if found,
 * offers to download and install it in place — no browser required. The APK is
 * fetched from the matching release asset (standard vs. clone build) and handed
 * to the system {@link PackageInstaller}, which shows the usual install
 * confirmation. If anything goes wrong, the release page is opened as a fallback.
 */
public final class UpdateChecker implements Runnable {

    private static final String REPO = "jean-voila/Feurstagram";
    private static final String LATEST_API = "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final String LATEST_PAGE = "https://github.com/" + REPO + "/releases/latest";

    /** Action prefix for the per-session PackageInstaller status callback. */
    private static final String INSTALL_ACTION = "com.feurstagram.extension.INSTALL_STATUS";

    private static boolean checked;

    private final Context context;
    /** Manual checks always run and report the outcome (up-to-date / failure). */
    private final boolean manual;

    private UpdateChecker(Context context, boolean manual) {
        this.context = context;
        this.manual = manual;
    }

    /** Kick off a one-shot background check if enabled. Runs at most once per process. */
    public static void check(Context context) {
        if (context == null || checked) return;
        checked = true;
        if (!Config.isAutoUpdateEnabled()) return;
        new Thread(new UpdateChecker(context, false)).start();
    }

    /**
     * Run an on-demand check (from the settings menu), regardless of the
     * auto-update toggle or whether a check already ran this process. Always
     * tells the user the result.
     */
    public static void checkNow(Context context) {
        if (context == null) return;
        Toast.makeText(context, "Checking for updates…", Toast.LENGTH_SHORT).show();
        new Thread(new UpdateChecker(context, true)).start();
    }

    @Override
    public void run() {
        Handler ui = new Handler(Looper.getMainLooper());
        try {
            java.net.HttpURLConnection connection =
                    (java.net.HttpURLConnection) new java.net.URL(LATEST_API).openConnection();
            connection.setRequestProperty("User-Agent", "Feurstagram-UpdateCheck");
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);

            String body;
            try (InputStream input = connection.getInputStream();
                 Scanner scanner = new Scanner(input).useDelimiter("\\A")) {
                body = scanner.hasNext() ? scanner.next() : "";
            } finally {
                connection.disconnect();
            }

            String tag = extractTagName(body);
            if (tag == null) {
                if (manual) ui.post(() -> toast("Update check failed"));
                return;
            }

            if (isNewer(normalize(tag), installedVersion())) {
                String apkUrl = extractApkUrl(body, isCloneBuild());
                ui.post(() -> showPrompt(context, tag, apkUrl));
            } else if (manual) {
                ui.post(() -> toast("FeurStagram is up to date (" + installedVersion() + ")"));
            }
        } catch (Throwable t) {
            // Network/parse failures are non-fatal: silent for auto checks,
            // reported for manual ones.
            if (manual) ui.post(() -> toast("Update check failed"));
        }
    }

    private void toast(String message) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show();
    }

    /** True when this is the side-by-side clone package (asset name ends in -clone.apk). */
    private boolean isCloneBuild() {
        String pkg = context.getPackageName();
        return pkg != null && pkg.endsWith(".feurstagram");
    }

    /** Pull the first "tag_name":"..." value out of the release JSON. */
    private static String extractTagName(String json) {
        if (json == null) return null;
        int key = json.indexOf("\"tag_name\"");
        if (key < 0) return null;
        int colon = json.indexOf(':', key);
        if (colon < 0) return null;
        int open = json.indexOf('"', colon + 1);
        if (open < 0) return null;
        int close = json.indexOf('"', open + 1);
        if (close < 0) return null;
        return json.substring(open + 1, close);
    }

    /**
     * Pick the download URL of the .apk asset matching this build: the
     * "-clone.apk" asset for a clone install, the plain ".apk" asset otherwise.
     * Returns null when no suitable asset is present.
     */
    static String extractApkUrl(String json, boolean clone) {
        if (json == null) return null;
        List<String> urls = new ArrayList<>();
        int idx = 0;
        while ((idx = json.indexOf("\"browser_download_url\"", idx)) >= 0) {
            int colon = json.indexOf(':', idx);
            if (colon < 0) break;
            int open = json.indexOf('"', colon + 1);
            if (open < 0) break;
            int close = json.indexOf('"', open + 1);
            if (close < 0) break;
            urls.add(json.substring(open + 1, close));
            idx = close + 1;
        }
        for (String url : urls) {
            if (!url.endsWith(".apk")) continue;
            boolean cloneAsset = url.endsWith("-clone.apk");
            if (cloneAsset == clone) return url;
        }
        return null;
    }

    /** "v434-0-0-44-74" -> "434.0.0.44.74" so it matches the installed versionName. */
    static String normalize(String tag) {
        if (tag == null) return "";
        String value = tag.trim();
        if (value.startsWith("v") || value.startsWith("V")) {
            value = value.substring(1);
        }
        return value.replace('-', '.');
    }

    /** Installed versionName (Instagram's), e.g. "435.0.0.37.76". "0" on failure. */
    private String installedVersion() {
        try {
            PackageManager pm = context.getPackageManager();
            String name = pm.getPackageInfo(context.getPackageName(), 0).versionName;
            return name == null ? "0" : name;
        } catch (Throwable t) {
            return "0";
        }
    }

    /** True if dotted version `latest` is strictly greater than `installed`. */
    static boolean isNewer(String latest, String installed) {
        String[] a = latest.split("\\.");
        String[] b = installed.split("\\.");
        int len = Math.max(a.length, b.length);
        for (int i = 0; i < len; i++) {
            int x = i < a.length ? parse(a[i]) : 0;
            int y = i < b.length ? parse(b[i]) : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    private static int parse(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Throwable t) {
            return 0;
        }
    }

    private static void showPrompt(Context context, String tag, String apkUrl) {
        try {
            Dialog dialog = new Dialog(context);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            dialog.setContentView(buildContent(context, dialog, tag, apkUrl));
            Window window = dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawable(new ColorDrawable(0));
                window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                window.setDimAmount(0.6f);
            }
            dialog.show();
        } catch (Throwable ignored) {
        }
    }

    private static View buildContent(Context context, Dialog dialog, String tag, String apkUrl) {
        FrameLayout frame = new FrameLayout(context);
        int pad = Settings.dp(context, 24);
        frame.setPadding(pad, pad, pad, pad);

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Settings.roundedRect(Settings.SURFACE, 28, context));
        card.setPadding(pad, pad, pad, pad);
        frame.addView(card, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(context);
        title.setText("Update available");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f);
        title.setTextColor(Settings.ON_SURFACE);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        card.addView(title);

        boolean canInstall = apkUrl != null;
        TextView body = new TextView(context);
        body.setText(canInstall
                ? "Feurstagram " + tag + " is available. Download and install it now."
                : "Feurstagram " + tag + " is available. Open the release page to download it.");
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        body.setTextColor(Settings.ON_SURFACE_VARIANT);
        body.setPadding(0, Settings.dp(context, 12), 0, 0);
        card.addView(body);

        LinearLayout buttons = new LinearLayout(context);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END);
        LinearLayout.LayoutParams buttonsLp =
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        buttonsLp.setMargins(0, Settings.dp(context, 24), 0, 0);
        card.addView(buttons, buttonsLp);

        Button later = Settings.makeButton(context, "Later", 0, Settings.ON_SURFACE_VARIANT, false);
        later.setOnClickListener(v -> dialog.dismiss());
        buttons.addView(later);

        View spacer = new View(context);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(Settings.dp(context, 8), 1));
        buttons.addView(spacer);

        Button download = Settings.makeButton(context,
                canInstall ? "Update" : "Download", Settings.PRIMARY, Settings.ON_PRIMARY, true);
        download.setOnClickListener(v -> {
            if (!canInstall) {
                dialog.dismiss();
                openReleasePage(context);
                return;
            }
            // The system installer needs "install unknown apps" for this app.
            // Send the user to grant it and let them tap Update again, keeping
            // the dialog up so the flow can resume.
            if (!canRequestInstall(context)) {
                requestInstallPermission(context);
                Toast.makeText(context,
                        "Allow installing apps, then tap Update again.",
                        Toast.LENGTH_LONG).show();
                return;
            }
            dialog.dismiss();
            startDownload(context, apkUrl, tag);
        });
        buttons.addView(download);

        return frame;
    }

    // --- Download + install -------------------------------------------------

    /** Where the background download lands (app-specific external dir, no permission needed). */
    private static final String UPDATE_FILE_NAME = "feurstagram-update.apk";

    /**
     * Download the update in the background through the system {@link DownloadManager}.
     * It shows its own progress notification, survives the app being backgrounded,
     * and follows GitHub's redirect to the asset CDN. When it finishes we hand the
     * APK to the {@link PackageInstaller}; if the app was killed meanwhile, tapping
     * the completed-download notification still opens the installer (apk mime type).
     */
    private static void startDownload(Context context, String url, String tag) {
        Context app = context.getApplicationContext();
        try {
            DownloadManager dm = (DownloadManager) app.getSystemService(Context.DOWNLOAD_SERVICE);
            File dir = app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (dm == null || dir == null) {
                throw new IllegalStateException("DownloadManager unavailable");
            }
            File dest = new File(dir, UPDATE_FILE_NAME);
            if (dest.exists() && !dest.delete()) {
                // Stale file we can't remove: fall back rather than install something old.
                throw new IllegalStateException("Could not clear previous download");
            }

            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            request.setTitle("FeurStagram " + tag);
            request.setDescription("Downloading update…");
            request.setMimeType("application/vnd.android.package-archive");
            request.addRequestHeader("User-Agent", "Feurstagram-UpdateCheck");
            request.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalFilesDir(app, Environment.DIRECTORY_DOWNLOADS, UPDATE_FILE_NAME);

            long downloadId = dm.enqueue(request);
            registerDownloadReceiver(app, dm, downloadId, dest);
            Toast.makeText(context,
                    "Downloading update in the background — you'll be prompted to install when it's ready.",
                    Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            Toast.makeText(context, "Couldn't start the download. Opening release page…", Toast.LENGTH_LONG).show();
            openReleasePage(context);
        }
    }

    /**
     * Wait for the background download to finish, then launch the install. The
     * receiver is registered on the application context so it outlives the settings
     * dialog; it unregisters itself on completion.
     */
    private static void registerDownloadReceiver(Context app, DownloadManager dm, long downloadId, File dest) {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                long finished = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (finished != downloadId) return; // a different download completed
                try {
                    c.unregisterReceiver(this);
                } catch (Throwable ignored) {
                }
                int status = queryStatus(dm, downloadId);
                if (status == DownloadManager.STATUS_SUCCESSFUL && dest.exists()) {
                    installApk(c.getApplicationContext(), dest);
                } else {
                    Toast.makeText(c, "Update download failed. Opening release page…", Toast.LENGTH_LONG).show();
                    openReleasePage(c);
                }
            }
        };
        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        // The completion broadcast comes from the system, so the receiver must be exported.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            app.registerReceiver(receiver, filter);
        }
    }

    /** Read the DownloadManager status column for a finished download, or -1. */
    private static int queryStatus(DownloadManager dm, long downloadId) {
        try (Cursor cursor = dm.query(new DownloadManager.Query().setFilterById(downloadId))) {
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    private static void installApk(Context context, File apk) {
        Context app = context.getApplicationContext();
        PackageInstaller installer = app.getPackageManager().getPackageInstaller();
        PackageInstaller.Session session = null;
        boolean committed = false;
        try {
            PackageInstaller.SessionParams params =
                    new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            int sessionId = installer.createSession(params);
            session = installer.openSession(sessionId);

            try (InputStream in = new FileInputStream(apk);
                 OutputStream out = session.openWrite("feurstagram", 0, apk.length())) {
                byte[] buffer = new byte[65536];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    out.write(buffer, 0, n);
                }
                session.fsync(out);
            }

            String action = INSTALL_ACTION + "." + sessionId;
            registerInstallReceiver(app, action, apk);

            Intent intent = new Intent(action).setPackage(app.getPackageName());
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                flags |= PendingIntent.FLAG_MUTABLE;
            }
            PendingIntent pending = PendingIntent.getBroadcast(app, sessionId, intent, flags);
            session.commit(pending.getIntentSender());
            committed = true;
        } catch (Throwable t) {
            if (session != null) {
                try {
                    session.abandon();
                } catch (Throwable ignored) {
                }
            }
            apk.delete();
            Toast.makeText(context, "Install failed. Opening release page…", Toast.LENGTH_LONG).show();
            openReleasePage(context);
        } finally {
            if (session != null && committed) {
                session.close();
            }
        }
    }

    private static void registerInstallReceiver(Context app, String action, File apk) {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                int statusCode = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Integer.MIN_VALUE);
                if (statusCode == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    // The system needs the user to confirm the install: launch its UI.
                    Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                    if (confirm != null) {
                        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        try {
                            c.startActivity(confirm);
                        } catch (Throwable ignored) {
                        }
                    }
                    return;
                }
                // Terminal status: clean up.
                try {
                    c.unregisterReceiver(this);
                } catch (Throwable ignored) {
                }
                apk.delete();
                if (statusCode != PackageInstaller.STATUS_SUCCESS) {
                    String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                    Toast.makeText(c,
                            message == null ? "Install cancelled" : "Install failed: " + message,
                            Toast.LENGTH_LONG).show();
                }
            }
        };
        IntentFilter filter = new IntentFilter(action);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            app.registerReceiver(receiver, filter);
        }
    }

    /** Whether this app may install packages (API 26+ gates on a per-app toggle). */
    private static boolean canRequestInstall(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true;
        try {
            return context.getPackageManager().canRequestPackageInstalls();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Open the per-app "install unknown apps" settings screen. */
    private static void requestInstallPermission(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        try {
            Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + context.getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Throwable t) {
            try {
                Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void openReleasePage(Context context) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(LATEST_PAGE));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Throwable t) {
            Toast.makeText(context, "No browser available", Toast.LENGTH_LONG).show();
        }
    }
}
