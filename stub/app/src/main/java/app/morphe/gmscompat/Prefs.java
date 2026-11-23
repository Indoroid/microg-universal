package app.morphe.gmscompat;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Set;
import java.util.TreeSet;

final class Prefs {
    private static final String KEEP_ALIVE = "keep_alive";
    private static final String CLIENTS = "clients";

    private Prefs() {
    }

    private static SharedPreferences sp(Context ctx) {
        return ctx.getSharedPreferences("stub", Context.MODE_PRIVATE);
    }

    static boolean keepAlive(Context ctx) {
        return sp(ctx).getBoolean(KEEP_ALIVE, true);
    }

    static void setKeepAlive(Context ctx, boolean value) {
        sp(ctx).edit().putBoolean(KEEP_ALIVE, value).apply();
    }

    static Set<String> clients(Context ctx) {
        return new TreeSet<>(sp(ctx).getStringSet(CLIENTS, new TreeSet<>()));
    }

    static boolean addClient(Context ctx, String pkg) {
        Set<String> s = clients(ctx);
        if (!s.add(pkg)) return false;
        sp(ctx).edit().putStringSet(CLIENTS, s).apply();
        return true;
    }
}
