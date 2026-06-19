package com.tempmail.autofill;

import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class ProviderPreset {
    private static final String PREF_BROWSER_PROVIDER_INDEX = "browser_provider_index";

    private static final List<ProviderPreset> BROWSER_PRESETS;

    static {
        List<ProviderPreset> presets = new ArrayList<>();
        presets.add(new ProviderPreset("10 Minute Mail", "https://10minutemail.com/"));
        presets.add(new ProviderPreset("Temp Mail", "https://temp-mail.org/"));
        presets.add(new ProviderPreset("Minute Inbox", "https://www.minuteinbox.com/"));
        presets.add(new ProviderPreset("MailPory", "https://mailporary.com/10minutemail"));
        presets.add(new ProviderPreset("Fake Email", "https://fake-email.pro/10-minute-mail"));
        presets.add(new ProviderPreset("Guerrilla Mail", "https://www.guerrillamail.com/"));
        presets.add(new ProviderPreset("Mailinator", "https://www.mailinator.com/"));
        presets.add(new ProviderPreset("Trash Mail", "https://www.trash-mail.com/"));
        presets.add(new ProviderPreset("Mohmal", "https://www.mohmal.com/"));
        presets.add(new ProviderPreset("YOPmail", "https://www.yopmail.com/"));
        presets.add(new ProviderPreset("EmailOnDeck", "https://www.emailondeck.com/"));
        presets.add(new ProviderPreset("Tempail", "https://tempail.com/"));
        presets.add(new ProviderPreset("DropMail", "https://dropmail.me/"));
        presets.add(new ProviderPreset("TempMailo", "https://tempmailo.com/"));
        presets.add(new ProviderPreset("SmailPro", "https://smailpro.com/"));
        BROWSER_PRESETS = Collections.unmodifiableList(presets);
    }

    public final String name;
    public final String url;

    private ProviderPreset(String name, String url) {
        this.name = name;
        this.url = url;
    }

    public static List<ProviderPreset> getBrowserPresets() {
        return BROWSER_PRESETS;
    }

    public static int getBrowserPresetCount() {
        return BROWSER_PRESETS.size();
    }

    public static ProviderPreset getCurrentBrowserPreset(SharedPreferences prefs) {
        return getBrowserPresetAtIndex(getStoredBrowserIndex(prefs));
    }

    public static ProviderPreset getNextBrowserPreset(SharedPreferences prefs, boolean advance) {
        int count = getBrowserPresetCount();
        if (count == 0) {
            return null;
        }
        int currentIndex = getStoredBrowserIndex(prefs);
        int nextIndex = (currentIndex + 1) % count;
        if (advance && prefs != null) {
            prefs.edit().putInt(PREF_BROWSER_PROVIDER_INDEX, nextIndex).apply();
        }
        return getBrowserPresetAtIndex(nextIndex);
    }

    public static String buildBrowserRotationSummary(SharedPreferences prefs) {
        ProviderPreset activePreset = getCurrentBrowserPreset(prefs);
        if (activePreset == null) {
            return "";
        }
        int currentIndex = getStoredBrowserIndex(prefs) + 1;
        return String.format(Locale.US, "%s (%d/%d)", activePreset.name, currentIndex, getBrowserPresetCount());
    }

    private static ProviderPreset getBrowserPresetAtIndex(int index) {
        if (BROWSER_PRESETS.isEmpty()) {
            return null;
        }
        int safeIndex = ((index % BROWSER_PRESETS.size()) + BROWSER_PRESETS.size()) % BROWSER_PRESETS.size();
        return BROWSER_PRESETS.get(safeIndex);
    }

    private static int getStoredBrowserIndex(SharedPreferences prefs) {
        if (prefs == null || BROWSER_PRESETS.isEmpty()) {
            return 0;
        }
        int count = BROWSER_PRESETS.size();
        int savedIndex = prefs.getInt(PREF_BROWSER_PROVIDER_INDEX, 0);
        return ((savedIndex % count) + count) % count;
    }
}
