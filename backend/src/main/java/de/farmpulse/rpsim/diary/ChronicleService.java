package de.farmpulse.rpsim.diary;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

import de.farmpulse.rpsim.domain.DiaryEntry;
import de.farmpulse.rpsim.domain.DiaryEntryType;
import de.farmpulse.rpsim.domain.Milestone;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.finance.FarmReportService;
import de.farmpulse.rpsim.finance.FarmReportService.CategoryLine;
import de.farmpulse.rpsim.finance.FarmReportService.Report;
import de.farmpulse.rpsim.narration.FallbackTemplates;
import de.farmpulse.rpsim.repository.DiaryEntryRepository;
import de.farmpulse.rpsim.time.GameTime;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Roadmap V3 R3-T2: the farm chronicle (owner decisions in QUESTIONS.md) - head with farm name and game date, the
 * backstory, the milestones, all diary entries by game day (notes marked) and the key figures of every farm report
 * (K3). The backend builds it once as data: the Markdown file and the print view of the diary app show the same content.
 */
@Service
public class ChronicleService {

    /** Without a farm name and a map name. */
    public static final String DEFAULT_NAME = "FarmPulse";
    static final String BACKSTORY_CATEGORY = "BACKSTORY";

    public record Chronicle(String farmName, long gameTime, long gameDay, String backstory,
                            List<MilestoneLine> milestones, List<DayLine> days, List<ReportLine> reports) {
    }

    public record MilestoneLine(String key, String title, String text, long gameTime, long gameDay) {
    }

    public record DayLine(long gameDay, List<EntryLine> entries) {
    }

    /** {@code note} = free player note (purely narrative). */
    public record EntryLine(long gameTime, String entryType, String title, String text, boolean note) {
    }

    public record AmountLine(String label, long amount) {
    }

    /** {@code fruit} is the German label; {@code yieldLiters} null = not recorded. */
    public record FieldLine(int farmlandId, String fruit, Double hectares, boolean harvested, boolean withered,
                            Long yieldLiters) {
    }

    /** {@code taxStatus} OPEN / ASSESSED, null without a tax year; profit and tax only when assessed. */
    public record ReportLine(int year, int months, long operatingIncome, long operatingExpense, long operatingResult,
                             String taxStatus, Long profit, Long tax, List<AmountLine> income,
                             List<AmountLine> expenses, List<FieldLine> fields) {
    }

    private final DiaryEntryRepository entries;
    private final MilestoneService milestones;
    private final FarmReportService reports;
    private final FallbackTemplates labels;
    private final Properties categories = new Properties();

    public ChronicleService(DiaryEntryRepository entries, MilestoneService milestones, FarmReportService reports,
                            FallbackTemplates labels) {
        this.entries = entries;
        this.milestones = milestones;
        this.reports = reports;
        this.labels = labels;
        try (InputStream in = new ClassPathResource("chronicle/de.properties").getInputStream()) {
            categories.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Farm name of the settings, else the map name, else {@value #DEFAULT_NAME}. */
    public static String farmName(Savegame sg) {
        if (sg.getFarmName() != null && !sg.getFarmName().isBlank()) {
            return sg.getFarmName().strip();
        }
        return sg.getMapName() != null && !sg.getMapName().isBlank() ? sg.getMapName().strip() : DEFAULT_NAME;
    }

    /** {@code chronik-<name>.md}, every run of other characters than letters, digits, "_" and "-" replaced by "-". */
    public static String fileName(Savegame sg) {
        String name = farmName(sg).replaceAll("[^\\p{L}\\p{N}_-]+", "-").replaceAll("^-+|-+$", "");
        return "chronik-" + (name.isEmpty() ? DEFAULT_NAME : name) + ".md";
    }

    @Transactional(readOnly = true)
    public Chronicle build(Savegame sg) {
        List<DiaryEntry> all = entries.findBySavegameOrderByGameTimeAscIdAsc(sg);
        String backstory = all.stream().filter(e -> BACKSTORY_CATEGORY.equals(e.getCategory())).findFirst()
                .map(DiaryEntry::getText).orElse(null);

        List<MilestoneLine> ms = new ArrayList<>();
        for (Milestone m : milestones.list(sg)) {
            DiaryEntry e = m.getDiaryEntryId() == null ? null : entries.findById(m.getDiaryEntryId()).orElse(null);
            ms.add(new MilestoneLine(m.getMilestoneKey().name(), e == null ? m.getMilestoneKey().name() : e.getTitle(),
                    e == null ? null : e.getText(), m.getReachedGameTime(), GameTime.dayIndex(m.getReachedGameTime())));
        }

        List<DayLine> days = new ArrayList<>();
        for (DiaryEntry e : all) {
            if (BACKSTORY_CATEGORY.equals(e.getCategory())) {
                continue;
            }
            long day = GameTime.dayIndex(e.getGameTime());
            if (days.isEmpty() || days.get(days.size() - 1).gameDay() != day) {
                days.add(new DayLine(day, new ArrayList<>()));
            }
            days.get(days.size() - 1).entries().add(new EntryLine(e.getGameTime(), e.getEntryType().name(), e.getTitle(),
                    e.getText(), e.getEntryType() == DiaryEntryType.PLAYER_NOTE));
        }

        List<ReportLine> rs = reports.list(sg).stream().sorted(Comparator.comparingInt(Report::year))
                .map(this::report).toList();
        long now = sg.getCurrentGameTime();
        return new Chronicle(farmName(sg), now, GameTime.dayIndex(now), backstory, ms, days, rs);
    }

    private ReportLine report(Report r) {
        return new ReportLine(r.year(), r.months(), r.totals().operatingIncome(), r.totals().operatingExpense(),
                r.totals().operatingResult(), r.tax() == null ? null : r.tax().status(),
                r.tax() == null ? null : r.tax().profit(), r.tax() == null ? null : r.tax().tax(),
                amounts(r.income()), amounts(r.expenses()),
                r.fields().stream().map(f -> new FieldLine(f.farmlandId(), labels.label(f.fruitType()), f.hectares(),
                        f.harvested(), f.withered(), f.yieldLiters())).toList());
    }

    private List<AmountLine> amounts(List<CategoryLine> lines) {
        return lines.stream().map(l -> new AmountLine(categories.getProperty(l.category(), l.category()), l.amount()))
                .toList();
    }

    // ------------------------------------------------------------------------------------------ Markdown

    @Transactional(readOnly = true)
    public String markdown(Savegame sg) {
        return markdown(build(sg));
    }

    public static String markdown(Chronicle c) {
        StringBuilder md = new StringBuilder();
        md.append("# Hofchronik – ").append(c.farmName()).append("\n\n");
        md.append("Stand: ").append(time(c.gameTime())).append("\n\n");

        md.append("## Vorgeschichte\n\n");
        md.append(c.backstory() == null || c.backstory().isBlank() ? "_Keine Vorgeschichte erfasst._" : c.backstory().strip())
                .append("\n\n");

        md.append("## Meilensteine\n\n");
        if (c.milestones().isEmpty()) {
            md.append("_Noch keine Meilensteine erreicht._\n\n");
        } else {
            for (MilestoneLine m : c.milestones()) {
                md.append("- Tag ").append(m.gameDay()).append(": **").append(m.title()).append("**");
                if (m.text() != null && !m.text().isBlank()) {
                    md.append(" – ").append(m.text().strip());
                }
                md.append('\n');
            }
            md.append('\n');
        }

        md.append("## Tagebuch\n\n");
        if (c.days().isEmpty()) {
            md.append("_Noch keine Einträge._\n\n");
        }
        for (DayLine d : c.days()) {
            md.append("### Tag ").append(d.gameDay()).append("\n\n");
            for (EntryLine e : d.entries()) {
                md.append("- ").append(clock(e.gameTime())).append(" – **").append(e.title()).append("**");
                if (e.note()) {
                    md.append(" _(eigene Notiz)_");
                } else if ("MILESTONE".equals(e.entryType())) {
                    md.append(" _(Meilenstein)_");
                }
                md.append('\n');
                if (e.text() != null && !e.text().isBlank()) {
                    for (String line : e.text().strip().split("\\R")) {
                        md.append("  ").append(line).append('\n');
                    }
                }
            }
            md.append('\n');
        }

        md.append("## Jahresberichte\n\n");
        if (c.reports().isEmpty()) {
            md.append("_Noch kein Jahresbericht._\n");
        }
        for (ReportLine r : c.reports()) {
            md.append("### Jahr ").append(r.year()).append("\n\n");
            md.append("Erfasste Monate: ").append(r.months()).append("\n\n");
            md.append("| Kennzahl | Betrag |\n| --- | ---: |\n");
            md.append("| Einnahmen | ").append(euro(r.operatingIncome())).append(" |\n");
            md.append("| Ausgaben | ").append(euro(r.operatingExpense())).append(" |\n");
            md.append("| Betriebsergebnis | ").append(euro(r.operatingResult())).append(" |\n");
            md.append("| Gewinn (Steuer) | ").append(r.profit() == null ? open(r) : euro(r.profit())).append(" |\n");
            md.append("| Steuer | ").append(r.tax() == null ? open(r) : euro(r.tax())).append(" |\n\n");
            if (!r.income().isEmpty() || !r.expenses().isEmpty()) {
                md.append("#### Einnahmen und Ausgaben je Kategorie\n\n| Kategorie | Betrag |\n| --- | ---: |\n");
                for (AmountLine l : r.income()) {
                    md.append("| ").append(cell(l.label())).append(" | ").append(euro(l.amount())).append(" |\n");
                }
                for (AmountLine l : r.expenses()) {
                    md.append("| ").append(cell(l.label())).append(" | ").append(euro(l.amount())).append(" |\n");
                }
                md.append('\n');
            }
            if (!r.fields().isEmpty()) {
                md.append("#### Felder\n\n| Feld | Kultur | Fläche | Ertrag |\n| --- | --- | ---: | ---: |\n");
                for (FieldLine f : r.fields()) {
                    md.append("| Feld ").append(f.farmlandId()).append(" | ").append(cell(f.fruit())).append(" | ")
                            .append(f.hectares() == null ? "–" : decimal(f.hectares()) + " ha").append(" | ")
                            .append(fieldYield(f)).append(" |\n");
                }
                md.append('\n');
            }
        }
        return md.toString().stripTrailing() + "\n";
    }

    private static String open(ReportLine r) {
        return r.taxStatus() == null ? "–" : "offen";
    }

    private static String fieldYield(FieldLine f) {
        if (f.withered()) {
            return "verdorrt";
        }
        if (!f.harvested()) {
            return "nicht geerntet";
        }
        return f.yieldLiters() == null ? "geerntet" : NumberFormat.getIntegerInstance(Locale.GERMANY)
                .format(f.yieldLiters()) + " l";
    }

    private static String cell(String s) {
        return s == null ? "–" : s.replace("|", "\\|");
    }

    static String euro(long amount) {
        return NumberFormat.getIntegerInstance(Locale.GERMANY).format(amount) + " €";
    }

    private static String decimal(double v) {
        NumberFormat f = NumberFormat.getNumberInstance(Locale.GERMANY);
        f.setMaximumFractionDigits(1);
        return f.format(v);
    }

    /** "Tag 12, 08:30" like the diary app. */
    static String time(long gameTime) {
        return "Tag " + GameTime.dayIndex(gameTime) + ", " + clock(gameTime);
    }

    static String clock(long gameTime) {
        long inDay = Math.floorMod(gameTime, GameTime.days(1));
        return String.format(Locale.ROOT, "%02d:%02d", inDay / 3_600_000, (inDay % 3_600_000) / 60_000);
    }
}
