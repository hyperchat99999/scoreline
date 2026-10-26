package app.scoreline.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import app.scoreline.model.Follow;
import app.scoreline.model.Sport;
import app.scoreline.model.Team;

/**
 * The curated starting point for discovery: popular competitions and teams per sport. Full team
 * lists are fetched live from ESPN when a competition is opened; this list only has to be good
 * enough to make onboarding instant. IDs were taken from ESPN's own team endpoints.
 */
public final class Catalog {
    private Catalog() {}

    public static final class Competition {
        public final Sport sport;
        public final String league;
        public final String name;
        public final String logo;
        public final String logoDark;
        /** Whether ESPN publishes a team list we can browse. */
        public final boolean browsable;

        Competition(Sport sport, String league, String name, String logo, String logoDark, boolean browsable) {
            this.sport = sport;
            this.league = league;
            this.name = name;
            this.logo = logo;
            this.logoDark = logoDark;
            this.browsable = browsable;
        }

        public Follow follow() {
            return Follow.competition(sport, league, name, logo, logoDark);
        }
    }

    private static final String LEAGUE = "https://a.espncdn.com/i/leaguelogos/soccer/500/";
    private static final String LEAGUE_DARK = "https://a.espncdn.com/i/leaguelogos/soccer/500-dark/";
    private static final String US = "https://a.espncdn.com/i/teamlogos/leagues/500/";
    private static final String US_DARK = "https://a.espncdn.com/i/teamlogos/leagues/500-dark/";

    public static final List<Competition> COMPETITIONS = List.of(
            soccer("uefa.champions", "Champions League", 2),
            soccer("eng.1", "Premier League", 23),
            soccer("esp.1", "LALIGA", 15),
            soccer("ita.1", "Serie A", 12),
            soccer("ger.1", "Bundesliga", 10),
            soccer("fra.1", "Ligue 1", 9),
            soccer("uefa.europa", "Europa League", 2310),
            soccer("usa.1", "MLS", 19),
            soccer("ksa.1", "Saudi Pro League", 2488),
            soccer("ind.1", "Indian Super League", 2334),
            soccer("fifa.world", "FIFA World Cup", 4),
            us(Sport.BASKETBALL, "nba", "NBA", true),
            us(Sport.BASKETBALL, "wnba", "WNBA", true),
            new Competition(Sport.RACING, "f1", "Formula 1", US + "f1.png", US + "f1.png", false),
            us(Sport.FOOTBALL, "nfl", "NFL", true),
            us(Sport.HOCKEY, "nhl", "NHL", true),
            us(Sport.BASEBALL, "mlb", "MLB", true));

    /** International men's sides. ESPN has no browsable list for cricket, so it lives here. */
    public static final List<Team> CRICKET_NATIONS = List.of(
            cricket("6", "India", "IND", "050ceb"),
            cricket("2", "Australia", "AUS", "f2c300"),
            cricket("1", "England", "ENG", "1c2c5b"),
            cricket("7", "Pakistan", "PAK", "01411c"),
            cricket("5", "New Zealand", "NZ", "1b1b1b"),
            cricket("3", "South Africa", "SA", "007a4d"),
            cricket("8", "Sri Lanka", "SL", "0b2d8c"),
            cricket("25", "Bangladesh", "BAN", "006a4e"),
            cricket("4", "West Indies", "WI", "7b0041"),
            cricket("40", "Afghanistan", "AFG", "0066b3"),
            cricket("29", "Ireland", "IRE", "169b62"),
            cricket("9", "Zimbabwe", "ZIM", "d40000"));

    /** Popular teams, in the order onboarding shows them. */
    public static final List<Follow> POPULAR = List.of(
            club("esp.1", "86", "Real Madrid", "Real Madrid", "RMA", "ffffff"),
            club("esp.1", "83", "Barcelona", "Barcelona", "BAR", "990000"),
            club("eng.1", "382", "Manchester City", "Man City", "MNC", "99c5ea"),
            club("eng.1", "364", "Liverpool", "Liverpool", "LIV", "d11317"),
            club("eng.1", "359", "Arsenal", "Arsenal", "ARS", "e20520"),
            club("eng.1", "363", "Chelsea", "Chelsea", "CHE", "144992"),
            club("eng.1", "360", "Manchester United", "Man United", "MAN", "da020e"),
            club("fra.1", "160", "Paris Saint-Germain", "PSG", "PSG", "011F68"),
            club("ger.1", "132", "Bayern Munich", "Bayern", "MUN", "dc052d"),
            club("ita.1", "111", "Juventus", "Juventus", "JUV", "000000"),
            club("ita.1", "110", "Internazionale", "Inter Milan", "INT", "00239c"),
            club("ita.1", "103", "AC Milan", "Milan", "MIL", "e4002b"),
            club("ger.1", "124", "Borussia Dortmund", "Dortmund", "DOR", "ffee00"),
            club("esp.1", "1068", "Atlético Madrid", "Atlético", "ATM", "ca3624"),
            club("eng.1", "367", "Tottenham Hotspur", "Spurs", "TOT", "ffffff"),
            club("usa.1", "20232", "Inter Miami CF", "Miami", "MIA", "231f20"),
            club("ksa.1", "817", "Al Nassr", "Al Nassr", "NSR", "f7f316"),
            Follow.team(Sport.CRICKET, "intl", CRICKET_NATIONS.get(0)),
            Follow.team(Sport.CRICKET, "intl", CRICKET_NATIONS.get(1)),
            Follow.team(Sport.CRICKET, "intl", CRICKET_NATIONS.get(2)),
            Follow.team(Sport.CRICKET, "intl", CRICKET_NATIONS.get(3)),
            franchise(Sport.BASKETBALL, "nba", "13", "Los Angeles Lakers", "Lakers", "LAL", "552583"),
            franchise(Sport.BASKETBALL, "nba", "9", "Golden State Warriors", "Warriors", "GS", "fdb927"),
            franchise(Sport.BASKETBALL, "nba", "2", "Boston Celtics", "Celtics", "BOS", "008348"),
            franchise(Sport.BASKETBALL, "nba", "4", "Chicago Bulls", "Bulls", "CHI", "ce1141"),
            franchise(Sport.BASKETBALL, "nba", "18", "New York Knicks", "Knicks", "NY", "1d428a"),
            franchise(Sport.FOOTBALL, "nfl", "12", "Kansas City Chiefs", "Chiefs", "KC", "e31837"),
            franchise(Sport.FOOTBALL, "nfl", "6", "Dallas Cowboys", "Cowboys", "DAL", "002a5c"),
            franchise(Sport.FOOTBALL, "nfl", "21", "Philadelphia Eagles", "Eagles", "PHI", "06424d"),
            franchise(Sport.HOCKEY, "nhl", "21", "Toronto Maple Leafs", "Maple Leafs", "TOR", "003e7e"),
            franchise(Sport.HOCKEY, "nhl", "6", "Edmonton Oilers", "Oilers", "EDM", "00205b"),
            franchise(Sport.BASEBALL, "mlb", "10", "New York Yankees", "Yankees", "NYY", "132448"),
            franchise(Sport.BASEBALL, "mlb", "19", "Los Angeles Dodgers", "Dodgers", "LAD", "005a9c"));

    public static List<Competition> competitions(Sport sport) {
        List<Competition> out = new ArrayList<>();
        for (Competition c : COMPETITIONS) if (c.sport == sport) out.add(c);
        return out;
    }

    public static List<Follow> popular(Sport sport) {
        if (sport == Sport.CRICKET) {
            List<Follow> out = new ArrayList<>();
            for (Team t : CRICKET_NATIONS) out.add(Follow.team(Sport.CRICKET, "intl", t));
            return out;
        }
        List<Follow> out = new ArrayList<>();
        for (Follow f : POPULAR) if (f.sport == sport) out.add(f);
        return out;
    }

    public static Competition competition(Sport sport, String league) {
        for (Competition c : COMPETITIONS) if (c.sport == sport && c.league.equals(league)) return c;
        return null;
    }

    /** Sports in the order Discover lists them. */
    public static List<Sport> sports() {
        List<Sport> out = new ArrayList<>(List.of(Sport.SOCCER, Sport.CRICKET, Sport.BASKETBALL, Sport.RACING,
                Sport.FOOTBALL, Sport.HOCKEY, Sport.BASEBALL));
        return Collections.unmodifiableList(out);
    }

    // ---- Builders ------------------------------------------------------------------------------

    private static Competition soccer(String slug, String name, int logoId) {
        return new Competition(Sport.SOCCER, slug, name, LEAGUE + logoId + ".png", LEAGUE_DARK + logoId + ".png", true);
    }

    private static Competition us(Sport sport, String slug, String name, boolean browsable) {
        return new Competition(sport, slug, name, US + slug + ".png", US_DARK + slug + ".png", browsable);
    }

    private static Team cricket(String id, String name, String abbr, String color) {
        String logo = "https://a.espncdn.com/i/teamlogos/cricket/500/" + id + ".png";
        return new Team(id, name, name, abbr, logo, "", color);
    }

    private static Follow club(String league, String id, String name, String shortName, String abbr, String color) {
        String logo = "https://a.espncdn.com/i/teamlogos/soccer/500/" + id + ".png";
        String dark = "https://a.espncdn.com/i/teamlogos/soccer/500-dark/" + id + ".png";
        return Follow.team(Sport.SOCCER, league, new Team(id, name, shortName, abbr, logo, dark, color));
    }

    private static Follow franchise(Sport sport, String league, String id, String name, String shortName, String abbr, String color) {
        String code = abbr.toLowerCase();
        String logo = "https://a.espncdn.com/i/teamlogos/" + league + "/500/" + code + ".png";
        String dark = "https://a.espncdn.com/i/teamlogos/" + league + "/500-dark/" + code + ".png";
        return Follow.team(sport, league, new Team(id, name, shortName, abbr, logo, dark, color));
    }
}
