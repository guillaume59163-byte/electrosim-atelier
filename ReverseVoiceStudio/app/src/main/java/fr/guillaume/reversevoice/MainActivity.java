package fr.guillaume.reversevoice;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

public class MainActivity extends Activity {
    private static final int RECORD_AUDIO_REQUEST = 731;
    private static final int AUDIO_FILE_REQUEST = 732;
    private WebView webView;
    private ValueCallback<Uri[]> fileChooserCallback;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);

        webView.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> {
                    boolean asksForAudio = false;
                    for (String resource : request.getResources()) {
                        if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) {
                            asksForAudio = true;
                            break;
                        }
                    }
                    if (asksForAudio && hasMicrophonePermission()) {
                        request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                    } else {
                        request.deny();
                    }
                });
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             WebChromeClient.FileChooserParams fileChooserParams) {
                if (fileChooserCallback != null) {
                    fileChooserCallback.onReceiveValue(null);
                }
                fileChooserCallback = callback;
                Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                picker.addCategory(Intent.CATEGORY_OPENABLE);
                picker.setType("audio/*");
                try {
                    MainActivity.this.startActivityForResult(
                            Intent.createChooser(picker, "Choisir un audio"), AUDIO_FILE_REQUEST);
                    return true;
                } catch (Exception error) {
                    fileChooserCallback = null;
                    return false;
                }
            }
        });
        webView.addJavascriptInterface(new AudioBridge(this), "Android");
        setContentView(webView);
        webView.loadUrl("file:///android_asset/index.html");
    }

    private boolean hasMicrophonePermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void sendMicrophonePermission(boolean allowed) {
        webView.evaluateJavascript("window.onMicrophonePermission(" + allowed + ");", null);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == RECORD_AUDIO_REQUEST) {
            boolean allowed = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            sendMicrophonePermission(allowed);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != AUDIO_FILE_REQUEST || fileChooserCallback == null) return;
        Uri[] chosen = null;
        if (resultCode == RESULT_OK && data != null && data.getData() != null) {
            chosen = new Uri[]{data.getData()};
        }
        fileChooserCallback.onReceiveValue(chosen);
        fileChooserCallback = null;
    }

    private class AudioBridge {
        private final Context context;

        AudioBridge(Context context) {
            this.context = context;
        }

        @JavascriptInterface
        public void requestMicrophonePermission() {
            runOnUiThread(() -> {
                if (hasMicrophonePermission()) {
                    sendMicrophonePermission(true);
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, RECORD_AUDIO_REQUEST);
                } else {
                    sendMicrophonePermission(false);
                }
            });
        }

        @JavascriptInterface
        public void saveWav(String base64Audio, String suggestedName) {
            new Thread(() -> {
                try {
                    byte[] bytes = Base64.decode(base64Audio, Base64.DEFAULT);
                    String fileName = sanitizeFilename(suggestedName);
                    saveBytes(bytes, fileName);
                    runOnUiThread(() -> {
                        Toast.makeText(context, "Audio enregistré dans Téléchargements", Toast.LENGTH_LONG).show();
                        webView.evaluateJavascript("window.onNativeSaved();", null);
                    });
                } catch (Exception error) {
                    runOnUiThread(() -> webView.evaluateJavascript("window.onNativeSaveError();", null));
                }
            }).start();
        }

        private String sanitizeFilename(String name) {
            String clean = name == null ? "reverse-voice.wav" : name.replaceAll("[^a-zA-Z0-9._-]", "_");
            if (clean.length() == 0) clean = "reverse-voice.wav";
            if (!clean.toLowerCase().endsWith(".wav")) clean += ".wav";
            return clean;
        }

        private void saveBytes(byte[] bytes, String fileName) throws Exception {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                values.put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav");
                values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/Reverse Voice");
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri == null) throw new IllegalStateException("Impossible de créer le fichier audio");
                try (OutputStream output = getContentResolver().openOutputStream(uri)) {
                    if (output == null) throw new IllegalStateException("Impossible d'écrire le fichier audio");
                    output.write(bytes);
                }
            } else {
                File folder = new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS), "Reverse Voice");
                if (!folder.exists() && !folder.mkdirs()) {
                    throw new IllegalStateException("Impossible de créer le dossier Téléchargements");
                }
                File outputFile = new File(folder, fileName);
                try (FileOutputStream output = new FileOutputStream(outputFile)) {
                    output.write(bytes);
                }
                MediaScannerConnection.scanFile(context,
                        new String[]{outputFile.getAbsolutePath()}, new String[]{"audio/wav"}, null);
            }
        }
    }
}
