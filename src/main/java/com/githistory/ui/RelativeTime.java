package com.githistory.ui;

import java.time.Duration;
import java.time.Instant;

/** Fechas relativas estilo GitKraken: "hace 13 h", "ayer", "hace 3 sem". */
public final class RelativeTime {

    private RelativeTime() {}

    public static String format(Instant instant) {
        Duration age = Duration.between(instant, Instant.now());
        if (age.isNegative()) {
            age = Duration.ZERO;
        }
        long minutes = age.toMinutes();
        if (minutes < 1) {
            return "ahora mismo";
        }
        if (minutes < 60) {
            return "hace " + minutes + " min";
        }
        long hours = age.toHours();
        if (hours < 24) {
            return "hace " + hours + " h";
        }
        long days = age.toDays();
        if (days == 1) {
            return "ayer";
        }
        if (days < 7) {
            return "hace " + days + " días";
        }
        long weeks = days / 7;
        if (weeks < 5) {
            return "hace " + weeks + " sem";
        }
        long months = days / 30;
        if (months < 12) {
            return "hace " + months + " meses";
        }
        return "hace " + (days / 365) + " años";
    }
}
