package de.farmpulse.rpsim.time;

/**
 * TODO T-08: the month length changed ("days per period" changed by the player, or the first real calendar replaced
 * the fallback). Scheduled dates stored in game ms are remapped: same month index, new month start.
 */
public record CalendarChangedEvent(Long savegameId, GameTime.Anchor previous, GameTime.Anchor current) {

    /** Maps a stored game time (a month start or a date within a month) onto the new calendar. */
    public long remap(long gameTime) {
        long index = previous.monthIndex(gameTime);
        long offset = gameTime - previous.monthStart(index);
        long newLength = current.msPerMonth();
        return current.monthStart(index) + Math.min(offset, newLength - 1);
    }

    public Long remap(Long gameTime) {
        return gameTime == null ? null : remap(gameTime.longValue());
    }
}
