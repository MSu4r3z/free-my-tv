package io.freemytv;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Outline;
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
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.Interpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
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
 * Mismo lenguaje visual que las páginas 1 y 2: fondo transparente (se ve el
 * mismo cielo de HomeView), carruseles de 4.5 cartillas, aro en la seleccionada
 * y nombre solo al enfocar. Los colores siguen el día/noche de HomeView.
 *
 * Arriba desde la primera fila con contenido regresa al panel anterior.
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

    // Solo para accesibilidad (TalkBack); no se dibujan.
    private static final String[] HEADINGS = {
        "Aniken: Sigue viendo",
        "Stremio: Biblioteca",
        "SmartTube: Recomendaciones",
        "Netflix: Destacados"
    };

    // ---- Estilo (mismos valores que HomeView) ----
    private static final int ROWS = 4;
    private static final float VISIBLE_CARDS = 4.5f;
    private static final float FOCUS_SCALE = 1.05f;
    private static final long FOCUS_MS = 200;
    private static final long SCROLL_MS = 240;
    private static final long IMAGE_FADE_MS = 200;
    private static final int INK_LIGHT = 0xFF1D1D1F;   // L_INK
    private static final int INK_NIGHT = 0xFFF5F5F7;   // WHITE
    private static final int HOLDER_LIGHT = 0xFFDADADF;
    private static final int HOLDER_NIGHT = 0xFF2C2C2E;
    private static final int RING_ALPHA = 0xE6;

    // Misma curva que RecommendationMotion.ease en HomeView.
    private final Interpolator ease = t -> t * t * (3 - 2 * t);

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

    // Medidas calculadas a partir de la pantalla.
    private final int margin;
    private final int stripPad;
    private final int inset;
    private final int radius;
    private final int ringStroke;
    private final int captionH;
    private final int vPad;
    private final int cellW;
    private final int cellH;
    private final int imgW;
    private final int imgH;
    private final int ringH;
    private final ViewOutlineProvider rounded;

    // Tono día/noche (lo fija HomeView).
    private float tone;
    private int ink = INK_LIGHT;
    private int holder = HOLDER_LIGHT;

    private boolean active;
    private boolean destroyed;
    private boolean loading;
    private boolean okPressed;
    private boolean pendingFocus;
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

        android.util.DisplayMetrics metrics =
                activity.getResources().getDisplayMetrics();
        int width = metrics.widthPixels;
        int height = metrics.heightPixels;
        scale = Math.max(0.3f, width / 1920f);

        margin = px(80);                       // MARGIN de HomeView
        inset = px(8);
        radius = px(18);                       // esquinas de las tarjetas
        ringStroke = Math.max(2, Math.round(2.5f * scale));
        captionH = px(32);
        vPad = px(8);
        stripPad = margin - inset;

        int top = px(24);
        int bottom = px(10);
        int rowH = Math.max(px(120), (height - top - bottom) / ROWS);

        cellH = rowH - 2 * vPad;
        // 4 cartillas completas + media: el ancho útil se divide en 4.5.
        cellW = Math.round((width - stripPad) / VISIBLE_CARDS);
        imgW = cellW - 2 * inset;
        imgH = Math.min(Math.round(imgW * 9f / 16f),
                cellH - 2 * inset - captionH);
        ringH = imgH + 2 * inset;

        rounded = new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(
                        0, 0, view.getWidth(), view.getHeight(), radius);
            }
        };

        setOrientation(VERTICAL);
        setPadding(0, top, 0, bottom);
        setClipChildren(false);
        setClipToPadding(false);
        setFocusable(false);
        // Sin fondo propio: se ve el cielo de HomeView, como en la página 2.

        for (int i = 0; i < ROWS; i++) {
            Row row = new Row(i);
            rows.add(row);
            addView(row.scroll, new LayoutParams(
                    LayoutParams.MATCH_PARENT, rowH));
        }
    }

    /** Sin fondo ni superposiciones: el fundido no necesita capa aparte. */
    @Override
    public boolean hasOverlappingRendering() {
        return false;
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

    private GradientDrawable ringDrawable() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Color.TRANSPARENT);
        drawable.setCornerRadius(radius + inset);
        drawable.setStroke(ringStroke, Ui.alpha(ink, RING_ALPHA / 255f));
        return drawable;
    }

    private int skeletonColor() {
        return (holder & 0x00FFFFFF) | 0x99000000;
    }

    private static int mix(int a, int b, float f) {
        int r = Math.round(((a >> 16) & 255) * (1 - f) + ((b >> 16) & 255) * f);
        int g = Math.round(((a >> 8) & 255) * (1 - f) + ((b >> 8) & 255) * f);
        int bl = Math.round((a & 255) * (1 - f) + (b & 255) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    /**
     * 0 = día, 1 = noche (el mismo valor que usa HomeView).
     * Se redondea a 32 pasos para no repintar todas las tarjetas en cada fotograma.
     */
    void setNight(float night) {
        float q = Math.round(Ui.clamp(night) * 32f) / 32f;
        if (q == tone) return;
        tone = q;
        ink = mix(INK_LIGHT, INK_NIGHT, q);
        holder = mix(HOLDER_LIGHT, HOLDER_NIGHT, q);
        for (Row row : rows) row.applyTone();
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
        for (Row row : rows) row.cancelScroll();
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
            row.cancelScroll();
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

    /** Fila con contenido más cercana a {@code from}, o -1 si no hay. */
    private int nearestFilled(int from) {
        for (int d = 0; d < rows.size(); d++) {
            int a = from + d;
            int b = from - d;
            if (a >= 0 && a < rows.size() && rows.get(a).hasContent()) {
                return a;
            }
            if (b >= 0 && b < rows.size() && rows.get(b).hasContent()) {
                return b;
            }
        }
        return -1;
    }

    /** Siguiente fila con contenido en la dirección dada, o -1. */
    private int filledFrom(int start, int step) {
        for (int i = start + step; i >= 0 && i < rows.size(); i += step) {
            if (rows.get(i).hasContent()) return i;
        }
        return -1;
    }

    private void selectRow(int index) {
        int target = nearestFilled(
                Math.max(0, Math.min(rows.size() - 1, index)));
        if (target < 0) {
            // Aún sin datos: se enfoca cuando lleguen.
            pendingFocus = true;
            return;
        }
        pendingFocus = false;
        selectedRow = target;
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
                        int previous = filledFrom(selectedRow, -1);
                        if (previous < 0) {
                            navigation.toPreviousPage();
                        } else {
                            selectRow(previous);
                        }
                    } else {
                        int next = filledFrom(selectedRow, 1);
                        if (next >= 0) selectRow(next);
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
        final HorizontalScrollView scroll;
        final LinearLayout strip;
        final List<View> cards = new ArrayList<>();
        final List<ImageView> images = new ArrayList<>();
        final List<TextView> captions = new ArrayList<>();
        final List<View> rings = new ArrayList<>();
        final List<View> boxes = new ArrayList<>();
        final List<View> skeletons = new ArrayList<>();

        List<Entry> entries = new ArrayList<>();
        String signature = "";
        int selected;
        boolean skeleton;

        private ValueAnimator scrollAnim;
        private int goalX;

        Row(int index) {
            this.index = index;

            scroll = new HorizontalScrollView(activity);
            scroll.setHorizontalScrollBarEnabled(false);
            scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
            scroll.setFillViewport(false);
            scroll.setClipToPadding(false);
            scroll.setClipChildren(false);
            scroll.setFocusable(false);
            scroll.setPadding(0, vPad, 0, vPad);
            scroll.setContentDescription(HEADINGS[index]);

            strip = new LinearLayout(activity);
            strip.setOrientation(HORIZONTAL);
            strip.setClipChildren(false);
            strip.setPadding(stripPad, 0, margin, 0);

            scroll.addView(strip, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));

            scroll.setOnScrollChangeListener(
                    (view, x, y, oldX, oldY) -> syncImages());

            showSkeleton();
        }

        boolean hasContent() {
            return !entries.isEmpty() && !skeleton;
        }

        void cancelScroll() {
            if (scrollAnim != null) {
                scrollAnim.cancel();
                scrollAnim = null;
            }
        }

        private void clearCards() {
            cancelScroll();
            strip.removeAllViews();
            cards.clear();
            images.clear();
            captions.clear();
            rings.clear();
            boxes.clear();
            skeletons.clear();
        }

        /** Repinta los colores que dependen del día/noche. */
        void applyTone() {
            for (TextView t : captions) t.setTextColor(ink);
            for (View v : rings) v.setBackground(ringDrawable());
            for (View v : boxes) v.setBackgroundColor(holder);
            for (View v : skeletons) v.setBackgroundColor(skeletonColor());
        }

        /** Cartillas vacías sin texto: filas sin datos o cargando. */
        void showSkeleton() {
            clearCards();
            skeleton = true;

            int count = (int) Math.ceil(VISIBLE_CARDS);
            for (int i = 0; i < count; i++) {
                FrameLayout cell = new FrameLayout(activity);
                cell.setFocusable(false);

                View box = new View(activity);
                box.setBackgroundColor(skeletonColor());
                box.setClipToOutline(true);
                box.setOutlineProvider(rounded);
                skeletons.add(box);

                FrameLayout.LayoutParams boxParams =
                        new FrameLayout.LayoutParams(imgW, imgH);
                boxParams.leftMargin = inset;
                boxParams.topMargin = inset;
                cell.addView(box, boxParams);

                strip.addView(cell, new LayoutParams(cellW, cellH));
            }
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
                    && (!entries.isEmpty() || skeleton)) {
                return;
            }

            String selectedKey = entries.isEmpty()
                    ? null
                    : entries.get(Math.min(selected, entries.size() - 1))
                            .key();
            boolean hadFocus = scroll.hasFocus();

            signature = newSignature;
            entries = next;
            selected = Math.min(selected, Math.max(0, next.size() - 1));

            for (int i = 0; selectedKey != null && i < next.size(); i++) {
                if (selectedKey.equals(next.get(i).key())) {
                    selected = i;
                    break;
                }
            }

            if (entries.isEmpty()) {
                showSkeleton();
                scroll.scrollTo(0, 0);
                if (hadFocus) main.post(AppContentPage.this::restoreFocus);
                return;
            }

            clearCards();
            skeleton = false;

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
            FrameLayout cell = new FrameLayout(activity);
            cell.setFocusable(true);
            cell.setClickable(true);
            cell.setClipChildren(false);
            // La escala se hace alrededor del centro de la imagen,
            // no del nombre que hay debajo.
            cell.setPivotX(cellW / 2f);
            cell.setPivotY(ringH / 2f);

            // Aro de foco (oculto hasta recibir foco).
            View ring = new View(activity);
            ring.setBackground(ringDrawable());
            ring.setAlpha(0f);
            cell.addView(ring, new FrameLayout.LayoutParams(cellW, ringH));
            rings.add(ring);

            // Imagen con esquinas redondeadas.
            FrameLayout box = new FrameLayout(activity);
            box.setBackgroundColor(holder);
            box.setClipToOutline(true);
            box.setOutlineProvider(rounded);
            boxes.add(box);

            ImageView image = new ImageView(activity);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setImportantForAccessibility(
                    View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            box.addView(image, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));

            if (entry.duration > 0
                    && entry.position > 0
                    && entry.position < entry.duration) {
                int barH = px(5);

                View track = new View(activity);
                track.setBackgroundColor(0x66000000);
                FrameLayout.LayoutParams trackParams =
                        new FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT, barH);
                trackParams.gravity = Gravity.BOTTOM;
                box.addView(track, trackParams);

                View fill = new View(activity);
                fill.setBackgroundColor(0xFFF5F5F7);
                int fillW = Math.max(1, Math.round(imgW
                        * (float) (entry.position
                                / (double) entry.duration)));
                FrameLayout.LayoutParams fillParams =
                        new FrameLayout.LayoutParams(fillW, barH);
                fillParams.gravity = Gravity.BOTTOM | Gravity.START;
                box.addView(fill, fillParams);
            }

            FrameLayout.LayoutParams boxParams =
                    new FrameLayout.LayoutParams(imgW, imgH);
            boxParams.leftMargin = inset;
            boxParams.topMargin = inset;
            cell.addView(box, boxParams);

            // Nombre: solo visible con la cartilla seleccionada.
            TextView caption = text(entry.title, 21, ink);
            caption.setTypeface(
                    Typeface.create("sans-serif-medium", Typeface.NORMAL));
            caption.setPadding(inset + px(4), 0, inset, 0);
            caption.setAlpha(0f);
            FrameLayout.LayoutParams captionParams =
                    new FrameLayout.LayoutParams(cellW, captionH);
            captionParams.topMargin = ringH;
            cell.addView(caption, captionParams);
            captions.add(caption);

            cell.setContentDescription(
                    entry.title + ". " + entry.subtitle);
            cell.setOnClickListener(view -> open(entry));

            cell.setOnFocusChangeListener((view, focused) -> {
                animateFocus(cell, ring, caption, focused);
                if (focused) {
                    selectedRow = index;
                    selected = positionInRow;
                    revealSelected();
                }
            });

            strip.addView(cell, new LayoutParams(cellW, cellH));
            cards.add(cell);
            images.add(image);
        }

        private void animateFocus(View cell, View ring, View caption,
                boolean focused) {
            float s = focused ? FOCUS_SCALE : 1f;

            cell.animate().cancel();
            cell.animate()
                .scaleX(s)
                .scaleY(s)
                .setDuration(FOCUS_MS)
                .setInterpolator(ease)
                .start();

            ring.animate().cancel();
            ring.animate()
                .alpha(focused ? 1f : 0f)
                .setDuration(FOCUS_MS)
                .setInterpolator(ease)
                .start();

            caption.animate().cancel();
            caption.animate()
                .alpha(focused ? 1f : 0f)
                .setDuration(FOCUS_MS)
                .setInterpolator(ease)
                .start();
        }

        void move(int direction) {
            if (!hasContent()) return;
            selected = Math.max(
                    0, Math.min(entries.size() - 1, selected + direction));
            focusSelected();
        }

        void focusSelected() {
            if (!hasContent() || cards.isEmpty()) return;
            int at = Math.min(selected, cards.size() - 1);
            cards.get(at).requestFocus();
            revealSelected();
        }

        void activate() {
            if (hasContent() && selected < cards.size()) {
                cards.get(selected).performClick();
            }
        }

        private int currentGoal() {
            return scrollAnim != null && scrollAnim.isRunning()
                    ? goalX
                    : scroll.getScrollX();
        }

        private void animateScrollTo(int target) {
            if (scrollAnim != null) {
                scrollAnim.cancel();
                scrollAnim = null;
            }
            int from = scroll.getScrollX();
            goalX = target;
            if (from == target) return;

            scrollAnim = ValueAnimator.ofInt(from, target);
            scrollAnim.setDuration(SCROLL_MS);
            scrollAnim.setInterpolator(ease);
            scrollAnim.addUpdateListener(animation ->
                    scroll.scrollTo((Integer) animation.getAnimatedValue(), 0));
            scrollAnim.start();
        }

        /**
         * Mantiene la cartilla seleccionada visible y, cuando hay más
         * a la derecha, deja asomar media cartilla (4.5 visibles).
         */
        private void revealSelected() {
            if (cards.isEmpty() || !hasContent()) return;
            int viewport = scroll.getWidth();
            if (viewport <= 0) return;

            View card = cards.get(Math.min(selected, cards.size() - 1));
            int left = card.getLeft();
            int right = card.getRight();
            int half = cellW / 2;

            int x = currentGoal();
            int target = x;

            if (left < x + stripPad) {
                target = left - stripPad;
            } else if (right > x + viewport - half) {
                target = right + half - viewport;
            }

            int maxX = Math.max(0, strip.getWidth() - viewport);
            if (strip.getWidth() > 0) target = Math.min(target, maxX);
            target = Math.max(0, target);

            if (target != x) animateScrollTo(target);
            syncImages();
        }

        /**
         * Mantiene imágenes en la ventana visible y una tarjeta
         * vecina a cada lado.
         */
        void syncImages() {
            if (!active || destroyed || images.isEmpty()
                    || entries.size() != images.size()) return;

            int start = Math.max(0, scroll.getScrollX() / cellW - 1);
            int end = Math.min(
                    images.size() - 1,
                    (scroll.getScrollX()
                            + Math.max(scroll.getWidth(), px(900)))
                            / cellW + 1);

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
                    view.animate().cancel();
                    view.setAlpha(0f);
                    view.animate()
                        .alpha(1f)
                        .setDuration(IMAGE_FADE_MS)
                        .setInterpolator(ease)
                        .start();
                } else {
                    requestImage(entry.image);
                }
            }
        }
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

                if (pendingFocus) selectRow(selectedRow);
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

        // Las cartillas son horizontales: se prefiere la miniatura.
        entry.image = value(cursor, "thumbnail_uri");
        if (entry.image.isEmpty()) {
            entry.image = value(cursor, "poster_art_uri");
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
