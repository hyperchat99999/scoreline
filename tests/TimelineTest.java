import app.scoreline.model.*;
public class TimelineTest {
    public static void main(String[] args) {
        Match m = match(Sport.SOCCER); m.period=2; m.clock="93'";
        check(Timeline.of(m).max==90, "Stoppage time is not extra time");
        m.period=3; m.clock="105'"; check(Timeline.of(m).max==120, "Extra-time duration");
        m.clock="Pens"; check(Timeline.of(m).indeterminate, "Shootout has no time progress");
        m=match(Sport.BASKETBALL); m.league="nba"; m.period=4; m.clockSeconds=150;
        check(Timeline.of(m).value==45 && Timeline.of(m).max==48, "NBA Q4 countdown");
        m.period=5; m.clockSeconds=120;
        check(Timeline.of(m).value==51 && Timeline.of(m).max==53, "NBA overtime");
        m.league="wnba"; m.period=4; m.clockSeconds=0;
        check(Timeline.of(m).max==40, "WNBA regulation");
        m=match(Sport.BASEBALL);m.period=5;m.clock="Mid 5";
        check(Timeline.of(m).value==9,"Middle inning progress");
        m.clock="End 5";check(Timeline.of(m).value==10,"End inning progress");
        m=match(Sport.HOCKEY);m.clock="SO";
        check(Timeline.of(m).indeterminate,"Hockey shootout");
        m=match(Sport.CRICKET);m.oversLimit=20;m.period=2;m.battingTeamId="away";
        check(Timeline.of(m).value==183 && Timeline.of(m).max==240,"Cricket legal balls");
        m.battingTeamId="";check(Timeline.of(m).indeterminate,"Unknown batting side");
        m=match(Sport.RACING);check(Timeline.of(m).indeterminate,"No invented race progress");
        m.state=Match.State.POST;check(Timeline.of(m).value==Timeline.of(m).max,"Finished race");
        m=match(Sport.RACING);m.laps=56;m.period=34;
        check(!Timeline.of(m).indeterminate && Timeline.of(m).max==56 && Timeline.of(m).value==33,"Race laps run");
        m.period=60;check(Timeline.of(m).value==56,"Lap count past the distance is clamped");
        System.out.println("PASS: 15 sport progress regression checks");
    }
    static Match match(Sport sport) {
        Match m=new Match();m.sport=sport;m.state=Match.State.LIVE;
        m.home=new Match.Side(new Team("home","Home","Home","H","","",""),"100/2","10.3",false);
        m.away=new Match.Side(new Team("away","Away","Away","A","","",""),"100/2","10.3",false);
        return m;
    }
    static void check(boolean ok,String message) {if(!ok)throw new AssertionError(message);}
}
