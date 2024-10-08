package com.tencent.paddleocrncnn;

// Android imports
import android.os.Build;
import android.Manifest;
import android.os.Environment;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.provider.MediaStore;
import android.content.pm.PackageManager;
import android.media.ExifInterface;

// AndroidX imports
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

// Java I/O imports
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;


public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final int PERMISSION_REQUEST_CODE = 1;

    private ImageView imageView;
    private Bitmap yourSelectedImage = null;
    private Uri imageUri;
    private final PaddleOCRNcnn paddleocrncnn = new PaddleOCRNcnn();

    private final ActivityResultLauncher<Intent> cameraLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK) {
                    try {
                        yourSelectedImage = BitmapFactory.decodeStream(
                                getContentResolver().openInputStream(imageUri));
                        imageView.setImageBitmap(yourSelectedImage);
                    } catch (FileNotFoundException e) {
                        Log.e(TAG, "Camera image not found", e);
                    }
                }
            }
    );

    private final ActivityResultLauncher<Intent> galleryLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri selectedImage = result.getData().getData();
                    try {
                        yourSelectedImage = decodeUri(selectedImage);
                        imageView.setImageBitmap(yourSelectedImage);
                    } catch (FileNotFoundException e) {
                        Log.e(TAG, "Gallery image not found", e);
                    }
                }
            }
    );

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        if (!paddleocrncnn.Init(getAssets())) {
            Log.e(TAG, "paddleocrncnn Init failed");
        }

        imageView = findViewById(R.id.imageView);
        setupButtons();
    }

    private void setupButtons() {
        Button buttonImage = findViewById(R.id.buttonImage);
        buttonImage.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_PICK);
            intent.setType("image/*");
            galleryLauncher.launch(intent);
        });

        Button buttonCamera = findViewById(R.id.buttonCamera);
        buttonCamera.setOnClickListener(v -> requestPermission());

        Button buttonDetect = findViewById(R.id.buttonDetect);
        buttonDetect.setOnClickListener(v -> detectObjects(false));

        Button buttonDetectGPU = findViewById(R.id.buttonDetectGPU);
        buttonDetectGPU.setOnClickListener(v -> detectObjects(true));
    }

    private void detectObjects(boolean useGPU) {
        if (yourSelectedImage == null) return;

        Bitmap bitmap = yourSelectedImage.copy(Bitmap.Config.ARGB_8888, true);
        PaddleOCRNcnn.Obj[] objects = paddleocrncnn.Detect(bitmap, useGPU);
        showObjects(objects);
    }

    private void requestPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.CAMERA},
                    PERMISSION_REQUEST_CODE);
        } else {
            requestCamera();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE &&
                grantResults.length > 0 &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            requestCamera();
        }
    }

    private void requestCamera() {
        File outputImage = new File(getExternalFilesDir(null), "output_image.jpg");
        try {
            if (outputImage.exists()) {
                outputImage.delete();
            }
            outputImage.createNewFile();

            imageUri = FileProvider.getUriForFile(this,
                    "com.tencent.paddleocrncnn.fileprovider", outputImage);

            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            intent.putExtra(MediaStore.EXTRA_OUTPUT, imageUri);
            cameraLauncher.launch(intent);
        } catch (IOException e) {
            Log.e(TAG, "Error creating camera file", e);
        }
    }

    private void showObjects(PaddleOCRNcnn.Obj[] objects) {
        if (objects == null) {
            imageView.setImageBitmap(yourSelectedImage);
            return;
        }

        Bitmap rgba = yourSelectedImage.copy(Bitmap.Config.ARGB_8888, true);
        Canvas canvas = new Canvas(rgba);
        drawDetections(canvas, objects);
        imageView.setImageBitmap(rgba);
    }

    private void drawDetections(Canvas canvas, PaddleOCRNcnn.Obj[] objects) {
        final int[] colors = getDetectionColors();
        Paint paint = createStrokePaint();
        Paint textBgPaint = createTextBackgroundPaint();
        Paint textPaint = createTextPaint();

        for (int i = 0; i < objects.length; i++) {
            paint.setColor(colors[i % colors.length]);
            PaddleOCRNcnn.Obj obj = objects[i];
            drawBoundingBox(canvas, obj, paint);
            drawLabel(canvas, obj, textBgPaint, textPaint);
        }
    }

    private Paint createStrokePaint() {
        Paint paint = new Paint();
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(4);
        return paint;
    }

    private Paint createTextBackgroundPaint() {
        Paint paint = new Paint();
        paint.setColor(Color.WHITE);
        paint.setStyle(Paint.Style.FILL);
        return paint;
    }

    private Paint createTextPaint() {
        Paint paint = new Paint();
        paint.setColor(Color.BLACK);
        paint.setTextSize(56);
        paint.setTextAlign(Paint.Align.LEFT);
        return paint;
    }

    private void drawBoundingBox(Canvas canvas, PaddleOCRNcnn.Obj obj, Paint paint) {
        canvas.drawLine(obj.x0, obj.y0, obj.x1, obj.y1, paint);
        canvas.drawLine(obj.x1, obj.y1, obj.x2, obj.y2, paint);
        canvas.drawLine(obj.x2, obj.y2, obj.x3, obj.y3, paint);
        canvas.drawLine(obj.x3, obj.y3, obj.x0, obj.y0, paint);
    }

    private void drawLabel(Canvas canvas, PaddleOCRNcnn.Obj obj, Paint textBgPaint, Paint textPaint) {
        String text = obj.label;
        float textWidth = textPaint.measureText(text);
        float textHeight = -textPaint.ascent() + textPaint.descent();

        float x = obj.x0;
        float y = Math.max(0, obj.y0 - textHeight);
        if (x + textWidth > canvas.getWidth()) {
            x = canvas.getWidth() - textWidth;
        }

        canvas.drawRect(x, y, x + textWidth, y + textHeight, textBgPaint);
        canvas.drawText(text, x, y - textPaint.ascent(), textPaint);
    }

    private int[] getDetectionColors() {
        return new int[] {
                Color.rgb(54, 67, 244),   Color.rgb(99, 30, 233),
                Color.rgb(176, 39, 156),  Color.rgb(183, 58, 103),
                Color.rgb(181, 81, 63),   Color.rgb(243, 150, 33),
                Color.rgb(244, 169, 3),   Color.rgb(212, 188, 0),
                Color.rgb(136, 150, 0),   Color.rgb(80, 175, 76),
                Color.rgb(74, 195, 139),  Color.rgb(57, 220, 205),
                Color.rgb(59, 235, 255),  Color.rgb(7, 193, 255),
                Color.rgb(0, 152, 255),   Color.rgb(34, 87, 255),
                Color.rgb(72, 85, 121),   Color.rgb(158, 158, 158),
                Color.rgb(139, 125, 96)
        };
    }


    private Bitmap decodeUri(Uri selectedImage) throws FileNotFoundException {
        // Decode image size
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        try (InputStream input = getContentResolver().openInputStream(selectedImage)) {
            BitmapFactory.decodeStream(input, null, options);
        } catch (IOException e) {
            Log.e(TAG, "Error reading image dimensions", e);
            throw new FileNotFoundException("Failed to read image");
        }

        // Calculate inSampleSize
        options.inSampleSize = calculateInSampleSize(options, 640, 640);

        // Decode bitmap with inSampleSize set
        options.inJustDecodeBounds = false;
        Bitmap bitmap;
        try (InputStream input = getContentResolver().openInputStream(selectedImage)) {
            bitmap = BitmapFactory.decodeStream(input, null, options);
        } catch (IOException e) {
            Log.e(TAG, "Error decoding bitmap", e);
            throw new FileNotFoundException("Failed to decode image");
        }

        return rotateImageIfRequired(selectedImage, bitmap);
    }

    private Bitmap rotateImageIfRequired(Uri imageUri, Bitmap bitmap) {
        try (InputStream input = getContentResolver().openInputStream(imageUri)) {
            // Create ExifInterface from input stream instead of Uri directly
            ExifInterface ei = new ExifInterface(input);
            int orientation = ei.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL);

            switch (orientation) {
                case ExifInterface.ORIENTATION_ROTATE_90:
                    return rotateImage(bitmap, 90);
                case ExifInterface.ORIENTATION_ROTATE_180:
                    return rotateImage(bitmap, 180);
                case ExifInterface.ORIENTATION_ROTATE_270:
                    return rotateImage(bitmap, 270);
                default:
                    return bitmap;
            }
        } catch (IOException e) {
            Log.e(TAG, "Error checking image rotation", e);
            return bitmap;
        }
    }

    private int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;

        if (height > reqHeight || width > reqWidth) {
            final int halfHeight = height / 2;
            final int halfWidth = width / 2;

            while ((halfHeight / inSampleSize) >= reqHeight
                    && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }

        return inSampleSize;
    }

    private Bitmap rotateImage(Bitmap source, float angle) {
        Matrix matrix = new Matrix();
        matrix.postRotate(angle);
        return Bitmap.createBitmap(source, 0, 0, source.getWidth(),
                source.getHeight(), matrix, true);
    }
}