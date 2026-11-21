package br.com.audio15.mobile;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.provider.MediaStore;
import android.content.Context;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.FFprobeKit;
import com.arthenica.ffmpegkit.MediaInformation;
import com.arthenica.ffmpegkit.MediaInformationSession;
import com.arthenica.ffmpegkit.ReturnCode;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;

import org.json.JSONArray;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;
import java.text.SimpleDateFormat;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public final class DownloadService extends Service {
    public static final String ACTION_ENQUEUE="br.com.audio15.ENQUEUE";
    public static final String ACTION_CANCEL="br.com.audio15.CANCEL";
    private static final String CHANNEL="audio15_jobs";
    private static final int NOTIFICATION_ID=101;
    private static final long MAX_BYTES=15_000_000L;
    private static final double MAX_SECONDS=3600.0;
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private final AtomicInteger queued=new AtomicInteger(0);
    private volatile boolean canceled=false;
    private volatile String currentProcessId=null;
    private volatile boolean working=false;
    private int lastPercent=-1;

    @Override public void onCreate() {
        super.onCreate();
        NotificationChannel channel = new NotificationChannel(CHANNEL,"Audio15 Downloads",NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("Andamento da conversão MP3");
        ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
    }
    @Override public IBinder onBind(Intent i){return null;}
    @Override public int onStartCommand(Intent i,int flags,int startId) {
        if(i==null)return START_NOT_STICKY;
        if(ACTION_CANCEL.equals(i.getAction())) {
            canceled=true;
            String process=currentProcessId;
            if(process!=null) {
                try { YoutubeDL.getInstance().destroyProcessById(process); } catch(Exception ignored){}
            }
            try{FFmpegKit.cancel();}catch(Exception ignored){}
            report("Cancelamento solicitado...",0,false);
            return START_NOT_STICKY;
        }
        if(!ACTION_ENQUEUE.equals(i.getAction()))return START_NOT_STICKY;
        String type=i.getStringExtra("type"), value=i.getStringExtra("value");
        if(type==null||value==null)return START_NOT_STICKY;
        if(!"file".equals(type)&&(!"link".equals(type)||!AudioJob.isSupportedUrl(value))) {
            report("Link inválido",0,false);
            return START_NOT_STICKY;
        }
        if(queued.getAndIncrement()==0) {
            startForeground(NOTIFICATION_ID,buildNotification("Iniciando Audio15 Mobile",0,true));
        }
        executor.execute(()->{
            canceled=false;
            working=true;
            try { process(type,value); }
            catch(Exception e) {
                if(canceled) report("Conversão cancelada",0,false);
                else report("Erro: "+AudioJob.niceError(e.getMessage()),0,false);
            } finally {
                working=false;
                currentProcessId=null;
                int remaining=queued.decrementAndGet();
                if(remaining<=0) {
                    stopForeground(STOP_FOREGROUND_REMOVE);
                    stopSelfResult(startId);
                } else {
                    report(remaining+" solicitação(ões) na fila",0,true);
                }
            }
        });
        return START_NOT_STICKY;
    }
    private Notification buildNotification(String msg,int pct,boolean ongoing) {
        Intent open=new Intent(this,MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi=PendingIntent.getActivity(this,11,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b=new Notification.Builder(this,CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Audio15 Mobile")
            .setContentText(msg)
            .setContentIntent(pi)
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing);
        if(ongoing) {
            b.setProgress(100,Math.max(0,Math.min(100,pct)),pct==0);
            Intent c=new Intent(this,DownloadService.class);
            c.setAction(ACTION_CANCEL);
            PendingIntent cancel=PendingIntent.getService(this,12,c,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            b.addAction(android.R.drawable.ic_menu_close_clear_cancel,"Parar",cancel);
        }
        return b.build();
    }
    private void report(String status,int percent,boolean ongoing) {
        getSharedPreferences("audio15",Context.MODE_PRIVATE).edit().putString("status",status).apply();
        ((NotificationManager)getSystemService(NOTIFICATION_SERVICE))
            .notify(ongoing?NOTIFICATION_ID:NOTIFICATION_ID+1,buildNotification(status,percent,ongoing));
    }
    private void process(String type,String value)throws Exception {
        File workDir=new File(getCacheDir(),"audio15_"+System.currentTimeMillis()+"_"+Thread.currentThread().getId());
        if(!workDir.mkdirs())throw new Exception("Não foi possível preparar a pasta temporária");
        try {
            File source;
            if("file".equals(type)) {
                source=new File(workDir,"origem.bin");
                report("Lendo o vídeo compartilhado...",0,true);
                try(InputStream in=getContentResolver().openInputStream(Uri.parse(value));
                    OutputStream out=new FileOutputStream(source)) {
                    if(in==null)throw new Exception("Sem acesso ao arquivo compartilhado");
                    byte[] buf=new byte[1024*128];int n;
                    while((n=in.read(buf))!=-1) {
                        if(canceled)throw new Exception("Cancelado");
                        out.write(buf,0,n);
                    }
                }
            } else {
                report("Preparando download do vídeo...",0,true);
                YoutubeDL.getInstance().init(getApplicationContext());
                YoutubeDLRequest req=new YoutubeDLRequest(value);
                req.addOption("--no-playlist");
                req.addOption("-f","bestaudio/best");
                req.addOption("--no-part");
                req.addOption("-o",new File(workDir,"origem.%(ext)s").getAbsolutePath());
                // Faz apenas o download da faixa de áudio quando a plataforma permite.
                String processId="audio15_"+System.nanoTime();
                currentProcessId=processId;
                lastPercent=-1;
                YoutubeDL.getInstance().execute(req,processId,(pct,eta,line)->{
                    int percentage=Math.max(0,Math.min(100,pct.intValue()));
                    if(percentage!=lastPercent) {
                        lastPercent=percentage;
                        report("Baixando áudio: "+percentage+"%",percentage,true);
                    }
                    return kotlin.Unit.INSTANCE;
                });
                currentProcessId=null;
                File[] files=workDir.listFiles(file->file.isFile()&&file.getName().startsWith("origem."));
                if(files==null||files.length==0)throw new Exception("Nenhuma faixa de áudio encontrada");
                Arrays.sort(files,Comparator.comparingLong(File::length).reversed());
                source=files[0];
            }
            if(canceled)throw new Exception("Cancelado");
            if(source.length()==0)throw new Exception("Vídeo sem áudio");
            report("Analisando a duração...",0,true);
            MediaInformationSession probe=FFprobeKit.getMediaInformation(source.getAbsolutePath());
            MediaInformation media=probe.getMediaInformation();
            if(media==null||media.getDuration()==null)throw new Exception("Não foi possível verificar a duração do áudio");
            double duration=Double.parseDouble(media.getDuration());
            if(!Double.isFinite(duration)||duration<=0)throw new Exception("Duração de áudio inválida");
            // O intervalo de 3600 s mantém o exemplo 1h26 -> 1h + 26min.
            // Pequenas variações de container (<500ms) não criam uma parte vazia.
            int parts=(int)Math.ceil((duration-0.5)/MAX_SECONDS);
            parts=Math.max(1,parts);
            if(parts>100)throw new Exception("Áudio muito longo para processamento");
            String stamp=new SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(new Date());
            JSONArray saved=new JSONArray();
            for(int k=0;k<parts;k++) {
                if(canceled)throw new Exception("Cancelado");
                double start=k*MAX_SECONDS;
                double piece=Math.min(MAX_SECONDS,Math.max(0,duration-start));
                // Limita a duração física mesmo com preenchimento de quadros MP3.
                piece=Math.min(piece,3599.96);
                if(piece<=0.01)continue;
                report("Convertendo MP3 32 kbps: parte "+(k+1)+" de "+parts,0,true);
                File output=new File(workDir,String.format(Locale.US,"parte_%02d.mp3",k+1));
                String[] args={"-hide_banner","-loglevel","error","-y",
                    "-ss",String.format(Locale.US,"%.3f",start),
                    "-i",source.getAbsolutePath(),
                    "-t",String.format(Locale.US,"%.3f",piece),
                    "-map","0:a:0","-vn","-c:a","libmp3lame","-b:a","32k",
                    "-ac","1","-ar","44100","-map_metadata","-1",
                    "-id3v2_version","3","-write_xing","0",
                    output.getAbsolutePath()};
                FFmpegSession session=FFmpegKit.executeWithArguments(args);
                if(!ReturnCode.isSuccess(session.getReturnCode())||!output.exists()) {
                    throw new Exception("Falha na conversão MP3 da parte "+(k+1));
                }
                if(output.length()>MAX_BYTES)throw new Exception("Parte "+(k+1)+" excedeu 15 MB");
                if(output.length()==0)throw new Exception("MP3 vazio");
                String name="Audio15_"+stamp+"_parte_"+String.format(Locale.US,"%02d",k+1)+".mp3";
                Uri uri=publishMp3(output,name);
                saved.put(uri.toString());
                // Recuperação de partes prontas caso a última parte apresente erro.
                getSharedPreferences("audio15",MODE_PRIVATE).edit().putString("last_uris",saved.toString()).apply();
            }
            if(canceled)throw new Exception("Cancelado");
            if(saved.length()==0)throw new Exception("Nenhum MP3 foi gerado");
            report("Concluído: "+saved.length()+" MP3(s) em Downloads/Audio15. Toque para compartilhar no WhatsApp.",100,false);
        } finally {
            deleteWork(workDir);
        }
    }
    private Uri publishMp3(File file,String name)throws Exception {
        ContentValues values=new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME,name);
        values.put(MediaStore.MediaColumns.MIME_TYPE,"audio/mpeg");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH,"Download/Audio15");
        values.put(MediaStore.MediaColumns.IS_PENDING,1);
        Uri uri=getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values);
        if(uri==null)throw new Exception("Não foi possível salvar o MP3");
        boolean success=false;
        try(OutputStream dst=getContentResolver().openOutputStream(uri);
            InputStream in=new FileInputStream(file)) {
            if(dst==null)throw new Exception("Não foi possível criar o arquivo MP3");
            byte[] buf=new byte[64*1024];int n;
            while((n=in.read(buf))!=-1)dst.write(buf,0,n);
            success=true;
        } finally {
            if(!success)getContentResolver().delete(uri,null,null);
        }
        values.clear();
        values.put(MediaStore.MediaColumns.IS_PENDING,0);
        getContentResolver().update(uri,values,null,null);
        return uri;
    }
    private void deleteWork(File dir) {
        if(dir==null||!dir.exists())return;
        File[] files=dir.listFiles();
        if(files!=null)for(File f:files){try{f.delete();}catch(Exception ignored){}}
        try{dir.delete();}catch(Exception ignored){}
    }
    @Override public void onDestroy() {
        canceled=true;
        executor.shutdownNow();
        super.onDestroy();
    }
}
