package io.freemytv;

import android.app.Activity;
import android.os.Bundle;
import android.database.Cursor;
import android.net.Uri;
import android.graphics.Color;
import android.media.tv.TvContract;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.LinkedHashMap;
import java.util.Map;

public class TvDataDiagnosticActivity extends Activity {
    private TextView output;

    private static final String[] PACKAGES = {
        "com.netflix.ninja",
        "com.stremio.one",
        "org.smarttube.stable",
        "tv.pluto.android",
        "com.shubh.anililitv"
    };

    private static final class Group {
        String pkg;
        String channelId;
        String name;
        int total;
        int pictures;
        int links;
        int descriptions;
        int episodes;
        int partial;
        int weighted;
        long minWeight = Long.MAX_VALUE;
        long maxWeight = Long.MIN_VALUE;

        final Map<String, Integer> types = new LinkedHashMap<>();
        final Map<String, Integer> genres = new LinkedHashMap<>();
        final StringBuilder samples = new StringBuilder();
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        output = new TextView(this);
        output.setTextColor(Color.WHITE);
        output.setTextSize(16);
        output.setPadding(32, 24, 32, 40);
        output.setText("Consultando canales y clasificación…");

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(20, 20, 25));
        scroll.setFocusable(true);
        scroll.addView(output);
        setContentView(scroll);
        scroll.requestFocus();

        new Thread(() -> {
            StringBuilder report = new StringBuilder(
                "DIAGNÓSTICO — FUENTES Y CATEGORÍAS\n\n"
                + "Solo lectura. No cambia recomendaciones ni historial.\n"
                + "Muestra nombres de canales y géneros publicados.\n"
                + "No muestra títulos, descripciones ni enlaces.\n"
                + "Las muestras no están ordenadas por prioridad.\n\n"
            );

            Map<String, Group> groups = readChannels(report);
            inspectPrograms(report, groups);

            final String result = report.toString();
            android.util.Log.i(
                    "FreeMyTVDiag",
                    "========== INICIO DEL INFORME "
                            + System.currentTimeMillis()
                            + " ==========");

            for (String line : result.split("\\r?\\n")) {
                android.util.Log.i(
                        "FreeMyTVDiag",
                        line.isEmpty() ? " " : line);
            }

            android.util.Log.i(
                    "FreeMyTVDiag",
                    "========== FIN DEL INFORME ==========");
            runOnUiThread(() -> {
                if (!isFinishing() && !isDestroyed()) {
                    output.setText(result);
                }
            });
        }, "tv-category-diagnostic").start();
    }

    private boolean wanted(String pkg) {
        for (String candidate : PACKAGES) {
            if (candidate.equals(pkg)) return true;
        }
        return false;
    }

    private Map<String, Group> readChannels(StringBuilder report) {
        Map<String, Group> groups = new LinkedHashMap<>();

        try (Cursor c = getContentResolver().query(
                Uri.parse("content://android.media.tv/channel"),
                new String[] {
                    "_id", "package_name", "display_name", "type"
                },
                null, null, null)) {

            if (c == null) {
                report.append("Canales: cursor nulo.\n\n");
                return groups;
            }

            while (c.moveToNext()) {
                String pkg = text(c, "package_name");
                if (!wanted(pkg)) continue;
                if (!"TYPE_PREVIEW".equals(text(c, "type"))) continue;

                Group g = new Group();
                g.pkg = pkg;
                g.channelId = text(c, "_id");
                g.name = clean(text(c, "display_name"), 100);

                groups.put(pkg + ":" + g.channelId, g);
            }
        } catch (Exception e) {
            report.append("Error al consultar canales: ")
                .append(e.getClass().getSimpleName())
                .append("\n\n");
        }

        return groups;
    }

    private void inspectPrograms(
            StringBuilder report, Map<String, Group> groups) {

        // Proyección nula para tolerar diferencias entre proveedores.
        // No se imprimen blobs, identificadores internos ni URIs.
        try (Cursor c = getContentResolver().query(
                Uri.parse("content://android.media.tv/preview_program"),
                null, null, null, null)) {

            if (c == null) {
                report.append("Programas: cursor nulo.\n");
                return;
            }

            String[] fields = {
                "type",
                "canonical_genre",
                "genre",
                "broadcast_genre",
                "weight",
                "season_display_number",
                "episode_display_number",
                "short_description",
                "long_description",
                "poster_art_uri",
                "thumbnail_uri",
                "intent_uri",
                "last_playback_position_millis",
                "duration_millis"
            };

            report.append("COLUMNAS DEL PROVEEDOR\n");
            for (String field : fields) {
                report.append(c.getColumnIndex(field) >= 0
                        ? "  Disponible: " : "  Ausente: ")
                    .append(field).append('\n');
            }

            while (c.moveToNext()) {
                String pkg = text(c, "package_name");
                if (!wanted(pkg)) continue;

                String channelId = text(c, "channel_id");
                String key = pkg + ":" + channelId;
                Group g = groups.get(key);

                if (g == null) {
                    g = new Group();
                    g.pkg = pkg;
                    g.channelId = channelId;
                    g.name = "(nombre de canal no disponible)";
                    groups.put(key, g);
                }

                g.total++;

                Long typeValue = number(c, "type");
                String type = typeName(typeValue);
                count(g.types, type);

                boolean image = !text(c, "poster_art_uri").isEmpty()
                        || !text(c, "thumbnail_uri").isEmpty();
                boolean link = !text(c, "intent_uri").isEmpty();
                boolean description =
                        !text(c, "short_description").isEmpty()
                        || !text(c, "long_description").isEmpty();
                boolean episode =
                        !text(c, "episode_display_number").isEmpty()
                        || !text(c, "episode_number").isEmpty();

                Long position = number(c, "last_playback_position_millis");
                Long duration = number(c, "duration_millis");
                boolean partial = position != null && duration != null
                        && position > 0 && duration > position;

                if (image) g.pictures++;
                if (link) g.links++;
                if (description) g.descriptions++;
                if (episode) g.episodes++;
                if (partial) g.partial++;

                StringBuilder rowGenres = new StringBuilder();
                for (String column : new String[] {
                        "canonical_genre", "genre", "broadcast_genre"}) {
                    String value = text(c, column);
                    if (value.isEmpty()) continue;

                    String entry = column + "=" + clean(value, 140);
                    if (g.genres.containsKey(entry)
                            || g.genres.size() < 12) {
                        count(g.genres, entry);
                    } else {
                        count(g.genres, "(otras combinaciones)");
                    }

                    if (rowGenres.length() > 0) rowGenres.append("; ");
                    rowGenres.append(entry);
                }

                Long weight = number(c, "weight");
                if (weight != null) {
                    g.weighted++;
                    g.minWeight = Math.min(g.minWeight, weight);
                    g.maxWeight = Math.max(g.maxWeight, weight);
                }

                // Primeras tres filas observadas: solo metadatos.
                if (g.total <= 3) {
                    g.samples.append("  Muestra ").append(g.total)
                        .append(": ").append(type).append('\n')
                        .append("    Imagen=").append(yes(image))
                        .append(", enlace=").append(yes(link))
                        .append(", episodio=").append(yes(episode))
                        .append('\n')
                        .append("    Progreso parcial=").append(yes(partial))
                        .append(", descripción=").append(yes(description))
                        .append('\n')
                        .append("    Peso=")
                        .append(weight == null ? "ausente" : weight.toString())
                        .append('\n')
                        .append("    Géneros=")
                        .append(rowGenres.length() == 0
                                ? "sin datos" : rowGenres.toString())
                        .append('\n');
                }
            }
        } catch (Exception e) {
            report.append("\nError al consultar programas: ")
                .append(e.getClass().getSimpleName())
                .append(". No se muestran detalles privados.\n");
        }

        for (String pkg : PACKAGES) {
            report.append("\n====================\n")
                .append(pkg).append('\n');

            boolean found = false;
            for (Group g : groups.values()) {
                if (!pkg.equals(g.pkg)) continue;
                found = true;
                appendGroup(report, g);
            }

            if (!found) {
                report.append("Sin canales ni programas visibles ")
                    .append("en esta consulta.\n");
            }
        }

        report.append("\nNOTAS\n")
            .append("- Tipo y género no prueban que sea una recomendación.\n")
            .append("- Una descripción presente no indica su significado.\n")
            .append("- Los pesos se interpretan dentro de cada canal;\n")
            .append("  no sirven para comparar Netflix con otra app.\n")
            .append("- Animación no equivale automáticamente a anime.\n");
    }

    private void appendGroup(StringBuilder report, Group g) {
        report.append("\nCANAL: ").append(g.name)
            .append(" [").append(g.channelId).append("]\n")
            .append("Registros: ").append(g.total).append('\n');

        report.append("Tipos publicados:\n");
        appendCounts(report, g.types);

        report.append("Géneros/combinaciones publicadas:\n");
        appendCounts(report, g.genres);

        report.append("Con imagen: ").append(g.pictures)
            .append(" / enlace: ").append(g.links).append('\n')
            .append("Con descripción: ").append(g.descriptions)
            .append(" / episodio: ").append(g.episodes).append('\n')
            .append("Con progreso parcial: ").append(g.partial).append('\n')
            .append("Con peso de ordenación: ").append(g.weighted);

        if (g.weighted > 0) {
            report.append(" (").append(g.minWeight)
                .append(" a ").append(g.maxWeight).append(")");
        }

        report.append('\n').append(g.samples);
    }

    private static void appendCounts(
            StringBuilder report, Map<String, Integer> counts) {
        if (counts.isEmpty()) {
            report.append("  Sin datos.\n");
            return;
        }

        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            report.append("  ").append(entry.getKey())
                .append(": ").append(entry.getValue()).append('\n');
        }
    }

    private static void count(Map<String, Integer> counts, String key) {
        Integer previous = counts.get(key);
        counts.put(key, previous == null ? 1 : previous + 1);
    }

    private static String typeName(Long value) {
        if (value == null) return "Tipo ausente";

        if (value == TvContract.PreviewPrograms.TYPE_MOVIE) {
            return "Película (" + value + ")";
        }
        if (value == TvContract.PreviewPrograms.TYPE_TV_SERIES) {
            return "Serie (" + value + ")";
        }
        if (value == TvContract.PreviewPrograms.TYPE_TV_SEASON) {
            return "Temporada (" + value + ")";
        }
        if (value == TvContract.PreviewPrograms.TYPE_TV_EPISODE) {
            return "Episodio (" + value + ")";
        }
        return "Otro tipo (" + value + ")";
    }

    private static String yes(boolean value) {
        return value ? "sí" : "no";
    }

    private static String clean(String value, int limit) {
        String result = value.replaceAll("[\\p{Cntrl}]+", " ").trim();
        return result.length() <= limit
                ? result : result.substring(0, limit) + "…";
    }

    private static String text(Cursor c, String column) {
        int i = c.getColumnIndex(column);
        if (i < 0 || c.isNull(i)) return "";
        try {
            if (c.getType(i) == Cursor.FIELD_TYPE_BLOB) return "";
            String value = c.getString(i);
            return value == null ? "" : value.trim();
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static Long number(Cursor c, String column) {
        int i = c.getColumnIndex(column);
        if (i < 0 || c.isNull(i)) return null;
        try {
            return Long.valueOf(c.getString(i));
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
