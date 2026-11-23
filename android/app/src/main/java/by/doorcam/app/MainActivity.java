package by.doorcam.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.hardware.camera2.*;
import android.os.*;
import android.text.InputType;
import android.util.Log;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import java.net.URI;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class MainActivity extends Activity {
    private static final int HOME = 0, SETTINGS = 1;
    private static final int CAMERA_PREVIEW_PERMISSION = 3;
    private final ScrollView[] pages = new ScrollView[2];
    private final Button[] tabs = new Button[pages.length];
    private int selectedTab = HOME;
    private SharedPreferences prefs;
    private TextView state, lastMotion, resources, sensitivityLabel, lanAddress;
    private EditText origin, key, limit;
    private Spinner cameras;
    private SeekBar sensitivity;
    private Button start, stop;
    private ToggleButton recordingSection, remoteSection;
    private CheckBox calibration;
    private TextView calibrationStatus;
    private ZonePreview zonePreview;
    private AlertDialog cameraPreviewDialog;
    private MotionZone motionZone;
    private byte[] displayedPreview;
    private final List<String> ids = new ArrayList<>();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private ScheduledExecutorService resourceSampler;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            boolean active = MonitorService.running;
            String text = MonitorService.status;
            if (!text.contentEquals(state.getText())) state.setText(text);
            long event = MonitorService.lastEvent;
            String motion = event == 0 ? "Движение ещё не зафиксировано" : "Последнее движение · " +
                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(new Date(event));
            if (!motion.contentEquals(lastMotion.getText())) lastMotion.setText(motion);
            String url = MonitorService.lanUrl;
            String address = !active ? "Адрес появится после запуска наблюдения"
                    : url.isEmpty() ? "Ожидание подключения к Wi-Fi" : url;
            if (!address.contentEquals(lanAddress.getText())) lanAddress.setText(address);
            start.setVisibility(active ? View.GONE : View.VISIBLE);
            stop.setVisibility(active ? View.VISIBLE : View.GONE);
            cameras.setEnabled(!active);
            sensitivity.setEnabled(!active);
            limit.setEnabled(!active);
            origin.setEnabled(!active);
            key.setEnabled(!active);
            if (isCalibrating()) refreshCalibration(active);
            ui.postDelayed(this, 750);
        }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = getSharedPreferences("doorcam", MODE_PRIVATE);
        LinearLayout root = column();
        FrameLayout screens = new FrameLayout(this);
        root.addView(screens, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        LinearLayout body = page(screens, HOME, "Doorcam");
        buildHome(body);
        buildSettings(page(screens, SETTINGS, "Настройки"));
        bottomTabs(root);
        setContentView(root);
        showTab(saved == null ? HOME : saved.getInt("tab", HOME));
    }

    private LinearLayout page(FrameLayout screens, int tab, String title) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setPadding(dp(16), dp(12), dp(16), dp(8));
        pages[tab] = scroll;
        screens.addView(scroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout body = column();
        int width = Math.min(520, getResources().getConfiguration().screenWidthDp - 32);
        scroll.addView(body, new FrameLayout.LayoutParams(dp(width), ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        body.setFocusableInTouchMode(true);
        heading(body, title, 26, 0).setLetterSpacing(-0.025f);
        return body;
    }

    private void bottomTabs(LinearLayout root) {
        LinearLayout bar = new LinearLayout(this);
        bar.setPadding(dp(4), dp(4), dp(4), dp(4));
        bar.setBackground(surface(R.color.doorcam_surface, 32, R.color.doorcam_border));
        bar.setElevation(dp(8));
        int width = Math.min(260, getResources().getConfiguration().screenWidthDp - 32);
        LinearLayout.LayoutParams position = new LinearLayout.LayoutParams(dp(width), ViewGroup.LayoutParams.WRAP_CONTENT);
        position.gravity = Gravity.CENTER_HORIZONTAL;
        position.bottomMargin = dp(8);
        root.addView(bar, position);

        String[] names = {"Главная", "Настройки"};
        for (int i = 0; i < tabs.length; i++) {
            int tab = i;
            Button button = new Button(this);
            button.setText(names[i]);
            button.setAllCaps(false);
            button.setTextSize(13);
            button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            button.setMinWidth(0);
            button.setMinimumWidth(0);
            button.setMinHeight(dp(48));
            button.setPadding(dp(4), dp(8), dp(4), dp(8));
            button.setStateListAnimator(null);
            button.setOnClickListener(v -> showTab(tab));
            tabs[i] = button;
            bar.addView(button, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
    }

    private void showTab(int tab) {
        View focus = getCurrentFocus();
        if (tab != selectedTab && focus instanceof EditText) {
            getSystemService(InputMethodManager.class).hideSoftInputFromWindow(focus.getWindowToken(), 0);
        }
        selectedTab = tab;
        for (int i = 0; i < pages.length; i++) {
            boolean selected = i == tab;
            pages[i].setVisibility(selected ? View.VISIBLE : View.GONE);
            Button button = tabs[i];
            button.setSelected(selected);
            button.setTextColor(getColor(selected ? R.color.doorcam_on_accent : R.color.doorcam_text));
            button.setBackground(new RippleDrawable(ColorStateList.valueOf(selected ? 0x30000000 : 0x30FFFFFF),
                    surface(selected ? R.color.doorcam_accent : R.color.doorcam_surface, 26, 0), null));
        }
        pages[tab].getChildAt(0).requestFocus();
        updateCalibrationVisibility();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("tab", selectedTab);
    }

    private void buildHome(LinearLayout body) {
        LinearLayout hero = card(body, 12);
        hero.setBackground(rounded(new int[]{getColor(R.color.doorcam_hero), getColor(R.color.doorcam_surface)},
                24, R.color.doorcam_border));
        LinearLayout headline = new LinearLayout(this);
        headline.setGravity(Gravity.CENTER_VERTICAL);
        hero.addView(headline);
        LinearLayout status = column();
        headline.addView(status, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        state = heading(status, MonitorService.status, 20, 0);
        state.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        ImageView lens = new ImageView(this);
        lens.setImageResource(R.drawable.doorcam_lens);
        lens.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams lensParams = new LinearLayout.LayoutParams(dp(38), dp(38));
        lensParams.setMarginStart(dp(12));
        headline.addView(lens, lensParams);
        lastMotion = label(hero, "", 13, R.color.doorcam_muted, 6);
        start = action(hero, "Начать наблюдение", R.color.doorcam_on_accent, R.color.doorcam_accent, 0);
        stop = action(hero, "Остановить наблюдение", R.color.doorcam_stop, R.color.doorcam_surface, R.color.doorcam_stop);
        start.setVisibility(MonitorService.running ? View.GONE : View.VISIBLE);
        stop.setVisibility(MonitorService.running ? View.VISIBLE : View.GONE);
        start.setOnClickListener(v -> prepare());
        stop.setOnClickListener(v -> stopService(new Intent(this, MonitorService.class)));
        action(hero, "Предпросмотр камеры", R.color.doorcam_text, R.color.doorcam_surface, R.color.doorcam_border)
                .setOnClickListener(v -> showCameraPreview());

        divider(hero, 12);
        Switch detector = new Switch(this);
        detector.setText("Детектор движения");
        detector.setTextSize(14);
        detector.setTextColor(getColor(R.color.doorcam_text));
        detector.setMinHeight(dp(48));
        detector.setSwitchPadding(dp(12));
        detector.setChecked(prefs.getBoolean(MonitorService.MOTION_ENABLED, true));
        add(hero, detector, 2);
        detector.setOnCheckedChangeListener((button, checked) -> MonitorService.setMotionEnabled(prefs, checked));
    }

    private void showCameraPreview() {
        if (cameraPreviewDialog != null) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PREVIEW_PERMISSION);
            return;
        }
        boolean active = MonitorService.running;
        if (!active && ids.isEmpty()) populateCameras();
        int selection = cameras.getSelectedItemPosition();
        if (!active && selection == AdapterView.INVALID_POSITION) {
            message("Камера не найдена");
            return;
        }
        LinearLayout content = column();
        content.setPadding(dp(18), dp(10), dp(18), 0);
        FrameLayout frame = new FrameLayout(this);
        frame.setBackground(surface(R.color.doorcam_background, 16, R.color.doorcam_border));
        frame.setClipToOutline(true);
        int height = Math.min(260, getResources().getConfiguration().screenHeightDp / 2);
        content.addView(frame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(height)));
        ImageView lens = new ImageView(this);
        lens.setImageResource(R.drawable.doorcam_lens);
        lens.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        TextView previewStatus = label(content, "Ждём кадр с камеры…", 13, R.color.doorcam_muted, 8);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Предпросмотр камеры").setView(content)
                .setPositiveButton("Закрыть", null).create();
        ImageView still = new ImageView(this);
        still.setScaleType(ImageView.ScaleType.FIT_CENTER);
        Runnable fallback = new Runnable() {
            private byte[] displayed;
            @Override public void run() {
                if (!dialog.isShowing()) return;
                byte[] bytes = MonitorService.previewFrame;
                if (bytes != displayed) {
                    displayed = bytes;
                    still.setImageBitmap(bytes == null ? null : BitmapFactory.decodeByteArray(bytes, 0, bytes.length));
                    lens.setVisibility(bytes == null ? View.VISIBLE : View.GONE);
                }
                ui.postDelayed(this, 500);
            }
        };
        CameraPreview video = new CameraPreview(this, active ? null : ids.get(selection), () -> {
            ui.removeCallbacks(fallback);
            MonitorService.previewVisible = false;
            still.setImageDrawable(null);
            lens.setVisibility(View.GONE);
            previewStatus.setVisibility(View.GONE);
        }, error -> {
            if (!dialog.isShowing()) return;
            Log.w("Doorcam", "Предпросмотр камеры", error);
            if (active && MonitorService.running) {
                previewStatus.setText("Непрерывный предпросмотр недоступен. Показываем кадры.");
                MonitorService.previewVisible = true;
                ui.removeCallbacks(fallback);
                ui.post(fallback);
            } else {
                previewStatus.setText("Не удалось получить изображение с камеры");
            }
        });
        video.setContentDescription("Изображение с камеры");
        frame.addView(video, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        frame.addView(still, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        frame.addView(lens, new FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER));
        cameraPreviewDialog = dialog;
        dialog.setOnDismissListener(dismissed -> {
            ui.removeCallbacks(fallback);
            cameraPreviewDialog = null;
            updateCalibrationVisibility();
        });
        dialog.show();
        dialog.getWindow().setBackgroundDrawable(new InsetDrawable(
                surface(R.color.doorcam_surface, 22, R.color.doorcam_border), dp(12), 0, dp(12), 0));
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setAllCaps(false);
    }

    private void buildWifi(LinearLayout wifi) {
        lanAddress = label(wifi, "", 15, R.color.doorcam_muted, 0);
        lanAddress.setMinHeight(dp(48));
        lanAddress.setGravity(Gravity.CENTER_VERTICAL);
        lanAddress.setAutoLinkMask(android.text.util.Linkify.WEB_URLS);
        lanAddress.setTextIsSelectable(true);
        divider(wifi, 8);
        label(wifi, "Логин: " + LanServer.USER, 14, R.color.doorcam_text, 12);
        TextView passwordLabel = label(wifi, "Пароль", 14, R.color.doorcam_muted, 8);
        TextView password = label(wifi, MonitorService.lanPassword(prefs), 16, R.color.doorcam_text, 4);
        password.setMinHeight(dp(48));
        password.setGravity(Gravity.CENTER_VERTICAL);
        password.setId(View.generateViewId());
        passwordLabel.setLabelFor(password.getId());
        password.setTypeface(Typeface.MONOSPACE);
        password.setTextIsSelectable(true);
    }

    private void buildSettings(LinearLayout body) {
        LinearLayout recording = column();
        LinearLayout cameraCard = card(body, 12);
        cameraCard.setBackground(rounded(new int[]{getColor(R.color.doorcam_hero), getColor(R.color.doorcam_surface)},
                18, R.color.doorcam_border));
        recordingSection = settingsSection(cameraCard, "Камера и запись", recording);
        TextView cameraLabel = label(recording, "Камера", 14, R.color.doorcam_muted, 0);
        cameras = new Spinner(this);
        cameras.setId(View.generateViewId());
        cameras.setMinimumHeight(dp(48));
        cameraLabel.setLabelFor(cameras.getId());
        add(recording, cameras, 4);
        populateCameras();
        TextView motionLabel = label(recording, "Чувствительность движения", 14, R.color.doorcam_muted, 12);
        sensitivityLabel = heading(recording, "", 16, 4);
        sensitivity = new SeekBar(this);
        sensitivity.setId(View.generateViewId());
        sensitivity.setMinimumHeight(dp(48));
        motionLabel.setLabelFor(sensitivity.getId());
        sensitivity.setMax(100);
        sensitivity.setProgress(prefs.getInt("sensitivity", 50));
        sensitivityLabel.setText(sensitivity.getProgress() + " %");
        sensitivity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int value, boolean fromUser) { sensitivityLabel.setText(value + " %"); }
            public void onStartTrackingTouch(SeekBar s) { }
            public void onStopTrackingTouch(SeekBar s) { }
        });
        add(recording, sensitivity, 0);
        motionCalibration(recording);
        limit = field(recording, "Лимит видеоархива, МиБ", InputType.TYPE_CLASS_NUMBER,
                String.valueOf(prefs.getInt("limit", 1024)));

        label(body, "Подключения", 13, R.color.doorcam_muted, 14);
        LinearLayout connections = card(body, 6);
        LinearLayout remote = column();
        remoteSection = settingsSection(connections, "Удалённый доступ", remote);
        label(remote, "Для записи без Telegram и удалённого просмотра оставьте оба поля пустыми.",
                13, R.color.doorcam_muted, 0);
        origin = field(remote, "Адрес сайта Doorcam", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI,
                prefs.getString("origin", ""));
        origin.setHint("https://doorcam.example.com");
        key = field(remote, "Ключ устройства", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD,
                prefs.getString("key", ""));
        key.setSaveEnabled(false);

        divider(connections, 0);
        LinearLayout wifi = column();
        settingsSection(connections, "Просмотр по Wi-Fi", wifi);
        buildWifi(wifi);

        LinearLayout diagnostics = column();
        settingsSection(card(body, 12), "Нагрузка Doorcam", diagnostics);
        resources = heading(diagnostics, "", 15, 0);
        label(diagnostics, "100 % ЦП = одно ядро", 13, R.color.doorcam_muted, 4);
    }

    private ToggleButton settingsSection(LinearLayout section, String title, LinearLayout content) {
        section.setPadding(dp(14), 0, dp(14), 0);
        ToggleButton header = new ToggleButton(this);
        header.setText(title);
        header.setTextOn(title);
        header.setTextOff(title);
        header.setAllCaps(false);
        header.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        header.setTextSize(15);
        header.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        header.setTextColor(getColor(R.color.doorcam_text));
        header.setMinHeight(dp(52));
        header.setPadding(dp(2), 0, dp(2), 0);
        header.setStateListAnimator(null);
        header.setBackground(new RippleDrawable(ColorStateList.valueOf(0x30FFFFFF), null,
                surface(R.color.doorcam_surface, 12, 0)));
        header.setCompoundDrawableTintList(ColorStateList.valueOf(getColor(R.color.doorcam_muted)));
        header.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, android.R.drawable.arrow_down_float, 0);
        add(section, header, 0);
        content.setPadding(dp(2), dp(4), dp(2), dp(14));
        content.setVisibility(View.GONE);
        add(section, content, 0);
        header.setOnCheckedChangeListener((button, expanded) -> {
            if (!expanded && content.hasFocus()) {
                getSystemService(InputMethodManager.class).hideSoftInputFromWindow(header.getWindowToken(), 0);
                header.requestFocusFromTouch();
            }
            header.setTextColor(getColor(expanded ? R.color.doorcam_accent : R.color.doorcam_text));
            header.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0,
                    expanded ? android.R.drawable.arrow_up_float : android.R.drawable.arrow_down_float, 0);
            content.setVisibility(expanded ? View.VISIBLE : View.GONE);
            if (header == recordingSection) updateCalibrationVisibility();
        });
        return header;
    }

    private void motionCalibration(LinearLayout recording) {
        motionZone = MotionZone.read(prefs);
        calibration = new CheckBox(this);
        calibration.setText("Настроить зону движения");
        calibration.setMinHeight(dp(48));
        add(recording, calibration, 8);
        LinearLayout controls = column();
        controls.setVisibility(View.GONE);
        add(recording, controls, 4);

        zonePreview = new ZonePreview();
        controls.addView(zonePreview, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(180)));
        calibrationStatus = label(controls, "", 14, R.color.doorcam_muted, 8);
        String[] names = {"Левая граница", "Верхняя граница", "Правая граница", "Нижняя граница"};
        int[] edges = {motionZone.left, motionZone.top, motionZone.right, motionZone.bottom};
        SeekBar[] sliders = new SeekBar[edges.length];
        for (int i = 0; i < edges.length; i++) {
            int edge = i;
            TextView caption = label(controls, names[i] + " · " + edges[i] + " %", 14, R.color.doorcam_muted, 8);
            SeekBar slider = new SeekBar(this);
            sliders[i] = slider;
            slider.setId(View.generateViewId());
            slider.setMinimumHeight(dp(48));
            slider.setMax(100);
            slider.setProgress(edges[i]);
            caption.setLabelFor(slider.getId());
            slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                    if (fromUser) {
                        int value = edge < 2 ? Math.min(progress, edges[edge + 2] - MotionZone.MIN_SIZE)
                                : Math.max(progress, edges[edge - 2] + MotionZone.MIN_SIZE);
                        if (value != progress) bar.setProgress(value);
                        if (edges[edge] != value) {
                            edges[edge] = value;
                            applyMotionZone(new MotionZone(edges[0], edges[1], edges[2], edges[3]));
                        }
                    }
                    caption.setText(names[edge] + " · " + bar.getProgress() + " %");
                }
                public void onStartTrackingTouch(SeekBar bar) { }
                public void onStopTrackingTouch(SeekBar bar) { }
            });
            add(controls, slider, 0);
        }
        Button reset = new Button(this);
        reset.setText("Весь кадр");
        reset.setAllCaps(false);
        reset.setMinHeight(dp(48));
        reset.setOnClickListener(v -> {
            for (int i = 0; i < edges.length; i++) {
                edges[i] = i < 2 ? 0 : 100;
                sliders[i].setProgress(edges[i]);
            }
            applyMotionZone(MotionZone.FULL);
        });
        add(controls, reset, 0);
        calibration.setOnCheckedChangeListener((button, checked) -> {
            controls.setVisibility(checked ? View.VISIBLE : View.GONE);
            updateCalibrationVisibility();
        });
    }

    private boolean isCalibrating() {
        return selectedTab == SETTINGS && recordingSection.isChecked() && calibration.isChecked();
    }

    private void updateCalibrationVisibility() {
        boolean calibrating = isCalibrating();
        MonitorService.previewVisible = calibrating;
        if (calibrating) refreshCalibration(MonitorService.running);
        if (!calibrating) MonitorService.previewFrame = null;
        if (!calibrating) clearCalibrationPreview();
    }

    private void applyMotionZone(MotionZone zone) {
        motionZone = zone;
        MonitorService.motionZone = zone;
        MonitorService.calibrationMotionAt = 0;
        zone.save(prefs);
        zonePreview.invalidate();
    }

    private void refreshCalibration(boolean active) {
        byte[] preview = active ? MonitorService.previewFrame : null;
        if (displayedPreview != preview) {
            displayedPreview = preview;
            zonePreview.setImageBitmap(preview == null ? null : BitmapFactory.decodeByteArray(preview, 0, preview.length));
        }
        long latestMotion = MonitorService.calibrationMotionAt;
        String text = !active ? "Начните наблюдение для предпросмотра" : preview == null ? "Ждём кадр с камеры…"
                : !MonitorService.motionEnabled ? "Детектор движения выключен"
                : latestMotion > 0 && SystemClock.elapsedRealtime() - latestMotion < 1800
                ? "Движение в зоне" : "Движения в зоне нет";
        if (!text.contentEquals(calibrationStatus.getText())) calibrationStatus.setText(text);
    }

    private void clearCalibrationPreview() {
        displayedPreview = null;
        zonePreview.setImageDrawable(null);
    }

    private final class ZonePreview extends ImageView {
        private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF frame = new RectF();
        private final RectF zone = new RectF();

        ZonePreview() {
            super(MainActivity.this);
            setScaleType(ScaleType.FIT_CENTER);
            setBackgroundColor(Color.BLACK);
            setContentDescription("Предпросмотр камеры и границы зоны движения");
            outline.setStyle(Paint.Style.STROKE);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (getDrawable() == null) return;
            frame.set(getDrawable().getBounds());
            getImageMatrix().mapRect(frame);
            float x = frame.width() / 100f, y = frame.height() / 100f;
            zone.set(frame.left + motionZone.left * x, frame.top + motionZone.top * y,
                    frame.left + motionZone.right * x, frame.top + motionZone.bottom * y);
            outline.setColor(Color.BLACK);
            outline.setStrokeWidth(dp(4));
            canvas.drawRect(zone, outline);
            outline.setColor(getColor(R.color.doorcam_accent));
            outline.setStrokeWidth(dp(2));
            canvas.drawRect(zone, outline);
        }
    }

    private LinearLayout column() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private void add(LinearLayout parent, View child, int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(top);
        parent.addView(child, params);
    }

    private GradientDrawable rounded(int[] colors, int radius, int stroke) {
        GradientDrawable shape = new GradientDrawable(GradientDrawable.Orientation.TL_BR, colors);
        shape.setCornerRadius(dp(radius));
        if (stroke != 0) shape.setStroke(dp(1), getColor(stroke));
        return shape;
    }

    private GradientDrawable surface(int color, int radius, int stroke) {
        GradientDrawable shape = rounded(null, radius, stroke);
        shape.setColor(getColor(color));
        return shape;
    }

    private LinearLayout card(LinearLayout parent, int top) {
        LinearLayout card = column();
        card.setBackground(surface(R.color.doorcam_surface, 18, R.color.doorcam_border));
        card.setPadding(dp(14), dp(12), dp(14), dp(8));
        add(parent, card, top);
        return card;
    }

    private void divider(LinearLayout parent, int top) {
        View line = new View(this);
        line.setBackgroundColor(getColor(R.color.doorcam_border));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        params.topMargin = dp(top);
        parent.addView(line, params);
    }

    private TextView label(LinearLayout parent, String text, int size, int color, int top) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(getColor(color));
        add(parent, view, top);
        return view;
    }

    private TextView heading(LinearLayout parent, String text, int size, int top) {
        TextView view = label(parent, text, size, R.color.doorcam_text, top);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        if (Build.VERSION.SDK_INT >= 28 && size >= 20) view.setAccessibilityHeading(true);
        return view;
    }

    private Button action(LinearLayout parent, String text, int textColor, int fill, int stroke) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(getColor(textColor));
        button.setMinHeight(dp(48));
        button.setPadding(dp(12), dp(8), dp(12), dp(8));
        button.setStateListAnimator(null);
        GradientDrawable background = surface(fill, 14, stroke);
        int ripple = fill == R.color.doorcam_accent ? 0x30000000 : 0x30FFFFFF;
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(ripple), background, null));
        add(parent, button, 8);
        return button;
    }

    private EditText field(LinearLayout parent, String text, int type, String value) {
        TextView caption = label(parent, text, 14, R.color.doorcam_muted, 12);
        EditText edit = new EditText(this);
        edit.setSingleLine(true);
        edit.setInputType(type);
        edit.setText(value);
        edit.setTextSize(16);
        edit.setMinHeight(dp(48));
        edit.setId(View.generateViewId());
        caption.setLabelFor(edit.getId());
        add(parent, edit, 4);
        return edit;
    }

    private void populateCameras() {
        CameraManager manager = getSystemService(CameraManager.class);
        List<String> names = new ArrayList<>();
        ids.clear();
        int preferred = 0;
        try {
            for (String id : manager.getCameraIdList()) {
                Integer facing = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
                boolean back = facing != null && facing == CameraCharacteristics.LENS_FACING_BACK;
                if (back && !prefs.contains("camera")) preferred = ids.size();
                ids.add(id);
                names.add((back ? "Основная" : facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT ? "Фронтальная" : "Внешняя") + " · " + id);
            }
        } catch (CameraAccessException | SecurityException ignored) { }
        cameras.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names));
        int saved = ids.indexOf(prefs.getString("camera", ""));
        if (!ids.isEmpty()) cameras.setSelection(saved >= 0 ? saved : preferred);
    }

    private void prepare() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, 1);
            return;
        }
        if (ids.isEmpty()) populateCameras();
        if (ids.isEmpty()) { message("Камера не найдена"); return; }
        int mib;
        try {
            mib = Integer.parseInt(limit.getText().toString());
            if (mib < 64 || mib > 32768) throw new NumberFormatException();
        } catch (NumberFormatException e) { fieldError(limit, "Введите от 64 до 32768 МиБ"); return; }
        String url = origin.getText().toString().trim(), secret = key.getText().toString().trim();
        if (!url.isEmpty()) {
            try {
                URI uri = new URI(url);
                if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null
                        || uri.getRawQuery() != null || uri.getRawFragment() != null
                        || !(uri.getRawPath().isEmpty() || uri.getRawPath().equals("/"))) throw new IllegalArgumentException();
                url = "https://" + uri.getRawAuthority();
            } catch (Exception e) { fieldError(origin, "Укажите адрес HTTPS без пути и параметров"); return; }
        }
        if (secret.isEmpty() != url.isEmpty()) { fieldError(key, "Для подключения нужны адрес и ключ"); return; }
        prefs.edit().putString("origin", url).putString("key", secret).putString("camera", ids.get(cameras.getSelectedItemPosition()))
            .putInt("sensitivity", sensitivity.getProgress()).putInt("limit", mib).apply();
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !prefs.getBoolean("askedNotifications", false)) {
            prefs.edit().putBoolean("askedNotifications", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2);
            return;
        }
        startForegroundService(new Intent(this, MonitorService.class));
    }

    private void fieldError(EditText field, String error) {
        showTab(SETTINGS);
        (field == limit ? recordingSection : remoteSection).setChecked(true);
        field.requestFocus();
        field.setError(error);
        field.post(() -> field.requestRectangleOnScreen(new Rect(0, 0, field.getWidth(), field.getHeight()), true));
    }

    private void message(String msg) { Toast.makeText(this, msg, Toast.LENGTH_LONG).show(); }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == 1) {
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                populateCameras(); prepare();
            } else message("Разрешите доступ к камере в настройках приложения");
        } else if (code == 2) prepare();
        else if (code == CAMERA_PREVIEW_PERMISSION) {
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) showCameraPreview();
            else message("Разрешите доступ к камере в настройках приложения");
        }
    }
    @Override protected void onResume() {
        super.onResume();
        updateCalibrationVisibility();
        ui.post(refresh);
        resources.setText("ЦП — · RAM —");
        ResourceUsage usage = new ResourceUsage();
        ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor();
        resourceSampler = sampler;
        sampler.scheduleWithFixedDelay(() -> {
            String text = usage.sample(SystemClock.uptimeMillis(), android.os.Process.getElapsedCpuTime(), Debug.getPss());
            ui.post(() -> {
                if (resourceSampler == sampler) resources.setText(text);
            });
        }, 0, 5, TimeUnit.SECONDS);
    }

    @Override protected void onPause() {
        ui.removeCallbacks(refresh);
        if (cameraPreviewDialog != null) cameraPreviewDialog.dismiss();
        MonitorService.previewVisible = false;
        MonitorService.previewFrame = null;
        clearCalibrationPreview();
        resourceSampler.shutdownNow();
        resourceSampler = null;
        super.onPause();
    }
}
