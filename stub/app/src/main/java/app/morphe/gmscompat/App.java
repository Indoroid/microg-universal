package app.morphe.gmscompat;

import android.app.Application;

import com.google.android.material.color.DynamicColors;

public final class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        // Material You: wallpaper-based colors on Android 12+, baseline M3 palette otherwise.
        DynamicColors.applyToActivitiesIfAvailable(this);
    }
}
