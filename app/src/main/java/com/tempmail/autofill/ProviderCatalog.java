package com.tempmail.autofill;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class ProviderCatalog {
    public static final class ProviderSite {
        public final String name;
        public final String url;

        ProviderSite(String name, String url) {
            this.name = name;
            this.url = url;
        }
    }

    private static final List<ProviderSite> PROVIDERS = Collections.unmodifiableList(Arrays.asList(
            new ProviderSite("10MinuteMail", "https://10minutemail.com/"),
            new ProviderSite("Temp-Mail", "https://temp-mail.org/"),
            new ProviderSite("MinuteInbox", "https://www.minuteinbox.com/"),
            new ProviderSite("Mailporary", "https://mailporary.com/10minutemail"),
            new ProviderSite("Fake Email", "https://fake-email.pro/10-minute-mail"),
            new ProviderSite("Guerrilla Mail", "https://www.guerrillamail.com/"),
            new ProviderSite("Mailinator", "https://www.mailinator.com/"),
            new ProviderSite("Trash-Mail", "https://www.trash-mail.com/"),
            new ProviderSite("Mohmal", "https://www.mohmal.com/"),
            new ProviderSite("YOPmail", "https://www.yopmail.com/"),
            new ProviderSite("EmailOnDeck", "https://www.emailondeck.com/"),
            new ProviderSite("Tempail", "https://tempail.com/"),
            new ProviderSite("DropMail", "https://dropmail.me/"),
            new ProviderSite("TempMailo", "https://tempmailo.com/"),
            new ProviderSite("SmailPro", "https://smailpro.com/")
    ));

    private ProviderCatalog() {}

    public static int size() {
        return PROVIDERS.size();
    }

    public static ProviderSite get(int index) {
        if (PROVIDERS.isEmpty()) {
            throw new IllegalStateException("Provider catalog is empty");
        }
        return PROVIDERS.get(normalizeIndex(index));
    }

    public static int normalizeIndex(int index) {
        if (PROVIDERS.isEmpty()) {
            return 0;
        }
        int size = PROVIDERS.size();
        int normalized = index % size;
        return normalized < 0 ? normalized + size : normalized;
    }
}
