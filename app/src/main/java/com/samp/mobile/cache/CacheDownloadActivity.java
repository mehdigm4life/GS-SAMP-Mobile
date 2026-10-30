package com.samp.mobile.cache;

import android.content.DialogInterface;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.samp.mobile.R;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * The single page where the cache is installed, repaired and verified.
 *
 * Nothing is downloaded in an external browser any more: the manifest is read
 * from the project's own server, the files are written straight into
 * /storage/emulated/0/GTA, then every one of them is checked again (size and,
 * when the server publishes one, a checksum) before the launcher is allowed to
 * start the game.
 *
 * The screen is a thin view over {@link CacheManager}: it renders {@link
 * CacheManager.Stats} snapshots and forwards the three user intents - pause,
 * resume, cancel. Every event is mirrored into the shared diagnostics file so a
 * failed install can be explained from a single log.
 */
public class CacheDownloadActivity extends AppCompatActivity implements CacheManager.Listener {

    /** Cache folder, normally /storage/emulated/0/GTA. */
    public static final String EXTRA_ROOT = "extra_root";

    /** Human readable list of what was missing, shown in the log. */
    public static final String EXTRA_MISSING = "extra_missing";

    private static final String DEFAULT_ROOT = "/storage/emulated/0/GTA";

    /** Leave a moment to read "all files verified" before the game opens. */
    private static final long AUTO_START_DELAY_MS = 1500L;

    /** If the page is still idle after this long, the pass is started again. */
    private static final long WATCHDOG_DELAY_MS = 2000L;

    /** How many log lines the on-screen pane keeps. */
    private static final int LOG_LINES = 60;

    private final Handler handler = new Handler(Looper.getMainLooper());

    /** Delivers CacheManager callbacks on the UI thread. */
    private final Executor mainExecutor = new Executor() {
        @Override
        public void execute(Runnable command) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                command.run();
            } else {
                handler.post(command);
            }
        }
    };

    private CacheManager manager;
    private File root;

    private TextView tvPercent;
    private TextView tvSizeLeft;
    private TextView tvStage;
    private TextView tvFile;
    private TextView tvSpeed;
    private TextView tvEta;
    private TextView tvDownloaded;
    private TextView tvFiles;
    private TextView tvVerify;
    private TextView tvLog;

    private ProgressBar pbMain;
    private ProgressBar pbFile;

    private Button btnToggle;
    private Button btnCancel;

    private final List<String> logLines = new ArrayList<>();
    private boolean leaving;
    private int lastLoggedPercent = -1;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_cache_download);

        bindViews();

        String path = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_ROOT);
        root = new File(path == null || path.trim().isEmpty() ? DEFAULT_ROOT : path.trim());

        String missing = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_MISSING);

        // A multi-gigabyte transfer must not be interrupted by the screen
        // turning off mid-file.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        int gpuType = GpuDetector.detect();
        DiagLog.line("installer opened: root=" + root.getAbsolutePath()
                + " gpuType=" + gpuType
                + " texture=." + GpuDetector.formatSuffixFor(gpuType)
                + " renderer=\"" + GpuDetector.lastRenderer() + "\"");
        DiagLog.line("gl extensions: " + GpuDetector.lastExtensions());
        if (missing != null && !missing.trim().isEmpty()) {
            DiagLog.line("gate reported: " + missing);
        }

        manager = new CacheManager(root, mainExecutor, gpuType);
        manager.setListener(this);

        // The manifest decides what is missing; this is only a fast hint that
        // the folder is not even present.
        DiagLog.line("folder exists=" + root.isDirectory());

        if (isOnMobileData()) {
            // Inform, never block: the player already asked for the install by
            // tapping "Install files", so the transfer begins immediately and
            // the warning goes to the log instead of into a dialog.
            onLog(getString(R.string.cache_mobile_data_note));
        }

        // Start now, and keep a watchdog running so the page can never sit
        // idle waiting for a tap.
        startInstall();
        handler.postDelayed(startIfStillIdle, WATCHDOG_DELAY_MS);
    }

    /**
     * Begins the install pass, once.
     *
     * Safe to call from onCreate, onPostResume and the watchdog: the manager
     * leaves {@code IDLE} synchronously, so every later call is a no-op.
     */
    private void startInstall() {
        if (manager == null || isFinishing()) {
            return;
        }
        if (manager.getStage() != CacheManager.Stage.IDLE) {
            return;
        }
        DiagLog.line("install pass starting");
        manager.start();
    }

    private final Runnable startIfStillIdle = new Runnable() {
        @Override
        public void run() {
            if (manager != null && !isFinishing()
                    && manager.getStage() == CacheManager.Stage.IDLE) {
                DiagLog.line("watchdog: the pass had not started, starting it now");
                startInstall();
            }
        }
    };

    @Override
    protected void onPostResume() {
        super.onPostResume();
        // Covers any resume that bypassed onCreate's start (permission round
        // trip, process kept alive, screen unlocked mid-install).
        startInstall();
    }

    private void bindViews() {
        tvPercent = findViewById(R.id.tv_percent);
        tvSizeLeft = findViewById(R.id.tv_size_left);
        tvStage = findViewById(R.id.tv_stage);
        tvFile = findViewById(R.id.tv_file);
        tvSpeed = findViewById(R.id.tv_speed);
        tvEta = findViewById(R.id.tv_eta);
        tvDownloaded = findViewById(R.id.tv_downloaded);
        tvFiles = findViewById(R.id.tv_files);
        tvVerify = findViewById(R.id.tv_verify);
        tvLog = findViewById(R.id.tv_log);

        pbMain = findViewById(R.id.pb_main);
        pbFile = findViewById(R.id.pb_file);

        btnToggle = findViewById(R.id.btn_toggle);
        btnCancel = findViewById(R.id.btn_cancel);

        tvLog.setMovementMethod(new ScrollingMovementMethod());
        tvLog.setText("");

        btnToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onToggleClicked();
            }
        });
        btnCancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmCancel();
            }
        });
    }

    // ------------------------------------------------------------------
    // CacheManager.Listener  (always called on the main thread)
    // ------------------------------------------------------------------

    @Override
    public void onStats(CacheManager.Stats stats) {
        if (isFinishing()) {
            return;
        }
        applyStats(stats);
    }

    @Override
    public void onLog(String line) {
        DiagLog.line(line);
        logLines.add(line);
        while (logLines.size() > LOG_LINES) {
            logLines.remove(0);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < logLines.size(); i++) {
            sb.append(logLines.get(i)).append('\n');
        }
        tvLog.setText(sb.toString());
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    private void applyStats(CacheManager.Stats s) {
        int percent = Math.max(0, Math.min(100, s.percent));
        tvPercent.setText(getString(R.string.cache_percent_format, percent));
        pbMain.setProgress(percent);
        pbFile.setProgress(Math.max(0, Math.min(100, s.filePercent)));

        if (s.totalBytes > 0) {
            tvSizeLeft.setText(CacheManager.Fmt.bytes(Math.max(0, s.totalBytes - s.doneBytes)));
        } else {
            tvSizeLeft.setText(R.string.cache_value_unknown);
        }

        tvDownloaded.setText(CacheManager.Fmt.bytes(s.doneBytes));
        tvSpeed.setText(CacheManager.Fmt.speed(s.bytesPerSecond));
        tvEta.setText(CacheManager.Fmt.duration(s.etaSeconds));

        if (s.filesTotal > 0) {
            tvFiles.setText(s.filesDone + " / " + s.filesTotal);
        } else {
            tvFiles.setText(R.string.cache_value_unknown);
        }

        if (s.currentFile != null && !s.currentFile.isEmpty()) {
            tvFile.setText(s.currentFile);
        }

        tvStage.setText(stageText(s));

        applyVerifyChip(s);
        applyButtons(s);

        if (s.stage == CacheManager.Stage.COMPLETE && !leaving) {
            leaving = true;
            DiagLog.line("cache verified; handing back to the launcher");
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    finishReady();
                }
            }, AUTO_START_DELAY_MS);
        }

        if (s.percent != lastLoggedPercent && s.percent % 10 == 0 && s.percent > 0) {
            lastLoggedPercent = s.percent;
            DiagLog.line("progress " + s.percent + "% ("
                    + CacheManager.Fmt.bytes(s.doneBytes) + " / "
                    + CacheManager.Fmt.bytes(s.totalBytes) + ")");
        }
    }

    private String stageText(CacheManager.Stats s) {
        switch (s.stage) {
            case CONNECTING:
                return getString(R.string.cache_stage_connecting);
            case VERIFYING:
                return getString(R.string.cache_stage_verifying);
            case DOWNLOADING:
                return getString(R.string.cache_stage_downloading);
            case PAUSED:
                return getString(R.string.cache_stage_paused);
            case COMPLETE:
                return getString(R.string.cache_stage_complete);
            case CANCELLED:
                return getString(R.string.cache_stage_cancelled);
            case FAILED:
                return s.message == null || s.message.isEmpty()
                        ? getString(R.string.cache_stage_failed)
                        : getString(R.string.cache_stage_failed) + " · " + s.message;
            default:
                return getString(R.string.cache_stage_idle);
        }
    }

    private void applyVerifyChip(CacheManager.Stats s) {
        int background;
        int textColor;
        String text;

        switch (s.stage) {
            case COMPLETE:
                background = R.drawable.cache_chip_ok;
                textColor = R.color.cache_ok;
                text = getString(R.string.cache_verify_ok);
                break;
            case VERIFYING:
            case CONNECTING:
                background = R.drawable.cache_chip_warn;
                textColor = R.color.cache_warn;
                text = getString(R.string.cache_verify_running);
                break;
            case FAILED:
                background = R.drawable.cache_chip_error;
                textColor = R.color.cache_error;
                text = getString(R.string.cache_verify_problem);
                break;
            case DOWNLOADING:
            case PAUSED:
                background = R.drawable.cache_chip_warn;
                textColor = R.color.cache_warn;
                text = getString(R.string.cache_verify_problem);
                break;
            default:
                background = R.drawable.cache_chip_warn;
                textColor = R.color.cache_warn;
                text = getString(R.string.cache_verify_pending);
                break;
        }

        tvVerify.setBackgroundResource(background);
        tvVerify.setTextColor(ContextCompat.getColor(this, textColor));
        tvVerify.setText(text);
    }

    private void applyButtons(CacheManager.Stats s) {
        switch (s.stage) {
            case COMPLETE:
                setToggle(R.string.cache_btn_play, R.drawable.cache_ic_play, true);
                btnCancel.setVisibility(View.GONE);
                break;

            case DOWNLOADING:
                setToggle(R.string.cache_btn_pause, R.drawable.cache_ic_pause, true);
                btnCancel.setVisibility(View.VISIBLE);
                break;

            case PAUSED:
                setToggle(R.string.cache_btn_resume, R.drawable.cache_ic_play, true);
                btnCancel.setVisibility(View.VISIBLE);
                break;

            case CONNECTING:
            case VERIFYING:
                // Short label: it has to fit next to the cancel button.
                setToggle(R.string.cache_btn_working, R.drawable.cache_ic_download, false);
                btnCancel.setVisibility(View.VISIBLE);
                break;

            case FAILED:
            case CANCELLED:
                setToggle(R.string.cache_btn_retry, R.drawable.cache_ic_refresh, true);
                btnCancel.setVisibility(View.GONE);
                break;

            default:
                setToggle(R.string.cache_btn_start, R.drawable.cache_ic_download, true);
                btnCancel.setVisibility(View.GONE);
                break;
        }
    }

    private void setToggle(int textRes, int iconRes, boolean enabled) {
        btnToggle.setText(textRes);
        btnToggle.setCompoundDrawablesRelativeWithIntrinsicBounds(iconRes, 0, 0, 0);
        btnToggle.setEnabled(enabled);
        btnToggle.setAlpha(enabled ? 1f : 0.55f);
    }


    // ------------------------------------------------------------------
    // Interactions
    // ------------------------------------------------------------------

    private void onToggleClicked() {
        CacheManager.Stage stage = manager.getStage();

        if (stage == CacheManager.Stage.COMPLETE) {
            finishReady();
            return;
        }
        if (stage == CacheManager.Stage.DOWNLOADING) {
            manager.pause();
            DiagLog.line("user paused the download");
            return;
        }
        if (stage == CacheManager.Stage.PAUSED) {
            manager.resume();
            DiagLog.line("user resumed the download");
            return;
        }
        if (stage == CacheManager.Stage.FAILED || stage == CacheManager.Stage.CANCELLED) {
            DiagLog.line("user retried");
            manager.retry();
            return;
        }
        if (stage == CacheManager.Stage.IDLE) {
            manager.start();
        }
    }

    private void confirmCancel() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.cache_exit_title)
                .setMessage(R.string.cache_exit_message)
                .setCancelable(true)
                .setPositiveButton(R.string.cache_exit_leave, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        DiagLog.line("user cancelled the install");
                        manager.cancel();
                        setResult(RESULT_CANCELED);
                        finish();
                    }
                })
                .setNegativeButton(R.string.cache_exit_stay, null)
                .show();
    }

    private void finishReady() {
        setResult(RESULT_OK);
        finish();
    }

    private boolean isOnMobileData() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm == null) {
                return false;
            }
            NetworkInfo info = cm.getActiveNetworkInfo();
            return info != null && info.isConnected()
                    && info.getType() == ConnectivityManager.TYPE_MOBILE;
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    @Override
    public void onBackPressed() {
        if (manager != null && manager.isBusy()) {
            confirmCancel();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        if (manager != null) {
            // Pause rather than cancel: the .part files stay on disk so the
            // next launch continues exactly where this one stopped.
            manager.pause();
            manager.setListener(null);
        }
        DiagLog.line("installer closed (finishing=" + isFinishing() + ")");
    }
}
