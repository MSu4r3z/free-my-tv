## `app/src/io/freemytv/AppContentPage.java`

```java
package io.freemytv;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.LruCache;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Página 3: canales publicados por las aplicaciones.
 * Solo lectura: no modifica las listas ni el historial de las apps.
 *
 * Arriba desde la primera fila regresa al panel de tiempo/cripto/HDMI.
 * Abajo desde la última fila permanece en esta página.
 */
final class AppContentPage extends LinearLayout {
    interface Navigation {
        void toPreviousPage();
    }

    private static final String ANIKEN = "com.shubh.anililitv";
    private static final String STREMIO = "com.stremio.one";
    private static final String SMARTTUBE = "org.smarttube.stable";
    private static final String NETFLIX = "com.netflix.ninja";

    private static final String[] HEADINGS = {
        "Aniken: Sigue viendo",
        "Stremio: Biblioteca",
        "SmartTube: Recomendaciones",
        "Netflix: Destacados"
    };

    private final Activity activity;
    private final Navigation navigation;
    private final Handler main = new Handler(Looper.getMainLooper());

    private final ExecutorService dataWorker =
            Executors.newSingleThreadExecutor();
    private final ExecutorService imageWorker =
            Executors.newSingleThreadExecutor();

    private final Set<String> pendingImages = new HashSet<>();
    private final Map<String, Long> imageFailures = new HashMap<>();

    private final LruCache<String, Bitmap> imageCache =
            new LruCache<String, Bitmap>(12 * 1024 * 1024) {
                @Override
                protected int sizeOf(String key, Bitmap bitmap) {
                    return bitmap.getAllocationByteCount();
                }
            };

    private final List<Row> rows = new ArrayList<>();
    private final float scale;

    private boolean active;
    private boolean destroyed;
    private boolean loading;
    private boolean okPressed;
    private int generation;
    private int selectedRow;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            if (!active || destroyed) return;
            loadData();
            main.postDelayed(this, 60000);
        }
    };

    private static final class Entry {
        long id;
        long weight = Long.MIN_VALUE;
        long position;
        long duration;
        String pkg;
        String title;
        String subtitle;
        String image;
        String intent;

        String key() {
            return pkg + "|" + intent;
        }
    }

    private static final class Channel {
        String pkg;
        String name;
    }

    AppContentPage(Activity activity, Navigation navigation) {
        super(activity);
        this.activity = activity;
        this.navigation = navigation;

        int width = activity.getResources()
                .getDisplayMetrics().widthPixels;
        scale = Math.max(0.3f, width / 1920f);

        setOrientation(VERTICAL);
        setPadding(px(60), px(28), px(60), px(16));
        setClipChildren(false);
        setFocusable(false);
        setBackground(new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0xFA202633, 0xFA10131B}));

        TextView heading = text("Tus aplicaciones", 35, Color.WHITE);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        addView(heading, new LayoutParams(
                LayoutParams.MATCH_PARENT, px(54)));

        for (int i = 0; i < HEADINGS.length; i++) {
            Row row = new Row(i);
            rows.add(row);
            addView(row.root, new LayoutParams(
                    LayoutParams.MATCH_PARENT, px(219)));
        }

        TextView help = text(
                "← → Explorar   ·   ↑ Panel / fila anterior"
                + "   ·   ↓ Fila siguiente   ·   Atrás: panel",
                19, 0xFFADB5C4);
        help.setGravity(Gravity.CENTER);
        addView(help, new LayoutParams(
                LayoutParams.MATCH_PARENT, px(34)));
    }

    private int px(float value) {
        return Math.max(1, Math.round(value * scale));
    }

    private TextView text(String value, float size, int color) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextColor(color);
        view.setTextSize(
                android.util.TypedValue.COMPLEX_UNIT_PX, px(size));
        view.setSingleLine(true);
        view.setEllipsize(android.text.TextUtils.TruncateAt.END);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    void start() {
        if (active || destroyed) return;
        active = true;
        loadData();
        main.removeCallbacks(refresh);
        main.postDelayed(refresh, 60000);
    }

    void stop() {
        active = false;
        okPressed = false;
        main.removeCallbacks(refresh);
    }

    void destroyPage() {
        destroyed = true;
        active = false;
        generation++;
        main.removeCallbacksAndMessages(null);
        dataWorker.shutdownNow();
        imageWorker.shutdownNow();
        imageCache.evictAll();
        pendingImages.clear();
        imageFailures.clear();

        for (Row row : rows) {
            for (ImageView image : row.images) {
                image.setImageDrawable(null);
            }
        }
    }

    void focusFirst() {
        selectRow(0);
    }

    void focusLast() {
        selectRow(rows.size() - 1);
    }

    void restoreFocus() {
        selectRow(selectedRow);
    }

    private void selectRow(int index) {
        selectedRow = Math.max(0, Math.min(rows.size() - 1, index));
        rows.get(selectedRow).focusSelected();
    }

    /**
     * HomeActivity entrega las teclas a HomeView.
     * HomeView delega aquí las flechas y OK de esta página.
     * Atrás se gestiona en HomeView.
     */
    boolean onRemote(KeyEvent event) {
        int code = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;

        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) {
                    okPressed = false;
                    rows.get(selectedRow).move(
                            code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                }
                return true;

            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) {
                    okPressed = false;
                    if (code == KeyEvent.KEYCODE_DPAD_UP) {
                        if (selectedRow == 0) {
                            navigation.toPreviousPage();
                        } else {
                            selectRow(selectedRow - 1);
                        }
                    } else if (selectedRow < rows.size() - 1) {
                        selectRow(selectedRow + 1);
                    }
                }
                return true;

            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (down) {
                    if (event.getRepeatCount() == 0) okPressed = true;
                } else {
                    if (okPressed && !event.isCanceled()) {
                        rows.get(selectedRow).activate();
                    }
                    okPressed = false;
                }
                return true;

            default:
                return false;
        }
    }

    private final class Row {
        final int index;
        final LinearLayout root;
        final HorizontalScrollView scroll;
        final LinearLayout strip;
        final List<View> cards = new ArrayList<>();
        final List<ImageView> images = new ArrayList<>();

        List<Entry> entries = new ArrayList<>();
        String signature = "";
        int selected;

        Row(int index) {
            this.index = index;

            root = new LinearLayout(activity);
            root.setOrientation(VERTICAL);
            root.setClipChildren(false);

            TextView title = text(HEADINGS[index], 25, 0xFFF5F5F7);
            title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            root.addView(title, new LayoutParams(
                    LayoutParams.MATCH_PARENT, px(35)));

            scroll = new HorizontalScrollView(activity);
            scroll.setHorizontalScrollBarEnabled(false);
            scroll.setFillViewport(false);
            scroll.setSmoothScrollingEnabled(true);
            scroll.setClipToPadding(false);
            scroll.setPadding(px(7), px(7), px(7), px(7));

            strip = new LinearLayout(activity);
            strip.setOrientation(HORIZONTAL);
            strip.setClipChildren(false);

            scroll.addView(strip, new FrameLayout.LayoutParams(
                    LayoutParams.WRAP_CONTENT,
                    LayoutParams.MATCH_PARENT));

            root.addView(scroll, new LayoutParams(
                    LayoutParams.MATCH_PARENT, px(179)));

            scroll.setOnScrollChangeListener(
                    (view, x, y, oldX, oldY) -> syncImages());

            showEmpty("Cargando…");
        }

        void showEmpty(String message) {
            strip.removeAllViews();
            cards.clear();
            images.clear();

            TextView empty = text(message, 23, 0xFFB8C0CF);
            empty.setPadding(px(18), 0, px(18), 0);
            empty.setFocusable(true);
            empty.setBackground(background(false));
            empty.setContentDescription(
                    HEADINGS[index] + ". " + message);
            empty.setOnFocusChangeListener((view, focused) -> {
                if (focused) selectedRow = index;
                view.setBackground(background(focused));
            });

            strip.addView(empty, new LayoutParams(px(900), px(160)));
            cards.add(empty);
        }

        void bind(List<Entry> next) {
            StringBuilder mark = new StringBuilder();
            for (Entry e : next) {
                mark.append(e.key()).append('|')
                    .append(e.title).append('|')
                    .append(e.subtitle).append('|')
                    .append(e.image).append('|')
                    .append(e.position).append('|')
                    .append(e.duration).append('\n');
            }

            String newSignature = mark.toString();
            if (newSignature.equals(signature)
                    && (!entries.isEmpty() || cards.size() == 1)) {
                if (next.isEmpty()) {
                    showEmpty("Sin contenido disponible");
                }
                return;
            }

            String selectedKey = entries.isEmpty()
                    ? null
                    : entries.get(Math.min(selected, entries.size() - 1))
                            .key();
            boolean hadFocus = root.hasFocus();

            signature = newSignature;
            entries = next;
            selected = Math.min(selected, Math.max(0, next.size() - 1));

            for (int i = 0; selectedKey != null && i < next.size(); i++) {
                if (selectedKey.equals(next.get(i).key())) {
                    selected = i;
                    break;
                }
            }

            strip.removeAllViews();
            cards.clear();
            images.clear();

            if (entries.isEmpty()) {
                showEmpty("Sin contenido disponible");
                if (hadFocus) focusSelected();
                return;
            }

            for (int i = 0; i < entries.size(); i++) {
                addCard(entries.get(i), i);
            }

            scroll.post(() -> {
                if (destroyed) return;
                if (hadFocus) focusSelected();
                syncImages();
            });
        }

        private void addCard(Entry entry, int positionInRow) {
            FrameLayout card = new FrameLayout(activity);
            card.setFocusable(true);
            card.setClickable(true);
            card.setClipToOutline(true);
            card.setBackground(background(false));

            ImageView image = new ImageView(activity);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setBackgroundColor(0xFF303746);
            image.setImportantForAccessibility(
                    View.IMPORTANT_FOR_ACCESSIBILITY_NO);

            card.addView(image, new FrameLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT, px(118)));

            TextView label = text(entry.title, 21, Color.WHITE);
            label.setPadding(px(9), 0, px(9), 0);
            FrameLayout.LayoutParams labelParams =
                    new FrameLayout.LayoutParams(
                            LayoutParams.MATCH_PARENT, px(27));
            labelParams.topMargin = px(119);
            card.addView(label, labelParams);

            TextView detail = text(entry.subtitle, 16, 0xFFC5CDDA);
            detail.setPadding(px(9), 0, px(9), 0);
            FrameLayout.LayoutParams detailParams =
                    new FrameLayout.LayoutParams(
                            LayoutParams.MATCH_PARENT, px(20));
            detailParams.topMargin = px(145);
            card.addView(detail, detailParams);

            if (entry.duration > 0
                    && entry.position > 0
                    && entry.position < entry.duration) {
                ProgressBar progress = new ProgressBar(
                        activity, null,
                        android.R.attr.progressBarStyleHorizontal);
                progress.setMax(1000);
                progress.setProgress((int) Math.min(
                        1000, entry.position * 1000.0 / entry.duration));
                progress.setProgressTintList(
                        android.content.res.ColorStateList.valueOf(
                                0xFF79BFFF));

                FrameLayout.LayoutParams progressParams =
                        new FrameLayout.LayoutParams(
                                LayoutParams.MATCH_PARENT, px(5));
                progressParams.topMargin = px(113);
                card.addView(progress, progressParams);
            }

            card.setContentDescription(
                    entry.title + ". " + entry.subtitle);
            card.setOnClickListener(view -> open(entry));

            card.setOnFocusChangeListener((view, focused) -> {
                view.animate().cancel();
                view.animate()
                    .scaleX(focused ? 1.025f : 1f)
                    .scaleY(focused ? 1.025f : 1f)
                    .setDuration(170)
                    .setInterpolator(
                        new android.view.animation
                                .AccelerateDecelerateInterpolator())
                    .start();
                view.setBackground(background(focused));

                if (focused) {
                    selectedRow = index;
                    selected = positionInRow;
                    revealSelected();
                }
            });

            LayoutParams params = new LayoutParams(px(300), px(165));
            params.rightMargin = px(20);
            strip.addView(card, params);
            cards.add(card);
            images.add(image);
        }

        void move(int direction) {
            if (entries.isEmpty()) return;
            selected = Math.max(
                    0, Math.min(entries.size() - 1, selected + direction));
            focusSelected();
        }

        void focusSelected() {
            if (cards.isEmpty()) return;
            int at = Math.min(selected, cards.size() - 1);
            cards.get(at).requestFocus();
            revealSelected();
        }

        void activate() {
            if (!entries.isEmpty() && selected < cards.size()) {
                cards.get(selected).performClick();
            }
        }

        private void revealSelected() {
            if (cards.isEmpty()) return;
            View card = cards.get(Math.min(selected, cards.size() - 1));
            int left = card.getLeft();
            int right = card.getRight();
            int viewport = scroll.getWidth();
            if (viewport <= 0) return;

            int x = scroll.getScrollX();
            if (left < x + px(10)) {
                scroll.smoothScrollTo(Math.max(0, left - px(10)), 0);
            } else if (right > x + viewport - px(20)) {
                scroll.smoothScrollTo(right - viewport + px(20), 0);
            }
            syncImages();
        }

        /**
         * Mantiene imágenes en la ventana visible y una tarjeta
         * vecina a cada lado.
         */
        void syncImages() {
            if (!active || destroyed || images.isEmpty()) return;

            int stride = px(320);
            int start = Math.max(0, scroll.getScrollX() / stride - 1);
            int end = Math.min(
                    images.size() - 1,
                    (scroll.getScrollX()
                            + Math.max(scroll.getWidth(), px(900)))
                            / stride + 1);

            for (int i = 0; i < images.size(); i++) {
                ImageView view = images.get(i);
                if ((i < start || i > end) && i != selected) {
                    view.setImageDrawable(null);
                    view.setTag(null);
                    continue;
                }

                Entry entry = entries.get(i);
                if (entry.image.equals(view.getTag())) continue;

                Bitmap bitmap = imageCache.get(entry.image);
                if (bitmap != null) {
                    view.setImageBitmap(bitmap);
                    view.setTag(entry.image);
                } else {
                    requestImage(entry.image);
                }
            }
        }
    }

    private GradientDrawable background(boolean focused) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(focused ? 0xFF34465F : 0xFF262E3D);
        drawable.setCornerRadius(px(12));
        drawable.setStroke(px(focused ? 2 : 1),
                focused ? 0xFFF5F5F7 : 0xFF414B5D);
        return drawable;
    }

    private void loadData() {
        if (loading || destroyed) return;
        loading = true;
        final int token = generation;

        dataWorker.execute(() -> {
            List<List<Entry>> result = null;
            try {
                result = readData();
            } catch (Exception ignored) {
                // No imprimir títulos, enlaces ni datos privados.
            }

            final List<List<Entry>> ready = result;
            main.post(() -> {
                loading = false;
                if (destroyed || token != generation || !active) return;

                if (ready == null) {
                    Toast.makeText(activity,
                            "No se pudieron leer los canales de las apps.",
                            Toast.LENGTH_SHORT).show();
                    return;
                }

                for (int i = 0; i < rows.size(); i++) {
                    rows.get(i).bind(ready.get(i));
                    rows.get(i).syncImages();
                }
            });
        });
    }

    private List<List<Entry>> readData() {
        List<List<Entry>> result = new ArrayList<>();
        for (int i = 0; i < 4; i++) result.add(new ArrayList<>());

        Map<String, Channel> channels = new HashMap<>();

        try (Cursor c = activity.getContentResolver().query(
                Uri.parse("content://android.media.tv/channel"),
                new String[] {"_id", "package_name", "display_name", "type"},
                null, null, null)) {
            if (c != null) {
                while (c.moveToNext()) {
                    if (!"TYPE_PREVIEW".equals(value(c, "type"))) continue;
                    Channel channel = new Channel();
                    channel.pkg = value(c, "package_name");
                    channel.name = value(c, "display_name")
                            .toLowerCase(java.util.Locale.ROOT);
                    channels.put(
                            channel.pkg + ":" + value(c, "_id"), channel);
                }
            }
        }

        // Aniken: continuar viendo con progreso numérico incompleto.
        try (Cursor c = activity.getContentResolver().query(
                Uri.parse("content://android.media.tv/watch_next_program"),
                null, null, null, null)) {
            if (c != null) {
                while (c.moveToNext()) {
                    if (!ANIKEN.equals(value(c, "package_name"))) continue;
                    if (number(c, "watch_next_type", -1) != 0) continue;

                    Entry entry = readEntry(c);
                    if (entry == null || entry.position <= 0
                            || entry.duration <= entry.position) continue;

                    entry.subtitle = "Seguir viendo" + episode(c);
                    result.get(0).add(entry);
                }
            }
        }

        try (Cursor c = activity.getContentResolver().query(
                Uri.parse("content://android.media.tv/preview_program"),
                null, null, null, null)) {
            if (c != null) {
                while (c.moveToNext()) {
                    String pkg = value(c, "package_name");
                    Channel channel = channels.get(
                            pkg + ":" + value(c, "channel_id"));
                    if (channel == null) continue;

                    int row = -1;
                    String subtitle = "";

                    if (STREMIO.equals(pkg)
                            && (channel.name.contains("biblioteca")
                                || channel.name.contains("library"))) {
                        row = 1;
                        long type = number(c, "type", -1);
                        subtitle = type == 0
                                ? "Película · Stremio"
                                : "Biblioteca · Stremio";
                        subtitle += episode(c);
                    } else if (SMARTTUBE.equals(pkg)
                            && (channel.name.contains("recomendad")
                                || channel.name.contains("recommend"))) {
                        row = 2;
                        subtitle = "Recomendado por SmartTube";
                    } else if (NETFLIX.equals(pkg)) {
                        row = 3;
                        subtitle = "Canal de Netflix";
                    }

                    if (row < 0) continue;
                    Entry entry = readEntry(c);
                    if (entry == null) continue;

                    entry.subtitle = subtitle;
                    result.get(row).add(entry);
                }
            }
        }

        for (int row = 0; row < result.size(); row++) {
            final boolean useWeight = row == 2;
            List<Entry> list = result.get(row);

            Collections.sort(list, new Comparator<Entry>() {
                @Override
                public int compare(Entry a, Entry b) {
                    if (useWeight && a.weight != b.weight) {
                        return Long.compare(b.weight, a.weight);
                    }
                    // Orden local estable; no representa un ranking.
                    return Long.compare(a.id, b.id);
                }
            });

            Set<String> seen = new HashSet<>();
            List<Entry> unique = new ArrayList<>();
            for (Entry entry : list) {
                if (seen.add(entry.key())) unique.add(entry);
                if (unique.size() >= 60) break;
            }
            result.set(row, unique);
        }

        return result;
    }

    private Entry readEntry(Cursor cursor) {
        Entry entry = new Entry();
        entry.id = number(cursor, "_id", 0);
        entry.pkg = value(cursor, "package_name");
        entry.title = value(cursor, "title");
        entry.intent = value(cursor, "intent_uri");
        entry.image = value(cursor, "poster_art_uri");

        if (entry.image.isEmpty()) {
            entry.image = value(cursor, "thumbnail_uri");
        }
        if (entry.title.isEmpty() || entry.intent.isEmpty()) return null;

        entry.position = number(
                cursor, "last_playback_position_millis", 0);
        entry.duration = number(cursor, "duration_millis", 0);
        entry.weight = number(cursor, "weight", Long.MIN_VALUE);
        entry.subtitle = "";
        return entry;
    }

    private static String episode(Cursor cursor) {
        String season = value(cursor, "season_display_number");
        String episode = value(cursor, "episode_display_number");
        if (episode.isEmpty()) return "";
        return " · " + (season.isEmpty() ? "" : "T" + season + " ")
                + "E" + episode;
    }

    private static String value(Cursor cursor, String column) {
        int index = cursor.getColumnIndex(column);
        if (index < 0 || cursor.isNull(index)) return "";
        try {
            if (cursor.getType(index) == Cursor.FIELD_TYPE_BLOB) return "";
            String text = cursor.getString(index);
            return text == null ? "" : text.trim();
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static long number(
            Cursor cursor, String column, long fallback) {
        try {
            String text = value(cursor, column);
            return text.isEmpty() ? fallback : Long.parseLong(text);
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private void requestImage(String address) {
        if (address.isEmpty() || destroyed || !active) return;
        if (pendingImages.contains(address)) return;

        Long failed = imageFailures.get(address);
        if (failed != null
                && SystemClock.elapsedRealtime() - failed < 60000) return;

        pendingImages.add(address);
        imageWorker.execute(() -> {
            Bitmap bitmap = null;
            try {
                bitmap = readImage(address);
            } catch (Exception | OutOfMemoryError ignored) {
            }

            final Bitmap ready = bitmap;
            main.post(() -> {
                pendingImages.remove(address);
                if (destroyed) return;

                if (ready == null) {
                    imageFailures.put(
                            address, SystemClock.elapsedRealtime());
                } else {
                    imageFailures.remove(address);
                    imageCache.put(address, ready);
                    if (active) {
                        for (Row row : rows) row.syncImages();
                    }
                }
            });
        });
    }

    private Bitmap readImage(String address) throws Exception {
        Uri uri = Uri.parse(address);
        String scheme = uri.getScheme();
        if (scheme == null) return null;

        HttpURLConnection connection = null;
        InputStream stream;

        if ("https".equalsIgnoreCase(scheme)
                || "http".equalsIgnoreCase(scheme)) {
            connection = (HttpURLConnection) new URL(address).openConnection();
            connection.setConnectTimeout(6000);
            connection.setReadTimeout(6000);
            if (connection.getResponseCode() != 200) {
                connection.disconnect();
                return null;
            }
            stream = connection.getInputStream();
        } else if ("content".equalsIgnoreCase(scheme)
                || "android.resource".equalsIgnoreCase(scheme)) {
            stream = activity.getContentResolver().openInputStream(uri);
        } else {
            return null;
        }

        byte[] bytes;
        try (InputStream input = stream;
             ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            if (input == null) return null;
            byte[] block = new byte[8192];
            int count;
            int total = 0;
            while ((count = input.read(block)) != -1) {
                total += count;
                if (total > 6 * 1024 * 1024) return null;
                buffer.write(block, 0, count);
            }
            bytes = buffer.toByteArray();
        } finally {
            if (connection != null) connection.disconnect();
        }

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        while (bounds.outWidth / options.inSampleSize > 640
                || bounds.outHeight / options.inSampleSize > 400) {
            options.inSampleSize *= 2;
        }

        return BitmapFactory.decodeByteArray(
                bytes, 0, bytes.length, options);
    }

    /**
     * Respeta el intent_uri publicado, limitando el destino
     * a la aplicación que publicó la tarjeta.
     */
    private void open(Entry entry) {
        try {
            Intent intent = Intent.parseUri(
                    entry.intent, Intent.URI_INTENT_SCHEME);
            intent.setSelector(null);
            intent.setClipData(null);

            if (intent.getComponent() != null
                    && !entry.pkg.equals(
                        intent.getComponent().getPackageName())) {
                intent.setComponent(null);
            }

            intent.setPackage(entry.pkg);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            ResolveInfo resolved = activity.getPackageManager()
                    .resolveActivity(intent, 0);

            if (resolved == null || resolved.activityInfo == null
                    || !resolved.activityInfo.exported
                    || !entry.pkg.equals(
                        resolved.activityInfo.packageName)) {
                throw new IllegalStateException("No exported destination");
            }

            activity.startActivity(intent);
            return;
        } catch (Exception ignored) {
        }

        try {
            Intent fallback = HomeApps.launchIntent(
                    activity.getPackageManager(), entry.pkg);
            if (fallback != null) {
                Toast.makeText(activity,
                        "No se pudo abrir el contenido; abriendo la app.",
                        Toast.LENGTH_SHORT).show();
                activity.startActivity(fallback);
                return;
            }
        } catch (Exception ignored) {
        }

        Toast.makeText(activity,
                "No se pudo abrir esta aplicación.",
                Toast.LENGTH_SHORT).show();
    }
}
```
