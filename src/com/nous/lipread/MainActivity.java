package com.nous.lipread;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.VideoView;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity implements TextToSpeech.OnInitListener {
    private static final int PICK_VIDEO = 10;
    private static final int CAPTURE_VIDEO = 11;
    private static final int SAVE_TRANSCRIPT = 12;
    private static final int SAVE_AUDIO = 13;
    private static final String PREFS = "lipread_settings";
    private static final String ENDPOINT_KEY = "endpoint";

    private static final String STATE_SELECTED_URI = "selected_uri";
    private static final String STATE_PENDING_URI = "pending_uri";
    private static final String STATE_PENDING_PATH = "pending_path";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Uri selectedVideo;
    private Uri pendingCapture;
    private File pendingCaptureFile;
    private String transcript = "";
    private File generatedAudio;
    private boolean generatedAudioIsWave;
    private String activeGenerationId;
    private String generationTranscript = "";
    private boolean operationBusy;
    private boolean generationBusy;
    private boolean ttsReady;
    private volatile boolean destroyed;
    private TextToSpeech tts;

    private VideoView preview;
    private EditText endpointInput;
    private EditText tokenInput;
    private TextView selectedLabel;
    private TextView transcriptView;
    private TextView statusView;
    private TextView confidenceView;
    private ProgressBar progress;
    private Button recordButton;
    private Button chooseButton;
    private Button analyzeButton;
    private Button speakButton;
    private Button saveAudioButton;
    private Button saveTranscriptButton;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(buildUi());
        tts = new TextToSpeech(this, this);
        endpointInput.setText(getSharedPreferences(PREFS, MODE_PRIVATE).getString(ENDPOINT_KEY, ""));

        restoreState(state);
        cleanupOrphanedCaptures();
        handleIncomingVideo(getIntent());
        updateControls();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (selectedVideo != null) outState.putString(STATE_SELECTED_URI, selectedVideo.toString());
        if (pendingCapture != null) outState.putString(STATE_PENDING_URI, pendingCapture.toString());
        if (pendingCaptureFile != null) outState.putString(STATE_PENDING_PATH, pendingCaptureFile.getAbsolutePath());
    }

    private void restoreState(Bundle state) {
        if (state == null) return;
        String selected = state.getString(STATE_SELECTED_URI);
        if (selected != null) selectVideo(Uri.parse(selected), "Selected video");
        String pendingUri = state.getString(STATE_PENDING_URI);
        String pendingPath = state.getString(STATE_PENDING_PATH);
        if (pendingUri != null && pendingPath != null) {
            File restored = new File(pendingPath);
            if (isCaptureFile(restored)) {
                pendingCapture = Uri.parse(pendingUri);
                pendingCaptureFile = restored;
            }
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingVideo(intent);
    }

    private void handleIncomingVideo(Intent intent) {
        if (intent != null && intent.getData() != null && intent.getType() != null
                && intent.getType().startsWith("video/")) {
            selectVideo(intent.getData(), "Opened video");
        }
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(246, 245, 251));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(22), dp(20), dp(40));
        scroll.addView(root);

        TextView title = text("LipRead Lab", 30, Color.rgb(31, 27, 55));
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);
        TextView subtitle = text("Experimental visual-only speech recognition", 15, Color.rgb(86, 80, 111));
        subtitle.setPadding(0, dp(3), 0, dp(18));
        root.addView(subtitle);

        TextView notice = text("Privacy: audio is removed on this device before upload. The server receives a silent MP4. Lip movements are ambiguous, so every transcript is an experimental guess—not a verbatim record.", 14, Color.rgb(63, 53, 103));
        notice.setPadding(dp(14), dp(12), dp(14), dp(12));
        notice.setBackground(rounded(Color.rgb(232, 228, 253), 16));
        root.addView(notice, matchWrap(0, dp(14)));

        preview = new VideoView(this);
        preview.setBackgroundColor(Color.BLACK);
        preview.setOnClickListener(v -> {
            if (selectedVideo != null && !operationBusy && !generationBusy) preview.start();
        });
        root.addView(preview, matchWrap(dp(220), dp(14)));

        selectedLabel = text("No video selected", 14, Color.rgb(91, 86, 111));
        selectedLabel.setPadding(0, dp(8), 0, dp(8));
        root.addView(selectedLabel);

        LinearLayout choices = new LinearLayout(this);
        choices.setOrientation(LinearLayout.HORIZONTAL);
        recordButton = button("Record clip", false);
        chooseButton = button("Choose file", false);
        recordButton.setOnClickListener(v -> recordVideo());
        chooseButton.setOnClickListener(v -> chooseVideo());
        choices.addView(recordButton, weighted());
        choices.addView(spacer(dp(10)));
        choices.addView(chooseButton, weighted());
        root.addView(choices);

        TextView endpointLabel = text("HTTPS transcription endpoint", 13, Color.rgb(60, 55, 80));
        endpointLabel.setTypeface(Typeface.DEFAULT_BOLD);
        endpointLabel.setPadding(0, dp(22), 0, dp(6));
        root.addView(endpointLabel);
        endpointInput = new EditText(this);
        endpointInput.setSingleLine(true);
        endpointInput.setHint("https://your-server.example/transcribe");
        endpointInput.setTextSize(14);
        endpointInput.setPadding(dp(14), dp(12), dp(14), dp(12));
        endpointInput.setBackground(rounded(Color.WHITE, 12));
        root.addView(endpointInput, matchWrap(0, 0));

        TextView tokenLabel = text("Bearer token (optional)", 13, Color.rgb(60, 55, 80));
        tokenLabel.setTypeface(Typeface.DEFAULT_BOLD);
        tokenLabel.setPadding(0, dp(12), 0, dp(6));
        root.addView(tokenLabel);
        tokenInput = new EditText(this);
        tokenInput.setSingleLine(true);
        tokenInput.setHint("Server access token");
        tokenInput.setTextSize(14);
        tokenInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        tokenInput.setPadding(dp(14), dp(12), dp(14), dp(12));
        tokenInput.setBackground(rounded(Color.WHITE, 12));
        root.addView(tokenInput, matchWrap(0, 0));

        analyzeButton = button("Analyze silent video", true);
        analyzeButton.setOnClickListener(v -> analyze());
        root.addView(analyzeButton, matchWrap(dp(52), dp(14)));

        progress = new ProgressBar(this);
        progress.setVisibility(View.GONE);
        root.addView(progress, centered(dp(44), dp(44)));
        statusView = text("", 14, Color.rgb(79, 72, 101));
        statusView.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(statusView);

        TextView resultTitle = text("Transcript", 20, Color.rgb(31, 27, 55));
        resultTitle.setTypeface(Typeface.DEFAULT_BOLD);
        resultTitle.setPadding(0, dp(24), 0, dp(8));
        root.addView(resultTitle);
        transcriptView = text("Your transcript will appear here.", 18, Color.rgb(50, 45, 66));
        transcriptView.setTextIsSelectable(true);
        transcriptView.setMinHeight(dp(130));
        transcriptView.setPadding(dp(16), dp(16), dp(16), dp(16));
        transcriptView.setBackground(rounded(Color.WHITE, 14));
        root.addView(transcriptView, matchWrap(0, 0));
        confidenceView = text("", 13, Color.rgb(112, 73, 43));
        confidenceView.setPadding(0, dp(8), 0, dp(8));
        root.addView(confidenceView);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        speakButton = button("Generate and play voice", false);
        saveAudioButton = button("Save generated audio", false);
        saveTranscriptButton = button("Save transcript", false);
        speakButton.setOnClickListener(v -> generateVoice());
        saveAudioButton.setOnClickListener(v -> requestSaveAudio());
        saveTranscriptButton.setOnClickListener(v -> requestSaveTranscript());
        actions.addView(speakButton, matchWrap(dp(48), dp(8)));
        actions.addView(saveAudioButton, matchWrap(dp(48), dp(8)));
        actions.addView(saveTranscriptButton, matchWrap(dp(48), dp(8)));
        root.addView(actions);

        TextView guidance = text("Best results: one person, face forward, mouth large in frame, steady lighting, no cuts, and a short clip. The backend should reject clips where the face or lips cannot be tracked reliably.", 13, Color.rgb(88, 83, 106));
        guidance.setPadding(0, dp(20), 0, 0);
        root.addView(guidance);
        return scroll;
    }

    private void chooseVideo() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("video/*");
        startActivityForResult(intent, PICK_VIDEO);
    }

    private void recordVideo() {
        cleanupPendingCapture();
        File directory = CaptureFileProvider.captureDirectory(this);
        if (!directory.exists() && !directory.mkdirs()) {
            showStatus("Could not create a private recording destination.", true);
            return;
        }
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
        pendingCaptureFile = new File(directory, "lipread_" + stamp + ".mp4");
        pendingCapture = CaptureFileProvider.uriForFile(pendingCaptureFile);
        Intent intent = new Intent(MediaStore.ACTION_VIDEO_CAPTURE);
        intent.putExtra(MediaStore.EXTRA_OUTPUT, pendingCapture);
        intent.putExtra(MediaStore.EXTRA_DURATION_LIMIT, 30);
        intent.setClipData(ClipData.newRawUri("LipRead Lab recording", pendingCapture));
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (intent.resolveActivity(getPackageManager()) == null) {
            cleanupPendingCapture();
            showStatus("No camera app is available.", true);
            return;
        }
        startActivityForResult(intent, CAPTURE_VIDEO);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_VIDEO && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) {}
            selectVideo(uri, "Selected file");
        } else if (requestCode == CAPTURE_VIDEO) {
            if (resultCode == RESULT_OK && pendingCapture != null && pendingCaptureFile != null
                    && pendingCaptureFile.isFile() && pendingCaptureFile.length() > 0) {
                Uri completed = pendingCapture;
                pendingCapture = null;
                pendingCaptureFile = null;
                selectVideo(completed, "Recorded clip");
            } else {
                cleanupPendingCapture();
                showStatus("Recording was canceled or empty.", true);
            }
        } else if (requestCode == SAVE_TRANSCRIPT && resultCode == RESULT_OK && data != null) {
            writeText(data.getData(), transcript);
        } else if (requestCode == SAVE_AUDIO && resultCode == RESULT_OK && data != null && generatedAudio != null) {
            copyFile(data.getData(), generatedAudio, generatedAudioIsWave);
        }
    }

    private void selectVideo(Uri uri, String label) {
        if (uri == null || operationBusy || generationBusy) return;
        deletePreviousOwnedCapture(uri);
        selectedVideo = uri;
        selectedLabel.setText(label + " • tap preview to play");
        preview.setVideoURI(uri);
        preview.seekTo(1);
        clearResult();
        showStatus("Ready to analyze. Any recorded audio will be stripped locally.", false);
        updateControls();
    }

    private void deletePreviousOwnedCapture(Uri replacement) {
        if (selectedVideo == null || selectedVideo.equals(replacement)
                || !CaptureFileProvider.AUTHORITY.equals(selectedVideo.getAuthority())) return;
        String name = selectedVideo.getLastPathSegment();
        if (name == null) return;
        File previous = new File(CaptureFileProvider.captureDirectory(this), name);
        if (isCaptureFile(previous)) previous.delete();
    }

    private void clearResult() {
        transcript = "";
        transcriptView.setText("Your transcript will appear here.");
        confidenceView.setText("");
        cancelGeneration();
    }

    private void analyze() {
        if (selectedVideo == null) {
            showStatus("Record or choose a video first.", true);
            return;
        }
        final String endpoint;
        try {
            endpoint = EndpointValidator.normalize(endpointInput.getText().toString());
        } catch (IllegalArgumentException e) {
            endpointInput.setError(e.getMessage());
            return;
        }
        final String bearerToken = tokenInput.getText().toString().trim();
        final Uri videoSnapshot = selectedVideo;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(ENDPOINT_KEY, endpoint)
                .apply();
        clearResult();
        setOperationBusy(true, "Removing audio locally…");
        worker.execute(() -> {
            File silent = null;
            try {
                silent = File.createTempFile("silent-upload-", ".mp4", getCacheDir());
                SilentVideoProcessor.createVideoOnlyCopy(getContentResolver(), videoSnapshot, silent);
                runOnUiThread(() -> showStatus("Uploading silent video for visual-only analysis…", false));
                TranscriptionResponse response = VideoUploader.upload(endpoint, silent, bearerToken);
                runOnUiThread(() -> showResult(response));
            } catch (Exception e) {
                runOnUiThread(() -> {
                    setOperationBusy(false, "");
                    showStatus(readableError(e), true);
                });
            } finally {
                if (silent != null && silent.exists()) silent.delete();
            }
        });
    }

    private void showResult(TranscriptionResponse response) {
        transcript = response.transcript.trim();
        transcriptView.setText(transcript.isEmpty() ? "The model returned an empty transcript." : transcript);
        if (response.confidence >= 0) {
            confidenceView.setText(String.format(Locale.US, "Server confidence: %.0f%%. Treat this as a hint, not proof.", response.confidence * 100));
        } else {
            confidenceView.setText("No confidence score was supplied. Visually verify the result.");
        }
        setOperationBusy(false, "Analysis complete");
    }

    @Override
    public void onInit(int status) {
        if (destroyed) return;
        if (status == TextToSpeech.SUCCESS) {
            ttsReady = tts.setLanguage(Locale.US) >= TextToSpeech.LANG_AVAILABLE;
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) {
                    if (!destroyed && id.equals(activeGenerationId)) runOnUiThread(() -> {
                        if (!destroyed) showStatus("Generating voice…", false);
                    });
                }

                @Override public void onDone(String id) {
                    if (destroyed || !id.equals(activeGenerationId)) return;
                    runOnUiThread(() -> completeGenerationOnUiThread(id));
                }

                @Override public void onError(String id) {
                    if (destroyed || !id.equals(activeGenerationId)) return;
                    runOnUiThread(() -> {
                        if (!destroyed) failGeneration(id);
                    });
                }
            });
        } else {
            ttsReady = false;
            showStatus("Text-to-speech is unavailable on this device.", true);
        }
        updateControls();
    }

    private void completeGenerationOnUiThread(String id) {
        if (destroyed || !id.equals(activeGenerationId)) return;
        File completed = generatedAudio;
        if (completed == null || !completed.isFile()) {
            failGeneration(id);
            return;
        }
        boolean wave = false;
        try {
            wave = GeneratedAudio.isWave(completed);
            if (wave) {
                File renamed = new File(completed.getParentFile(), id + ".wav");
                if (completed.renameTo(renamed)) generatedAudio = renamed;
            }
        } catch (IOException ignored) {}
        finishGeneration(id, wave);
    }

    private void generateVoice() {
        if (transcript.isEmpty() || tts == null || !ttsReady || operationBusy || generationBusy) return;
        cancelGeneratedAudio();
        String id = "lipread-" + UUID.randomUUID();
        activeGenerationId = id;
        generationTranscript = transcript;
        generatedAudio = new File(getCacheDir(), id + ".audio");
        generatedAudioIsWave = false;
        generationBusy = true;
        updateControls();
        showStatus("Starting voice generation…", false);
        int result = tts.synthesizeToFile(generationTranscript, new Bundle(), generatedAudio, id);
        if (result == TextToSpeech.ERROR) failGeneration(id);
    }

    private void finishGeneration(String id, boolean wave) {
        if (destroyed || !id.equals(activeGenerationId)) return;
        generatedAudioIsWave = wave;
        generationBusy = false;
        activeGenerationId = null;
        updateControls();
        showStatus(wave
                ? "Voice generated and detected as RIFF/WAVE. Playback started."
                : "Voice generated. Format was not RIFF/WAVE; it can be saved as generic audio.", false);
        tts.speak(generationTranscript, TextToSpeech.QUEUE_FLUSH, null, "play-" + id);
    }

    private void failGeneration(String id) {
        if (destroyed || !id.equals(activeGenerationId)) return;
        activeGenerationId = null;
        generationBusy = false;
        cancelGeneratedAudio();
        updateControls();
        showStatus("Voice generation failed.", true);
    }

    private void requestSaveTranscript() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TITLE, "lipread_transcript.txt");
        startActivityForResult(intent, SAVE_TRANSCRIPT);
    }

    private void requestSaveAudio() {
        if (generatedAudio == null || !generatedAudio.exists() || generationBusy) return;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(generatedAudioIsWave ? "audio/wav" : "application/octet-stream");
        intent.putExtra(Intent.EXTRA_TITLE, generatedAudioIsWave ? "lipread_voice.wav" : "lipread_voice.audio");
        startActivityForResult(intent, SAVE_AUDIO);
    }

    private void writeText(Uri destination, String value) {
        if (destination == null) return;
        setOperationBusy(true, "Saving transcript…");
        worker.execute(() -> {
            try (OutputStream out = getContentResolver().openOutputStream(destination, "wt")) {
                if (out == null) throw new IOException("Cannot open destination");
                out.write(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                runOnUiThread(() -> setOperationBusy(false, "Transcript saved."));
            } catch (IOException e) {
                runOnUiThread(() -> {
                    setOperationBusy(false, "");
                    showStatus("Could not save transcript.", true);
                });
            }
        });
    }

    private void copyFile(Uri destination, File source, boolean wave) {
        if (destination == null) return;
        setOperationBusy(true, "Saving generated audio…");
        worker.execute(() -> {
            try (InputStream in = new FileInputStream(source);
                 OutputStream out = getContentResolver().openOutputStream(destination, "wt")) {
                if (out == null) throw new IOException("Cannot open destination");
                byte[] buffer = new byte[32 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
                runOnUiThread(() -> setOperationBusy(false, wave ? "WAV audio saved." : "Generated audio saved."));
            } catch (IOException e) {
                runOnUiThread(() -> {
                    setOperationBusy(false, "");
                    showStatus("Could not save generated audio.", true);
                });
            }
        });
    }

    private void setOperationBusy(boolean busy, String message) {
        operationBusy = busy;
        progress.setVisibility(busy || generationBusy ? View.VISIBLE : View.GONE);
        updateControls();
        if (!message.isEmpty()) showStatus(message, false);
    }

    private void updateControls() {
        if (recordButton == null) return;
        boolean idle = !operationBusy && !generationBusy;
        setControlEnabled(recordButton, idle);
        setControlEnabled(chooseButton, idle);
        setControlEnabled(analyzeButton, idle && selectedVideo != null);
        endpointInput.setEnabled(idle);
        tokenInput.setEnabled(idle);
        boolean hasTranscript = !transcript.isEmpty();
        setControlEnabled(speakButton, idle && hasTranscript && ttsReady);
        setControlEnabled(saveTranscriptButton, idle && hasTranscript);
        setControlEnabled(saveAudioButton, idle && generatedAudio != null && generatedAudio.exists());
        progress.setVisibility(operationBusy || generationBusy ? View.VISIBLE : View.GONE);
    }

    private void setControlEnabled(Button button, boolean enabled) {
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1.0f : 0.42f);
    }

    private void showStatus(String message, boolean error) {
        statusView.setText(message);
        statusView.setTextColor(error ? Color.rgb(176, 45, 53) : Color.rgb(79, 72, 101));
    }

    private String readableError(Exception e) {
        String message = e.getMessage();
        if (message == null || message.trim().isEmpty()) message = e.getClass().getSimpleName();
        if (message.length() > 300) message = message.substring(0, 300) + "…";
        return "Analysis failed: " + message;
    }

    private void cancelGeneration() {
        if (tts != null && generationBusy) tts.stop();
        activeGenerationId = null;
        generationBusy = false;
        generationTranscript = "";
        cancelGeneratedAudio();
    }

    private void cancelGeneratedAudio() {
        if (generatedAudio != null && generatedAudio.exists()) generatedAudio.delete();
        generatedAudio = null;
        generatedAudioIsWave = false;
    }

    private void cleanupPendingCapture() {
        if (pendingCaptureFile != null && pendingCaptureFile.exists()) pendingCaptureFile.delete();
        pendingCapture = null;
        pendingCaptureFile = null;
    }

    private void cleanupOrphanedCaptures() {
        File directory = CaptureFileProvider.captureDirectory(this);
        File[] files = directory.listFiles();
        if (files == null) return;
        for (File file : files) {
            if ((pendingCaptureFile == null || !file.equals(pendingCaptureFile))
                    && !isSelectedCapture(file)) file.delete();
        }
    }

    private boolean isSelectedCapture(File file) {
        return selectedVideo != null
                && CaptureFileProvider.AUTHORITY.equals(selectedVideo.getAuthority())
                && file.getName().equals(selectedVideo.getLastPathSegment());
    }

    private boolean isCaptureFile(File file) {
        try {
            File directory = CaptureFileProvider.captureDirectory(this).getCanonicalFile();
            return file.getCanonicalFile().getParentFile().equals(directory);
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        activeGenerationId = null;
        generationBusy = false;
        worker.shutdownNow();
        cancelGeneratedAudio();
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        super.onDestroy();
    }

    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setLineSpacing(0, 1.12f);
        return view;
    }

    private Button button(String value, boolean primary) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setTextColor(primary ? Color.WHITE : Color.rgb(57, 48, 119));
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setBackground(rounded(primary ? Color.rgb(91, 75, 219) : Color.rgb(235, 232, 249), 12));
        return button;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private LinearLayout.LayoutParams matchWrap(int height, int topMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                height == 0 ? LinearLayout.LayoutParams.WRAP_CONTENT : height);
        params.topMargin = topMargin;
        return params;
    }

    private LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0, dp(48), 1f);
    }

    private View spacer(int width) {
        View view = new View(this);
        view.setLayoutParams(new LinearLayout.LayoutParams(width, 1));
        return view;
    }

    private LinearLayout.LayoutParams centered(int width, int height) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height);
        params.gravity = Gravity.CENTER_HORIZONTAL;
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
