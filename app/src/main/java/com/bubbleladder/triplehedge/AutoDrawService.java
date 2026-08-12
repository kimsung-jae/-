package com.bubbleladder.triplehedge;

import android.app.*;
import android.content.*;
import android.os.*;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AutoDrawService extends Service {
    public static final String CHANNEL_ID="bubble_single_live";
    public static final int NOTI_ID=2201;
    private final Handler h=new Handler(Looper.getMainLooper());
    private final ExecutorService ex=Executors.newSingleThreadExecutor();
    private boolean syncing=false;
    private int retry=0;

    private final Runnable notificationTick=new Runnable(){
        @Override public void run(){
            updateNotification();
            h.postDelayed(this,5000);
        }
    };

    private final Runnable fetchTask=new Runnable(){
        @Override public void run(){ doSync(); }
    };

    @Override public void onCreate(){
        super.onCreate();
        createChannel();
        startForeground(NOTI_ID,buildNotification());
        h.post(notificationTick);
        h.post(fetchTask); // immediate sync on service start
    }

    @Override public int onStartCommand(Intent intent,int flags,int startId){
        return START_STICKY;
    }

    private void doSync(){
        if(syncing)return;
        syncing=true;
        ex.execute(()->{
            boolean advanced=false;
            try{
                GameCore.SyncResult sr=GameCore.sync(this);
                advanced=sr.newRoundResolved;
            }catch(Exception ignored){}
            final boolean ok=advanced;
            h.post(()->{
                syncing=false;
                sendBroadcast(new Intent(GameCore.ACTION_UPDATED).setPackage(getPackageName()));
                updateNotification();
                h.removeCallbacks(fetchTask);
                if(ok){
                    retry=0;
                    scheduleAtNextDraw();
                }else{
                    // API may publish a few seconds after the 3-minute boundary.
                    if(retry<3){ retry++; h.postDelayed(fetchTask,12000); }
                    else { retry=0; scheduleAtNextDraw(); }
                }
            });
        });
    }

    private void scheduleAtNextDraw(){
        long delay=GameCore.millisToNextDraw()+7000L;
        h.postDelayed(fetchTask,delay);
    }

    private void createChannel(){
        if(Build.VERSION.SDK_INT>=26){
            NotificationChannel ch=new NotificationChannel(CHANNEL_ID,"보글사다리 자동추첨",NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("다음 추첨시간, 1픽, 실전 승률을 표시합니다.");
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(){
        Intent open=new Intent(this,MainActivity.class);
        PendingIntent pi=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        SharedPreferences sp=GameCore.prefs(this);
        String pick=sp.getString(GameCore.K_LAST_PICK,"분석 대기");
        String zone=sp.getString(GameCore.K_LAST_ZONE,"-");
        String bet=sp.getString(GameCore.K_LAST_BET,"-");
        int n=sp.getInt(GameCore.K_LIVE_BETS,0),w=sp.getInt(GameCore.K_LIVE_WINS,0);
        String rate=n>0?GameCore.pct((double)w/n):"-";
        String text="다음 "+GameCore.countdownText()+" · "+pick+" · "+zone+" · "+bet+" · 승률 "+rate;
        Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CHANNEL_ID):new Notification.Builder(this);
        return b.setSmallIcon(android.R.drawable.ic_popup_sync)
                .setContentTitle("보글사다리 최강1픽 · 백그라운드 ON")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pi)
                .build();
    }

    private void updateNotification(){
        NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        nm.notify(NOTI_ID,buildNotification());
    }

    @Override public void onDestroy(){
        h.removeCallbacksAndMessages(null);
        ex.shutdownNow();
        super.onDestroy();
    }

    @Override public android.os.IBinder onBind(Intent intent){return null;}
}
