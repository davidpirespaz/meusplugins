package br.com.audio15.mobile;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.content.ActivityNotFoundException;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import java.util.ArrayList;

public final class MainActivity extends Activity {
    private EditText linkField;
    private TextView statusField;
    private Button shareButton;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable poll = new Runnable() {
        @Override public void run() { updateStatus(); handler.postDelayed(this, 800); }
    };
    private int dp(float n) { return (int)(getResources().getDisplayMetrics().density*n+0.5f); }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        processShared(getIntent());
    }
    @Override protected void onResume() { super.onResume(); handler.post(poll); }
    @Override protected void onPause() { handler.removeCallbacks(poll); super.onPause(); }
    @Override public void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        processShared(i);
    }

    private TextView text(String s, int size, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        return t;
    }
    private Button button(String label) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(25,134,105)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(56));
        params.topMargin = dp(11);
        b.setLayoutParams(params);
        return b;
    }
    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(13,49,47));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22),dp(42),dp(22),dp(35));

        TextView title = text("Audio15 Mobile",32,Color.WHITE);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);
        TextView subtitle = text("MP3 32 kbps • até 1 h • até 15 MB • pronto para WhatsApp",15,Color.rgb(168,243,202));
        subtitle.setPadding(0,dp(12),0,dp(25));
        root.addView(subtitle);

        linkField = new EditText(this);
        linkField.setHint("Cole o link do YouTube, Instagram ou X");
        linkField.setTextColor(Color.BLACK);
        linkField.setHintTextColor(Color.DKGRAY);
        linkField.setBackgroundColor(Color.WHITE);
        linkField.setMinLines(2);
        linkField.setMaxLines(4);
        linkField.setTextSize(15);
        linkField.setPadding(dp(12),dp(12),dp(12),dp(12));
        root.addView(linkField,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(100)));

        Button start = button("Baixar áudio em MP3 (32 kbps)");
        start.setOnClickListener(v -> {
            String u=AudioJob.extractUrl(linkField.getText().toString());
            if(u==null) { Toast.makeText(this,"Informe um link do YouTube, Instagram ou X",Toast.LENGTH_LONG).show(); return; }
            startJob("link",u);
        });
        root.addView(start);

        Button cancel = button("Parar download / conversão");
        cancel.setOnClickListener(v -> {
            Intent i = new Intent(this,DownloadService.class);
            i.setAction(DownloadService.ACTION_CANCEL);
            startService(i);
            Toast.makeText(this,"Cancelamento solicitado",Toast.LENGTH_SHORT).show();
        });
        root.addView(cancel);

        statusField = text("Nenhum download iniciado",16,Color.WHITE);
        statusField.setPadding(0,dp(22),0,dp(12));
        root.addView(statusField);

        shareButton = button("Compartilhar áudio no WhatsApp");
        shareButton.setEnabled(false);
        shareButton.setOnClickListener(v -> shareLastFiles());
        root.addView(shareButton);

        TextView help = text("COMO USAR SEM COPIAR O LINK\n\nNo YouTube, Instagram e X: toque em Compartilhar → Audio15 Mobile. O download começa automaticamente.\n\nNo WhatsApp: compartilhe o próprio arquivo de vídeo → Audio15 Mobile.\n\nOs arquivos são salvos em Downloads/Audio15, em MP3 compatível com WhatsApp.\n\nDownloads privados, protegidos ou sem permissão podem falhar. Use apenas conteúdos que você tem autorização para salvar.",15,Color.rgb(214,235,229));
        help.setPadding(0,dp(32),0,0);
        root.addView(help);

        scroll.addView(root);
        setContentView(scroll);
    }

    private void processShared(Intent i) {
        if (i==null || !Intent.ACTION_SEND.equals(i.getAction())) return;
        Uri uri;
        if (Build.VERSION.SDK_INT >= 33)
            uri = i.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
        else
            uri = i.getParcelableExtra(Intent.EXTRA_STREAM);
        if(uri!=null) {
            startJob("file",uri.toString());
            return;
        }
        String url=AudioJob.extractUrl(i.getStringExtra(Intent.EXTRA_TEXT));
        if(url!=null) {
            if(linkField!=null)linkField.setText(url);
            startJob("link",url);
        } else Toast.makeText(this,"Link ou arquivo não reconhecido",Toast.LENGTH_LONG).show();
    }

    private void startJob(String type,String value) {
        if(value==null || value.isEmpty())return;
        Intent i=new Intent(this,DownloadService.class);
        i.setAction(DownloadService.ACTION_ENQUEUE);
        i.putExtra("type",type);
        i.putExtra("value",value);
        try {
            if(Build.VERSION.SDK_INT>=26) startForegroundService(i); else startService(i);
            Toast.makeText(this,"Solicitação enviada para a fila",Toast.LENGTH_SHORT).show();
        }catch(Exception e){
            Toast.makeText(this,"Não foi possível iniciar: "+e.getMessage(),Toast.LENGTH_LONG).show();
        }
    }

    private void updateStatus() {
        android.content.SharedPreferences p=getSharedPreferences("audio15",MODE_PRIVATE);
        statusField.setText(p.getString("status","Nenhum download iniciado"));
        shareButton.setEnabled(p.getString("last_uris","").length()>3);
    }

    private void shareLastFiles() {
        String raw=getSharedPreferences("audio15",MODE_PRIVATE).getString("last_uris","");
        try {
            JSONArray arr=new JSONArray(raw);
            ArrayList<Uri> uris =new ArrayList<>();
            for(int j=0;j<arr.length();j++)uris.add(Uri.parse(arr.getString(j)));
            if(uris.isEmpty())throw new IllegalStateException("Não há arquivos");
            Intent share = new Intent(uris.size()==1?Intent.ACTION_SEND:Intent.ACTION_SEND_MULTIPLE);
            share.setType("audio/mpeg");
            if(uris.size()==1)share.putExtra(Intent.EXTRA_STREAM,uris.get(0));
            else share.putParcelableArrayListExtra(Intent.EXTRA_STREAM,uris);
            ClipData cd=ClipData.newUri(getContentResolver(),"MP3 Audio15",uris.get(0));
            for(int j=1;j<uris.size();j++)cd.addItem(new ClipData.Item(uris.get(j)));
            share.setClipData(cd);
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try {
                share.setPackage("com.whatsapp");
                startActivity(share);
            } catch(ActivityNotFoundException e) {
                share.setPackage(null);
                startActivity(Intent.createChooser(share,"Enviar MP3 para..."));
            }
        } catch(Exception e) {
            Toast.makeText(this,"Não foi possível compartilhar: "+e.getMessage(),Toast.LENGTH_LONG).show();
        }
    }
}
