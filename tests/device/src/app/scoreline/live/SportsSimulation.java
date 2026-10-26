package app.scoreline.live;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.*;
import org.json.*;
import java.lang.reflect.*;
import java.io.*;
import java.util.*;
import app.scoreline.model.*;
import app.scoreline.data.Espn;
import app.scoreline.core.Http;

/** Separate instrumentation APK; never included in the consumer app. No preferences are changed. */
public final class SportsSimulation extends Instrumentation {
    private int checks;
    private final List<String> failures = new ArrayList<>();
    private Bundle args;
    private static final int TEST_ID = 990001;
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); args=arguments; start(); }
    @Override public void onStart() {
        String held = args.getString("hold");
        if (held != null) { hold("OFF".equals(held) ? null : Sport.valueOf(held)); return; }
        Bundle result = new Bundle();
        try {
            modelTests();
            parserTests();
            renderTests();
            if ("true".equals(args.getString("preview"))) previews();
        } catch (Throwable e) { failures.add("Unexpected: " + android.util.Log.getStackTraceString(e)); }
        finally {
            getTargetContext().getSystemService(NotificationManager.class).cancel(TEST_ID);
            result.putString("stream", "\nChecks: " + checks + "\nFailures: " + failures.size() + "\n" + String.join("\n", failures));
            finish(failures.isEmpty() ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
        }
    }
    private void check(boolean ok, String message) { checks++; if(!ok) failures.add(message); }
    private Match fixture(Sport sport) {
        Match m=new Match(); m.id="SIMULATION"; m.sport=sport; m.state=Match.State.LIVE;
        m.start=System.currentTimeMillis()-3600_000; m.title="TEST event"; m.leagueName="SIMULATION";
        m.home=new Match.Side(new Team("home","TEST Home","Home","HOM","","","2464BC"),"2","",false);
        m.away=new Match.Side(new Team("away","TEST Away","Away","AWY","","","E23B4A"),"1","",false);
        m.period=2;m.clock="67'";
        switch(sport) {
            case CRICKET:
                m.league="test-series";m.oversLimit=20;m.period=2;m.battingTeamId="away";m.clock="16.2 ov";
                m.home=new Match.Side(m.home.team,"184/3","20",false);
                m.away=new Match.Side(m.away.team,"150/4","16.2",false);break;
            case BASKETBALL:
                m.league="nba";m.period=4;m.clock="Q4 2:30";m.clockSeconds=150;
                m.home=new Match.Side(m.home.team,"112","",false);m.away=new Match.Side(m.away.team,"108","",false);break;
            case FOOTBALL:m.league="nfl";m.period=4;m.clock="Q4 1:20";m.clockSeconds=80;break;
            case HOCKEY:m.league="nhl";m.period=3;m.clock="P3 2:00";m.clockSeconds=120;break;
            case BASEBALL:m.league="mlb";m.period=9;m.clock="Mid 9";break;
            case RACING: {
                m.league="f1";m.home=null;m.away=null;m.session="Race";m.laps=56;m.period=34;m.clock="Lap 34/56";
                String[][] grid={{"Max Verstappen","M. Verstappen","","3671C6"},{"Lando Norris","L. Norris","+2.3","FF8000"},
                        {"Charles Leclerc","C. Leclerc","+5.1","E8002D"},{"Lewis Hamilton","L. Hamilton","+8.0","E8002D"},{"Oscar Piastri","O. Piastri","+9.9","FF8000"}};
                for(int i=0;i<grid.length;i++){Match.Standing s=new Match.Standing(i+1,grid[i][0],grid[i][1],"");s.gap=grid[i][2];s.color=0xFF000000|Integer.parseInt(grid[i][3],16);m.standings.add(s);}
                break;
            }
            default:m.league="eng.1";
        }
        return m;
    }
    private void modelTests() {
        for (Sport sport:Sport.values()) for(Match.State state:Match.State.values()) {
            Match m=fixture(sport);m.state=state;Timeline t=Timeline.of(m);
            check(t.max>0 && t.value>=0 && t.value<=t.max,sport+" bounded "+state);
            check(state!=Match.State.PRE || t.value==0,sport+" upcoming progress");
            check(state!=Match.State.POST || t.value==t.max,sport+" final progress");
        }
        Match m=fixture(Sport.CRICKET);m.battingTeamId="unrecognised";
        check(Timeline.of(m).indeterminate,"Cricket unknown batting ID must not invent progress");
        m=fixture(Sport.CRICKET);m.away=new Match.Side(m.away.team,"150/4","16.8",false);
        check(Timeline.of(m).indeterminate,"Cricket invalid legal-ball notation must be indeterminate");
        m=fixture(Sport.CRICKET);m.period=0;
        check(Timeline.of(m).indeterminate,"Cricket missing innings must be indeterminate");
        m=fixture(Sport.SOCCER);m.clock="Break";m.period=2;
        check(Timeline.of(m).value==90,"Football end-of-regulation break must not reset progress");
        m=fixture(Sport.SOCCER);m.clock="93'";m.period=2;
        check(Timeline.of(m).max==90,"Added time is not extra time");
        m=fixture(Sport.BASEBALL);m.clock="Mid 9";
        check(Timeline.of(m).value==17,"Baseball middle inning");
        m.clock="End 9";check(Timeline.of(m).value==18,"Baseball end inning");
        m=fixture(Sport.BASKETBALL);m.period=6;m.clockSeconds=60;
        check(Timeline.of(m).max==58 && Timeline.of(m).value==57,"NBA double overtime");
        m.league="wnba";m.period=5;
        check(Timeline.of(m).max==45 && Timeline.of(m).value==44,"WNBA overtime");
    }
    private Object call(Class<?> type,String method,Class<?>[] sig,Object owner,Object... params) throws Exception {
        Method f=type.getDeclaredMethod(method,sig);f.setAccessible(true);return f.invoke(owner,params);
    }
    private Match parse(Sport sport,String name,String state,int period,int season) throws Exception {
        JSONObject status=new JSONObject().put("period",period).put("clock",120).put("displayClock","2:00")
                .put("type",new JSONObject().put("name",name).put("state",state).put("shortDetail",name));
        JSONArray teams=new JSONArray();
        for(String side:new String[]{"home","away"}) teams.put(new JSONObject().put("homeAway",side).put("score","2")
                .put("team",new JSONObject().put("id",side).put("displayName",side).put("abbreviation",side)));
        JSONObject e=new JSONObject().put("id","fixture").put("date","2026-10-24T12:00Z")
                .put("season",new JSONObject().put("type",season))
                .put("competitions",new JSONArray().put(new JSONObject().put("status",status).put("competitors",teams)));
        Espn espn=new Espn(new Http(new File(getContext().getCacheDir(),"http")));
        return (Match)call(Espn.class,"event",new Class<?>[]{JSONObject.class,Sport.class,String.class,String.class},espn,e,sport,sport==Sport.HOCKEY?"nhl":"nfl","Test");
    }
    private void parserTests() throws Exception {
        Match m=parse(Sport.HOCKEY,"STATUS_IN_PROGRESS","in",4,3);
        check(Timeline.of(m).max==80 && Timeline.of(m).value==78,"NHL playoffs uses 20-minute OT");
        m=parse(Sport.HOCKEY,"STATUS_IN_PROGRESS","in",4,2);
        check(Timeline.of(m).max==65 && Timeline.of(m).value==63,"NHL regular season OT");
        m=parse(Sport.FOOTBALL,"STATUS_IN_PROGRESS","in",5,3);
        check(Timeline.of(m).max==75 && Timeline.of(m).value==73,"NFL playoffs uses 15-minute OT");
        m=parse(Sport.FOOTBALL,"STATUS_IN_PROGRESS","in",5,2);
        check(Timeline.of(m).max==70 && Timeline.of(m).value==68,"NFL regular season OT");
        m=parse(Sport.HOCKEY,"STATUS_SHOOTOUT","in",5,2);
        check("SO".equals(m.clock) && Timeline.of(m).indeterminate,"NHL shootout parsed");
        m=parse(Sport.CRICKET,"STATUS_CANCELLED","post",0,2);
        check(m.state==Match.State.OFF,"Cancelled spelling recognised");
        JSONObject event=new JSONObject().put("id","weekend").put("name","TEST Grand Prix");
        JSONObject practice=new JSONObject().put("id","p3").put("date","2026-10-24T17:30Z")
                .put("type",new JSONObject().put("abbreviation","FP3"))
                .put("status",new JSONObject().put("type",new JSONObject().put("state","pre")));
        JSONObject race=new JSONObject(practice.toString()).put("id","race").put("type",new JSONObject().put("abbreviation","Race"));
        Class<?>[] sig={JSONObject.class,JSONObject.class,String.class,String.class};
        Match a=(Match)call(Espn.class,"race",sig,null,event,practice,"f1","F1");
        Match b=(Match)call(Espn.class,"race",sig,null,event,race,"f1","F1");
        check(!a.key().equals(b.key()),"F1 sessions have different keys");
        Class<?>[] gs={String.class,boolean.class};
        check("+13.0".equals(call(Espn.class,"gap",gs,null,"+13.078",true)) && "+0.112".equals(call(Espn.class,"gap",gs,null,"0.112",false))
                && "+1:02.3".equals(call(Espn.class,"gap",gs,null,"+1:02.345",true)) && "+1 Lap".equals(call(Espn.class,"gap",gs,null,"+1 Lap",true)),"F1 gaps: tenths in a race, thousandths otherwise");
        check("HUL".equals(new Match.Standing(9,"","N. H\u00fclkenberg","").code()) && "DEV".equals(new Match.Standing(9,"","N. de Vries","").code()),"F1 driver codes");
    }
    private PendingIntent noop() {
        return PendingIntent.getActivity(getTargetContext(),991,new Intent(getTargetContext(),app.scoreline.ui.HomeActivity.class),PendingIntent.FLAG_IMMUTABLE);
    }
    private IslandRenderer.Art art(Sport sport) {
        IslandRenderer.Art a=new IslandRenderer.Art();
        Bitmap logo=Bitmap.createBitmap(112,112,Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(logo);Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);p.setColor(Color.WHITE);p.setTextSize(58);p.setTypeface(Typeface.DEFAULT_BOLD);
        canvas.drawText("T",37,78,p);a.focus=logo;a.card=logo;return a;
    }
    private Notification notification(Match m,boolean moment) {
        return IslandRenderer.build(getTargetContext(),m,"home",art(m.sport),null,moment,noop(),noop());
    }
    private String chip(Notification n) {return n.extras.getString("android.shortCriticalText","");}
    private void renderTests() {
        for(Sport sport:Sport.values()) for(Match.State state:Match.State.values()) {
            Match m=fixture(sport);m.state=state;m.status=state==Match.State.OFF?"Cancelled":"Final";
            for(boolean rich:new boolean[]{false,true}) {
                Notification n=notification(m,rich);
                check(n.getSmallIcon()!=null,sport+" icon "+state);
                check(!n.extras.getCharSequence(Notification.EXTRA_TITLE,"").toString().isEmpty(),sport+" title "+state);
                check(n.extras.getString(Notification.EXTRA_TEMPLATE,"").contains("ProgressStyle"),sport+" progress template");
                if(state==Match.State.OFF) check(chip(n).toLowerCase(Locale.ROOT).contains("cancel"),sport+" cancelled chip is not live/final");
                Notification card=IslandRenderer.card(getTargetContext(),m,"home",art(sport),noop(),noop(),noop());
                check(!card.extras.getString(Notification.EXTRA_TEMPLATE,"").contains("ProgressStyle"),sport+" shade card cannot steal island");
            }
        }
        Match nba=fixture(Sport.BASKETBALL);
        check("112–108".equals(chip(notification(nba,false))),"NBA compact score preserves all digits without a long period suffix");
        Match m=fixture(Sport.RACING);String livePos=chip(notification(m,false));m.state=Match.State.POST;
        check("VER won".equals(chip(notification(m,false))),"F1 race result names the winner");
        m.session="Practice 3";check("FP3 VER".equals(chip(notification(m,false))) && !chip(notification(m,false)).equals(livePos),"F1 finished session is distinct from live P1");
        m=fixture(Sport.RACING);Notification race=notification(m,true);
        check("VER 34/56".equals(chip(race)),"F1 chip: leader and lap");
        check("Verstappen leads".equals(String.valueOf(race.extras.getCharSequence(Notification.EXTRA_TITLE))),"F1 title names the leader");
        check(String.valueOf(race.extras.getCharSequence(Notification.EXTRA_TEXT)).startsWith("1\u00a0VER \u00b7 2\u00a0NOR\u00a0+2.3 \u00b7 3\u00a0LEC\u00a0+5.1"),"F1 text: top five with gaps");
        check("VER".equals(String.valueOf(race.extras.getCharSequence("island.superx.leftInfo.content")))
                && "Lap 34/56".equals(String.valueOf(race.extras.getCharSequence("island.superx.rightInfo.capsuleContent"))),"F1 island: leader left, lap right");
        check("Lap 34/56".equals(String.valueOf(race.extras.getCharSequence(Notification.EXTRA_SUB_TEXT))) && race.getLargeIcon()!=null,"F1 header lap and leader tile");
        check(Timeline.of(m).max==56 && Timeline.of(m).value==33 && !Timeline.of(m).indeterminate,"F1 lap progress");
        m.laps=0;check("P1 VER".equals(chip(notification(m,false))) && Timeline.of(m).indeterminate,"F1 without the distance falls back to P1");
        m=fixture(Sport.CRICKET);m.battingTeamId="unknown";
        check(!chip(notification(m,false)).contains("184/3"),"Unknown cricket batting side must not show home as batting");
        m=fixture(Sport.BASKETBALL);m.updatesDelayed=true;
        check(notification(m,false).extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("Updates delayed"),"Offline label");
        Bitmap padded=Bitmap.createBitmap(160,160,Bitmap.Config.ARGB_8888);
        Canvas c=new Canvas(padded);Paint p=new Paint();p.setColor(Color.WHITE);c.drawRect(60,40,100,120,p);
        Bitmap badge=Badge.single(padded);
        int min=112,max=-1;for(int y=0;y<112;y++)for(int x=0;x<112;x++)if(Color.alpha(badge.getPixel(x,y))>24){min=Math.min(min,y);max=Math.max(max,y);}
        check(max-min>=100,"Logo transparent padding removed");
        check(Badge.pair(padded,null)!=null && Badge.pair(null,null)==null,"Missing logo fallback");
    }
    /**
     * {@code -e hold SOCCER}: posts that sport's live notification and leaves it up, so the shade
     * and status bar can be inspected by hand. The next hold replaces it; {@code -e hold OFF} clears it.
     */
    private void hold(Sport sport) {
        NotificationManager nm=getTargetContext().getSystemService(NotificationManager.class);
        runOnMainSync(() -> getTargetContext().stopService(new Intent(getTargetContext(), LiveService.class)));
        nm.cancel(TEST_ID);
        Bundle result=new Bundle();
        if (sport != null) {
            Match m=fixture(sport);
            if (sport==Sport.SOCCER) {
                m.moments.add(new Match.Moment(Match.Moment.Kind.GOAL,23,"23'","C. Palmer",true));
                m.moments.add(new Match.Moment(Match.Moment.Kind.GOAL,41,"41'","Son",false));
                m.moments.add(new Match.Moment(Match.Moment.Kind.PENALTY,54,"54'","N. Jackson",true));
            }
            Notification n=notification(m,false);
            n.extras.putCharSequence(Notification.EXTRA_TITLE,"TEST · "+n.extras.getCharSequence(Notification.EXTRA_TITLE));
            nm.notify(TEST_ID,n);
            result.putString("stream","Holding "+sport+": "+n.extras.getString("android.shortCriticalText","")+System.lineSeparator());
        }
        finish(Activity.RESULT_OK,result);
    }

    private void previews() throws Exception {
        NotificationManager nm=getTargetContext().getSystemService(NotificationManager.class);
        for(Sport sport:Sport.values()) {
            runOnMainSync(() -> getTargetContext().stopService(new Intent(getTargetContext(), LiveService.class)));
            Match m=fixture(sport);
            Notification n=notification(m,false);
            n.extras.putCharSequence(Notification.EXTRA_TITLE,"TEST ONLY - "+sport);
            // The T glyph and TEST ONLY title identify the simulation without changing chip width.
            nm.notify(TEST_ID,n);
            SystemClock.sleep(6500);
            Bitmap screen=getUiAutomation().takeScreenshot();
            if(screen!=null) {
                Bitmap top=Bitmap.createBitmap(screen,0,0,screen.getWidth(),Math.min(220,screen.getHeight()));
                File captureDir = new File(getTargetContext().getExternalFilesDir(null), "simulations");
                if (!captureDir.isDirectory() && !captureDir.mkdirs()) throw new IOException("Cannot create simulation capture folder");
                try(FileOutputStream out=new FileOutputStream(new File(captureDir,sport.name()+".png"))) {
                    top.compress(Bitmap.CompressFormat.PNG,100,out);
                }
                top.recycle();screen.recycle();
            }
        }
    }
}
