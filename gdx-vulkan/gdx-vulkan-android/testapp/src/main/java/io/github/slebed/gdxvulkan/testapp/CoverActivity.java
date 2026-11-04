package io.github.slebed.gdxvulkan.testapp;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;

/** An opaque activity that covers the one under it for a moment and finishes, which pauses that activity and destroys its surface. */
public class CoverActivity extends Activity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        View v = new View(this);
        v.setBackgroundColor(0xFF202020);
        setContentView(v);
        new Handler().postDelayed(new Runnable() {
            @Override public void run() { finish(); }
        }, 1500);
    }
}
