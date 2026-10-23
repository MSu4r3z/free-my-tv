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

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        output = new TextView(this);
        output.setTextColor(Color.WHITE);
        output.setTextSize(18);
        output.setPadding(40, 30, 40, 30);
        output.setText("Consultando datos publicados en Android TV…");

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(20, 20, 25));
        scroll.addView(output);
        setContentView(scroll);

        new Thread(() -> {
            StringBuilder report = new StringBuilder(
                    "DIAGNÓSTICO DE CONTENIDO\n"
                    + "Solo lectura. No se muestran títulos ni enlaces.\n\n");

            inspect(report, "channel", "Canales");
            inspect(report, "watch_next_program", "Ver a continuación");
            inspect(report, "preview_program", "Programas de canales");

            final String result = report.toString();
            runOnUiThread(() -> {
                if (!isFinishing() && !isDestroyed()) {
                    output.setText(result);
                }
            });
        }, "tv-data-diagnostic").start();
    }

    private void inspect(StringBuilder report, String path, String label) {
        report.append(label).append(":\n");

        Uri uri = Uri.parse("content://android.media.tv/" + path);
        String[] columns = {"_id", "package_name"};

        try (Cursor cursor = getContentResolver().query(
                uri, columns, null, null, null)) {

            if (cursor == null) {
                report.append("  El proveedor devolvió un cursor nulo.\n\n");
                return;
            }

            Map<String, Integer> counts = new LinkedHashMap<>();
            int packageColumn = cursor.getColumnIndexOrThrow("package_name");
            int total = 0;

            while (cursor.moveToNext()) {
                String pkg = cursor.getString(packageColumn);
                if (pkg == null) pkg = "(sin paquete)";
                Integer previous = counts.get(pkg);
                counts.put(pkg, previous == null ? 1 : previous + 1);
                total++;
            }

            report.append("  Registros visibles: ").append(total).append('\n');

            for (Map.Entry<String, Integer> entry : counts.entrySet()) {
                report.append("  ").append(entry.getKey())
                        .append(": ").append(entry.getValue()).append('\n');
            }
        } catch (SecurityException e) {
            report.append("  Acceso denegado: ")
                    .append(e.getMessage()).append('\n');
        } catch (Exception e) {
            report.append("  Error: ").append(e.getClass().getSimpleName())
                    .append(": ").append(e.getMessage()).append('\n');
        }

        report.append('\n');
    }
}
