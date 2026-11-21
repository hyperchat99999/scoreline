package app.scoreline.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Optional supporting information, separate from the frequently refreshed score. */
public final class MatchDetail {
    public final List<Stat> stats;
    /** Provider's match recap description, never a full scraped article. */
    public final String summary;
    public final String link;

    public MatchDetail(List<Stat> stats, String summary, String link) {
        this.stats = Collections.unmodifiableList(new ArrayList<>(stats));
        this.summary = summary;
        this.link = link;
    }

    public static final class Stat {
        public final String key;
        public final String label;
        public final String home;
        public final String away;

        public Stat(String label, String home, String away) { this("", label, home, away); }

        public Stat(String key, String label, String home, String away) {
            this.key = key == null ? "" : key;
            this.label = label;
            this.home = home;
            this.away = away;
        }

        /** Comparable magnitude; unknown compound formats retain their text and stay neutral. */
        public double comparisonValue(String raw) {
            String value = raw == null ? "" : raw.trim().replace('\u2212', '-');
            if (key.equals("streak")) {
                if (!value.matches("[WL]\\d+")) return Double.NaN;
                double count = Double.parseDouble(value.substring(1));
                return value.charAt(0) == 'L' ? -count : count;
            }
            if (key.equals("Last Ten Games")) {
                if (!value.matches("\\d+-\\d+")) return Double.NaN;
                String[] record = value.split("-");
                double wins = Double.parseDouble(record[0]), losses = Double.parseDouble(record[1]);
                return wins + losses == 0 ? Double.NaN : wins / (wins + losses);
            }
            if (key.equals("possessionTime") && value.matches("\\d+:[0-5]\\d")) {
                String[] time = value.split(":");
                return Double.parseDouble(time[0]) * 60 + Double.parseDouble(time[1]);
            }
            if (isRate() && value.matches("\\d+[-/]\\d+")) {
                String[] parts = value.split("[-/]");
                double made = Double.parseDouble(parts[0]), attempts = Double.parseDouble(parts[1]);
                return attempts == 0 || made > attempts ? Double.NaN : made / attempts;
            }
            if (!value.matches("[+-]?(?:\\d+(?:\\.\\d+)?|\\.\\d+)%?")) return Double.NaN;
            double number = Double.parseDouble(value.replace("%", ""));
            return Double.isFinite(number) ? number : Double.NaN;
        }

        /** Formats whose components have the explicit meaning made/successful out of attempts. */
        public boolean isRate() {
            switch (key) {
                case "fieldGoalsMadeAttempted": case "threePointFieldGoalsMadeAttempted":
                case "freeThrowsMadeAttempted": case "thirdDownEff": case "fourthDownEff":
                case "powerPlay": return true;
                default: return false;
            }
        }

        public boolean isSignedComparison() {
            return key.equals("streak") || key.equals("goalDifference")
                    || comparisonValue(home) < 0 || comparisonValue(away) < 0;
        }

        public boolean isCountAndYards() {
            return key.equals("sacksYardsLost") || key.equals("totalPenaltiesYards");
        }

        /** Provider stat names where fewer is better. */
        public boolean lowerIsBetter() {
            switch (key) {
                case "goalsConceded": case "foulsCommitted": case "fouls": case "yellowCards": case "redCards":
                case "offsides": case "turnovers": case "totalTurnovers": case "teamTurnovers": case "penaltyMinutes":
                case "technicalFouls": case "totalTechnicalFouls": case "flagrantFouls":
                case "avgPointsAgainst": case "avgTotalTurnovers": case "avgTeamTurnovers":
                case "interceptions": case "fumblesLost": case "sacksYardsLost": case "totalPenaltiesYards":
                case "totalPointsPerGameAllowed": case "yardsPerGameAllowed": case "passingYardsPerGameAllowed": case "rushingYardsPerGameAllowed":
                case "avgGoalsAgainst": case "avgShotsAgainst": case "powerPlayGoalsAgainst": case "shortHandedGoalsAgainst":
                case "giveaways": case "penalties":
                case "batting.strikeouts": case "batting.runnersLeftOnBase":
                case "pitching.ERA": case "pitching.earnedRuns": case "pitching.walks": case "pitching.hits":
                case "fielding.errors":
                    return true;
                default:
                    return false;
            }
        }
    }
}
