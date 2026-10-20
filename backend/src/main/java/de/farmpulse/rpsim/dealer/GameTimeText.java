package de.farmpulse.rpsim.dealer;

import java.text.NumberFormat;
import java.util.Locale;

import de.farmpulse.rpsim.time.GameTime;

/** Texts of the dealer's mails and hints: "Tag 14, 09:00" like the diary app, "2,5" days, "12.300 €". */
public final class GameTimeText {

    private GameTimeText() {
    }

    /** "Tag 14, 09:00". */
    public static String of(long gameTime) {
        long inDay = Math.floorMod(gameTime, GameTime.MS_PER_DAY);
        return "Tag " + GameTime.dayIndex(gameTime) + ", "
                + String.format(Locale.ROOT, "%02d:%02d", inDay / GameTime.MS_PER_HOUR, (inDay % GameTime.MS_PER_HOUR) / 60_000);
    }

    /** Days with at most one decimal ("2,5"). */
    public static String days(double days) {
        NumberFormat f = NumberFormat.getNumberInstance(Locale.GERMANY);
        f.setMaximumFractionDigits(1);
        return f.format(days);
    }

    /** "12.300 €". */
    public static String money(long amount) {
        return NumberFormat.getIntegerInstance(Locale.GERMANY).format(amount) + " €";
    }
}
