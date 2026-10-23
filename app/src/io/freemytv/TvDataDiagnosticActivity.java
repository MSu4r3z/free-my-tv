package io.freemytv;

import android.app.Activity;
import android.os.Bundle;
import android.database.Cursor;
import android.net.Uri;
import android.graphics.Color;
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

    private static final class Stats {
        int total;
        int partial;
        int atEnd;
        int duration;
        int episode;
        int image;
        int link;
        int continueType;
        int nextType;
        int newType;
        int watchlistType;
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        output = new TextView(this);
        output.setTextColor(Color.WHITE);
        output.setTextSize(16);
        output.setPadding(32, 24, 32, 24);
        output.setText("Analizando los campos publicados por tus apps…");

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(20, 20, 25));
        scroll.setFocusable(true);
        scroll.addView(output);
        setContentView(scroll);
        scroll.requestFocus();

        new Thread(() -> {
            StringBuilder report = new StringBuilder(
                "DIAGNÓSTICO DE CONTENIDO — CAMPOS\n"
                + "Solo lectura. Sin títulos ni enlaces completos.\n"
                + "Desplázate con las flechas del mando.\n\n"
            );

            Map<String, String> channels = readChannels(report);
            inspect(report, "watch_next_program", channels, true);
            inspect(report, "preview_program", channels, false);

            final String result = report.toString();
            runOnUiThread(() -> {
                if (!isFinishing() && !isDestroyed()) {
                    output.setText(result);
                }
            });
        }, "tv-fields-diagnostic").start();
    }

    private boolean wanted(String pkg) {
        for (String candidate : PACKAGES) {
            if (candidate.equals(pkg)) return true;
        }
        return false;
    }

    private String appLabel(String pkg) {
        try {
            return getPackageManager().getApplicationLabel(
                getPackageManager().getApplicationInfo(pkg, 0)
            ).toString();
        } catch (Exception ignored) {
            return pkg;
        }
    }

    private Map<String, String> readChannels(StringBuilder report) {
        Map<String, String> names = new LinkedHashMap<>();

        try (Cursor c = getContentResolver().query(
                Uri.parse("content://android.media.tv/channel"),
                new String[] {"_id", "package_name", "display_name", "type"},
                null, null, null)) {

            if (c == null) return names;

            while (c.moveToNext()) {
                String pkg = text(c, "package_name");
                if (!wanted(pkg)) continue;

                // Excluir los canales de sintonización: aquí interesan
                // las filas de contenido de la pantalla de inicio.
                if (!"TYPE_PREVIEW".equals(text(c, "type"))) continue;

                String id = text(c, "_id");
                String name = text(c, "display_name");
                names.put(pkg + ":" + id, name);

                report.append("CANAL: ").append(appLabel(pkg))
                    .append(" / ").append(name)
                    .append(" [").append(id).append("]\n");
            }
            report.append('\n');
        } catch (Exception e) {
            report.append("Error al leer canales: ")
                .append(e.getClass().getSimpleName()).append('\n');
        }

        return names;
    }

    private void inspect(StringBuilder report, String table,
                         Map<String, String> channels, boolean watchNext) {
        report.append(watchNext
            ? "\nVER A CONTINUACIÓN\n"
            : "\nPROGRAMAS DE CANALES\n");

        Map<String, Stats> groups = new LinkedHashMap<>();

        // Proyección nula para detectar qué columnas ofrece realmente
        // este proveedor. Solo se analizan los campos indicados abajo;
        // no se guardan ni se muestran títulos, enlaces ni datos privados.
        try (Cursor c = getContentResolver().query(
                Uri.parse("content://android.media.tv/" + table),
                null, null, null, null)) {

            if (c == null) {
                report.append("Cursor nulo.\n");
                return;
            }

            String[] fields = {
                "last_playback_position_millis",
                "duration_millis",
                "season_display_number",
                "episode_display_number",
                "poster_art_uri",
                "intent_uri",
                "watch_next_type"
            };

            report.append("Columnas disponibles:\n");
            for (String field : fields) {
                if (c.getColumnIndex(field) >= 0) {
                    report.append("  ").append(field).append('\n');
                }
            }

            while (c.moveToNext()) {
                String pkg = text(c, "package_name");
                if (!wanted(pkg)) continue;

                String group = appLabel(pkg) + " [" + pkg + "]";

                if (!watchNext) {
                    String id = text(c, "channel_id");
                    String name = channels.get(pkg + ":" + id);
                    group += "\nCanal: "
                        + (name == null ? "(sin nombre disponible)" : name)
                        + " [" + id + "]";
                }

                Stats s = groups.get(group);
                if (s == null) {
                    s = new Stats();
                    groups.put(group, s);
                }

                s.total++;
                long position = number(c, "last_playback_position_millis");
                long duration = number(c, "duration_millis");

                if (duration > 0) s.duration++;
                if (position > 0 && duration > position) s.partial++;
                if (duration > 0 && position >= duration) s.atEnd++;

                if (!text(c, "episode_display_number").isEmpty()
                        || !text(c, "episode_number").isEmpty()) {
                    s.episode++;
                }

                if (!text(c, "poster_art_uri").isEmpty()) s.image++;
                if (!text(c, "intent_uri").isEmpty()) s.link++;

                if (watchNext) {
                    long type = number(c, "watch_next_type");
                    if (type == 0) s.continueType++;
                    if (type == 1) s.nextType++;
                    if (type == 2) s.newType++;
                    if (type == 3) s.watchlistType++;
                }
            }

            if (groups.isEmpty()) {
                report.append("Sin registros de las apps seleccionadas.\n");
            }

            for (Map.Entry<String, Stats> entry : groups.entrySet()) {
                Stats s = entry.getValue();
                report.append("\n").append(entry.getKey()).append('\n')
                    .append("  Registros: ").append(s.total).append('\n')
                    .append("  Con duración válida: ").append(s.duration).append('\n')
                    .append("  Con progreso entre inicio y final: ")
                    .append(s.partial).append('\n')
                    .append("  Posición igual o mayor a duración: ")
                    .append(s.atEnd).append('\n')
                    .append("  Con número de episodio: ").append(s.episode).append('\n')
                    .append("  Con URI de portada: ").append(s.image).append('\n')
                    .append("  Con URI de apertura: ").append(s.link).append('\n');

                if (watchNext) {
                    report.append("  Tipo continuar: ").append(s.continueType)
                        .append(" / siguiente: ").append(s.nextType).append('\n')
                        .append("  Tipo nuevo: ").append(s.newType)
                        .append(" / lista: ").append(s.watchlistType).append('\n');
                }
            }
        } catch (Exception e) {
            report.append("Error de consulta: ")
                .append(e.getClass().getSimpleName())
                .append(". No se muestran detalles privados.\n");
        }
    }

    private static String text(Cursor c, String column) {
        int i = c.getColumnIndex(column);
        if (i < 0 || c.isNull(i)) return "";
        try {
            String value = c.getString(i);
            return value == null ? "" : value.trim();
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static long number(Cursor c, String column) {
        int i = c.getColumnIndex(column);
        if (i < 0 || c.isNull(i)) return -1;
        try {
            return c.getLong(i);
        } catch (RuntimeException ignored) {
            return -1;
        }
    }
}
