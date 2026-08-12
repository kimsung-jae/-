package com.bubbleladder.triplehedge;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.*;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQ_EXPORT=8001,REQ_IMPORT=8002,REQ_NOTI=8003;
    private final Handler h=new Handler(Looper.getMainLooper());
    private final ExecutorService ex=Executors.newSingleThreadExecutor();

    private TextView countdown,status,pickTitle,pickValue,score,zone,betPlan,rankings,stats,profit,recent,martinState,bgState;
    private EditText baseStake,odds;
    private Spinner lowMode;
    private CheckBox background;
    private Button refresh,saveSetting,backup,restore,reset;

    private final BroadcastReceiver receiver=new BroadcastReceiver(){
        @Override public void onReceive(Context context,Intent intent){ reloadAsync(); }
    };

    private final Runnable countdownTask=new Runnable(){
        @Override public void run(){
            countdown.setText(GameCore.countdownText());
            h.postDelayed(this,1000);
        }
    };

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        setContentView(buildUi());
        loadSettings();
        bindActions();
        registerUpdateReceiver();
        h.post(countdownTask);
        requestNotificationPermissionIfNeeded();
        if(GameCore.prefs(this).getBoolean("auto_enabled",true)) startAutoService();
        reloadAsync();
    }

    private View buildUi(){
        ScrollView sv=new ScrollView(this);sv.setFillViewport(true);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(14),dp(16),dp(14),dp(30));root.setBackgroundColor(Color.rgb(7,17,31));sv.addView(root);

        root.addView(tv("보글사다리3 · 실전 최강 1픽",24,Color.WHITE,true));
        TextView sub=tv("삼치기 제거 · 3개 항목 중 검증성적이 가장 좋은 1픽 자동선택",12,Color.rgb(125,211,252),false);sub.setPadding(0,dp(4),0,dp(14));root.addView(sub);

        LinearLayout clock=card();clock.addView(tv("다음 추첨까지",12,Color.rgb(148,163,184),false));
        countdown=tv("--:--",38,Color.rgb(56,189,248),true);clock.addView(countdown);
        bgState=tv("백그라운드 상태 확인 중",12,Color.rgb(203,213,225),false);bgState.setPadding(0,dp(4),0,0);clock.addView(bgState);root.addView(clock);

        LinearLayout control=card();
        refresh=button("🔄 지금 조회/분석",Color.rgb(3,105,161));control.addView(refresh,new LinearLayout.LayoutParams(-1,dp(54)));
        background=new CheckBox(this);background.setText("백그라운드 자동추첨 ON");background.setTextColor(Color.WHITE);background.setTextSize(15);background.setPadding(0,dp(8),0,0);control.addView(background);
        status=tv("조회 준비",12,Color.rgb(203,213,225),false);status.setPadding(0,dp(6),0,0);control.addView(status);root.addView(control);

        LinearLayout hero=card();hero.addView(tv("다음 회차 실전 최강 1픽",12,Color.rgb(148,163,184),false));
        pickTitle=tv("분석 대기",15,Color.WHITE,true);pickTitle.setPadding(0,dp(4),0,0);hero.addView(pickTitle);
        pickValue=tv("-",34,Color.rgb(52,211,153),true);hero.addView(pickValue);
        score=tv("모델 신뢰점수 -",14,Color.rgb(253,224,71),true);hero.addView(score);
        zone=tv("구간 -",14,Color.rgb(125,211,252),true);zone.setPadding(0,dp(3),0,0);hero.addView(zone);
        betPlan=tv("배팅계획 -",18,Color.WHITE,true);betPlan.setPadding(0,dp(10),0,0);hero.addView(betPlan);
        TextView caveat=tv("※ 모델 신뢰점수는 실제 당첨확률이 아닙니다. 미래 데이터를 보지 않는 순차검증 성적과 현재 신호를 함께 비교해 1픽을 고릅니다.",11,Color.rgb(148,163,184),false);caveat.setPadding(0,dp(9),0,0);hero.addView(caveat);root.addView(hero);

        LinearLayout ranks=card();ranks.addView(section("좌/우 · 줄수 · 홀짝 비교"));rankings=tv("-",14,Color.WHITE,false);rankings.setLineSpacing(0,1.30f);ranks.addView(rankings);root.addView(ranks);

        LinearLayout strategy=card();strategy.addView(section("배팅 설정 / 제한 마틴"));
        LinearLayout in=new LinearLayout(this);in.setOrientation(LinearLayout.HORIZONTAL);
        baseStake=input("5000");baseStake.setHint("기본 배팅금액");baseStake.setInputType(InputType.TYPE_CLASS_NUMBER);
        odds=input("1.95");odds.setHint("배당");odds.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);
        in.addView(baseStake,new LinearLayout.LayoutParams(0,dp(52),1));LinearLayout.LayoutParams olp=new LinearLayout.LayoutParams(0,dp(52),1);olp.setMargins(dp(8),0,0,0);in.addView(odds,olp);strategy.addView(in);

        TextView lowLabel=tv("저신뢰 구간 처리",12,Color.rgb(148,163,184),false);lowLabel.setPadding(0,dp(10),0,dp(4));strategy.addView(lowLabel);
        lowMode=new Spinner(this);String[] opts={"PASS (권장)","기본배팅"};ArrayAdapter<String> ad=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,opts);lowMode.setAdapter(ad);strategy.addView(lowMode,new LinearLayout.LayoutParams(-1,dp(50)));
        saveSetting=button("설정 저장",Color.rgb(30,64,175));LinearLayout.LayoutParams slp=new LinearLayout.LayoutParams(-1,dp(48));slp.setMargins(0,dp(8),0,0);strategy.addView(saveSetting,slp);
        martinState=tv("마틴 상태 -",14,Color.rgb(253,224,71),true);martinState.setPadding(0,dp(10),0,0);strategy.addView(martinState);
        TextView policy=tv("규칙: 기본배팅 → 1패 시 ×2 → 2패 후에는 강승구간일 때만 최종 ×4 허용. 당첨 시 즉시 기본배팅으로 복귀하며, 최종 ×4 결과 후에는 승패와 관계없이 체인을 종료합니다. 저신뢰 구간은 위 설정대로 PASS 또는 기본배팅합니다.",12,Color.rgb(203,213,225),false);policy.setPadding(0,dp(6),0,0);policy.setLineSpacing(0,1.2f);strategy.addView(policy);root.addView(strategy);

        LinearLayout st=card();st.addView(section("실전 / 모의 검증"));stats=tv("-",14,Color.WHITE,false);stats.setLineSpacing(0,1.28f);st.addView(stats);profit=tv("-",15,Color.rgb(52,211,153),true);profit.setPadding(0,dp(10),0,0);st.addView(profit);root.addView(st);

        LinearLayout rc=card();rc.addView(section("최근 10회 결과"));recent=tv("-",13,Color.WHITE,false);recent.setLineSpacing(0,1.25f);rc.addView(recent);root.addView(rc);

        LinearLayout data=card();data.addView(section("데이터 백업"));
        LinearLayout dr=new LinearLayout(this);dr.setOrientation(LinearLayout.HORIZONTAL);backup=button("💾 백업",Color.rgb(21,128,61));restore=button("📂 복원",Color.rgb(109,40,217));dr.addView(backup,new LinearLayout.LayoutParams(0,dp(50),1));LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(0,dp(50),1);rp.setMargins(dp(8),0,0,0);dr.addView(restore,rp);data.addView(dr);reset=button("전체 기록 초기화",Color.rgb(127,29,29));LinearLayout.LayoutParams rset=new LinearLayout.LayoutParams(-1,dp(46));rset.setMargins(0,dp(8),0,0);data.addView(reset,rset);root.addView(data);

        root.addView(tv("제한 마틴은 손실을 없애는 방법이 아니며, 연속 실패 시 손실폭이 커질 수 있습니다. 앱은 마틴을 최대 ×4에서 강제로 종료하도록 제한합니다.",11,Color.rgb(148,163,184),false));
        return sv;
    }

    private void bindActions(){
        refresh.setOnClickListener(v->manualSync());
        saveSetting.setOnClickListener(v->saveSettings());
        background.setOnCheckedChangeListener((v,on)->{GameCore.prefs(this).edit().putBoolean("auto_enabled",on).apply();if(on)startAutoService();else stopAutoService();});
        backup.setOnClickListener(v->startExport());restore.setOnClickListener(v->startImport());reset.setOnClickListener(v->confirmReset());
    }

    private void loadSettings(){
        android.content.SharedPreferences sp=GameCore.prefs(this);baseStake.setText(String.valueOf(sp.getInt(GameCore.K_BASE_STAKE,5000)));odds.setText(String.valueOf(sp.getFloat(GameCore.K_ODDS,1.95f)));lowMode.setSelection("BASE".equals(sp.getString(GameCore.K_LOW_MODE,"PASS"))?1:0);background.setChecked(sp.getBoolean("auto_enabled",true));
    }

    private void saveSettings(){
        int s=readStake();double o=readOdds();String low=lowMode.getSelectedItemPosition()==1?"BASE":"PASS";
        GameCore.prefs(this).edit().putInt(GameCore.K_BASE_STAKE,s).putFloat(GameCore.K_ODDS,(float)o).putString(GameCore.K_LOW_MODE,low).apply();
        Toast.makeText(this,"설정 저장 완료",Toast.LENGTH_SHORT).show();reloadAsync();
    }

    private int readStake(){try{return Math.max(1,Integer.parseInt(baseStake.getText().toString().trim()));}catch(Exception e){return 5000;}}
    private double readOdds(){try{return Math.max(1.01,Double.parseDouble(odds.getText().toString().trim()));}catch(Exception e){return 1.95;}}

    private void manualSync(){status.setText("최신 결과 조회 및 분석 중...");refresh.setEnabled(false);ex.execute(()->{try{GameCore.sync(this);runOnUiThread(()->{status.setText("● 조회 완료 · "+new SimpleDateFormat("HH:mm:ss",Locale.KOREA).format(new Date()));refresh.setEnabled(true);reloadAsync();});}catch(Exception e){runOnUiThread(()->{status.setText("조회 실패: "+e.getMessage());refresh.setEnabled(true);});}});}

    private void reloadAsync(){ex.execute(()->{List<GameCore.Result> history=GameCore.loadHistory(this);GameCore.Analysis a=history.isEmpty()?null:GameCore.analyze(history);runOnUiThread(()->render(a,history));});}

    private void render(GameCore.Analysis a,List<GameCore.Result> history){
        android.content.SharedPreferences sp=GameCore.prefs(this);
        bgState.setText(background.isChecked()?"● 백그라운드 자동추첨 작동 · 알림에서 1픽/승률 확인":"○ 백그라운드 자동추첨 OFF");
        int stage=sp.getInt(GameCore.K_MARTIN_STAGE,0);martinState.setText(stage==0?"마틴 상태: 기본단계":stage==1?"마틴 상태: 1패 · 다음 유효구간 ×2":"마틴 상태: 2패 · 강승구간만 최종 ×4");
        if(a==null||a.best==null){pickTitle.setText("분석 대기");pickValue.setText("-");score.setText("모델 신뢰점수 -");zone.setText("구간 -");betPlan.setText("배팅계획 -");rankings.setText("-");stats.setText("데이터를 먼저 조회하세요.");profit.setText("-");return;}

        GameCore.DimAnalysis best=a.best;pickTitle.setText(best.label().split(" · ")[0]+" 최강1픽");pickValue.setText(GameCore.VALUE_NAME[best.dim][best.pick]);score.setText("모델 신뢰점수 "+GameCore.pct(best.quality)+" · 현재신호 "+GameCore.pct(best.confidence));zone.setText("구간: "+GameCore.zoneKo(a.zone));
        String pendingAction=sp.getString(GameCore.K_PENDING_ACTION,"");int pendingAmount=sp.getInt(GameCore.K_PENDING_STAKE,0);String lastBet=sp.getString(GameCore.K_LAST_BET,"-");betPlan.setText("배팅계획: "+(!pendingAction.isEmpty()?lastBet:"분석 후 결정"));

        GameCore.DimAnalysis[] copy=a.dims.clone();Arrays.sort(copy,(x,y)->Double.compare(y.quality,x.quality));StringBuilder rs=new StringBuilder();for(int i=0;i<copy.length;i++){GameCore.DimAnalysis d=copy[i];rs.append(i+1).append("위  ").append(d.label()).append("  · 신뢰 ").append(GameCore.pct(d.quality)).append(" · 현재 ").append(GameCore.pct(d.confidence)).append("\n     순차검증 ").append(d.btHit).append("/").append(d.btN).append(" ").append(GameCore.pct(d.btRate())).append(" · 최근60 ").append(d.recentHit).append("/").append(d.recentN).append(" ").append(GameCore.pct(d.recentRate()));if(i<copy.length-1)rs.append("\n");}rankings.setText(rs.toString());

        int predN=sp.getInt(GameCore.K_PRED_TOTAL,0),predH=sp.getInt(GameCore.K_PRED_HIT,0),bets=sp.getInt(GameCore.K_LIVE_BETS,0),wins=sp.getInt(GameCore.K_LIVE_WINS,0),passes=sp.getInt(GameCore.K_PASS_COUNT,0);double pnl=Double.longBitsToDouble(sp.getLong(GameCore.K_LIVE_PROFIT_BITS,Double.doubleToLongBits(0)));
        stats.setText("전체 1픽 예측: "+(predN>0?predH+"/"+predN+" = "+GameCore.pct((double)predH/predN):"-")+"\n최근20 예측: "+GameCore.recentPredictionRate(this,20)+"\n최근50 예측: "+GameCore.recentPredictionRate(this,50)+"\n실제 가상배팅: "+(bets>0?wins+"/"+bets+" = "+GameCore.pct((double)wins/bets):"-")+" · PASS "+passes+"회\n누적 결과 데이터: "+history.size()+"회");
        profit.setText("누적 가상수익: "+signedMoney(pnl)+" · 현재 기본금액 "+GameCore.money(sp.getInt(GameCore.K_BASE_STAKE,5000))+" · 배당 "+String.format(Locale.KOREA,"%.2f",sp.getFloat(GameCore.K_ODDS,1.95f)));

        String[] combos={"","좌3짝","좌4홀","우3홀","우4짝"};StringBuilder rr=new StringBuilder();for(int i=0;i<Math.min(10,history.size());i++){GameCore.Result r=history.get(i);rr.append(i==0?"최신  ":"      ").append(r.date).append(" · ").append(r.round).append("회 · ").append(combos[r.combo]);if(i<Math.min(10,history.size())-1)rr.append("\n");}recent.setText(rr.toString());
    }

    private String signedMoney(double v){return (v>=0?"+":"")+String.format(Locale.KOREA,"%,.0f원",v);}

    private void startAutoService(){
        requestNotificationPermissionIfNeeded();Intent i=new Intent(this,AutoDrawService.class);if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);bgState.setText("● 백그라운드 자동추첨 ON");
    }
    private void stopAutoService(){stopService(new Intent(this,AutoDrawService.class));bgState.setText("○ 백그라운드 자동추첨 OFF");}

    private void requestNotificationPermissionIfNeeded(){if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},REQ_NOTI);}

    private void registerUpdateReceiver(){IntentFilter f=new IntentFilter(GameCore.ACTION_UPDATED);if(Build.VERSION.SDK_INT>=33)registerReceiver(receiver,f,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(receiver,f);}

    private void startExport(){Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("application/json");i.putExtra(Intent.EXTRA_TITLE,"BubbleSinglePick_"+new SimpleDateFormat("yyyyMMdd_HHmm",Locale.KOREA).format(new Date())+".json");startActivityForResult(i,REQ_EXPORT);}
    private void startImport(){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("*/*");startActivityForResult(i,REQ_IMPORT);}
    @Override protected void onActivityResult(int rc,int result,Intent data){super.onActivityResult(rc,result,data);if(result!=RESULT_OK||data==null||data.getData()==null)return;Uri u=data.getData();if(rc==REQ_EXPORT)exportUri(u);else if(rc==REQ_IMPORT)new AlertDialog.Builder(this).setTitle("백업 복원").setMessage("현재 기록을 백업파일 내용으로 교체할까요?").setNegativeButton("취소",null).setPositiveButton("복원",(d,w)->importUri(u)).show();}
    private void exportUri(Uri u){try{OutputStream os=getContentResolver().openOutputStream(u);os.write(GameCore.backupJson(this).getBytes("UTF-8"));os.close();Toast.makeText(this,"백업 완료",Toast.LENGTH_LONG).show();}catch(Exception e){Toast.makeText(this,"백업 실패: "+e.getMessage(),Toast.LENGTH_LONG).show();}}
    private void importUri(Uri u){try{InputStream is=getContentResolver().openInputStream(u);BufferedReader br=new BufferedReader(new InputStreamReader(is,"UTF-8"));StringBuilder sb=new StringBuilder();String l;while((l=br.readLine())!=null)sb.append(l);br.close();GameCore.restoreJson(this,sb.toString());Toast.makeText(this,"복원 완료",Toast.LENGTH_LONG).show();loadSettings();reloadAsync();}catch(Exception e){new AlertDialog.Builder(this).setTitle("복원 실패").setMessage(e.getMessage()).setPositiveButton("확인",null).show();}}
    private void confirmReset(){new AlertDialog.Builder(this).setTitle("전체 기록 초기화").setMessage("누적 결과, 예측, 가상수익, 마틴 상태를 모두 지울까요?").setNegativeButton("취소",null).setPositiveButton("초기화",(d,w)->{boolean autoOn=background.isChecked();GameCore.prefs(this).edit().clear().putBoolean("auto_enabled",autoOn).apply();loadSettings();reloadAsync();}).show();}

    private LinearLayout card(){LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);c.setPadding(dp(14),dp(14),dp(14),dp(14));c.setBackground(round(Color.rgb(17,24,39),18));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,0,0,dp(12));c.setLayoutParams(lp);return c;}
    private TextView section(String s){TextView v=tv(s,17,Color.WHITE,true);v.setPadding(0,0,0,dp(9));return v;}
    private TextView tv(String s,float z,int c,boolean bold){TextView v=new TextView(this);v.setText(s);v.setTextSize(z);v.setTextColor(c);if(bold)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return v;}
    private Button button(String s,int c){Button b=new Button(this);b.setText(s);b.setTextColor(Color.WHITE);b.setTextSize(13);b.setAllCaps(false);b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);b.setBackground(round(c,14));return b;}
    private EditText input(String s){EditText e=new EditText(this);e.setText(s);e.setTextColor(Color.WHITE);e.setHintTextColor(Color.GRAY);e.setTextSize(15);e.setSingleLine(true);e.setPadding(dp(10),0,dp(10),0);e.setBackground(round(Color.rgb(30,41,59),12));return e;}
    private GradientDrawable round(int c,int r){GradientDrawable g=new GradientDrawable();g.setColor(c);g.setCornerRadius(dp(r));return g;}
    private int dp(int v){return (int)(v*getResources().getDisplayMetrics().density+.5f);}

    @Override protected void onDestroy(){h.removeCallbacksAndMessages(null);try{unregisterReceiver(receiver);}catch(Exception ignored){}ex.shutdownNow();super.onDestroy();}
}
