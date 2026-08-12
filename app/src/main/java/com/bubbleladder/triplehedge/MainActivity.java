package com.bubbleladder.triplehedge;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.text.*;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    static final String API="https://api.bepick.io/game/bubble_ladder3";
    static final String PREF="bubble_triple_hedge_v1";
    static final String KH="history", KPI="pending_idx", KPE="pending_exclude", KPS="pending_stake", KPO="pending_odds",
            KLT="live_total", KLS="live_success", KLP="live_profit";
    static final int EXP=9101, IMP=9102, MAXH=5000, BTLIM=500, ECOUNT=8;
    final String[] COMBO={"","좌3짝","좌4홀","우3홀","우4짝"};
    final String[] EN={"최근8 가중","최근15 가중","최근30 안정","4상태 Markov-1","4상태 Markov-2","Binary 2-Bit","Regime Adaptive","연속상태 조건"};

    SharedPreferences sp;
    ExecutorService ex=Executors.newSingleThreadExecutor();
    Handler h=new Handler(Looper.getMainLooper());
    Runnable autoTask;
    Button refresh,backup,restore,calc,reset;
    CheckBox auto;
    EditText stake,odds;
    TextView status,next,triple,exclude,model,candidates,engines,backtest,live,profit,recent;

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        sp=getSharedPreferences(PREF,MODE_PRIVATE);
        setContentView(ui());
        List<R> saved=load();
        if(!saved.isEmpty()) render(analyze(saved),saved);
        refresh.setOnClickListener(v->refresh());
        backup.setOnClickListener(v->exportStart());
        restore.setOnClickListener(v->importStart());
        calc.setOnClickListener(v->recalc());
        reset.setOnClickListener(v->resetConfirm());
        autoTask=new Runnable(){ public void run(){ if(auto.isChecked()){ refresh(); h.postDelayed(this,180000); } } };
        auto.setOnCheckedChangeListener((v,on)->{ h.removeCallbacks(autoTask); if(on)h.postDelayed(autoTask,180000); });
    }

    View ui(){
        ScrollView sv=new ScrollView(this);
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14),dp(16),dp(14),dp(28)); root.setBackgroundColor(Color.rgb(7,19,26)); sv.addView(root);

        root.addView(t("보글사다리3 · 삼치기 Hedge",24,Color.WHITE,true));
        TextView sub=t("PASS 없음 · 매회 추천 · Binary 2-Bit + Markov + Regime + Hedge",12,Color.rgb(110,231,183),false);
        sub.setPadding(0,dp(4),0,dp(14)); root.addView(sub);

        LinearLayout ctrl=card(),r1=new LinearLayout(this); r1.setOrientation(LinearLayout.HORIZONTAL);
        refresh=btn("🔄 최신 결과 + 분석",Color.rgb(5,150,105)); reset=btn("초기화",Color.rgb(51,65,85));
        r1.addView(refresh,new LinearLayout.LayoutParams(0,dp(58),3));
        LinearLayout.LayoutParams rlp=new LinearLayout.LayoutParams(0,dp(58),1); rlp.setMargins(dp(8),0,0,0); r1.addView(reset,rlp); ctrl.addView(r1);

        LinearLayout r2=new LinearLayout(this); r2.setOrientation(LinearLayout.HORIZONTAL); r2.setPadding(0,dp(8),0,0);
        backup=btn("💾 백업",Color.rgb(21,128,61)); restore=btn("📂 복원",Color.rgb(109,40,217));
        r2.addView(backup,new LinearLayout.LayoutParams(0,dp(50),1));
        LinearLayout.LayoutParams ilp=new LinearLayout.LayoutParams(0,dp(50),1); ilp.setMargins(dp(8),0,0,0); r2.addView(restore,ilp); ctrl.addView(r2);

        auto=new CheckBox(this); auto.setText("3분 자동 새로고침"); auto.setTextColor(Color.WHITE); auto.setTextSize(15); ctrl.addView(auto);
        status=t("최신 결과 + 분석을 눌러 시작하세요.",13,Color.LTGRAY,false); status.setPadding(0,dp(6),0,0); ctrl.addView(status); root.addView(ctrl);

        LinearLayout hero=card(); hero.addView(t("다음 회차 삼치기",12,Color.GRAY,false));
        next=t("-",16,Color.WHITE,true); hero.addView(next);
        triple=t("분석 대기",30,Color.rgb(52,211,153),true); triple.setPadding(0,dp(6),0,dp(4)); hero.addView(triple);
        exclude=t("제외조합 -",15,Color.rgb(248,113,113),true); hero.addView(exclude);
        model=t("삼치기 모델점수 -",14,Color.rgb(253,224,71),true); hero.addView(model);
        TextView always=t("● ALWAYS 추천 모드 · PASS 없음",12,Color.rgb(110,231,183),true); always.setPadding(0,dp(10),0,0); hero.addView(always);
        TextView warn=t("※ 모델점수는 실제 당첨확률이 아니라 상대적 모델점수입니다.",11,Color.GRAY,false); warn.setPadding(0,dp(7),0,0); hero.addView(warn); root.addView(hero);

        LinearLayout cc=card(); cc.addView(sec("제외 후보 4개 조합 · 낮은 점수 순"));
        candidates=t("-",15,Color.WHITE,false); candidates.setLineSpacing(0,1.3f); cc.addView(candidates); root.addView(cc);

        LinearLayout ec=card(); ec.addView(sec("엔진별 삼치기 백테스트"));
        engines=t("-",13,Color.rgb(226,232,240),false); engines.setLineSpacing(0,1.25f); ec.addView(engines); root.addView(ec);

        LinearLayout bc=card(); bc.addView(sec("Hedge 삼치기 검증"));
        backtest=t("-",14,Color.WHITE,false); backtest.setLineSpacing(0,1.25f); bc.addView(backtest);
        live=t("실전 기록 · 아직 없음",14,Color.rgb(125,211,252),true); live.setPadding(0,dp(10),0,0); bc.addView(live); root.addView(bc);

        LinearLayout pc=card(); pc.addView(sec("고정배팅 · 수익 계산"));
        LinearLayout ir=new LinearLayout(this); ir.setOrientation(LinearLayout.HORIZONTAL);
        stake=input("5000"); stake.setHint("1개 배팅금액"); stake.setInputType(InputType.TYPE_CLASS_NUMBER);
        odds=input("1.95"); odds.setHint("배당"); odds.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);
        ir.addView(stake,new LinearLayout.LayoutParams(0,dp(52),1));
        LinearLayout.LayoutParams olp=new LinearLayout.LayoutParams(0,dp(52),1); olp.setMargins(dp(8),0,0,0); ir.addView(odds,olp); pc.addView(ir);
        calc=btn("수익 다시 계산",Color.rgb(30,64,175)); LinearLayout.LayoutParams clp=new LinearLayout.LayoutParams(-1,dp(48)); clp.setMargins(0,dp(8),0,0); pc.addView(calc,clp);
        profit=t("-",13,Color.rgb(226,232,240),false); profit.setPadding(0,dp(10),0,0); profit.setLineSpacing(0,1.25f); pc.addView(profit); root.addView(pc);

        LinearLayout rc=card(); rc.addView(sec("최근 10회 결과")); recent=t("-",14,Color.WHITE,false); recent.setLineSpacing(0,1.25f); rc.addView(recent); root.addView(rc);

        LinearLayout lc=card(); lc.addView(sec("삼치기 변환 규칙"));
        TextView logic=t("좌3짝 제외 → 우 + 4줄 + 홀\n좌4홀 제외 → 우 + 3줄 + 짝\n우3홀 제외 → 좌 + 4줄 + 짝\n우4짝 제외 → 좌 + 3줄 + 홀\n\n제외조합이 나오면 0/3, 나머지 조합이면 항상 2/3 적중입니다.",13,Color.LTGRAY,false);
        logic.setLineSpacing(0,1.25f); lc.addView(logic); root.addView(lc);
        root.addView(t("패턴 실험 도구이며 장기 수익 우위를 보장하지 않습니다.",11,Color.GRAY,false));
        return sv;
    }

    LinearLayout card(){ LinearLayout c=new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL); c.setPadding(dp(14),dp(14),dp(14),dp(14)); c.setBackground(round(Color.rgb(17,24,39),18)); LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2); lp.setMargins(0,0,0,dp(12)); c.setLayoutParams(lp); return c; }
    TextView sec(String s){ TextView v=t(s,17,Color.WHITE,true); v.setPadding(0,0,0,dp(9)); return v; }
    TextView t(String s,float z,int c,boolean b){ TextView v=new TextView(this); v.setText(s); v.setTextSize(z); v.setTextColor(c); if(b)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD); return v; }
    Button btn(String s,int c){ Button b=new Button(this); b.setText(s); b.setTextColor(Color.WHITE); b.setTextSize(13); b.setAllCaps(false); b.setTypeface(Typeface.DEFAULT,Typeface.BOLD); b.setBackground(round(c,14)); return b; }
    EditText input(String s){ EditText e=new EditText(this); e.setText(s); e.setTextColor(Color.WHITE); e.setHintTextColor(Color.GRAY); e.setSingleLine(true); e.setPadding(dp(10),0,dp(10),0); e.setBackground(round(Color.rgb(30,41,59),12)); return e; }
    GradientDrawable round(int c,int r){ GradientDrawable g=new GradientDrawable(); g.setColor(c); g.setCornerRadius(dp(r)); return g; }
    int dp(int v){ return (int)(v*getResources().getDisplayMetrics().density+.5f); }

    void refresh(){
        refresh.setEnabled(false); setStatus("최신 결과 조회 + Hedge 분석 중...",false);
        ex.execute(()->{
            try{
                List<R> merged=merge(load(),fetch()); save(merged); resolvePending(merged);
                A a=analyze(merged); savePending(merged,a);
                h.post(()->{ render(a,merged); setStatus("● 분석 완료 · "+new SimpleDateFormat("HH:mm:ss",Locale.KOREA).format(new Date()),true); refresh.setEnabled(true); });
            }catch(Exception e){ h.post(()->{ setStatus("조회 실패: "+e.getMessage(),false); status.setTextColor(Color.rgb(248,113,113)); refresh.setEnabled(true); }); }
        });
    }

    List<R> fetch() throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL(API).openConnection(); c.setRequestMethod("GET"); c.setConnectTimeout(12000); c.setReadTimeout(12000); c.setUseCaches(false); c.setRequestProperty("Accept","application/json");
        BufferedReader br=new BufferedReader(new InputStreamReader(c.getInputStream(),"UTF-8")); StringBuilder sb=new StringBuilder(); String l; while((l=br.readLine())!=null)sb.append(l); br.close(); c.disconnect();
        JSONObject root=new JSONObject(sb.toString()); JSONArray arr=root.optJSONArray("data"); if(arr==null)throw new Exception("data 없음");
        List<R> out=new ArrayList<>();
        for(int i=0;i<arr.length();i++){ JSONObject o=arr.optJSONObject(i); if(o==null)continue; int co=o.optInt("fd4",0); long idx=o.optLong("idx",0); if(co<1||co>4||idx<=0)continue; R r=new R(); r.idx=idx; r.date=o.optString("date",""); r.round=o.optInt("round",0); r.combo=co; out.add(r); }
        Collections.sort(out,(a,b)->Long.compare(b.idx,a.idx)); if(out.isEmpty())throw new Exception("결과 없음"); return out;
    }

    List<R> merge(List<R>a,List<R>b){ TreeMap<Long,R> m=new TreeMap<>(Collections.reverseOrder()); for(R r:a)m.put(r.idx,r); for(R r:b)m.put(r.idx,r); List<R> o=new ArrayList<>(m.values()); if(o.size()>MAXH)o=new ArrayList<>(o.subList(0,MAXH)); return o; }
    void save(List<R> list){ try{ JSONArray a=new JSONArray(); for(R r:list){ JSONObject o=new JSONObject(); o.put("i",r.idx);o.put("d",r.date);o.put("r",r.round);o.put("c",r.combo);a.put(o);} sp.edit().putString(KH,a.toString()).apply(); }catch(Exception ignored){} }
    List<R> load(){ List<R> o=new ArrayList<>(); String raw=sp.getString(KH,""); if(raw.isEmpty())return o; try{JSONArray a=new JSONArray(raw);for(int i=0;i<a.length();i++){JSONObject j=a.optJSONObject(i);if(j==null)continue;R r=new R();r.idx=j.optLong("i");r.date=j.optString("d");r.round=j.optInt("r");r.combo=j.optInt("c");if(r.idx>0&&r.combo>=1&&r.combo<=4)o.add(r);}}catch(Exception ignored){} Collections.sort(o,(a,b)->Long.compare(b.idx,a.idx));return o; }

    A analyze(List<R> desc){
        List<R> asc=new ArrayList<>(desc); Collections.sort(asc,Comparator.comparingLong(x->x.idx));
        EP[] perf=enginePerf(asc,asc.size()); double[] w=weights(perf), ens=new double[5]; double ws=0;
        for(int e=0;e<ECOUNT;e++){ double[] p=pred(asc,asc.size(),e); ws+=w[e]; for(int k=1;k<=4;k++)ens[k]+=p[k]*w[e]; }
        if(ws==0)for(int k=1;k<=4;k++)ens[k]=.25; else for(int k=1;k<=4;k++)ens[k]/=ws; norm(ens);
        A a=new A(); a.p=ens;a.exclude=argmin(ens);a.triple=tripleFor(a.exclude);a.modelSuccess=1-ens[a.exclude];a.perf=perf;a.w=w;a.hedge=hedgeTest(asc);
        a.rank=new ArrayList<>();for(int k=1;k<=4;k++)a.rank.add(k);a.rank.sort((x,y)->Double.compare(ens[x],ens[y]));return a;
    }

    EP[] enginePerf(List<R> a,int end){
        EP[] p=new EP[ECOUNT]; ArrayDeque<Boolean>[] q=new ArrayDeque[ECOUNT]; int[] rh=new int[ECOUNT];
        for(int e=0;e<ECOUNT;e++){p[e]=new EP();p[e].id=e;q[e]=new ArrayDeque<>();}
        int start=Math.max(15,end-BTLIM);
        for(int t=start;t<end;t++){int actual=a.get(t).combo;for(int e=0;e<ECOUNT;e++){boolean ok=actual!=argmin(pred(a,t,e));p[e].n++;if(ok)p[e].hit++;q[e].addLast(ok);if(ok)rh[e]++;if(q[e].size()>60&&q[e].removeFirst())rh[e]--;}}
        for(int e=0;e<ECOUNT;e++){p[e].rn=q[e].size();p[e].rhit=rh[e];}return p;
    }

    double[] weights(EP[] p){double[] w=new double[ECOUNT];for(int e=0;e<ECOUNT;e++){double all=p[e].n>0?(double)p[e].hit/p[e].n:.75, rec=p[e].rn>0?(double)p[e].rhit/p[e].rn:all;double rel=p[e].n/(p[e].n+80.0),rr=p[e].rn/(p[e].rn+40.0);double s=.75+(all-.75)*rel,r=.75+(rec-.75)*rr;w[e]=clamp(1+8*(s-.75)+5*(r-.75),.25,2.25);}return w;}

    HT hedgeTest(List<R> a){
        HT h=new HT();int n=a.size(),start=Math.max(20,n-BTLIM);int[] en=new int[ECOUNT],eh=new int[ECOUNT],rh=new int[ECOUNT];ArrayDeque<Boolean>[] q=new ArrayDeque[ECOUNT];for(int e=0;e<ECOUNT;e++)q[e]=new ArrayDeque<>();
        ArrayDeque<Boolean> hq=new ArrayDeque<>();int hr=0;
        for(int t=start;t<n;t++){double[] ens=new double[5];double ws=0;double[][] pp=new double[ECOUNT][];int[] exc=new int[ECOUNT];
            for(int e=0;e<ECOUNT;e++){pp[e]=pred(a,t,e);exc[e]=argmin(pp[e]);double all=en[e]>0?(double)eh[e]/en[e]:.75,rec=q[e].size()>0?(double)rh[e]/q[e].size():all;double s=.75+(all-.75)*(en[e]/(en[e]+80.0)),r=.75+(rec-.75)*(q[e].size()/(q[e].size()+40.0));double w=clamp(1+8*(s-.75)+5*(r-.75),.25,2.25);ws+=w;for(int k=1;k<=4;k++)ens[k]+=pp[e][k]*w;}
            for(int k=1;k<=4;k++)ens[k]/=ws;norm(ens);int actual=a.get(t).combo;boolean ok=actual!=argmin(ens);h.n++;if(ok)h.hit++;hq.addLast(ok);if(ok)hr++;if(hq.size()>50&&hq.removeFirst())hr--;
            for(int e=0;e<ECOUNT;e++){boolean eok=actual!=exc[e];en[e]++;if(eok)eh[e]++;q[e].addLast(eok);if(eok)rh[e]++;if(q[e].size()>60&&q[e].removeFirst())rh[e]--;}}
        h.rn=hq.size();h.rhit=hr;return h;
    }

    double[] pred(List<R>a,int end,int id){switch(id){case 0:return freq(a,end,8,1.15);case 1:return freq(a,end,15,1);case 2:return freq(a,end,30,.65);case 3:return markov1(a,end);case 4:return markov2(a,end);case 5:return binary(a,end);case 6:return regime(a,end);case 7:return streak(a,end);default:return uni();}}

    double[] freq(List<R>a,int end,int win,double pow){double[]c=prior();double tot=6;int s=Math.max(0,end-win),pos=1;for(int i=s;i<end;i++){double w=Math.pow(pos++,pow);c[a.get(i).combo]+=w;tot+=w;}for(int k=1;k<=4;k++)c[k]/=tot;return norm(c);}
    double[] markov1(List<R>a,int end){if(end<2)return freq(a,end,15,1);int last=a.get(end-1).combo;double[]c=prior();double tot=6;for(int i=Math.max(1,end-1000);i<end;i++)if(a.get(i-1).combo==last){c[a.get(i).combo]++;tot++;}for(int k=1;k<=4;k++)c[k]/=tot;return norm(c);}
    double[] markov2(List<R>a,int end){if(end<3)return markov1(a,end);int x=a.get(end-2).combo,y=a.get(end-1).combo,m=0;double[]c=prior();double tot=6;for(int i=Math.max(2,end-1500);i<end;i++)if(a.get(i-2).combo==x&&a.get(i-1).combo==y){c[a.get(i).combo]++;tot++;m++;}for(int k=1;k<=4;k++)c[k]/=tot;c=norm(c);return m<5?mix(markov1(a,end),c,.75):c;}
    double[] binary(List<R>a,int end){double pr=bitProb(a,end,true),pf=bitProb(a,end,false),pl=1-pr,p3=1-pf;double[]p=new double[5];p[1]=pl*p3;p[2]=pl*pf;p[3]=pr*p3;p[4]=pr*pf;return norm(p);}
    double bitProb(List<R>a,int end,boolean right){int s=Math.max(0,end-24),pos=1;double ones=1.5,tot=3;for(int i=s;i<end;i++){int b=right?right(a.get(i).combo):four(a.get(i).combo);double w=pos++;if(b==1)ones+=w;tot+=w;}double f=ones/tot;if(end<2)return clamp(f,.08,.92);int last=right?right(a.get(end-1).combo):four(a.get(end-1).combo);double to=1.5,tt=3;for(int i=Math.max(1,end-800);i<end;i++){int prev=right?right(a.get(i-1).combo):four(a.get(i-1).combo);if(prev==last){int cur=right?right(a.get(i).combo):four(a.get(i).combo);if(cur==1)to++;tt++;}}return clamp(.6*f+.4*(to/tt),.08,.92);}
    int right(int c){return c==3||c==4?1:0;} int four(int c){return c==2||c==4?1:0;}
    double[] regime(List<R>a,int end){double[]s=freq(a,end,12,1),l=freq(a,end,60,.35);double tv=0;for(int k=1;k<=4;k++)tv+=Math.abs(s[k]-l[k]);tv*=.5;return mix(s,l,clamp(.4+1.8*tv,.4,.85));}
    double[] streak(List<R>a,int end){if(end<3)return freq(a,end,12,1);int last=a.get(end-1).combo,st=1;for(int i=end-2;i>=0&&a.get(i).combo==last&&st<5;i--)st++;double[]c=prior();double tot=6;int m=0;for(int i=Math.max(2,end-1500);i<end;i++){int prev=a.get(i-1).combo;if(prev!=last)continue;int ss=1;for(int j=i-2;j>=0&&a.get(j).combo==prev&&ss<5;j--)ss++;if(ss==st){c[a.get(i).combo]++;tot++;m++;}}for(int k=1;k<=4;k++)c[k]/=tot;c=norm(c);return m<5?mix(freq(a,end,12,1),c,.78):c;}
    double[] prior(){double[]p=new double[5];for(int k=1;k<=4;k++)p[k]=1.5;return p;} double[]uni(){double[]p=new double[5];for(int k=1;k<=4;k++)p[k]=.25;return p;}
    double[]mix(double[]a,double[]b,double wa){double[]p=new double[5];for(int k=1;k<=4;k++)p[k]=a[k]*wa+b[k]*(1-wa);return norm(p);}
    double[]norm(double[]p){double s=0;for(int k=1;k<=4;k++){if(Double.isNaN(p[k])||Double.isInfinite(p[k])||p[k]<0)p[k]=0;s+=p[k];}if(s<=0)return uni();for(int k=1;k<=4;k++)p[k]/=s;return p;}
    int argmin(double[]p){int b=1;for(int k=2;k<=4;k++)if(p[k]<p[b])b=k;return b;} double clamp(double x,double lo,double hi){return Math.max(lo,Math.min(hi,x));}
    String tripleFor(int c){switch(c){case 1:return "우 + 4줄 + 홀";case 2:return "우 + 3줄 + 짝";case 3:return "좌 + 4줄 + 짝";case 4:return "좌 + 3줄 + 홀";default:return "-";}}

    void savePending(List<R>d,A a){if(d.isEmpty()||sp.getLong(KPI,-1)>0)return;R last=d.get(0);sp.edit().putLong(KPI,nextIdx(last)).putInt(KPE,a.exclude).putInt(KPS,stake()).putFloat(KPO,(float)odds()).apply();}
    void resolvePending(List<R>d){long idx=sp.getLong(KPI,-1);int exc=sp.getInt(KPE,0);if(idx<=0||exc<1||exc>4)return;R actual=null;for(R r:d)if(r.idx==idx){actual=r;break;}if(actual==null)return;boolean ok=actual.combo!=exc;int s=sp.getInt(KPS,5000);double o=sp.getFloat(KPO,1.95f);double pnl=ok?successProfit(s,o):-3.0*s;int n=sp.getInt(KLT,0)+1,hit=sp.getInt(KLS,0)+(ok?1:0);double old=Double.longBitsToDouble(sp.getLong(KLP,Double.doubleToLongBits(0)));sp.edit().putInt(KLT,n).putInt(KLS,hit).putLong(KLP,Double.doubleToLongBits(old+pnl)).remove(KPI).remove(KPE).remove(KPS).remove(KPO).apply();}
    long nextIdx(R r){try{if(r.round<480)return Long.parseLong(r.date.substring(2,8)+String.format(Locale.US,"%04d",r.round+1));SimpleDateFormat f=new SimpleDateFormat("yyyyMMdd",Locale.US);Calendar c=Calendar.getInstance();c.setTime(f.parse(r.date));c.add(Calendar.DAY_OF_MONTH,1);String d=f.format(c.getTime());return Long.parseLong(d.substring(2,8)+"0001");}catch(Exception e){return r.idx+1;}}

    int stake(){try{return Math.max(1,Integer.parseInt(stake.getText().toString().trim()));}catch(Exception e){return 5000;}}
    double odds(){try{return Math.max(1.01,Double.parseDouble(odds.getText().toString().trim()));}catch(Exception e){return 1.95;}}
    double successProfit(int s,double o){return s*(2*o-3);} double breakEven(double o){return 3/(2*o);}
    double btProfit(HT h,int s,double o){return h.hit*successProfit(s,o)-(h.n-h.hit)*3.0*s;}
    void recalc(){List<R>d=load();if(d.isEmpty()){profit.setText("먼저 최신 결과를 조회하세요.");return;}renderProfit(analyze(d));}

    void render(A a,List<R>d){
        R last=d.get(0); next.setText(last.round<480?last.date+" · "+(last.round+1)+"회":"다음날 · 1회");
        triple.setText(a.triple); exclude.setText("제외조합: "+COMBO[a.exclude]+" · 모델점수 "+pct(a.p[a.exclude])); model.setText("삼치기 모델점수: "+pct(a.modelSuccess));
        StringBuilder cr=new StringBuilder();for(int i=0;i<a.rank.size();i++){int k=a.rank.get(i);cr.append(i+1).append("순위  ").append(COMBO[k]).append("   ").append(pct(a.p[k]));if(i==0)cr.append("   ← 자동 제외");if(i<a.rank.size()-1)cr.append("\n");}candidates.setText(cr);
        StringBuilder es=new StringBuilder();for(int e=0;e<ECOUNT;e++){EP p=a.perf[e];double all=p.n>0?(double)p.hit/p.n:0,rec=p.rn>0?(double)p.rhit/p.rn:0;es.append(EN[e]).append(" · ").append(p.hit).append("/").append(p.n).append(p.n>0?" "+pct(all):"").append(" | 최근 ").append(p.rhit).append("/").append(p.rn).append(p.rn>0?" "+pct(rec):"").append(" | 가중 ").append(String.format(Locale.KOREA,"%.2f",a.w[e]));if(e<ECOUNT-1)es.append("\n");}engines.setText(es);
        double all=a.hedge.n>0?(double)a.hedge.hit/a.hedge.n:0,recp=a.hedge.rn>0?(double)a.hedge.rhit/a.hedge.rn:0;backtest.setText("전체 순차검증: "+a.hedge.hit+"/"+a.hedge.n+(a.hedge.n>0?" = "+pct(all):"")+"\n최근 50회: "+a.hedge.rhit+"/"+a.hedge.rn+(a.hedge.rn>0?" = "+pct(recp):"")+"\n균등 무작위 기준 삼치기 성공률: 75.0%\n누적 저장 결과: "+d.size()+"회");
        int n=sp.getInt(KLT,0),hit=sp.getInt(KLS,0);double lp=Double.longBitsToDouble(sp.getLong(KLP,Double.doubleToLongBits(0)));live.setText("실전 삼치기 기록 · "+(n>0?hit+"/"+n+" = "+pct((double)hit/n)+" · 누적 "+signed(lp):"아직 없음"));
        StringBuilder rr=new StringBuilder();for(int i=0;i<Math.min(10,d.size());i++){R r=d.get(i);rr.append(i==0?"최신  ":"      ").append(r.date).append(" - ").append(r.round).append(" · ").append(COMBO[r.combo]);if(i<Math.min(10,d.size())-1)rr.append("\n");}recent.setText(rr);renderProfit(a);
    }

    void renderProfit(A a){int s=stake();double o=odds();profit.setText("1회 총 배팅: "+won(3.0*s)+" ("+won(s)+" × 3개)\n삼치기 성공(2/3): "+signed(successProfit(s,o))+"\n제외조합 출현(0/3): "+signed(-3.0*s)+"\n손익분기 성공률: "+pct(breakEven(o))+"\n현재 Hedge 백테스트 가상손익: "+signed(btProfit(a.hedge,s,o)));}
    String pct(double x){return String.format(Locale.KOREA,"%.1f%%",x*100);}String won(double x){return String.format(Locale.KOREA,"%,.0f원",x);}String signed(double x){return (x>=0?"+":"")+String.format(Locale.KOREA,"%,.0f원",x);}
    void setStatus(String s,boolean ok){status.setText(s);status.setTextColor(ok?Color.rgb(52,211,153):Color.LTGRAY);}

    void exportStart(){Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("application/json");i.putExtra(Intent.EXTRA_TITLE,"BubbleTripleHedge_"+new SimpleDateFormat("yyyyMMdd_HHmm",Locale.KOREA).format(new Date())+".json");startActivityForResult(i,EXP);}
    void importStart(){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("*/*");startActivityForResult(i,IMP);}
    JSONObject backupJson() throws Exception{JSONObject r=new JSONObject();r.put("format","BubbleTripleHedgeBackup");r.put("history",new JSONArray(sp.getString(KH,"[]")));JSONObject s=new JSONObject();s.put(KPI,sp.getLong(KPI,-1));s.put(KPE,sp.getInt(KPE,0));s.put(KPS,sp.getInt(KPS,5000));s.put(KPO,sp.getFloat(KPO,1.95f));s.put(KLT,sp.getInt(KLT,0));s.put(KLS,sp.getInt(KLS,0));s.put(KLP,sp.getLong(KLP,Double.doubleToLongBits(0)));r.put("state",s);return r;}
    void exportUri(Uri u){try{OutputStream o=getContentResolver().openOutputStream(u);if(o==null)throw new Exception("파일 열기 실패");o.write(backupJson().toString(2).getBytes("UTF-8"));o.close();Toast.makeText(this,"백업 완료",Toast.LENGTH_LONG).show();}catch(Exception e){Toast.makeText(this,"백업 실패: "+e.getMessage(),Toast.LENGTH_LONG).show();}}
    void importUri(Uri u){try{InputStream is=getContentResolver().openInputStream(u);BufferedReader br=new BufferedReader(new InputStreamReader(is,"UTF-8"));StringBuilder sb=new StringBuilder();String l;while((l=br.readLine())!=null)sb.append(l);br.close();JSONObject r=new JSONObject(sb.toString());if(!"BubbleTripleHedgeBackup".equals(r.optString("format")))throw new Exception("백업 형식이 다릅니다.");SharedPreferences.Editor ed=sp.edit().putString(KH,r.getJSONArray("history").toString());JSONObject s=r.optJSONObject("state");if(s!=null){ed.putLong(KPI,s.optLong(KPI,-1));ed.putInt(KPE,s.optInt(KPE,0));ed.putInt(KPS,s.optInt(KPS,5000));ed.putFloat(KPO,(float)s.optDouble(KPO,1.95));ed.putInt(KLT,s.optInt(KLT,0));ed.putInt(KLS,s.optInt(KLS,0));ed.putLong(KLP,s.optLong(KLP,Double.doubleToLongBits(0)));}ed.apply();Toast.makeText(this,"복원 완료",Toast.LENGTH_LONG).show();recreate();}catch(Exception e){new AlertDialog.Builder(this).setTitle("복원 실패").setMessage(e.getMessage()).setPositiveButton("확인",null).show();}}
    @Override protected void onActivityResult(int rc,int res,Intent data){super.onActivityResult(rc,res,data);if(res!=RESULT_OK||data==null||data.getData()==null)return;Uri u=data.getData();if(rc==EXP)exportUri(u);else if(rc==IMP)new AlertDialog.Builder(this).setTitle("백업 복원").setMessage("현재 기록을 백업파일로 교체할까요?").setNegativeButton("취소",null).setPositiveButton("복원",(d,w)->importUri(u)).show();}
    void resetConfirm(){new AlertDialog.Builder(this).setTitle("전체 기록 초기화").setMessage("누적 결과·실전 적중·수익 기록을 모두 삭제합니다.").setNegativeButton("취소",null).setPositiveButton("초기화",(d,w)->{sp.edit().clear().apply();recreate();}).show();}

    @Override protected void onDestroy(){h.removeCallbacks(autoTask);ex.shutdownNow();super.onDestroy();}
    static class R{long idx;String date;int round,combo;}
    static class EP{int id,n,hit,rn,rhit;}
    static class HT{int n,hit,rn,rhit;}
    static class A{double[]p,w;int exclude;String triple;double modelSuccess;List<Integer>rank;EP[]perf;HT hedge;}
}
