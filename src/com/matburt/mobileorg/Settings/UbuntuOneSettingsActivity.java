package com.matburt.mobileorg.Settings;

import android.os.Bundle;
import android.preference.PreferenceActivity;

import com.matburt.mobileorg.R;

public class UbuntuOneSettingsActivity
{
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.ubuntuone_preferences);
    }
}