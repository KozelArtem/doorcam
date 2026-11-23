package by.doorcam.app;

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
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

public final class MainActivity extends Activity {
    private SharedPreferences prefs;
    private TextView state, sensitivityLabel, lanAddress;
    private EditText origin, key, limit;
    private Spinner cameras;
    private SeekBar sensitivity;
    private Button start, stop;
    private LinearLayout form;
    private final List<String> ids = new ArrayList<>();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            boolean active = MonitorService.running;
            String text = MonitorService.status;
            if (MonitorService.lastEvent != 0) text += "\nПоследнее движение · " +
                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(new Date(MonitorService.lastEvent));
            state.setText(text);
            String url = MonitorService.lanUrl;
            String address = !active ? "Адрес появится после запуска наблюдения"
                    : url.isEmpty() ? "Ожидание подключения к Wi-Fi" : url;
            if (!address.contentEquals(lanAddress.getText())) lanAddress.setText(address);
            start.setEnabled(!active);
            stop.setEnabled(active);
            for (int i = 0; i < form.getChildCount(); i++) form.getChildAt(i).setEnabled(!active);
            ui.postDelayed(this, 750);
        }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = getSharedPreferences("doorcam", MODE_PRIVATE);
        getWindow().setStatusBarColor(Color.rgb(27, 54, 48));
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(248, 250, 247));
        LinearLayout body = column();
        int edge = dp(22);
        body.setPadding(edge, dp(30), edge, edge);
        scroll.addView(body);
        setContentView(scroll);
        TextView title = label(body, "Дверной глазок", 30);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        state = label(body, MonitorService.status, 17);
        state.setPadding(0, dp(12), 0, dp(16));
        start = new Button(this);
        start.setText("Начать наблюдение");
        body.addView(start);
        stop = new Button(this);
        stop.setText("Остановить");
        body.addView(stop);
        start.setOnClickListener(v -> prepare());
        stop.setOnClickListener(v -> stopService(new Intent(this, MonitorService.class)));
        label(body, "Просмотр по Wi-Fi", 17);
        lanAddress = label(body, "", 16);
        lanAddress.setAutoLinkMask(android.text.util.Linkify.WEB_URLS);
        lanAddress.setTextIsSelectable(true);
        label(body, "Логин: " + LanServer.USER + " · Пароль", 14);
        label(body, MonitorService.lanPassword(prefs), 15).setTextIsSelectable(true);
        form = column();
        body.addView(form);
        label(form, "Камера", 15);
        cameras = new Spinner(this);
        form.addView(cameras);
        populateCameras();
        label(form, "Чувствительность движения", 15);
        sensitivityLabel = label(form, "", 14);
        sensitivity = new SeekBar(this);
        sensitivity.setMax(100);
        sensitivity.setProgress(prefs.getInt("sensitivity", 50));
        sensitivityLabel.setText(sensitivity.getProgress() + " %");
        sensitivity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int value, boolean fromUser) { sensitivityLabel.setText(value + " %"); }
            public void onStartTrackingTouch(SeekBar s) { }
            public void onStopTrackingTouch(SeekBar s) { }
        });
        form.addView(sensitivity);
        limit = field("Лимит видеоархива, МиБ", InputType.TYPE_CLASS_NUMBER, String.valueOf(prefs.getInt("limit", 1024)));
        origin = field("Адрес сайта Doorcam", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, prefs.getString("origin", ""));
        origin.setHint("https://doorcam.example.com");
        key = field("Ключ устройства", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, prefs.getString("key", ""));
        key.setSaveEnabled(false);
        label(form, "Для записи без Telegram и удалённого просмотра оставьте адрес и ключ пустыми.", 14);
    }

    private LinearLayout column() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView label(LinearLayout parent, String text, int size) {
        TextView view = new TextView(this);
        view.setText(text); view.setTextSize(size); view.setTextColor(Color.rgb(28, 51, 46));
        view.setPadding(0, dp(12), 0, dp(3)); parent.addView(view);
        return view;
    }
    private EditText field(String text, int type, String value) {
        TextView caption = label(form, text, 15);
        EditText edit = new EditText(this);
        edit.setSingleLine(true); edit.setInputType(type); edit.setText(value);
        edit.setId(View.generateViewId()); caption.setLabelFor(edit.getId());
        form.addView(edit); return edit;
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
        } catch (NumberFormatException e) { limit.setError("Введите от 64 до 32768 МиБ"); return; }
        String url = origin.getText().toString().trim(), secret = key.getText().toString().trim();
        if (!url.isEmpty()) {
            try {
                URI uri = new URI(url);
                if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null
                        || uri.getRawQuery() != null || uri.getRawFragment() != null
                        || !(uri.getRawPath().isEmpty() || uri.getRawPath().equals("/"))) throw new IllegalArgumentException();
                url = "https://" + uri.getRawAuthority();
            } catch (Exception e) { origin.setError("Укажите адрес HTTPS без пути и параметров"); return; }
        }
        if (secret.isEmpty() != url.isEmpty()) { key.setError("Для подключения нужны адрес и ключ"); return; }
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

    private void message(String msg) { Toast.makeText(this, msg, Toast.LENGTH_LONG).show(); }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == 1) {
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                populateCameras(); prepare();
            } else message("Разрешите доступ к камере в настройках приложения");
        } else if (code == 2) prepare();
    }
    @Override protected void onResume() { super.onResume(); ui.post(refresh); }
    @Override protected void onPause() { ui.removeCallbacks(refresh); super.onPause(); }
}
