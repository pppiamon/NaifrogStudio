package com.naifrog.studio;

import android.app.Instrumentation;
import android.content.*;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
@FixMethodOrder(org.junit.runners.MethodSorters.NAME_ASCENDING)
public class StudioInstrumentedTest {
    final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    final Context context = instrumentation.getTargetContext();
    MainActivity activity;
    @Before public void launch() {
        activity = (MainActivity)instrumentation.startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        instrumentation.waitForIdleSync();
    }
    @After public void finish() { instrumentation.runOnMainSync(() -> activity.finish()); }
    Bitmap portrait() throws IOException { try (InputStream in = instrumentation.getContext().getAssets().open("astronaut.png")) { return BitmapFactory.decodeStream(in); } }
    void capture(String name) throws Exception {
        instrumentation.waitForIdleSync(); Thread.sleep(600);
        Bitmap bitmap = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull(bitmap);
        File folder = new File(context.getFilesDir(), "qa"); folder.mkdirs();
        Images.save(bitmap, new File(folder, name + ".png"));
    }
    @Test public void a_homeAndLibraryRender() throws Exception {
        capture("01-home");
        instrumentation.runOnMainSync(() -> { activity.tab = 1; activity.render(); });
        capture("02-library-empty");
        instrumentation.runOnMainSync(() -> { activity.tab = 2; activity.render(); });
        capture("03-gallery-empty");
    }
    @Test public void b_offlineFaceDetectionAndMultiplePeople() throws Exception {
        FaceDetector detector = FaceDetection.getClient(new FaceDetectorOptions.Builder().setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE).setMinFaceSize(.04f).build());
        Bitmap portrait = portrait();
        List<Face> single = Tasks.await(detector.process(InputImage.fromBitmap(portrait, 0)), 60, TimeUnit.SECONDS);
        assertTrue("Must detect the test portrait offline", single.size() >= 1);
        Bitmap group = Bitmap.createBitmap(1024, 512, Bitmap.Config.ARGB_8888); Canvas c = new Canvas(group); c.drawBitmap(portrait, 0, 0, null); c.drawBitmap(portrait, 512, 0, null);
        List<Face> multiple = Tasks.await(detector.process(InputImage.fromBitmap(group, 0)), 60, TimeUnit.SECONDS);
        assertTrue("Must detect both people", multiple.size() >= 2);
        Bitmap empty = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888); empty.eraseColor(Color.GREEN);
        assertEquals(0, Tasks.await(detector.process(InputImage.fromBitmap(empty, 0)), 60, TimeUnit.SECONDS).size());
        detector.close();
        instrumentation.runOnMainSync(() -> {
            activity.state.original = group; activity.state.faces = new ArrayList<>();
            for (Face face : multiple) activity.state.faces.add(face.getBoundingBox());
            activity.state.faces.sort(Comparator.comparingInt(r -> r.left));
            activity.state.selection = null; activity.state.status = "检测到 2 位人物 · 已选第 1 位";
            activity.tab = 0; activity.render();
        });
        capture("04-multiple-portraits");
    }
    @Test public void c_maskBrushEraseUndoAndToolSwitch() throws Exception {
        instrumentation.runOnMainSync(() -> {
            activity.state.original = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888);
            activity.state.selection = null; activity.state.faces = new ArrayList<>(List.of(new Rect(140, 100, 370, 380)));
            activity.state.selectedFace = 0; activity.tab = 0; activity.render();
        });
        instrumentation.waitForIdleSync();
        instrumentation.runOnMainSync(() -> {
            EditCanvas editor = activity.editor;
            assertTrue(Images.hasSelection(editor.getMask()));
            editor.clearSelection(); assertFalse(Images.hasSelection(editor.getMask()));
            editor.mode = 1;
            long time = android.os.SystemClock.uptimeMillis();
            editor.onTouchEvent(MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, editor.getWidth()/2f, editor.getHeight()/2f, 0));
            editor.onTouchEvent(MotionEvent.obtain(time, time+20, MotionEvent.ACTION_UP, editor.getWidth()/2f, editor.getHeight()/2f, 0));
            assertTrue(Images.hasSelection(editor.getMask()));
            editor.mode = 2; activity.render();
            activity.editor.undo(); assertFalse(Images.hasSelection(activity.editor.getMask()));
            activity.editor.selectFace(new Rect(140, 100, 370, 380));
            assertEquals(0, Color.alpha(activity.editor.getMask().getPixel(0, 0)));
        });
    }
    @Test public void d_singlePortraitEditorRender() throws Exception {
        Bitmap portrait = portrait();
        FaceDetector detector = FaceDetection.getClient();
        List<Face> faces = Tasks.await(detector.process(InputImage.fromBitmap(portrait, 0)), 60, TimeUnit.SECONDS); detector.close();
        instrumentation.runOnMainSync(() -> {
            activity.state.original = portrait; activity.state.selection = null;
            activity.state.faces = new ArrayList<>(List.of(faces.get(0).getBoundingBox())); activity.state.selectedFace = 0;
            activity.state.status = "检测到 1 位人物 · 已选第 1 位"; activity.tab = 0; activity.render();
        });
        capture("05-portrait-editor");
        instrumentation.runOnMainSync(() -> activity.scroll.fullScroll(View.FOCUS_DOWN));
        capture("06-editor-controls");
    }
}
