package test.gmsclient;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.firebase.messaging.FirebaseMessaging;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        TextView t = new TextView(this);
        GoogleApiAvailability api = GoogleApiAvailability.getInstance();
        int code = api.isGooglePlayServicesAvailable(this);
        t.setText("isGooglePlayServicesAvailable = " + code);
        setContentView(t);
        if (code != 0) api.getErrorDialog(this, code, 0).show();
        try {
            FirebaseMessaging.getInstance().getToken().addOnCompleteListener(task ->
                    t.append("\nFCM: " + (task.isSuccessful() ? task.getResult() : task.getException())));
        } catch (IllegalStateException e) {
            // No google-services.json in this test client: FCM is out of scope, only availability is tested.
            t.append("\nFCM: not configured");
        }
    }
}
