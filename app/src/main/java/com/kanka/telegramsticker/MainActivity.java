package com.kanka.telegramsticker;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Color;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.ReturnCode;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int PICK_VIDEO = 10;
    private static final long LIMIT_BYTES = 255_000L;

    private Uri selectedUri;
    private File cachedInput;
    private File currentOutput;

    private VideoView preview;
    private TextView selectedText;
    private TextView status;
    private Spinner strengthSpinner;
    private SeekBar edgeSeek;
    private Button convertButton;
    private ProgressBar progress;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
    }

    private View buildUi() {
        int pad = dp(18);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.rgb(17, 17, 17));

        TextView title = text("StickerYap", 25, Color.WHITE);
        title.setTypeface(null, 1);
        root.addView(title);

        TextView sub = text("Video seç → yeşili temizle → Telegram uyumlu 512×512 VP9 WebM.", 14, 0xFFBDBDBD);
        sub.setPadding(0, dp(6), 0, dp(14));
        root.addView(sub);

        Button pick = new Button(this);
        pick.setText("Video Seç");
        pick.setOnClickListener(v -> pickVideo());
        root.addView(pick, matchWrap());

        selectedText = text("Henüz video seçilmedi", 13, 0xFFBDBDBD);
        selectedText.setPadding(0, dp(8), 0, dp(8));
        root.addView(selectedText);

        preview = new VideoView(this);
        LinearLayout.LayoutParams videoLp = new LinearLayout.LayoutParams(-1, dp(250));
        preview.setBackgroundColor(Color.BLACK);
        root.addView(preview, videoLp);

        TextView modeLabel = text("Yeşil temizleme", 15, Color.WHITE);
        modeLabel.setPadding(0, dp(14), 0, dp(4));
        root.addView(modeLabel);

        strengthSpinner = new Spinner(this);
        String[] modes = new String[]{"Dengeli", "Sert", "Çok Sert"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, modes);
        strengthSpinner.setAdapter(adapter);
        strengthSpinner.setSelection(1);
        root.addView(strengthSpinner, matchWrap());

        TextView edgeLabel = text("Kenar temizleme", 15, Color.WHITE);
        edgeLabel.setPadding(0, dp(12), 0, 0);
        root.addView(edgeLabel);

        edgeSeek = new SeekBar(this);
        edgeSeek.setMax(100);
        edgeSeek.setProgress(35);
        root.addView(edgeSeek, matchWrap());

        convertButton = new Button(this);
        convertButton.setText("Sticker Yap");
        convertButton.setEnabled(false);
        convertButton.setOnClickListener(v -> convert());
        LinearLayout.LayoutParams btnLp = matchWrap();
        btnLp.setMargins(0, dp(14), 0, 0);
        root.addView(convertButton, btnLp);

        progress = new ProgressBar(this);
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(dp(42), dp(42));
        pp.gravity = Gravity.CENTER_HORIZONTAL;
        pp.setMargins(0, dp(12), 0, 0);
        root.addView(progress, pp);

        status = text("", 14, 0xFFE0E0E0);
        status.setGravity(Gravity.CENTER_HORIZONTAL);
        status.setPadding(0, dp(8), 0, 0);
        root.addView(status);

        return root;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(-1, -2);
    }

    private TextView text(String value, int sp, int color) {
        TextView tv = new TextView(this);
        tv.setText(value);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        return tv;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void pickVideo() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("video/*");
        startActivityForResult(i, PICK_VIDEO);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_VIDEO && resultCode == RESULT_OK && data != null && data.getData() != null) {
            selectedUri = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(selectedUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {}
            selectedText.setText("Video hazır");
            preview.setVideoURI(selectedUri);
            preview.setOnPreparedListener(mp -> {
                mp.setLooping(true);
                preview.start();
            });
            convertButton.setEnabled(true);
            status.setText("");
        }
    }

    private void convert() {
        if (selectedUri == null) return;
        convertButton.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        status.setText("Video hazırlanıyor…");

        new Thread(() -> {
            try {
                cachedInput = new File(getCacheDir(), "input_" + System.currentTimeMillis() + ".mp4");
                copyUriToFile(selectedUri, cachedInput);
                double duration = readDurationSeconds(cachedInput);
                runOnUiThread(() -> status.setText("Yeşil temizleniyor ve WebM hazırlanıyor…"));
                encodeWithSizeTarget(duration);
            } catch (Exception e) {
                fail("Hata: " + e.getMessage());
            }
        }).start();
    }

    private double readDurationSeconds(File f) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(f.getAbsolutePath());
            String ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (ms == null) return 2.9;
            return Math.max(0.1, Long.parseLong(ms) / 1000.0);
        } finally {
            try { r.release(); } catch (Exception ignored) {}
        }
    }

    private void encodeWithSizeTarget(double duration) {
        encodeAttempt(duration, 470, 1);
    }

    private void encodeAttempt(double duration, int bitrateKbps, int attempt) {
        currentOutput = new File(getCacheDir(), "sticker_" + System.currentTimeMillis() + ".webm");
        if (currentOutput.exists()) currentOutput.delete();

        double similarity;
        switch (strengthSpinner.getSelectedItemPosition()) {
            case 0: similarity = 0.30; break;
            case 2: similarity = 0.43; break;
            default: similarity = 0.37; break;
        }
        double blend = 0.025 + (edgeSeek.getProgress() / 100.0) * 0.045;
        double despillMix = 0.06 + (edgeSeek.getProgress() / 100.0) * 0.16;
        double ptsFactor = duration > 2.90 ? (2.90 / duration) : 1.0;

        String filter = String.format(Locale.US,
                "setpts=%.8f*PTS,fps=24," +
                "format=rgba," +
                "colorkey=0x00FF00:%.3f:%.3f," +
                "despill=type=green:mix=%.3f:expand=0:red=0:green=-0.45:blue=0:brightness=0:alpha=0," +
                "scale=512:512:force_original_aspect_ratio=decrease:flags=lanczos," +
                "pad=512:512:(ow-iw)/2:(oh-ih)/2:color=0x00000000," +
                "format=yuva420p",
                ptsFactor, similarity, blend, despillMix);

        String cmd = "-y -i " + q(cachedInput.getAbsolutePath()) +
                " -an -vf " + q(filter) +
                " -t 2.90 -c:v libvpx-vp9 -pix_fmt yuva420p" +
                " -deadline good -cpu-used 6 -row-mt 1 -threads 4 -auto-alt-ref 0" +
                " -b:v " + bitrateKbps + "k -maxrate " + bitrateKbps + "k -bufsize " + (bitrateKbps * 2) + "k" +
                " -metadata:s:v:0 alpha_mode=1 " + q(currentOutput.getAbsolutePath());

        final int usedBitrate = bitrateKbps;
        FFmpegKit.executeAsync(cmd, session -> {
            if (!ReturnCode.isSuccess(session.getReturnCode()) || !currentOutput.exists()) {
                fail("Dönüştürme başarısız. FFmpeg kodu: " + session.getReturnCode());
                return;
            }

            long size = currentOutput.length();
            if (size <= LIMIT_BYTES) {
                try {
                    Uri saved = saveToDownloads(currentOutput);
                    runOnUiThread(() -> {
                        progress.setVisibility(View.GONE);
                        convertButton.setEnabled(true);
                        status.setText(String.format(Locale.US, "Hazır: %.1f KB • %d kbps", size / 1024.0, usedBitrate));
                        Toast.makeText(this, "Downloads/StickerYap'a kaydedildi", Toast.LENGTH_LONG).show();
                        shareResult(saved);
                    });
                } catch (Exception e) {
                    fail("Kaydetme hatası: " + e.getMessage());
                }
                return;
            }

            if (attempt >= 5) {
                fail("256 KB altına inemedi. Daha sert yeşil temizleme seçip tekrar dene.");
                return;
            }

            double ratio = (LIMIT_BYTES * 0.96) / (double) size;
            int next = Math.max(150, (int) Math.floor(usedBitrate * ratio));
            runOnUiThread(() -> status.setText("Boyut ayarlanıyor… " + attempt + "/5"));
            currentOutput.delete();
            encodeAttempt(duration, next, attempt + 1);
        });
    }

    private void copyUriToFile(Uri uri, File dst) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(dst)) {
            if (in == null) throw new Exception("Video açılamadı");
            byte[] buf = new byte[1024 * 256];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    private Uri saveToDownloads(File src) throws Exception {
        String name = "telegram_sticker_" + System.currentTimeMillis() + ".webm";
        ContentResolver resolver = getContentResolver();

        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, name);
            values.put(MediaStore.Downloads.MIME_TYPE, "video/webm");
            values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/StickerYap");
            values.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new Exception("Downloads kaydı açılamadı");
            try (InputStream in = new FileInputStream(src); OutputStream out = resolver.openOutputStream(uri)) {
                if (out == null) throw new Exception("Çıktı dosyası açılamadı");
                byte[] buf = new byte[1024 * 256];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.Downloads.IS_PENDING, 0);
            resolver.update(uri, done, null, null);
            return uri;
        }

        File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (!dir.exists()) dir.mkdirs();
        File outFile = new File(dir, name);
        try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(outFile)) {
            byte[] buf = new byte[1024 * 256];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        return Uri.fromFile(outFile);
    }

    private void shareResult(Uri uri) {
        if (uri == null) return;
        try {
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("video/webm");
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share, "Stickerı paylaş"));
        } catch (Exception ignored) {}
    }

    private void fail(String message) {
        runOnUiThread(() -> {
            progress.setVisibility(View.GONE);
            convertButton.setEnabled(selectedUri != null);
            status.setText(message);
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        });
    }

    private String q(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
