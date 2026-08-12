package com.bubbleladder.triplehedgev2;

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

public final class TripleCore {
    private TripleCore() {}

    public static final String API = "https://api.bepick.io/game/bubble_ladder3";
    // Keep V1 preference name so an update preserves history/settings/live record.
    public static final String PREF = "bubble_triple_hedge_v1";
    public static final String ACTION_UPDATED = "com.bubbleladder.triplehedgev2.TRIPLE_UPDATED";

    public static final String K_HISTORY="history", K_PENDING_IDX="pending_idx", K_PENDING_EXCLUDE="pending_exclude",
            K_PENDING_STAKE="pending_stake", K_PENDING_ODDS="pending_odds", K_PENDING_GRADE="pending_grade",
            K_LIVE_TOTAL="live_total", K_LIVE_SUCCESS="live_success", K_LIVE_PROFIT="live_profit",
            K_BASE_STAKE="base_stake_v2", K_ODDS="odds_v2", K_RECORDS="records_v2", K_AUTO="auto_enabled_v2",
            K_LAST_EXCLUDE="last_exclude_v2", K_LAST_TRIPLE="last_triple_v2", K_LAST_GRADE="last_grade_v2",
            K_LAST_GAP="last_gap_v2", K_LAST_SYNC="last_sync_v2";

    public static final int MAX_HISTORY=5000, BT_LIMIT=700, ENGINE_COUNT=9, FAMILY_COUNT=3;
    public static final String[] COMBO={"","좌3짝","좌4홀","우3홀","우4짝"};
    public static final String[] ENGINE={
            "최근8 가중","최근15 가중","최근30 안정",
            "4상태 Markov-1","4상태 Markov-2","연속상태 조건","유사상황 검색",
            "Binary 2-Bit","Regime Adaptive"
    };
    public static final String[] FAMILY={"통계계열","패턴계열","상태계열"};
    // Statistical 0-2, Pattern 3-6, State 7-8.
    private static final int[] ENGINE_FAMILY={0,0,0,1,1,1,1,2,2};

    public static SharedPreferences prefs(Context c){ return c.getSharedPreferences(PREF, Context.MODE_PRIVATE); }

    public static final class Result { public long idx; public String date; public int round,combo; }
    public static final class Perf { public int n,hit,rn,rhit; public double weight; public double rate(){return n==0?.75:(double)hit/n;} public double recentRate(){return rn==0?rate():(double)rhit/rn;} }
    public static final class HedgeStats {
        public int n,hit,rn,rhit;
        public int[] gradeN=new int[3], gradeHit=new int[3]; // 0 weak,1 normal,2 strong
        public double rate(){return n==0?.75:(double)hit/n;}
        public double recentRate(){return rn==0?rate():(double)rhit/rn;}
    }
    public static final class Analysis {
        public double[] occurrence=new double[5];
        public double[] excludeScore=new double[5];
        public int exclude;
        public String triple,grade;
        public double scoreGap;
        public List<Integer> rank;
        public Perf[] enginePerf;
        public Perf[] familyPerf;
        public HedgeStats hedge;
    }
    public static final class SyncResult { public boolean newRoundResolved; public Analysis analysis; public List<Result> history; }

    public static List<Result> fetch() throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(API).openConnection();
        c.setRequestMethod("GET"); c.setConnectTimeout(12000); c.setReadTimeout(12000); c.setUseCaches(false);
        c.setRequestProperty("Accept","application/json"); c.setRequestProperty("User-Agent","BubbleTripleHedge/2.0");
        int code=c.getResponseCode(); if(code<200||code>=300) throw new Exception("API HTTP "+code);
        BufferedReader br=new BufferedReader(new InputStreamReader(c.getInputStream(),"UTF-8"));
        StringBuilder sb=new StringBuilder(); String line; while((line=br.readLine())!=null) sb.append(line); br.close(); c.disconnect();
        JSONObject root=new JSONObject(sb.toString()); JSONArray arr=root.optJSONArray("data"); if(arr==null) throw new Exception("API data 없음");
        List<Result> out=new ArrayList<>();
        for(int i=0;i<arr.length();i++){
            JSONObject o=arr.optJSONObject(i); if(o==null) continue;
            int combo=o.optInt("fd4",0); long idx=o.optLong("idx",0); if(idx<=0||combo<1||combo>4) continue;
            Result r=new Result(); r.idx=idx; r.date=o.optString("date",""); r.round=o.optInt("round",0); r.combo=combo; out.add(r);
        }
        out.sort((a,b)->Long.compare(b.idx,a.idx)); if(out.isEmpty()) throw new Exception("결과 없음"); return out;
    }

    public static List<Result> load(Context c){
        List<Result> out=new ArrayList<>(); String raw=prefs(c).getString(K_HISTORY,""); if(raw==null||raw.isEmpty()) return out;
        try{ JSONArray a=new JSONArray(raw); for(int i=0;i<a.length();i++){ JSONObject j=a.optJSONObject(i); if(j==null) continue; Result r=new Result(); r.idx=j.optLong("i");r.date=j.optString("d");r.round=j.optInt("r");r.combo=j.optInt("c");if(r.idx>0&&r.combo>=1&&r.combo<=4)out.add(r);} }catch(Exception ignored){}
        out.sort((a,b)->Long.compare(b.idx,a.idx)); return out;
    }
    public static void save(Context c,List<Result> list){
        try{JSONArray a=new JSONArray();for(Result r:list){JSONObject o=new JSONObject();o.put("i",r.idx);o.put("d",r.date);o.put("r",r.round);o.put("c",r.combo);a.put(o);}prefs(c).edit().putString(K_HISTORY,a.toString()).apply();}catch(Exception ignored){}
    }
    public static List<Result> merge(List<Result>a,List<Result>b){TreeMap<Long,Result>m=new TreeMap<>(Collections.reverseOrder());for(Result r:a)m.put(r.idx,r);for(Result r:b)m.put(r.idx,r);List<Result>o=new ArrayList<>(m.values());if(o.size()>MAX_HISTORY)o=new ArrayList<>(o.subList(0,MAX_HISTORY));return o;}

    public static SyncResult sync(Context c) throws Exception {
        List<Result> before=load(c); long latestBefore=before.isEmpty()?-1:before.get(0).idx;
        List<Result> merged=merge(before,fetch()); save(c,merged);
        boolean resolved=resolvePending(c,merged);
        Analysis a=analyze(merged); savePending(c,merged,a);
        prefs(c).edit().putLong(K_LAST_SYNC,System.currentTimeMillis()).apply();
        SyncResult sr=new SyncResult(); sr.newRoundResolved=resolved||(!merged.isEmpty()&&merged.get(0).idx!=latestBefore);sr.analysis=a;sr.history=merged;return sr;
    }

    private static void savePending(Context c,List<Result>d,Analysis a){
        if(d.isEmpty()||a==null)return; SharedPreferences sp=prefs(c); long next=nextIdx(d.get(0)); long existing=sp.getLong(K_PENDING_IDX,-1);
        if(existing==next)return; if(existing>0)return;
        int stake=Math.max(1,sp.getInt(K_BASE_STAKE,sp.getInt(K_PENDING_STAKE,5000))); double odds=Math.max(1.01,sp.getFloat(K_ODDS,1.95f));
        sp.edit().putLong(K_PENDING_IDX,next).putInt(K_PENDING_EXCLUDE,a.exclude).putInt(K_PENDING_STAKE,stake).putFloat(K_PENDING_ODDS,(float)odds).putString(K_PENDING_GRADE,a.grade)
                .putInt(K_LAST_EXCLUDE,a.exclude).putString(K_LAST_TRIPLE,a.triple).putString(K_LAST_GRADE,a.grade).putFloat(K_LAST_GAP,(float)a.scoreGap).apply();
    }

    private static boolean resolvePending(Context c,List<Result>d){
        SharedPreferences sp=prefs(c); long idx=sp.getLong(K_PENDING_IDX,-1); int exc=sp.getInt(K_PENDING_EXCLUDE,0); if(idx<=0||exc<1||exc>4)return false;
        Result actual=null;for(Result r:d)if(r.idx==idx){actual=r;break;}if(actual==null)return false;
        boolean ok=actual.combo!=exc;int s=sp.getInt(K_PENDING_STAKE,5000);double o=sp.getFloat(K_PENDING_ODDS,1.95f);String grade=sp.getString(K_PENDING_GRADE,"약");
        double pnl=ok?successProfit(s,o):-3.0*s;int n=sp.getInt(K_LIVE_TOTAL,0)+1,hit=sp.getInt(K_LIVE_SUCCESS,0)+(ok?1:0);double old=Double.longBitsToDouble(sp.getLong(K_LIVE_PROFIT,Double.doubleToLongBits(0)));
        appendRecord(c,idx,exc,actual.combo,grade,ok,pnl);
        sp.edit().putInt(K_LIVE_TOTAL,n).putInt(K_LIVE_SUCCESS,hit).putLong(K_LIVE_PROFIT,Double.doubleToLongBits(old+pnl))
                .remove(K_PENDING_IDX).remove(K_PENDING_EXCLUDE).remove(K_PENDING_STAKE).remove(K_PENDING_ODDS).remove(K_PENDING_GRADE).apply();return true;
    }

    private static void appendRecord(Context c,long idx,int exc,int actual,String grade,boolean ok,double pnl){
        try{SharedPreferences sp=prefs(c);JSONArray a=new JSONArray(sp.getString(K_RECORDS,"[]"));JSONObject o=new JSONObject();o.put("idx",idx);o.put("exclude",exc);o.put("actual",actual);o.put("grade",grade);o.put("ok",ok);o.put("pnl",pnl);a.put(o);JSONArray out=new JSONArray();for(int i=Math.max(0,a.length()-1500);i<a.length();i++)out.put(a.get(i));sp.edit().putString(K_RECORDS,out.toString()).apply();}catch(Exception ignored){}
    }

    public static Analysis analyze(List<Result> desc){
        if(desc==null||desc.isEmpty())return null;List<Result>asc=new ArrayList<>(desc);asc.sort(Comparator.comparingLong(x->x.idx));int end=asc.size();
        Perf[] ep=enginePerf(asc,end);Perf[] fp=familyPerf(asc,end);double[] ew=new double[ENGINE_COUNT],fw=new double[FAMILY_COUNT];for(int e=0;e<ENGINE_COUNT;e++){ew[e]=failureAwareWeight(ep[e]);ep[e].weight=ew[e];}for(int f=0;f<FAMILY_COUNT;f++){fw[f]=failureAwareWeight(fp[f]);fp[f].weight=fw[f];}
        double[][] familyP=currentFamilyPred(asc,end,ew);double[] ens=new double[5];double sw=0;for(int f=0;f<FAMILY_COUNT;f++){sw+=fw[f];for(int k=1;k<=4;k++)ens[k]+=familyP[f][k]*fw[f];}if(sw<=0)for(int k=1;k<=4;k++)ens[k]=.25;else for(int k=1;k<=4;k++)ens[k]/=sw;norm(ens);
        HedgeStats ht=hedgeTest(asc);int exc=argmin(ens);double gap=scoreGap(ens);String grade=classify(gap,ht.recentRate(),ht.rn);
        Analysis a=new Analysis();a.occurrence=ens;a.exclude=exc;a.triple=tripleFor(exc);a.grade=grade;a.scoreGap=gap;a.enginePerf=ep;a.familyPerf=fp;a.hedge=ht;
        for(int k=1;k<=4;k++)a.excludeScore[k]=excludeScore(ens[k]);a.rank=new ArrayList<>();for(int k=1;k<=4;k++)a.rank.add(k);a.rank.sort((x,y)->Double.compare(a.excludeScore[y],a.excludeScore[x]));return a;
    }

    private static Perf[] enginePerf(List<Result>a,int end){
        Perf[]p=new Perf[ENGINE_COUNT];@SuppressWarnings("unchecked")ArrayDeque<Boolean>[]q=new ArrayDeque[ENGINE_COUNT];int[]rh=new int[ENGINE_COUNT];for(int e=0;e<ENGINE_COUNT;e++){p[e]=new Perf();q[e]=new ArrayDeque<>();}
        int start=Math.max(16,end-BT_LIMIT);for(int t=start;t<end;t++){int actual=a.get(t).combo;for(int e=0;e<ENGINE_COUNT;e++){boolean ok=actual!=argmin(pred(a,t,e));p[e].n++;if(ok)p[e].hit++;q[e].addLast(ok);if(ok)rh[e]++;if(q[e].size()>60&&q[e].removeFirst())rh[e]--;}}
        for(int e=0;e<ENGINE_COUNT;e++){p[e].rn=q[e].size();p[e].rhit=rh[e];}return p;
    }

    private static Perf[] familyPerf(List<Result>a,int end){
        Perf[]p=new Perf[FAMILY_COUNT];@SuppressWarnings("unchecked")ArrayDeque<Boolean>[]q=new ArrayDeque[FAMILY_COUNT];int[]rh=new int[FAMILY_COUNT];for(int f=0;f<FAMILY_COUNT;f++){p[f]=new Perf();q[f]=new ArrayDeque<>();}
        int start=Math.max(16,end-BT_LIMIT);for(int t=start;t<end;t++){double[][]fam=rawFamilyPred(a,t);int actual=a.get(t).combo;for(int f=0;f<FAMILY_COUNT;f++){boolean ok=actual!=argmin(fam[f]);p[f].n++;if(ok)p[f].hit++;q[f].addLast(ok);if(ok)rh[f]++;if(q[f].size()>60&&q[f].removeFirst())rh[f]--;}}
        for(int f=0;f<FAMILY_COUNT;f++){p[f].rn=q[f].size();p[f].rhit=rh[f];}return p;
    }

    private static double failureAwareWeight(Perf p){
        double all=shrink(p.rate(),p.n,90,.75), rec=shrink(p.recentRate(),p.rn,35,.75);
        double pos=6*Math.max(0,all-.75)+4*Math.max(0,rec-.75);double neg=12*Math.max(0,.75-all)+9*Math.max(0,.75-rec);
        return clamp(1+pos-neg,.15,2.35);
    }

    private static double[][] currentFamilyPred(List<Result>a,int end,double[]engineW){
        double[][]out=new double[FAMILY_COUNT][5];double[]sw=new double[FAMILY_COUNT];for(int e=0;e<ENGINE_COUNT;e++){int f=ENGINE_FAMILY[e];double[]p=pred(a,end,e);double w=engineW[e];sw[f]+=w;for(int k=1;k<=4;k++)out[f][k]+=p[k]*w;}
        for(int f=0;f<FAMILY_COUNT;f++){if(sw[f]<=0)for(int k=1;k<=4;k++)out[f][k]=.25;else for(int k=1;k<=4;k++)out[f][k]/=sw[f];norm(out[f]);}return out;
    }
    private static double[][] rawFamilyPred(List<Result>a,int end){double[][]out=new double[FAMILY_COUNT][5];int[]n=new int[FAMILY_COUNT];for(int e=0;e<ENGINE_COUNT;e++){int f=ENGINE_FAMILY[e];double[]p=pred(a,end,e);n[f]++;for(int k=1;k<=4;k++)out[f][k]+=p[k];}for(int f=0;f<FAMILY_COUNT;f++){for(int k=1;k<=4;k++)out[f][k]/=Math.max(1,n[f]);norm(out[f]);}return out;}

    private static HedgeStats hedgeTest(List<Result>a){
        HedgeStats h=new HedgeStats();int end=a.size(),start=Math.max(20,end-BT_LIMIT);
        int[]en=new int[ENGINE_COUNT],eh=new int[ENGINE_COUNT],erh=new int[ENGINE_COUNT],fn=new int[FAMILY_COUNT],fh=new int[FAMILY_COUNT],frh=new int[FAMILY_COUNT];
        @SuppressWarnings("unchecked")ArrayDeque<Boolean>[]eq=new ArrayDeque[ENGINE_COUNT];@SuppressWarnings("unchecked")ArrayDeque<Boolean>[]fq=new ArrayDeque[FAMILY_COUNT];for(int e=0;e<ENGINE_COUNT;e++)eq[e]=new ArrayDeque<>();for(int f=0;f<FAMILY_COUNT;f++)fq[f]=new ArrayDeque<>();
        ArrayDeque<Boolean>hq=new ArrayDeque<>();int hr=0;
        for(int t=start;t<end;t++){
            double[][]pp=new double[ENGINE_COUNT][];int[]eexc=new int[ENGINE_COUNT];double[]ew=new double[ENGINE_COUNT];
            for(int e=0;e<ENGINE_COUNT;e++){pp[e]=pred(a,t,e);eexc[e]=argmin(pp[e]);Perf pe=perfFrom(en[e],eh[e],eq[e].size(),erh[e]);ew[e]=failureAwareWeight(pe);}
            double[][]fam=new double[FAMILY_COUNT][5];double[]fsw=new double[FAMILY_COUNT];for(int e=0;e<ENGINE_COUNT;e++){int f=ENGINE_FAMILY[e];fsw[f]+=ew[e];for(int k=1;k<=4;k++)fam[f][k]+=pp[e][k]*ew[e];}
            for(int f=0;f<FAMILY_COUNT;f++){if(fsw[f]<=0)for(int k=1;k<=4;k++)fam[f][k]=.25;else for(int k=1;k<=4;k++)fam[f][k]/=fsw[f];norm(fam[f]);}
            double[]ens=new double[5];double wsum=0;double[]fw=new double[FAMILY_COUNT];for(int f=0;f<FAMILY_COUNT;f++){Perf pf=perfFrom(fn[f],fh[f],fq[f].size(),frh[f]);fw[f]=failureAwareWeight(pf);wsum+=fw[f];for(int k=1;k<=4;k++)ens[k]+=fam[f][k]*fw[f];}for(int k=1;k<=4;k++)ens[k]/=Math.max(.0001,wsum);norm(ens);
            int actual=a.get(t).combo,exc=argmin(ens);boolean ok=actual!=exc;double rr=hq.isEmpty()?.75:(double)hr/hq.size();String grade=classify(scoreGap(ens),rr,hq.size());int gi=gradeIndex(grade);
            h.n++;if(ok)h.hit++;h.gradeN[gi]++;if(ok)h.gradeHit[gi]++;hq.addLast(ok);if(ok)hr++;if(hq.size()>50&&hq.removeFirst())hr--;
            for(int e=0;e<ENGINE_COUNT;e++){boolean eok=actual!=eexc[e];en[e]++;if(eok)eh[e]++;eq[e].addLast(eok);if(eok)erh[e]++;if(eq[e].size()>60&&eq[e].removeFirst())erh[e]--;}
            for(int f=0;f<FAMILY_COUNT;f++){boolean fok=actual!=argmin(fam[f]);fn[f]++;if(fok)fh[f]++;fq[f].addLast(fok);if(fok)frh[f]++;if(fq[f].size()>60&&fq[f].removeFirst())frh[f]--;}
        }
        h.rn=hq.size();h.rhit=hr;return h;
    }
    private static Perf perfFrom(int n,int hit,int rn,int rhit){Perf p=new Perf();p.n=n;p.hit=hit;p.rn=rn;p.rhit=rhit;return p;}

    private static double scoreGap(double[]p){int first=1,second=2;if(p[second]<p[first]){int z=first;first=second;second=z;}for(int k=3;k<=4;k++){if(p[k]<p[first]){second=first;first=k;}else if(p[k]<p[second])second=k;}return clamp((p[second]-p[first])*400.0,0,100);}
    private static double excludeScore(double occurrence){return clamp(50.0+(.25-occurrence)*400.0,0,100);}
    private static String classify(double gap,double recent,int n){if(gap>=14&&(n<20||recent>=.77))return "강";if(gap>=6&&(n<20||recent>=.745))return "보통";return "약";}
    private static int gradeIndex(String g){return "강".equals(g)?2:"보통".equals(g)?1:0;}

    private static double[] pred(List<Result>a,int end,int id){switch(id){case 0:return freq(a,end,8,1.15);case 1:return freq(a,end,15,1.0);case 2:return freq(a,end,30,.65);case 3:return markov1(a,end);case 4:return markov2(a,end);case 5:return streak(a,end);case 6:return similar(a,end);case 7:return binary(a,end);case 8:return regime(a,end);default:return uni();}}
    private static double[] freq(List<Result>a,int end,int win,double pow){double[]c=prior();double tot=6;int s=Math.max(0,end-win),pos=1;for(int i=s;i<end;i++){double w=Math.pow(pos++,pow);c[a.get(i).combo]+=w;tot+=w;}for(int k=1;k<=4;k++)c[k]/=tot;return norm(c);}
    private static double[] markov1(List<Result>a,int end){if(end<2)return freq(a,end,15,1);int last=a.get(end-1).combo;double[]c=prior();double tot=6;for(int i=Math.max(1,end-1200);i<end;i++)if(a.get(i-1).combo==last){c[a.get(i).combo]++;tot++;}for(int k=1;k<=4;k++)c[k]/=tot;return norm(c);}
    private static double[] markov2(List<Result>a,int end){if(end<3)return markov1(a,end);int x=a.get(end-2).combo,y=a.get(end-1).combo,m=0;double[]c=prior();double tot=6;for(int i=Math.max(2,end-1800);i<end;i++)if(a.get(i-2).combo==x&&a.get(i-1).combo==y){c[a.get(i).combo]++;tot++;m++;}for(int k=1;k<=4;k++)c[k]/=tot;c=norm(c);return m<6?mix(markov1(a,end),c,.72):c;}
    private static double[] streak(List<Result>a,int end){if(end<3)return freq(a,end,12,1);int last=a.get(end-1).combo,st=1;for(int i=end-2;i>=0&&a.get(i).combo==last&&st<5;i--)st++;double[]c=prior();double tot=6;int m=0;for(int i=Math.max(2,end-1800);i<end;i++){int prev=a.get(i-1).combo;if(prev!=last)continue;int ss=1;for(int j=i-2;j>=0&&a.get(j).combo==prev&&ss<5;j--)ss++;if(ss==st){c[a.get(i).combo]++;tot++;m++;}}for(int k=1;k<=4;k++)c[k]/=tot;c=norm(c);return m<6?mix(freq(a,end,12,1),c,.76):c;}
    private static double[] similar(List<Result>a,int end){
        if(end<5)return markov2(a,end);int len=Math.min(5,end);double[]c=prior();double tot=6;int matches=0;
        for(int i=Math.max(len,end-2200);i<end;i++){
            int dist=0;for(int j=1;j<=len;j++)if(a.get(end-j).combo!=a.get(i-j).combo)dist+=j<=2?2:1;
            if(dist<=3){double w=dist==0?5.0:dist==1?3.0:dist==2?1.8:1.0;c[a.get(i).combo]+=w;tot+=w;matches++;}
        }
        for(int k=1;k<=4;k++)c[k]/=tot;c=norm(c);return matches<8?mix(markov2(a,end),c,.70):c;
    }
    private static double[] binary(List<Result>a,int end){double pr=bitProb(a,end,true),pf=bitProb(a,end,false),pl=1-pr,p3=1-pf;double[]p=new double[5];p[1]=pl*p3;p[2]=pl*pf;p[3]=pr*p3;p[4]=pr*pf;return norm(p);}
    private static double bitProb(List<Result>a,int end,boolean right){int s=Math.max(0,end-24),pos=1;double ones=1.5,tot=3;for(int i=s;i<end;i++){int b=right?right(a.get(i).combo):four(a.get(i).combo);double w=pos++;if(b==1)ones+=w;tot+=w;}double f=ones/tot;if(end<2)return clamp(f,.08,.92);int last=right?right(a.get(end-1).combo):four(a.get(end-1).combo);double to=1.5,tt=3;for(int i=Math.max(1,end-1000);i<end;i++){int prev=right?right(a.get(i-1).combo):four(a.get(i-1).combo);if(prev==last){int cur=right?right(a.get(i).combo):four(a.get(i).combo);if(cur==1)to++;tt++;}}return clamp(.6*f+.4*(to/tt),.08,.92);}
    private static int right(int c){return c==3||c==4?1:0;}private static int four(int c){return c==2||c==4?1:0;}
    private static double[] regime(List<Result>a,int end){double[]s=freq(a,end,12,1),m=freq(a,end,30,.65),l=freq(a,end,70,.25);double tv=0;for(int k=1;k<=4;k++)tv+=Math.abs(s[k]-l[k]);tv*=.5;double alpha=clamp(.42+1.9*tv,.42,.86);double[]base=mix(m,l,.68);return mix(s,base,alpha);}
    private static double[] prior(){double[]p=new double[5];for(int k=1;k<=4;k++)p[k]=1.5;return p;}private static double[]uni(){double[]p=new double[5];for(int k=1;k<=4;k++)p[k]=.25;return p;}
    private static double[]mix(double[]a,double[]b,double wa){double[]p=new double[5];for(int k=1;k<=4;k++)p[k]=a[k]*wa+b[k]*(1-wa);return norm(p);}private static double[]norm(double[]p){double s=0;for(int k=1;k<=4;k++){if(Double.isNaN(p[k])||Double.isInfinite(p[k])||p[k]<0)p[k]=0;s+=p[k];}if(s<=0)return uni();for(int k=1;k<=4;k++)p[k]/=s;return p;}
    private static int argmin(double[]p){int b=1;for(int k=2;k<=4;k++)if(p[k]<p[b])b=k;return b;}private static double shrink(double rate,int n,double k,double base){return base+(rate-base)*(n/(n+k));}private static double clamp(double x,double lo,double hi){return Math.max(lo,Math.min(hi,x));}

    public static String tripleFor(int c){switch(c){case 1:return "우 + 4줄 + 홀";case 2:return "우 + 3줄 + 짝";case 3:return "좌 + 4줄 + 짝";case 4:return "좌 + 3줄 + 홀";default:return "-";}}
    public static long nextIdx(Result r){try{if(r.round<480)return Long.parseLong(r.date.substring(2,8)+String.format(Locale.US,"%04d",r.round+1));SimpleDateFormat f=new SimpleDateFormat("yyyyMMdd",Locale.US);Calendar c=Calendar.getInstance();c.setTime(f.parse(r.date));c.add(Calendar.DAY_OF_MONTH,1);String d=f.format(c.getTime());return Long.parseLong(d.substring(2,8)+"0001");}catch(Exception e){return r.idx+1;}}
    public static long millisToNextDraw(){long interval=180000L,now=System.currentTimeMillis(),mod=Math.floorMod(now,interval),left=interval-mod;return left==0?interval:left;}
    public static String countdownText(){long s=(millisToNextDraw()+999)/1000;return String.format(Locale.KOREA,"%02d:%02d",s/60,s%60);}
    public static double successProfit(int stake,double odds){return stake*(2*odds-3);}public static double breakEven(double odds){return 3/(2*odds);}public static String pct(double v){return String.format(Locale.KOREA,"%.1f%%",v*100);}public static String money(double v){return String.format(Locale.KOREA,"%,.0f원",v);}public static String signed(double v){return (v>=0?"+":"")+money(v);}

    public static String liveRate(Context c){SharedPreferences sp=prefs(c);int n=sp.getInt(K_LIVE_TOTAL,0),h=sp.getInt(K_LIVE_SUCCESS,0);return n==0?"-":h+"/"+n+" ("+pct((double)h/n)+")";}

    public static JSONObject backup(Context c) throws Exception {
        SharedPreferences sp=prefs(c);JSONObject root=new JSONObject();root.put("format","BubbleTripleHedgeV2Backup");root.put("history",new JSONArray(sp.getString(K_HISTORY,"[]")));root.put("records",new JSONArray(sp.getString(K_RECORDS,"[]")));
        JSONObject s=new JSONObject();String[]sk={K_PENDING_GRADE,K_LAST_TRIPLE,K_LAST_GRADE};for(String k:sk)s.put(k,sp.getString(k,""));String[]ik={K_PENDING_EXCLUDE,K_PENDING_STAKE,K_LIVE_TOTAL,K_LIVE_SUCCESS,K_BASE_STAKE,K_LAST_EXCLUDE};for(String k:ik)s.put(k,sp.getInt(k,0));s.put(K_PENDING_IDX,sp.getLong(K_PENDING_IDX,-1));s.put(K_PENDING_ODDS,sp.getFloat(K_PENDING_ODDS,1.95f));s.put(K_ODDS,sp.getFloat(K_ODDS,1.95f));s.put(K_LIVE_PROFIT,sp.getLong(K_LIVE_PROFIT,Double.doubleToLongBits(0)));s.put(K_LAST_GAP,sp.getFloat(K_LAST_GAP,0));s.put(K_AUTO,sp.getBoolean(K_AUTO,true));root.put("state",s);return root;
    }
    public static void restore(Context c,JSONObject root) throws Exception {
        String format=root.optString("format","");if(!"BubbleTripleHedgeV2Backup".equals(format)&&!"BubbleTripleHedgeBackup".equals(format))throw new Exception("백업 형식이 다릅니다.");SharedPreferences.Editor ed=prefs(c).edit();if(root.has("history"))ed.putString(K_HISTORY,root.getJSONArray("history").toString());if(root.has("records"))ed.putString(K_RECORDS,root.getJSONArray("records").toString());JSONObject s=root.optJSONObject("state");if(s!=null){
            if(s.has(K_PENDING_IDX))ed.putLong(K_PENDING_IDX,s.optLong(K_PENDING_IDX,-1)); if(s.has(K_PENDING_EXCLUDE))ed.putInt(K_PENDING_EXCLUDE,s.optInt(K_PENDING_EXCLUDE,0));
            if(s.has(K_PENDING_STAKE))ed.putInt(K_PENDING_STAKE,s.optInt(K_PENDING_STAKE,5000)); if(s.has(K_PENDING_ODDS))ed.putFloat(K_PENDING_ODDS,(float)s.optDouble(K_PENDING_ODDS,1.95));
            if(s.has(K_LIVE_TOTAL))ed.putInt(K_LIVE_TOTAL,s.optInt(K_LIVE_TOTAL,0)); if(s.has(K_LIVE_SUCCESS))ed.putInt(K_LIVE_SUCCESS,s.optInt(K_LIVE_SUCCESS,0)); if(s.has(K_LIVE_PROFIT))ed.putLong(K_LIVE_PROFIT,s.optLong(K_LIVE_PROFIT,Double.doubleToLongBits(0)));
            if(s.has(K_BASE_STAKE))ed.putInt(K_BASE_STAKE,s.optInt(K_BASE_STAKE,5000));if(s.has(K_ODDS))ed.putFloat(K_ODDS,(float)s.optDouble(K_ODDS,1.95));if(s.has(K_AUTO))ed.putBoolean(K_AUTO,s.optBoolean(K_AUTO,true));
            if(s.has(K_PENDING_GRADE))ed.putString(K_PENDING_GRADE,s.optString(K_PENDING_GRADE,"약"));if(s.has(K_LAST_EXCLUDE))ed.putInt(K_LAST_EXCLUDE,s.optInt(K_LAST_EXCLUDE,0));if(s.has(K_LAST_TRIPLE))ed.putString(K_LAST_TRIPLE,s.optString(K_LAST_TRIPLE,"-"));if(s.has(K_LAST_GRADE))ed.putString(K_LAST_GRADE,s.optString(K_LAST_GRADE,"약"));if(s.has(K_LAST_GAP))ed.putFloat(K_LAST_GAP,(float)s.optDouble(K_LAST_GAP,0));
        }ed.apply();
    }
}
