package com.bubbleladder.triplehedge;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.*;

public final class GameCore {
    private GameCore() {}

    public static final String API = "https://api.bepick.io/game/bubble_ladder3";
    public static final String PREF = "bubble_single_pick_v2";
    public static final String ACTION_UPDATED = "com.bubbleladder.triplehedge.UPDATED";

    public static final String K_HISTORY = "history";
    public static final String K_RECORDS = "records";
    public static final String K_BASE_STAKE = "base_stake";
    public static final String K_ODDS = "odds";
    public static final String K_LOW_MODE = "low_mode"; // PASS or BASE
    public static final String K_MARTIN_STAGE = "martin_stage"; // 0 base, 1 after one loss, 2 after two losses
    public static final String K_PENDING_IDX = "pending_idx";
    public static final String K_PENDING_DIM = "pending_dim";
    public static final String K_PENDING_PICK = "pending_pick";
    public static final String K_PENDING_ACTION = "pending_action";
    public static final String K_PENDING_STAKE = "pending_stake";
    public static final String K_PENDING_ODDS = "pending_odds";
    public static final String K_PENDING_STAGE = "pending_stage";
    public static final String K_PENDING_ZONE = "pending_zone";
    public static final String K_PENDING_SCORE = "pending_score";
    public static final String K_LIVE_BETS = "live_bets";
    public static final String K_LIVE_WINS = "live_wins";
    public static final String K_LIVE_PROFIT_BITS = "live_profit_bits";
    public static final String K_PASS_COUNT = "pass_count";
    public static final String K_PRED_TOTAL = "pred_total";
    public static final String K_PRED_HIT = "pred_hit";
    public static final String K_LAST_PICK = "last_pick";
    public static final String K_LAST_SCORE = "last_score";
    public static final String K_LAST_ZONE = "last_zone";
    public static final String K_LAST_BET = "last_bet";
    public static final String K_LAST_SYNC = "last_sync";

    public static final int DIM_LR = 0;
    public static final int DIM_LINES = 1;
    public static final int DIM_OE = 2;
    private static final int MODEL_COUNT = 10;
    private static final int MAX_HISTORY = 5000;
    private static final int BT_LIMIT = 360;

    public static final String[] DIM_NAME = {"좌/우", "3줄/4줄", "홀/짝"};
    public static final String[][] VALUE_NAME = {{"좌","우"},{"3줄","4줄"},{"홀","짝"}};
    public static final String[] MODEL_NAME = {
            "최근6", "최근10", "최근20", "최근30", "EWMA",
            "전이1", "전이2", "연속/교차", "Regime", "직전조합조건"
    };

    public static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static class Result {
        public long idx;
        public String date;
        public int round;
        public int combo;
        public int value(int dim) {
            if (dim == DIM_LR) return (combo == 1 || combo == 2) ? 0 : 1;
            if (dim == DIM_LINES) return (combo == 1 || combo == 3) ? 0 : 1;
            return (combo == 2 || combo == 3) ? 0 : 1; // 홀=0, 짝=1
        }
    }

    public static class ModelPerf {
        public int id, n, hit, rn, rhit;
        public double weight;
    }

    public static class DimAnalysis {
        public int dim;
        public int pick;
        public double pPrimary;
        public double confidence;
        public double quality;
        public int btN, btHit, recentN, recentHit;
        public ModelPerf[] perf;
        public String label() { return DIM_NAME[dim] + " · " + VALUE_NAME[dim][pick]; }
        public double btRate() { return btN == 0 ? .5 : (double)btHit / btN; }
        public double recentRate() { return recentN == 0 ? btRate() : (double)recentHit / recentN; }
    }

    public static class Analysis {
        public DimAnalysis[] dims;
        public DimAnalysis best;
        public String zone; // STRONG / NORMAL / LOW
    }

    public static class BetPlan {
        public String action; // BET/PASS
        public int amount;
        public int pendingStage;
        public String label;
        public boolean resetChainNow;
    }

    public static class SyncResult {
        public boolean newRoundResolved;
        public Analysis analysis;
        public List<Result> history;
    }

    public static List<Result> fetch() throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(API).openConnection();
        c.setRequestMethod("GET");
        c.setConnectTimeout(12000);
        c.setReadTimeout(12000);
        c.setUseCaches(false);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("User-Agent", "BubbleSinglePick/2.0");
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) throw new Exception("API HTTP " + code);
        BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        c.disconnect();

        JSONObject root = new JSONObject(sb.toString());
        JSONArray arr = root.optJSONArray("data");
        if (arr == null) throw new Exception("API data 없음");
        List<Result> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            int combo = o.optInt("fd4", 0);
            long idx = o.optLong("idx", 0);
            if (idx <= 0 || combo < 1 || combo > 4) continue;
            Result r = new Result();
            r.idx = idx;
            r.date = o.optString("date", "");
            r.round = o.optInt("round", 0);
            r.combo = combo;
            out.add(r);
        }
        out.sort((a,b)->Long.compare(b.idx,a.idx));
        if (out.isEmpty()) throw new Exception("결과 없음");
        return out;
    }

    public static List<Result> loadHistory(Context c) {
        List<Result> out = new ArrayList<>();
        String raw = prefs(c).getString(K_HISTORY, "");
        if (raw == null || raw.isEmpty()) return out;
        try {
            JSONArray a = new JSONArray(raw);
            for (int i=0;i<a.length();i++) {
                JSONObject o=a.optJSONObject(i); if(o==null) continue;
                Result r=new Result();
                r.idx=o.optLong("i",0); r.date=o.optString("d",""); r.round=o.optInt("r",0); r.combo=o.optInt("c",0);
                if(r.idx>0 && r.combo>=1 && r.combo<=4) out.add(r);
            }
        } catch(Exception ignored) {}
        out.sort((a,b)->Long.compare(b.idx,a.idx));
        return out;
    }

    public static void saveHistory(Context c, List<Result> list) {
        try {
            JSONArray a=new JSONArray();
            for(Result r:list){ JSONObject o=new JSONObject(); o.put("i",r.idx);o.put("d",r.date);o.put("r",r.round);o.put("c",r.combo);a.put(o); }
            prefs(c).edit().putString(K_HISTORY,a.toString()).apply();
        } catch(Exception ignored) {}
    }

    public static List<Result> merge(List<Result> oldList,List<Result> newList) {
        TreeMap<Long,Result> map=new TreeMap<>(Collections.reverseOrder());
        for(Result r:oldList) map.put(r.idx,r); for(Result r:newList) map.put(r.idx,r);
        List<Result> out=new ArrayList<>(map.values());
        if(out.size()>MAX_HISTORY) out=new ArrayList<>(out.subList(0,MAX_HISTORY));
        return out;
    }

    public static SyncResult sync(Context c) throws Exception {
        SharedPreferences sp=prefs(c);
        List<Result> before=loadHistory(c);
        long latestBefore=before.isEmpty() ? -1 : before.get(0).idx;
        List<Result> merged=merge(before,fetch());
        saveHistory(c,merged);
        boolean resolved=resolvePending(c,merged);
        Analysis a=analyze(merged);
        saveNextPrediction(c,merged,a);
        sp.edit().putLong(K_LAST_SYNC,System.currentTimeMillis()).apply();
        SyncResult sr=new SyncResult();
        sr.newRoundResolved = resolved || (!merged.isEmpty() && merged.get(0).idx != latestBefore);
        sr.analysis=a; sr.history=merged;
        return sr;
    }

    private static void saveNextPrediction(Context c,List<Result> desc,Analysis a) {
        if(desc.isEmpty() || a==null || a.best==null) return;
        SharedPreferences sp=prefs(c);
        long nextIdx=nextIdx(desc.get(0));
        long existing=sp.getLong(K_PENDING_IDX,-1);
        if(existing==nextIdx) return;
        if(existing>0) return; // unresolved prediction stays until its result appears

        BetPlan plan=plan(c,a);
        if(plan.resetChainNow) sp.edit().putInt(K_MARTIN_STAGE,0).apply();
        int base=sp.getInt(K_BASE_STAKE,5000);
        float odds=sp.getFloat(K_ODDS,1.95f);
        String pickLabel=a.best.label();
        String zoneKo=zoneKo(a.zone);
        String betLabel=plan.action.equals("PASS") ? "PASS" : plan.label+" · "+money(plan.amount);

        sp.edit()
                .putLong(K_PENDING_IDX,nextIdx)
                .putInt(K_PENDING_DIM,a.best.dim)
                .putInt(K_PENDING_PICK,a.best.pick)
                .putString(K_PENDING_ACTION,plan.action)
                .putInt(K_PENDING_STAKE,plan.amount)
                .putFloat(K_PENDING_ODDS,odds)
                .putInt(K_PENDING_STAGE,plan.pendingStage)
                .putString(K_PENDING_ZONE,a.zone)
                .putFloat(K_PENDING_SCORE,(float)a.best.quality)
                .putString(K_LAST_PICK,pickLabel)
                .putFloat(K_LAST_SCORE,(float)a.best.quality)
                .putString(K_LAST_ZONE,zoneKo)
                .putString(K_LAST_BET,betLabel)
                .apply();
    }

    private static boolean resolvePending(Context c,List<Result> history) {
        SharedPreferences sp=prefs(c);
        long idx=sp.getLong(K_PENDING_IDX,-1);
        if(idx<=0) return false;
        Result actual=null;
        for(Result r:history) if(r.idx==idx){ actual=r; break; }
        if(actual==null) return false;

        int dim=sp.getInt(K_PENDING_DIM,0);
        int pick=sp.getInt(K_PENDING_PICK,0);
        String action=sp.getString(K_PENDING_ACTION,"BET");
        int amount=sp.getInt(K_PENDING_STAKE,0);
        double odds=sp.getFloat(K_PENDING_ODDS,1.95f);
        int pStage=sp.getInt(K_PENDING_STAGE,0);
        String zone=sp.getString(K_PENDING_ZONE,"NORMAL");
        double score=sp.getFloat(K_PENDING_SCORE,.5f);
        boolean hit=actual.value(dim)==pick;

        int predN=sp.getInt(K_PRED_TOTAL,0)+1;
        int predHit=sp.getInt(K_PRED_HIT,0)+(hit?1:0);
        SharedPreferences.Editor ed=sp.edit().putInt(K_PRED_TOTAL,predN).putInt(K_PRED_HIT,predHit);

        double pnl=0;
        int newStage=sp.getInt(K_MARTIN_STAGE,0);
        if("PASS".equals(action)) {
            ed.putInt(K_PASS_COUNT,sp.getInt(K_PASS_COUNT,0)+1);
        } else {
            int bets=sp.getInt(K_LIVE_BETS,0)+1;
            int wins=sp.getInt(K_LIVE_WINS,0)+(hit?1:0);
            pnl=hit ? amount*(odds-1.0) : -amount;
            double old=Double.longBitsToDouble(sp.getLong(K_LIVE_PROFIT_BITS,Double.doubleToLongBits(0)));
            ed.putInt(K_LIVE_BETS,bets).putInt(K_LIVE_WINS,wins).putLong(K_LIVE_PROFIT_BITS,Double.doubleToLongBits(old+pnl));

            if(hit) newStage=0;
            else if(pStage==0) newStage=1;
            else if(pStage==1) newStage=2;
            else newStage=0; // final strong 4x loss: hard reset
        }
        ed.putInt(K_MARTIN_STAGE,newStage);
        appendRecord(c,idx,dim,pick,action,amount,odds,hit,pnl,pStage,zone,score);

        ed.remove(K_PENDING_IDX).remove(K_PENDING_DIM).remove(K_PENDING_PICK).remove(K_PENDING_ACTION)
                .remove(K_PENDING_STAKE).remove(K_PENDING_ODDS).remove(K_PENDING_STAGE)
                .remove(K_PENDING_ZONE).remove(K_PENDING_SCORE).apply();
        return true;
    }

    private static void appendRecord(Context c,long idx,int dim,int pick,String action,int amount,double odds,boolean hit,double pnl,int stage,String zone,double score) {
        SharedPreferences sp=prefs(c);
        try {
            JSONArray arr=new JSONArray(sp.getString(K_RECORDS,"[]"));
            JSONObject o=new JSONObject();
            o.put("idx",idx);o.put("dim",dim);o.put("pick",pick);o.put("action",action);o.put("amount",amount);o.put("odds",odds);o.put("hit",hit);o.put("pnl",pnl);o.put("stage",stage);o.put("zone",zone);o.put("score",score);
            arr.put(o);
            JSONArray out=new JSONArray();
            int start=Math.max(0,arr.length()-1200); for(int i=start;i<arr.length();i++) out.put(arr.get(i));
            sp.edit().putString(K_RECORDS,out.toString()).apply();
        } catch(Exception ignored) {}
    }

    public static BetPlan plan(Context c,Analysis a) {
        SharedPreferences sp=prefs(c);
        int base=Math.max(1,sp.getInt(K_BASE_STAKE,5000));
        String lowMode=sp.getString(K_LOW_MODE,"PASS");
        int stage=sp.getInt(K_MARTIN_STAGE,0);
        boolean low="LOW".equals(a.zone), strong="STRONG".equals(a.zone);
        BetPlan p=new BetPlan(); p.resetChainNow=false;

        if(stage==0) {
            if(low && "PASS".equals(lowMode)) { p.action="PASS";p.amount=0;p.pendingStage=0;p.label="저신뢰 PASS"; }
            else { p.action="BET";p.amount=base;p.pendingStage=0;p.label="기본배팅"; }
            return p;
        }

        if(stage==1) {
            if(low) {
                if("PASS".equals(lowMode)) { p.action="PASS";p.amount=0;p.pendingStage=1;p.label="저신뢰 PASS · 마틴대기"; }
                else { p.action="BET";p.amount=base;p.pendingStage=0;p.label="저신뢰 기본배팅 · 마틴중단";p.resetChainNow=true; }
            } else {
                p.action="BET";p.amount=base*2;p.pendingStage=1;p.label="마틴 1단계 ×2";
            }
            return p;
        }

        // two consecutive losses reached. Only a strong zone may use the final 4x recovery.
        if(strong) {
            p.action="BET";p.amount=base*4;p.pendingStage=2;p.label="강승구간 최종마틴 ×4";
        } else {
            p.resetChainNow=true;
            if(low && "PASS".equals(lowMode)) { p.action="PASS";p.amount=0;p.pendingStage=0;p.label="2패후 저신뢰 PASS · 체인리셋"; }
            else { p.action="BET";p.amount=base;p.pendingStage=0;p.label="2패후 기본배팅 · 체인리셋"; }
        }
        return p;
    }

    public static Analysis analyze(List<Result> desc) {
        if(desc==null || desc.isEmpty()) return null;
        List<Result> asc=new ArrayList<>(desc); asc.sort(Comparator.comparingLong(r->r.idx));
        Analysis a=new Analysis(); a.dims=new DimAnalysis[3];
        for(int d=0;d<3;d++) a.dims[d]=analyzeDim(asc,d);
        a.best=a.dims[0];
        for(int d=1;d<3;d++) if(a.dims[d].quality>a.best.quality) a.best=a.dims[d];

        double conf=a.best.confidence;
        double rr=shrink(a.best.recentRate(),a.best.recentN,50,.5);
        double all=shrink(a.best.btRate(),a.best.btN,120,.5);
        if(conf>=.575 && rr>=.535 && a.best.quality>=.545 && all>=.515) a.zone="STRONG";
        else if(conf<.525 || rr<.495 || a.best.quality<.515) a.zone="LOW";
        else a.zone="NORMAL";
        return a;
    }

    private static DimAnalysis analyzeDim(List<Result> asc,int dim) {
        int end=asc.size();
        ModelPerf[] perf=modelPerf(asc,end,dim);
        double sumW=0,p0=0,wa=0,wr=0;
        for(ModelPerf m:perf){
            m.weight=modelWeight(m);
            double p=modelP0(asc,end,dim,m.id);
            p0+=p*m.weight; sumW+=m.weight;
            double ar=m.n==0?.5:(double)m.hit/m.n, rr=m.rn==0?ar:(double)m.rhit/m.rn;
            wa+=shrink(ar,m.n,100,.5)*m.weight;
            wr+=shrink(rr,m.rn,40,.5)*m.weight;
        }
        if(sumW<=0){sumW=1;p0=.5;wa=.5;wr=.5;} else {p0/=sumW;wa/=sumW;wr/=sumW;}
        DimBT bt=backtestEnsemble(asc,dim);
        double conf=Math.max(p0,1-p0);
        double btAll=shrink(bt.rate(),bt.n,120,.5), btRecent=shrink(bt.recentRate(),bt.rn,50,.5);
        double quality=.52*conf+.28*btRecent+.20*btAll;
        DimAnalysis d=new DimAnalysis();
        d.dim=dim;d.pPrimary=p0;d.pick=p0>=.5?0:1;d.confidence=conf;d.quality=quality;
        d.btN=bt.n;d.btHit=bt.hit;d.recentN=bt.rn;d.recentHit=bt.rhit;d.perf=perf;
        return d;
    }

    private static ModelPerf[] modelPerf(List<Result> a,int end,int dim) {
        ModelPerf[] out=new ModelPerf[MODEL_COUNT];
        @SuppressWarnings("unchecked") ArrayDeque<Boolean>[] q=new ArrayDeque[MODEL_COUNT];
        int[] rh=new int[MODEL_COUNT];
        for(int m=0;m<MODEL_COUNT;m++){out[m]=new ModelPerf();out[m].id=m;q[m]=new ArrayDeque<>();}
        int start=Math.max(12,end-BT_LIMIT);
        for(int t=start;t<end;t++){
            int actual=a.get(t).value(dim);
            for(int m=0;m<MODEL_COUNT;m++){
                int pick=modelP0(a,t,dim,m)>=.5?0:1; boolean hit=pick==actual;
                out[m].n++;if(hit)out[m].hit++;q[m].addLast(hit);if(hit)rh[m]++;
                if(q[m].size()>60 && q[m].removeFirst())rh[m]--;
            }
        }
        for(int m=0;m<MODEL_COUNT;m++){out[m].rn=q[m].size();out[m].rhit=rh[m];}
        return out;
    }

    private static double modelWeight(ModelPerf m) {
        double all=m.n==0?.5:(double)m.hit/m.n, rec=m.rn==0?all:(double)m.rhit/m.rn;
        double sa=shrink(all,m.n,90,.5), sr=shrink(rec,m.rn,35,.5);
        return clamp(1.0+9.0*(sa-.5)+7.0*(sr-.5),.20,2.60);
    }

    private static class DimBT { int n,hit,rn,rhit; double rate(){return n==0?.5:(double)hit/n;} double recentRate(){return rn==0?rate():(double)rhit/rn;} }

    private static DimBT backtestEnsemble(List<Result> a,int dim) {
        DimBT bt=new DimBT(); int end=a.size(),start=Math.max(20,end-BT_LIMIT);
        int[] n=new int[MODEL_COUNT],hit=new int[MODEL_COUNT],rh=new int[MODEL_COUNT];
        @SuppressWarnings("unchecked") ArrayDeque<Boolean>[] q=new ArrayDeque[MODEL_COUNT];
        for(int m=0;m<MODEL_COUNT;m++)q[m]=new ArrayDeque<>();
        ArrayDeque<Boolean> recent=new ArrayDeque<>(); int recentHits=0;
        for(int t=start;t<end;t++){
            double p0=0,sw=0;double[] pp=new double[MODEL_COUNT];int[] picks=new int[MODEL_COUNT];
            for(int m=0;m<MODEL_COUNT;m++){
                pp[m]=modelP0(a,t,dim,m);picks[m]=pp[m]>=.5?0:1;
                double ar=n[m]==0?.5:(double)hit[m]/n[m], rr=q[m].isEmpty()?ar:(double)rh[m]/q[m].size();
                double w=clamp(1+9*(shrink(ar,n[m],90,.5)-.5)+7*(shrink(rr,q[m].size(),35,.5)-.5),.20,2.60);
                p0+=pp[m]*w;sw+=w;
            }
            p0=sw==0?.5:p0/sw;int ensemblePick=p0>=.5?0:1;int actual=a.get(t).value(dim);boolean ok=ensemblePick==actual;
            bt.n++;if(ok)bt.hit++;recent.addLast(ok);if(ok)recentHits++;if(recent.size()>60&&recent.removeFirst())recentHits--;
            for(int m=0;m<MODEL_COUNT;m++){boolean mok=picks[m]==actual;n[m]++;if(mok)hit[m]++;q[m].addLast(mok);if(mok)rh[m]++;if(q[m].size()>60&&q[m].removeFirst())rh[m]--;}
        }
        bt.rn=recent.size();bt.rhit=recentHits;return bt;
    }

    private static double modelP0(List<Result>a,int end,int dim,int model) {
        if(end<=0)return .5;
        switch(model){
            case 0:return weighted(a,end,dim,6,1.25);
            case 1:return weighted(a,end,dim,10,1.05);
            case 2:return weighted(a,end,dim,20,.85);
            case 3:return weighted(a,end,dim,30,.60);
            case 4:return ewma(a,end,dim,.82);
            case 5:return transition1(a,end,dim);
            case 6:return transition2(a,end,dim);
            case 7:return streakAlt(a,end,dim);
            case 8:return regime(a,end,dim);
            case 9:return comboContext(a,end,dim);
            default:return .5;
        }
    }

    private static double weighted(List<Result>a,int end,int dim,int win,double power){double z0=1.5,z1=1.5;int s=Math.max(0,end-win),pos=1;for(int i=s;i<end;i++){double w=Math.pow(pos++,power);if(a.get(i).value(dim)==0)z0+=w;else z1+=w;}return clamp(z0/(z0+z1),.06,.94);}
    private static double ewma(List<Result>a,int end,int dim,double lambda){double p=.5,weight=1;int s=Math.max(0,end-50);for(int i=s;i<end;i++){double x=a.get(i).value(dim)==0?1:0;p=lambda*p+(1-lambda)*x;weight*=lambda;}return clamp(.5+(p-.5)*.92,.08,.92);}
    private static double transition1(List<Result>a,int end,int dim){if(end<2)return weighted(a,end,dim,12,1);int prev=a.get(end-1).value(dim);double z0=1.5,z1=1.5;for(int i=Math.max(1,end-900);i<end;i++)if(a.get(i-1).value(dim)==prev){if(a.get(i).value(dim)==0)z0++;else z1++;}return z0/(z0+z1);}
    private static double transition2(List<Result>a,int end,int dim){if(end<3)return transition1(a,end,dim);int x=a.get(end-2).value(dim),y=a.get(end-1).value(dim),matches=0;double z0=1.5,z1=1.5;for(int i=Math.max(2,end-1400);i<end;i++)if(a.get(i-2).value(dim)==x&&a.get(i-1).value(dim)==y){if(a.get(i).value(dim)==0)z0++;else z1++;matches++;}double p=z0/(z0+z1);return matches<6?.72*transition1(a,end,dim)+.28*p:p;}
    private static double streakAlt(List<Result>a,int end,int dim){if(end<3)return weighted(a,end,dim,10,1);int last=a.get(end-1).value(dim),st=1;for(int i=end-2;i>=0&&a.get(i).value(dim)==last&&st<5;i--)st++;double z0=1.5,z1=1.5;int matches=0;for(int i=Math.max(2,end-1200);i<end;i++){int prev=a.get(i-1).value(dim);if(prev!=last)continue;int s=1;for(int j=i-2;j>=0&&a.get(j).value(dim)==prev&&s<5;j--)s++;if(s==st){if(a.get(i).value(dim)==0)z0++;else z1++;matches++;}}double p=z0/(z0+z1);return matches<5?.75*weighted(a,end,dim,10,1)+.25*p:p;}
    private static double regime(List<Result>a,int end,int dim){double s=weighted(a,end,dim,8,1.1),m=weighted(a,end,dim,20,.8),l=weighted(a,end,dim,50,.3);double drift=Math.abs(s-l);double alpha=clamp(.45+1.8*drift,.45,.82);return clamp(alpha*s+(1-alpha)*(.65*m+.35*l),.07,.93);}
    private static double comboContext(List<Result>a,int end,int dim){if(end<2)return weighted(a,end,dim,15,1);int combo=a.get(end-1).combo;double z0=1.5,z1=1.5;int matches=0;for(int i=Math.max(1,end-1200);i<end;i++)if(a.get(i-1).combo==combo){if(a.get(i).value(dim)==0)z0++;else z1++;matches++;}double p=z0/(z0+z1);return matches<7?.65*transition1(a,end,dim)+.35*p:p;}

    private static double shrink(double rate,int n,double k,double base){return base+(rate-base)*(n/(n+k));}
    private static double clamp(double x,double lo,double hi){return Math.max(lo,Math.min(hi,x));}

    public static long nextIdx(Result r) {
        try {
            if(r.round<480) return Long.parseLong(r.date.substring(2,8)+String.format(Locale.US,"%04d",r.round+1));
            SimpleDateFormat f=new SimpleDateFormat("yyyyMMdd",Locale.US);Calendar c=Calendar.getInstance();c.setTime(f.parse(r.date));c.add(Calendar.DAY_OF_MONTH,1);String d=f.format(c.getTime());return Long.parseLong(d.substring(2,8)+"0001");
        }catch(Exception e){return r.idx+1;}
    }

    public static long millisToNextDraw() {
        long interval=180000L, now=System.currentTimeMillis(), mod=Math.floorMod(now,interval);
        long left=interval-mod; return left==0?interval:left;
    }
    public static String countdownText() { long s=(millisToNextDraw()+999)/1000; return String.format(Locale.KOREA,"%02d:%02d",s/60,s%60); }
    public static String money(long v){return String.format(Locale.KOREA,"%,d원",v);}
    public static String pct(double v){return String.format(Locale.KOREA,"%.1f%%",v*100);}
    public static String zoneKo(String z){return "STRONG".equals(z)?"강승구간":"LOW".equals(z)?"저신뢰":"일반구간";}

    public static String recentPredictionRate(Context c,int limit) {
        try {
            JSONArray a=new JSONArray(prefs(c).getString(K_RECORDS,"[]"));int n=0,h=0;
            for(int i=a.length()-1;i>=0&&n<limit;i--){JSONObject o=a.optJSONObject(i);if(o==null)continue;n++;if(o.optBoolean("hit",false))h++;}
            return n==0?"-":h+"/"+n+" ("+pct((double)h/n)+")";
        }catch(Exception e){return "-";}
    }

    public static String backupJson(Context c) throws Exception {
        SharedPreferences sp=prefs(c);JSONObject r=new JSONObject();r.put("format","BubbleSinglePickBackup");r.put("version",2);r.put("history",new JSONArray(sp.getString(K_HISTORY,"[]")));r.put("records",new JSONArray(sp.getString(K_RECORDS,"[]")));
        JSONObject s=new JSONObject();String[] strKeys={K_LOW_MODE,K_PENDING_ACTION,K_PENDING_ZONE,K_LAST_PICK,K_LAST_ZONE,K_LAST_BET};String[] intKeys={K_BASE_STAKE,K_MARTIN_STAGE,K_PENDING_DIM,K_PENDING_PICK,K_PENDING_STAKE,K_PENDING_STAGE,K_LIVE_BETS,K_LIVE_WINS,K_PASS_COUNT,K_PRED_TOTAL,K_PRED_HIT};String[] longKeys={K_PENDING_IDX,K_LIVE_PROFIT_BITS,K_LAST_SYNC};
        for(String k:strKeys)s.put(k,sp.getString(k,""));for(String k:intKeys)s.put(k,sp.getInt(k,0));for(String k:longKeys)s.put(k,sp.getLong(k,0));s.put(K_ODDS,sp.getFloat(K_ODDS,1.95f));s.put(K_PENDING_ODDS,sp.getFloat(K_PENDING_ODDS,1.95f));s.put(K_PENDING_SCORE,sp.getFloat(K_PENDING_SCORE,.5f));s.put(K_LAST_SCORE,sp.getFloat(K_LAST_SCORE,.5f));r.put("state",s);return r.toString(2);
    }

    public static void restoreJson(Context c,String raw) throws Exception {
        JSONObject r=new JSONObject(raw);if(!"BubbleSinglePickBackup".equals(r.optString("format")))throw new Exception("이 앱의 백업파일이 아닙니다.");SharedPreferences.Editor ed=prefs(c).edit();ed.clear();ed.putString(K_HISTORY,r.optJSONArray("history")!=null?r.getJSONArray("history").toString():"[]");ed.putString(K_RECORDS,r.optJSONArray("records")!=null?r.getJSONArray("records").toString():"[]");JSONObject s=r.optJSONObject("state");if(s!=null){Iterator<String> it=s.keys();while(it.hasNext()){String k=it.next();Object v=s.get(k);if(v instanceof Integer)ed.putInt(k,(Integer)v);else if(v instanceof Long)ed.putLong(k,(Long)v);else if(v instanceof Double)ed.putFloat(k,((Double)v).floatValue());else if(v instanceof Boolean)ed.putBoolean(k,(Boolean)v);else if(v instanceof String)ed.putString(k,(String)v);else if(v instanceof Number){if(k.equals(K_ODDS)||k.equals(K_PENDING_ODDS)||k.equals(K_PENDING_SCORE)||k.equals(K_LAST_SCORE))ed.putFloat(k,((Number)v).floatValue());else ed.putLong(k,((Number)v).longValue());}}}ed.apply();
    }
}
