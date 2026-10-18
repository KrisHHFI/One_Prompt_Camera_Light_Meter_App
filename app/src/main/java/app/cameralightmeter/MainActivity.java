package app.cameralightmeter;

import android.Manifest;
import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import java.util.Collections;
import java.util.Locale;

/**
 * Reflected-light exposure meter for film cameras.
 *
 * The phone camera's auto-exposure meters the scene (aiming for middle grey, like a
 * classic reflected meter). We read back the exposure it chose (shutter time, ISO,
 * aperture), convert that to an EV, then convert the EV to your film camera's settings.
 */
public class MainActivity extends Activity {

    private static final int REQ_CAMERA = 1;

    // Film speeds
    private static final int[] ISOS = {6, 12, 25, 50, 64, 80, 100, 125, 160, 200, 250, 320,
            400, 500, 640, 800, 1000, 1250, 1600, 3200, 6400};

    // Apertures in 1/3 stops
    private static final double[] APERTURES = {1.0, 1.1, 1.2, 1.4, 1.6, 1.8, 2.0, 2.2, 2.5, 2.8,
            3.2, 3.5, 4.0, 4.5, 5.0, 5.6, 6.3, 7.1, 8.0, 9.0, 10, 11, 13, 14, 16, 18, 20, 22,
            25, 29, 32, 36, 40, 45, 64};

    // Shutter speeds (seconds), the full-stop values marked on most film cameras
    private static final double[] SHUTTERS = {1.0 / 8000, 1.0 / 4000, 1.0 / 2000, 1.0 / 1000,
            1.0 / 500, 1.0 / 250, 1.0 / 125, 1.0 / 60, 1.0 / 30, 1.0 / 15, 1.0 / 8, 1.0 / 4,
            1.0 / 2, 1, 2, 4, 8, 15, 30};

    // ---- Settings (saved between launches) ----
    private int isoIdx = 12;          // ISO 400
    private int apertureIdx = 18;     // f/8
    private int shutterIdx = 6;       // 1/125
    private int compThirds = 0;       // exposure compensation in 1/3 stops
    private boolean aperturePriority = true;
    private boolean spot = false;

    // ---- State ----
    private boolean held = false;
    private boolean askedPermission = false;
    private double ev100 = Double.NaN;  // smoothed scene EV at ISO 100

    // ---- Views ----
    private TextureView preview;
    private View spotView;
    private TextView statusView, resultView, detailView, setValueView, isoValueView, compValueView;
    private Button modeButton, meteringButton, holdButton;

    // ---- Camera ----
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private CaptureRequest.Builder requestBuilder;
    private String cameraId;
    private Size previewSize;
    private Rect activeArray;
    private int maxAeRegions;
    private float fallbackAperture = 1.8f;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        preview = findViewById(R.id.preview);
        spotView = findViewById(R.id.spot);
        statusView = findViewById(R.id.status);
        resultView = findViewById(R.id.result);
        detailView = findViewById(R.id.detail);
        setValueView = findViewById(R.id.set_value);
        isoValueView = findViewById(R.id.iso_value);
        compValueView = findViewById(R.id.comp_value);
        modeButton = findViewById(R.id.btn_mode);
        meteringButton = findViewById(R.id.btn_metering);
        holdButton = findViewById(R.id.btn_hold);

        loadSettings();

        findViewById(R.id.set_minus).setOnClickListener(v -> stepFixed(-1));
        findViewById(R.id.set_plus).setOnClickListener(v -> stepFixed(+1));
        findViewById(R.id.iso_minus).setOnClickListener(v -> { isoIdx = clamp(isoIdx - 1, ISOS.length); changed(); });
        findViewById(R.id.iso_plus).setOnClickListener(v -> { isoIdx = clamp(isoIdx + 1, ISOS.length); changed(); });
        findViewById(R.id.comp_minus).setOnClickListener(v -> { compThirds = Math.max(-9, compThirds - 1); changed(); });
        findViewById(R.id.comp_plus).setOnClickListener(v -> { compThirds = Math.min(9, compThirds + 1); changed(); });
        modeButton.setOnClickListener(v -> { aperturePriority = !aperturePriority; changed(); });
        meteringButton.setOnClickListener(v -> { spot = !spot; applyMetering(); changed(); });
        holdButton.setOnClickListener(v -> { held = !held; updateUi(); });

        preview.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture s, int w, int h) { openCameraIfAllowed(); }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture s, int w, int h) { applyPreviewTransform(); }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture s) { return true; }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture s) { }
        });

        updateUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        cameraThread = new HandlerThread("camera");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
        if (preview.isAvailable()) openCameraIfAllowed();
    }

    @Override
    protected void onPause() {
        closeCamera();
        if (cameraThread != null) {
            cameraThread.quitSafely();
            cameraThread = null;
            cameraHandler = null;
        }
        saveSettings();
        super.onPause();
    }

    // =====================================================================
    // Camera
    // =====================================================================

    private void openCameraIfAllowed() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            if (!askedPermission) { // ask once, so a "Deny" doesn't loop the dialog
                askedPermission = true;
                requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
            }
            return;
        }
        openCamera();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_CAMERA) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                if (preview.isAvailable()) openCamera();
            } else {
                statusView.setText("Camera permission is needed to meter light");
            }
        }
    }

    private void openCamera() {
        if (camera != null || cameraHandler == null) return;
        CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
        try {
            cameraId = null;
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics c = manager.getCameraCharacteristics(id);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    cameraId = id;
                    break;
                }
            }
            if (cameraId == null) cameraId = manager.getCameraIdList()[0];

            CameraCharacteristics chars = manager.getCameraCharacteristics(cameraId);
            activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            Integer regions = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE);
            maxAeRegions = regions != null ? regions : 0;
            float[] apertures = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES);
            if (apertures != null && apertures.length > 0) fallbackAperture = apertures[0];

            StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            previewSize = choosePreviewSize(map != null ? map.getOutputSizes(SurfaceTexture.class) : null);

            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice device) {
                    camera = device;
                    startPreview();
                }
                @Override public void onDisconnected(CameraDevice device) {
                    device.close();
                    camera = null;
                }
                @Override public void onError(CameraDevice device, int error) {
                    device.close();
                    camera = null;
                    runOnUiThread(() -> statusView.setText("Camera error (" + error + ")"));
                }
            }, cameraHandler);
        } catch (CameraAccessException | SecurityException e) {
            statusView.setText("Couldn't open the camera");
        }
    }

    private static Size choosePreviewSize(Size[] sizes) {
        if (sizes == null || sizes.length == 0) return new Size(1280, 960);
        Size best = sizes[0];
        long target = 1280L * 960L;
        long bestScore = Long.MAX_VALUE;
        for (Size s : sizes) {
            boolean fourThree = Math.abs(s.getWidth() * 3 - s.getHeight() * 4) < 8;
            long score = Math.abs((long) s.getWidth() * s.getHeight() - target) + (fourThree ? 0 : 10_000_000L);
            if (score < bestScore) {
                bestScore = score;
                best = s;
            }
        }
        return best;
    }

    private void startPreview() {
        try {
            SurfaceTexture texture = preview.getSurfaceTexture();
            if (texture == null || camera == null) return;
            texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            Surface surface = new Surface(texture);
            runOnUiThread(this::applyPreviewTransform);

            requestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            requestBuilder.addTarget(surface);
            requestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
            requestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON);
            requestBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 0);

            camera.createCaptureSession(Collections.singletonList(surface), new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession s) {
                    session = s;
                    applyMetering();
                }
                @Override public void onConfigureFailed(CameraCaptureSession s) {
                    runOnUiThread(() -> statusView.setText("Couldn't start the camera preview"));
                }
            }, cameraHandler);
        } catch (CameraAccessException e) {
            runOnUiThread(() -> statusView.setText("Couldn't start the camera preview"));
        }
    }

    /** Sets average (whole frame) or spot (centre circle) metering and (re)starts the preview. */
    private void applyMetering() {
        runOnUiThread(() -> spotView.setVisibility(spot ? View.VISIBLE : View.GONE));
        if (session == null || requestBuilder == null) return;
        if (activeArray != null && maxAeRegions > 0) {
            MeteringRectangle region;
            if (spot) {
                int w = activeArray.width() / 10;
                int h = activeArray.height() / 10;
                region = new MeteringRectangle(activeArray.centerX() - w / 2, activeArray.centerY() - h / 2,
                        w, h, MeteringRectangle.METERING_WEIGHT_MAX);
            } else {
                region = new MeteringRectangle(0, 0, activeArray.width() - 1, activeArray.height() - 1,
                        MeteringRectangle.METERING_WEIGHT_MAX);
            }
            requestBuilder.set(CaptureRequest.CONTROL_AE_REGIONS, new MeteringRectangle[]{region});
        }
        try {
            session.setRepeatingRequest(requestBuilder.build(), captureCallback, cameraHandler);
        } catch (CameraAccessException | IllegalStateException ignored) {
        }
    }

    private final CameraCaptureSession.CaptureCallback captureCallback = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest request, TotalCaptureResult result) {
            Long exposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
            Integer sensitivity = result.get(CaptureResult.SENSOR_SENSITIVITY);
            Float aperture = result.get(CaptureResult.LENS_APERTURE);
            Integer boost = result.get(CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST);
            Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
            if (exposureNs == null || sensitivity == null || exposureNs <= 0 || sensitivity <= 0) return;

            double t = exposureNs / 1e9;
            double n = (aperture != null && aperture > 0) ? aperture : fallbackAperture;
            double iso = sensitivity * (boost != null && boost > 0 ? boost / 100.0 : 1.0);

            // Scene exposure value referenced to ISO 100
            double ev = log2(n * n / t) - log2(iso / 100.0);

            final boolean underexposed = aeState != null && aeState == CameraMetadata.CONTROL_AE_STATE_FLASH_REQUIRED;
            runOnUiThread(() -> onMeterReading(ev, underexposed));
        }
    };

    private void onMeterReading(double ev, boolean tooDark) {
        if (held) return;
        ev100 = Double.isNaN(ev100) ? ev : ev100 * 0.8 + ev * 0.2; // smooth out flicker
        statusView.setText(tooDark ? "Very dark: reading may be inaccurate" : (spot ? "Spot metering" : "Average metering"));
        updateUi();
    }

    /** Centre-crops the camera image so it fills the view without stretching. */
    private void applyPreviewTransform() {
        if (previewSize == null) return;
        int vw = preview.getWidth(), vh = preview.getHeight();
        if (vw == 0 || vh == 0) return;
        // In portrait the camera image is rotated, so swap width/height
        float cw = previewSize.getHeight(), ch = previewSize.getWidth();
        float scale = Math.max(vw / cw, vh / ch);
        Matrix m = new Matrix();
        m.setScale(cw * scale / vw, ch * scale / vh, vw / 2f, vh / 2f);
        preview.setTransform(m);
    }

    private void closeCamera() {
        if (session != null) {
            try { session.close(); } catch (Exception ignored) { }
            session = null;
        }
        if (camera != null) {
            camera.close();
            camera = null;
        }
        requestBuilder = null;
    }

    // =====================================================================
    // Exposure maths and UI
    // =====================================================================

    private void stepFixed(int dir) {
        if (aperturePriority) apertureIdx = clamp(apertureIdx + dir, APERTURES.length);
        else shutterIdx = clamp(shutterIdx + dir, SHUTTERS.length);
        changed();
    }

    private void changed() {
        saveSettings();
        updateUi();
    }

    private void updateUi() {
        int iso = ISOS[isoIdx];
        isoValueView.setText("ISO " + iso);
        compValueView.setText(compThirds == 0 ? "Compensation 0" : "Compensation " + formatComp(compThirds));
        modeButton.setText(aperturePriority ? "Mode: Aperture" : "Mode: Shutter");
        meteringButton.setText(spot ? "Spot" : "Average");
        holdButton.setText(held ? "Resume" : "Hold");

        if (aperturePriority) setValueView.setText("Aperture " + formatAperture(APERTURES[apertureIdx]));
        else setValueView.setText("Shutter " + formatShutter(SHUTTERS[shutterIdx]));

        if (Double.isNaN(ev100)) {
            resultView.setText("—");
            detailView.setText("Point the camera at your subject");
            return;
        }

        // EV for the chosen film speed, minus compensation (+comp = more exposure)
        double evIso = ev100 + log2(iso / 100.0) - compThirds / 3.0;
        double light = Math.pow(2, evIso); // = N² / t

        String result;
        String note;
        if (aperturePriority) {
            double n = APERTURES[apertureIdx];
            double t = n * n / light;
            int idx = nearestLog(SHUTTERS, t);
            double off = log2(SHUTTERS[idx] / t); // + means the marked speed gives more exposure
            if (t < SHUTTERS[0] / 1.41) {
                result = "Too bright";
                note = "Needs faster than 1/8000 s. Close the aperture or use slower film.";
            } else if (t > 30 * 1.41) {
                result = formatLong(t);
                note = "Longer than 30 s: use Bulb (and allow for reciprocity failure)";
            } else {
                result = formatShutter(SHUTTERS[idx]);
                note = "at " + formatAperture(n) + offsetText(off);
            }
        } else {
            double t = SHUTTERS[shutterIdx];
            double n = Math.sqrt(t * light);
            int idx = nearestLog(APERTURES, n);
            double off = 2 * log2(n / APERTURES[idx]); // + means the marked f-stop gives more exposure
            if (n < APERTURES[0] / 1.12) {
                result = "Too dark";
                note = "Needs wider than f/1.0. Use a slower shutter speed or faster film.";
            } else if (n > APERTURES[APERTURES.length - 1] * 1.12) {
                result = "Too bright";
                note = "Needs smaller than f/64. Use a faster shutter speed or slower film.";
            } else {
                result = formatAperture(APERTURES[idx]);
                note = "at " + formatShutter(t) + offsetText(off);
            }
        }

        resultView.setText(result);
        detailView.setText(String.format(Locale.US, "%s   ·   EV %.1f (ISO %d)%s",
                note, evIso, iso, held ? "   ·   HELD" : ""));
    }

    private static String offsetText(double stops) {
        if (Math.abs(stops) < 0.15) return "";
        return String.format(Locale.US, " (%+.1f stop)", stops);
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    private static double log2(double x) {
        return Math.log(x) / Math.log(2);
    }

    private static int clamp(int i, int len) {
        return Math.max(0, Math.min(len - 1, i));
    }

    private static int nearestLog(double[] values, double target) {
        int best = 0;
        double bestDiff = Double.MAX_VALUE;
        for (int i = 0; i < values.length; i++) {
            double d = Math.abs(Math.log(values[i]) - Math.log(target));
            if (d < bestDiff) {
                bestDiff = d;
                best = i;
            }
        }
        return best;
    }

    private static String formatAperture(double n) {
        if (n == Math.floor(n) && n >= 10) return "f/" + (int) n;
        return String.format(Locale.US, "f/%.1f", n);
    }

    private static String formatShutter(double t) {
        if (t >= 1) return (t == Math.floor(t) ? String.valueOf((int) t) : String.format(Locale.US, "%.1f", t)) + " s";
        return "1/" + Math.round(1 / t) + " s";
    }

    private static String formatLong(double t) {
        long s = Math.round(t);
        if (s < 60) return s + " s";
        return (s / 60) + " min " + (s % 60) + " s";
    }

    private static String formatComp(int thirds) {
        String sign = thirds > 0 ? "+" : "−";
        int a = Math.abs(thirds);
        int whole = a / 3, frac = a % 3;
        String f = frac == 1 ? "⅓" : frac == 2 ? "⅔" : "";
        return sign + (whole > 0 ? whole : "") + f + " EV";
    }

    private void loadSettings() {
        SharedPreferences p = getSharedPreferences("meter", MODE_PRIVATE);
        isoIdx = clamp(p.getInt("iso", isoIdx), ISOS.length);
        apertureIdx = clamp(p.getInt("aperture", apertureIdx), APERTURES.length);
        shutterIdx = clamp(p.getInt("shutter", shutterIdx), SHUTTERS.length);
        compThirds = Math.max(-9, Math.min(9, p.getInt("comp", 0)));
        aperturePriority = p.getBoolean("aperturePriority", true);
        spot = p.getBoolean("spot", false);
    }

    private void saveSettings() {
        getSharedPreferences("meter", MODE_PRIVATE).edit()
                .putInt("iso", isoIdx)
                .putInt("aperture", apertureIdx)
                .putInt("shutter", shutterIdx)
                .putInt("comp", compThirds)
                .putBoolean("aperturePriority", aperturePriority)
                .putBoolean("spot", spot)
                .apply();
    }
}
