package br.com.aprender.numeros;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.util.Locale;

public class MainActivity extends Activity {
    private WebView webView;
    private TextToSpeech voz;
    private boolean vozPronta = false;
    private Integer falaPendente = null;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        voz = new TextToSpeech(getApplicationContext(), status -> {
            if (status == TextToSpeech.SUCCESS && voz != null) {
                int idioma = voz.setLanguage(new Locale("pt", "BR"));
                voz.setSpeechRate(0.9f);
                vozPronta = idioma != TextToSpeech.LANG_MISSING_DATA
                        && idioma != TextToSpeech.LANG_NOT_SUPPORTED;
                if (vozPronta && falaPendente != null) {
                    pronunciar(falaPendente);
                    falaPendente = null;
                }
            }
        });
        webView = new WebView(this);
        webView.setBackgroundColor(Color.rgb(255,107,107));
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(false);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setBlockNetworkLoads(true);
        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, String url) {
                return !url.startsWith("file:///android_asset/");
            }
        });
        webView.addJavascriptInterface(new PonteVoz(), "AndroidVoz");
        setContentView(webView);
        webView.loadUrl("file:///android_asset/index.html");
    }
    private void pronunciar(int numero) {
        if (vozPronta && voz != null) {
            voz.speak(Integer.toString(numero), TextToSpeech.QUEUE_FLUSH, null, "numero_"+numero);
        } else { falaPendente = numero; }
    }
    public final class PonteVoz {
        @JavascriptInterface public void falarNumero(String texto) {
            try {
                int valor = Integer.parseInt(texto);
                if (valor >= 0 && valor <= 100) runOnUiThread(() -> pronunciar(valor));
            } catch (NumberFormatException ignored) { }
        }
        @JavascriptInterface public void pararFala() {
            runOnUiThread(() -> {
                falaPendente = null;
                if (voz != null) voz.stop();
            });
        }
    }
    @Override protected void onPause() {
        if (voz != null) voz.stop();
        super.onPause();
    }
    @Override protected void onDestroy() {
        if (webView != null) {
            webView.removeJavascriptInterface("AndroidVoz");
            webView.destroy();
        }
        if (voz != null) {
            voz.stop(); voz.shutdown(); voz = null;
        }
        super.onDestroy();
    }
}
