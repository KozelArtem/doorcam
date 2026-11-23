package by.doorcam.app;

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.hardware.camera2.*;
import android.os.*;
import android.text.InputType;
import android.view.*;
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
    private SharedPreferences prefs;
    private TextView state, lastMotion, resources, sensitivityLabel, lanAddress;
    private EditText origin, key, limit;
    private Spinner cameras;
    private SeekBar sensitivity;
    private Button start, stop;
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
            ui.postDelayed(this, 750);
        }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = getSharedPreferences("doorcam", MODE_PRIVATE);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setPadding(dp(20), dp(24), dp(20), dp(32));
        LinearLayout body = column();
        int width = Math.min(560, Math.max(0, getResources().getConfiguration().screenWidthDp - 40));
        scroll.addView(body, new FrameLayout.LayoutParams(dp(width), ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        body.setFocusableInTouchMode(true);
        body.requestFocus();
        setContentView(scroll);
        heading(body, "Doorcam", 32, 0).setLetterSpacing(-0.035f);
        label(body, "Дверной глазок", 16, R.color.doorcam_muted, 2);

        LinearLayout hero = card(body, 24);
        hero.setBackground(rounded(new int[]{getColor(R.color.doorcam_hero), getColor(R.color.doorcam_surface)},
                24, R.color.doorcam_border));
        LinearLayout headline = new LinearLayout(this);
        headline.setGravity(Gravity.CENTER_VERTICAL);
        hero.addView(headline);
        LinearLayout status = column();
        headline.addView(status, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        heading(status, "Мониторинг", 14, 0).setTextColor(getColor(R.color.doorcam_accent));
        state = heading(status, MonitorService.status, 24, 4);
        state.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        ImageView lens = new ImageView(this);
        lens.setImageResource(R.drawable.doorcam_lens);
        lens.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams lensParams = new LinearLayout.LayoutParams(dp(72), dp(72));
        lensParams.setMarginStart(dp(12));
        headline.addView(lens, lensParams);
        lastMotion = label(hero, "", 14, R.color.doorcam_muted, 12);
        start = action(hero, "Начать наблюдение", true);
        stop = action(hero, "Остановить наблюдение", false);
        start.setVisibility(MonitorService.running ? View.GONE : View.VISIBLE);
        stop.setVisibility(MonitorService.running ? View.VISIBLE : View.GONE);
        start.setOnClickListener(v -> prepare());
        stop.setOnClickListener(v -> stopService(new Intent(this, MonitorService.class)));
        divider(hero, 16);
        resources = heading(hero, "", 16, 12);
        label(hero, "Нагрузка Doorcam · 100 % ЦП = одно ядро", 13, R.color.doorcam_muted, 2);

        LinearLayout wifi = card(body, 16);
        heading(wifi, "Просмотр по Wi-Fi", 20, 0);
        lanAddress = label(wifi, "", 16, R.color.doorcam_muted, 8);
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

        LinearLayout recording = card(body, 16);
        heading(recording, "Параметры записи", 20, 0);
        TextView cameraLabel = label(recording, "Камера", 14, R.color.doorcam_muted, 16);
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
        limit = field(recording, "Лимит видеоархива, МиБ", InputType.TYPE_CLASS_NUMBER,
                String.valueOf(prefs.getInt("limit", 1024)));

        LinearLayout remote = card(body, 16);
        heading(remote, "Удалённый доступ", 20, 0);
        label(remote, "Для записи без Telegram и удалённого просмотра оставьте оба поля пустыми.",
                14, R.color.doorcam_muted, 8);
        origin = field(remote, "Адрес сайта Doorcam", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI,
                prefs.getString("origin", ""));
        origin.setHint("https://doorcam.example.com");
        key = field(remote, "Ключ устройства", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD,
                prefs.getString("key", ""));
        key.setSaveEnabled(false);
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
        card.setBackground(surface(R.color.doorcam_surface, 24, R.color.doorcam_border));
        card.setPadding(dp(20), dp(18), dp(20), dp(18));
        add(parent, card, top);
        return card;
    }

    private void divider(LinearLayout parent, int top) {
        View line = new View(this);
        line.setBackgroundColor(getColor(R.color.doorcam_border));
        line.setMinimumHeight(dp(1));
        add(parent, line, top);
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

    private Button action(LinearLayout parent, String text, boolean primary) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(16);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(getColor(primary ? R.color.doorcam_on_accent : R.color.doorcam_stop));
        button.setMinHeight(dp(52));
        button.setPadding(dp(16), dp(10), dp(16), dp(10));
        button.setStateListAnimator(null);
        GradientDrawable background = primary
                ? surface(R.color.doorcam_accent, 14, 0)
                : surface(R.color.doorcam_surface, 14, R.color.doorcam_stop);
        int ripple = primary ? 0x30000000 : 0x30FFFFFF;
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(ripple), background, null));
        add(parent, button, 16);
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
        field.requestFocus();
        field.requestRectangleOnScreen(new Rect(0, 0, field.getWidth(), field.getHeight()), true);
        field.setError(error);
    }

    private void message(String msg) { Toast.makeText(this, msg, Toast.LENGTH_LONG).show(); }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == 1) {
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                populateCameras(); prepare();
            } else message("Разрешите доступ к камере в настройках приложения");
        } else if (code == 2) prepare();
    }
    @Override protected void onResume() {
        super.onResume();
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
        resourceSampler.shutdownNow();
        resourceSampler = null;
        super.onPause();
    }
}
