package br.com.audio15.mobile;

import android.net.Uri;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Link parsing only: never downloads from an arbitrary URL host. */
public final class AudioJob {
    private AudioJob() {}
    public static String extractUrl(String text) {
        if (text == null) return null;
        Matcher m = Pattern.compile("https?://[^\\s<>\\\"']+", Pattern.CASE_INSENSITIVE).matcher(text);
        while (m.find()) {
            String url = m.group().replaceAll("[\\)\\]\\.,;!]+$", "");
            if (isSupportedUrl(url)) return url;
        }
        return null;
    }
    public static boolean isSupportedUrl(String text) {
        if (text == null) return false;
        try {
            Uri u = Uri.parse(text.trim());
            String scheme = u.getScheme();
            if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) return false;
            String host = u.getHost();
            if (host == null) return false;
            host = host.toLowerCase(Locale.ROOT);
            String[] valid = {"youtube.com", "youtu.be", "youtube-nocookie.com", "instagram.com", "twitter.com", "x.com", "t.co"};
            for (String h: valid)
                if (host.equals(h) || host.endsWith("." + h)) return true;
            return false;
        } catch (Exception ignored) { return false; }
    }
    public static String niceError(String message) {
        if (message == null || message.trim().isEmpty()) return "Falha ao processar o vídeo";
        message = message.replaceAll("[\\r\\n]+", " ").trim();
        return message.length() > 175 ? message.substring(0,175) : message;
    }
}
