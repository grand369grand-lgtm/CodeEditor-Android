package com.codeeditor.app;

import android.os.Bundle;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.codeeditor.app.utils.PreferenceManager;

/**
 * Settings Activity - Configure editor preferences.
 * iOS-style grouped settings layout.
 */
public class SettingsActivity extends AppCompatActivity {

    private PreferenceManager prefManager;

    private Switch switchAutoSave;
    private Switch switchWordWrap;
    private Switch switchShowLineNumbers;
    private Switch switchShowMinimap;
    private Switch switchDarkMode;
    private Switch switchVibrateOnKey;
    private SeekBar seekbarFontSize;
    private SeekBar seekbarTabSize;
    private TextView tvFontSizeValue;
    private TextView tvTabSizeValue;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        prefManager = new PreferenceManager(this);

        initViews();
        loadSettings();
        setupListeners();

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.settings_title);
        }
    }

    private void initViews() {
        switchAutoSave = findViewById(R.id.switch_auto_save);
        switchWordWrap = findViewById(R.id.switch_word_wrap);
        switchShowLineNumbers = findViewById(R.id.switch_show_line_numbers);
        switchShowMinimap = findViewById(R.id.switch_show_minimap);
        switchDarkMode = findViewById(R.id.switch_dark_mode);
        switchVibrateOnKey = findViewById(R.id.switch_vibrate_on_key);
        seekbarFontSize = findViewById(R.id.seekbar_font_size);
        seekbarTabSize = findViewById(R.id.seekbar_tab_size);
        tvFontSizeValue = findViewById(R.id.tv_font_size_value);
        tvTabSizeValue = findViewById(R.id.tv_tab_size_value);
    }

    private void loadSettings() {
        switchAutoSave.setChecked(prefManager.isAutoSave());
        switchWordWrap.setChecked(prefManager.isWordWrap());
        switchShowLineNumbers.setChecked(prefManager.showLineNumbers());
        switchShowMinimap.setChecked(prefManager.showMinimap());
        switchDarkMode.setChecked(prefManager.isDarkMode());
        switchVibrateOnKey.setChecked(prefManager.vibrateOnKey());

        int fontSize = prefManager.getFontSize();
        seekbarFontSize.setProgress(fontSize - 10);
        tvFontSizeValue.setText(fontSize + "sp");

        int tabSize = prefManager.getTabSize();
        seekbarTabSize.setProgress((tabSize / 2) - 1);
        tvTabSizeValue.setText(tabSize + "");
    }

    private void setupListeners() {
        switchAutoSave.setOnCheckedChangeListener((b, checked) -> prefManager.setAutoSave(checked));
        switchWordWrap.setOnCheckedChangeListener((b, checked) -> prefManager.setWordWrap(checked));
        switchShowLineNumbers.setOnCheckedChangeListener((b, checked) -> prefManager.setShowLineNumbers(checked));
        switchShowMinimap.setOnCheckedChangeListener((b, checked) -> prefManager.setShowMinimap(checked));
        switchDarkMode.setOnCheckedChangeListener((b, checked) -> {
            prefManager.setDarkMode(checked);
            recreate();
        });
        switchVibrateOnKey.setOnCheckedChangeListener((b, checked) -> prefManager.setVibrateOnKey(checked));

        seekbarFontSize.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int size = progress + 10;
                tvFontSizeValue.setText(size + "sp");
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                prefManager.setFontSize(seekBar.getProgress() + 10);
            }
        });

        seekbarTabSize.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int size = (progress + 1) * 2;
                tvTabSizeValue.setText(size + "");
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                int size = (seekBar.getProgress() + 1) * 2;
                prefManager.setTabSize(size);
            }
        });
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}
