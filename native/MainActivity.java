package de.fox.testpilot;

import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(FoxToolsPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
