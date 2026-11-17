package fr.guillaume.reversevoice;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaScannerConnection;
import android.media.MediaRecorder;
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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;

public class MainActivity extends Activity {
    private static final int RECORD_AUDIO_REQUEST = 731;
    private static final int AUDIO_FILE_REQUEST = 732;
    private static final int RECORDING_SAMPLE_RATE = 44100;
    private static final int BASE64_CHUNK_SIZE = 180000;
    private WebView webView;
    private ValueCallback<Uri[]> fileChooserCallback;
    private AudioRecord nativeAudioRecorder;
    private volatile boolean nativeRecording = false;
    private Thread nativeRecordingThread;
    private File nativeRecordingFile;
    private String nativeRecordingBase64;

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

    private void startNativeRecording() {
        if (!hasMicrophonePermission()) {
            webView.evaluateJavascript("window.onNativeRecordingError();", null);
            return;
        }
        if (nativeRecording) return;

        int minimumBuffer = AudioRecord.getMinBufferSize(RECORDING_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minimumBuffer <= 0) {
            webView.evaluateJavascript("window.onNativeRecordingError();", null);
            return;
        }

        int bufferSize = Math.max(minimumBuffer, 8192);
        AudioRecord recorder = new AudioRecord(MediaRecorder.AudioSource.MIC,
                RECORDING_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, bufferSize);
        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
            recorder.release();
            webView.evaluateJavascript("window.onNativeRecordingError();", null);
            return;
        }

        try {
            nativeRecordingFile = File.createTempFile("reverse_voice_", ".wav", getCacheDir());
            nativeRecordingBase64 = null;
            nativeAudioRecorder = recorder;
            nativeRecording = true;
            recorder.startRecording();
            nativeRecordingThread = new Thread(
                    () -> captureNativeAudio(recorder, bufferSize, nativeRecordingFile),
                    "ReverseVoiceRecorder");
            nativeRecordingThread.start();
            webView.evaluateJavascript("window.onNativeRecordingStarted();", null);
        } catch (Exception error) {
            nativeRecording = false;
            nativeAudioRecorder = null;
            recorder.release();
            webView.evaluateJavascript("window.onNativeRecordingError();", null);
        }
    }

    private void stopNativeRecording() {
        nativeRecording = false;
        AudioRecord recorder = nativeAudioRecorder;
        if (recorder != null) {
            try {
                recorder.stop();
            } catch (IllegalStateException ignored) {
                // The recorder has already stopped on its own.
            }
        }
    }

    private void captureNativeAudio(AudioRecord recorder, int bufferSize, File outputFile) {
        boolean written = false;
        try (FileOutputStream output = new FileOutputStream(outputFile)) {
            output.write(new byte[44]);
            byte[] buffer = new byte[bufferSize];
            while (nativeRecording) {
                int byteCount = recorder.read(buffer, 0, buffer.length);
                if (byteCount > 0) {
                    output.write(buffer, 0, byteCount);
                } else if (nativeRecording) {
                    throw new IOException("Lecture micro interrompue");
                }
            }
            written = outputFile.length() > 44;
        } catch (Exception error) {
            written = false;
        } finally {
            try {
                recorder.stop();
            } catch (IllegalStateException ignored) {
                // It was stopped by the user.
            }
            recorder.release();
            if (nativeAudioRecorder == recorder) nativeAudioRecorder = null;
            nativeRecording = false;

            if (written) {
                try {
                    writeWavHeader(outputFile);
                    nativeRecordingBase64 = encodeFileBase64(outputFile);
                    runOnUiThread(() -> webView.evaluateJavascript("window.onNativeRecordingReady();", null));
                } catch (Exception error) {
                    runOnUiThread(() -> webView.evaluateJavascript("window.onNativeRecordingError();", null));
                }
            } else {
                runOnUiThread(() -> webView.evaluateJavascript("window.onNativeRecordingError();", null));
            }
        }
    }

    private void writeWavHeader(File audioFile) throws IOException {
        long dataLength = audioFile.length() - 44;
        try (RandomAccessFile wave = new RandomAccessFile(audioFile, "rw")) {
            wave.seek(0);
            wave.writeBytes("RIFF");
            writeLittleEndianInt(wave, (int) (36 + dataLength));
            wave.writeBytes("WAVE");
            wave.writeBytes("fmt ");
            writeLittleEndianInt(wave, 16);
            writeLittleEndianShort(wave, (short) 1);
            writeLittleEndianShort(wave, (short) 1);
            writeLittleEndianInt(wave, RECORDING_SAMPLE_RATE);
            writeLittleEndianInt(wave, RECORDING_SAMPLE_RATE * 2);
            writeLittleEndianShort(wave, (short) 2);
            writeLittleEndianShort(wave, (short) 16);
            wave.writeBytes("data");
            writeLittleEndianInt(wave, (int) dataLength);
        }
    }

    private void writeLittleEndianInt(RandomAccessFile file, int value) throws IOException {
        file.write(value & 0xff);
        file.write((value >> 8) & 0xff);
        file.write((value >> 16) & 0xff);
        file.write((value >> 24) & 0xff);
    }

    private void writeLittleEndianShort(RandomAccessFile file, short value) throws IOException {
        file.write(value & 0xff);
        file.write((value >> 8) & 0xff);
    }

    private String encodeFileBase64(File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream((int) file.length())) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP);
        }
    }

    private int nativeRecordingChunkCount() {
        if (nativeRecordingBase64 == null || nativeRecordingBase64.length() == 0) return 0;
        return (nativeRecordingBase64.length() + BASE64_CHUNK_SIZE - 1) / BASE64_CHUNK_SIZE;
    }

    private String nativeRecordingChunk(int index) {
        if (nativeRecordingBase64 == null || index < 0) return "";
        int start = index * BASE64_CHUNK_SIZE;
        if (start >= nativeRecordingBase64.length()) return "";
        int end = Math.min(nativeRecordingBase64.length(), start + BASE64_CHUNK_SIZE);
        return nativeRecordingBase64.substring(start, end);
    }

    private void clearNativeRecording() {
        nativeRecordingBase64 = null;
        if (nativeRecordingFile != null && nativeRecordingFile.exists()) {
            nativeRecordingFile.delete();
        }
        nativeRecordingFile = null;
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
        public void startNativeRecording() {
            runOnUiThread(MainActivity.this::startNativeRecording);
        }

        @JavascriptInterface
        public void stopNativeRecording() {
            runOnUiThread(MainActivity.this::stopNativeRecording);
        }

        @JavascriptInterface
        public int nativeRecordingChunkCount() {
            return MainActivity.this.nativeRecordingChunkCount();
        }

        @JavascriptInterface
        public String nativeRecordingChunk(int index) {
            return MainActivity.this.nativeRecordingChunk(index);
        }

        @JavascriptInterface
        public void clearNativeRecording() {
            MainActivity.this.clearNativeRecording();
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
