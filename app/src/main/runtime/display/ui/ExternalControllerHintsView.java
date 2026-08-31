package com.winlator.cmod.runtime.display.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.winlator.cmod.R;
import com.winlator.cmod.runtime.input.controls.Binding;
import com.winlator.cmod.runtime.input.controls.ControlsProfile;
import com.winlator.cmod.runtime.input.controls.ExternalController;
import com.winlator.cmod.runtime.input.controls.ExternalControllerBinding;

import java.util.ArrayList;
import java.util.HashSet;

/**
 * Floating overlay (similar to {@code FrameRating}) that shows the controller mappings
 * of the current shortcut's {@link ControlsProfile} as "gamepad icon + description" rows.
 * The icon is chosen automatically from the {@code ic_input_xbox_*} drawables based on
 * the mapped gamepad source key.
 *
 * <p>The overlay can be dragged to any position on screen and tapped to toggle between
 * vertical and horizontal layout. Position and direction changes are reported through
 * {@link OnConfigChangedListener} so the host can persist them.
 */
public class ExternalControllerHintsView extends LinearLayout {
    public static final int DIRECTION_VERTICAL = LinearLayout.VERTICAL; // 1
    public static final int DIRECTION_HORIZONTAL = LinearLayout.HORIZONTAL; // 0

    /** Called whenever the overlay direction or position changes and should be saved. */
    public interface OnConfigChangedListener {
        void onConfigChanged();
    }

    private final Context context;
    private ControlsProfile lastProfile = null;
    private OnConfigChangedListener configChangedListener = null;

    // Position as a fraction of the parent size (0..1); -1 until the user actually drags it.
    private float positionXRatio = -1f;
    private float positionYRatio = -1f;

    private final int touchSlop;
    private float dX;
    private float dY;
    private float downRawX;
    private float downRawY;
    private boolean dragging = false;

    public ExternalControllerHintsView(Context context) {
        super(context);
        this.context = context;
        this.touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        setOrientation(DIRECTION_HORIZONTAL);
        GradientDrawable backdrop = new GradientDrawable();
        backdrop.setColor(0xA6000000);
        backdrop.setCornerRadius(dp(8));
        setBackground(backdrop);
        setPadding(dp(10), dp(6), dp(10), dp(6));
        setVisibility(GONE);
        initTouch();
    }

    public int getDirection() {
        return getOrientation();
    }

    /** Switches between vertical and horizontal layout, re-rendering rows for the new direction. */
    public void setDirection(int direction) {
        if (getOrientation() == direction) return;
        setOrientation(direction);
        if (lastProfile != null) setProfile(lastProfile);
    }

    public float getPositionX() {
        return positionXRatio;
    }

    public float getPositionY() {
        return positionYRatio;
    }

    /** Restores the overlay to the given fractional position of its parent (0..1 each). */
    public void setPosition(float xRatio, float yRatio) {
        positionXRatio = xRatio;
        positionYRatio = yRatio;
        if (getParent() == null || !(getParent() instanceof FrameLayout)) return;
        final FrameLayout parent = (FrameLayout) getParent();
        if (parent.getWidth() == 0) {
            // Parent not laid out yet; apply once layout is done.
            post(() -> applyPosition(xRatio, yRatio));
        } else {
            applyPosition(xRatio, yRatio);
        }
    }

    private void applyPosition(float xRatio, float yRatio) {
        FrameLayout parent = (FrameLayout) getParent();
        if (parent == null || parent.getWidth() <= 0 || parent.getHeight() <= 0) return;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) getLayoutParams();
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.leftMargin = Math.round(xRatio * parent.getWidth());
        lp.topMargin = Math.round(yRatio * parent.getHeight());
        setLayoutParams(lp);
    }

    public void setOnConfigChangedListener(OnConfigChangedListener listener) {
        this.configChangedListener = listener;
    }

    /**
     * Renders one row per {@link ExternalControllerBinding} found in the profile's
     * controllers. The text is the binding's user note when present, otherwise the
     * target {@link Binding} name. Duplicate source key codes are collapsed so the
     * view stays compact.
     */
    public void setProfile(ControlsProfile profile) {
        lastProfile = profile;
        removeAllViews();
        if (profile == null) {
            setVisibility(GONE);
            return;
        }
        ArrayList<ExternalController> controllers = profile.loadControllers();
        if (controllers == null || controllers.isEmpty()) {
            setVisibility(GONE);
            return;
        }
        HashSet<Integer> seenKeys = new HashSet<>();
        int rowCount = 0;
        for (ExternalController controller : controllers) {
            int bindingCount = controller.getControllerBindingCount();
            for (int i = 0; i < bindingCount; i++) {
                ExternalControllerBinding ecb = controller.getControllerBindingAt(i);
                if (ecb == null) continue;
                int keyCode = ecb.getKeyCodeForAxis();
                if (!seenKeys.add(keyCode)) continue;
                Binding binding = ecb.getBinding();
                if (binding == null || binding == Binding.NONE) continue;
                String text = ecb.getNote();
                if (text == null || text.trim().isEmpty()) {
                    text = binding.toString();
                }
                addRow(iconFor(keyCode), text);
                rowCount++;
            }
        }
        setVisibility(rowCount == 0 ? GONE : VISIBLE);
    }

    private void addRow(int iconRes, String text) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        ImageView icon = new ImageView(context);
        icon.setImageResource(iconRes);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(16), dp(16));
        iconLp.rightMargin = dp(6);
        icon.setLayoutParams(iconLp);

        TextView label = new TextView(context);
        label.setText(text);
        label.setTextColor(Color.WHITE);
        label.setTextSize(12);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setMaxWidth(dp(120));

        row.addView(icon);
        row.addView(label);

        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        if (getOrientation() == DIRECTION_VERTICAL) {
            rowLp.topMargin = dp(2);
            rowLp.bottomMargin = dp(2);
        } else {
            rowLp.leftMargin = dp(6);
            rowLp.rightMargin = dp(6);
        }
        row.setLayoutParams(rowLp);
        addView(row);
    }

    // Drag via setX/setY (like FrameRating) instead of per-move setLayoutParams(),
    // which would trigger a full measure/layout pass on the parent on every move.
    private void initTouch() {
        setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    dX = v.getX() - event.getRawX();
                    dY = v.getY() - event.getRawY();
                    downRawX = event.getRawX();
                    downRawY = event.getRawY();
                    dragging = false;
                    // Pin to absolute positioning from the first frame so gravity-based
                    // layout never fights the drag.
                    FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) getLayoutParams();
                    if (lp.gravity != (Gravity.TOP | Gravity.START)) {
                        lp.gravity = Gravity.TOP | Gravity.START;
                        lp.leftMargin = v.getLeft();
                        lp.topMargin = v.getTop();
                        setLayoutParams(lp);
                    }
                    return true;
                }
                case MotionEvent.ACTION_MOVE: {
                    float dx = Math.abs(event.getRawX() - downRawX);
                    float dy = Math.abs(event.getRawY() - downRawY);
                    if (dx > touchSlop || dy > touchSlop) {
                        dragging = true;
                    }
                    if (dragging) {
                        v.setX(event.getRawX() + dX);
                        v.setY(event.getRawY() + dY);
                        clampToParentBounds(v);
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    if (dragging) {
                        clampToParentBounds(v);
                        // Freeze the dragged position into layout params (single relayout).
                        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) getLayoutParams();
                        lp.leftMargin = v.getLeft();
                        lp.topMargin = v.getTop();
                        setLayoutParams(lp);
                        capturePosition();
                    } else {
                        // Tap: toggle vertical <-> horizontal layout.
                        setDirection(getOrientation() == DIRECTION_VERTICAL ? DIRECTION_HORIZONTAL : DIRECTION_VERTICAL);
                    }
                    dragging = false;
                    if (configChangedListener != null) configChangedListener.onConfigChanged();
                    return true;
                }
            }
            return false;
        });
    }

    private void clampToParentBounds(View view) {
        View parent = (View) view.getParent();
        if (parent == null) return;
        int maxX = parent.getWidth() - view.getWidth();
        int maxY = parent.getHeight() - view.getHeight();
        view.setX(Math.max(0, Math.min(view.getX(), Math.max(0, maxX))));
        view.setY(Math.max(0, Math.min(view.getY(), Math.max(0, maxY))));
    }

    private void capturePosition() {
        FrameLayout parent = (FrameLayout) getParent();
        if (parent == null || parent.getWidth() <= 0 || parent.getHeight() <= 0) return;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) getLayoutParams();
        positionXRatio = lp.leftMargin / (float) parent.getWidth();
        positionYRatio = lp.topMargin / (float) parent.getHeight();
    }

    /** Maps a JoyToKey source key code to the matching {@code ic_input_xbox_*} drawable. */
    public static int iconFor(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_A: return R.drawable.ic_input_xbox_button_a;
            case KeyEvent.KEYCODE_BUTTON_B: return R.drawable.ic_input_xbox_button_b;
            case KeyEvent.KEYCODE_BUTTON_X: return R.drawable.ic_input_xbox_button_x;
            case KeyEvent.KEYCODE_BUTTON_Y: return R.drawable.ic_input_xbox_button_y;
            case KeyEvent.KEYCODE_BUTTON_L1: return R.drawable.ic_input_xbox_lb;
            case KeyEvent.KEYCODE_BUTTON_R1: return R.drawable.ic_input_xbox_rb;
            case KeyEvent.KEYCODE_BUTTON_L2: return R.drawable.ic_input_xbox_lt;
            case KeyEvent.KEYCODE_BUTTON_R2: return R.drawable.ic_input_xbox_rt;
            case KeyEvent.KEYCODE_BUTTON_SELECT: return R.drawable.ic_input_xbox_button_view;
            case KeyEvent.KEYCODE_BUTTON_START: return R.drawable.ic_input_xbox_button_menu;
            case KeyEvent.KEYCODE_BUTTON_THUMBL: return R.drawable.ic_input_xbox_ls;
            case KeyEvent.KEYCODE_BUTTON_THUMBR: return R.drawable.ic_input_xbox_rs;
            case KeyEvent.KEYCODE_DPAD_UP: return R.drawable.ic_input_xbox_dpad_up;
            case KeyEvent.KEYCODE_DPAD_DOWN: return R.drawable.ic_input_xbox_dpad_down;
            case KeyEvent.KEYCODE_DPAD_LEFT: return R.drawable.ic_input_xbox_dpad_left;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return R.drawable.ic_input_xbox_dpad_right;
            case ExternalControllerBinding.AXIS_X_NEGATIVE: return R.drawable.ic_input_xbox_stick_l_left;
            case ExternalControllerBinding.AXIS_X_POSITIVE: return R.drawable.ic_input_xbox_stick_l_right;
            case ExternalControllerBinding.AXIS_Y_NEGATIVE: return R.drawable.ic_input_xbox_stick_l_up;
            case ExternalControllerBinding.AXIS_Y_POSITIVE: return R.drawable.ic_input_xbox_stick_l_down;
            case ExternalControllerBinding.AXIS_Z_NEGATIVE: return R.drawable.ic_input_xbox_stick_r_left;
            case ExternalControllerBinding.AXIS_Z_POSITIVE: return R.drawable.ic_input_xbox_stick_r_right;
            case ExternalControllerBinding.AXIS_RZ_NEGATIVE: return R.drawable.ic_input_xbox_stick_r_up;
            case ExternalControllerBinding.AXIS_RZ_POSITIVE: return R.drawable.ic_input_xbox_stick_r_down;
            default: return R.drawable.ic_input_xbox_button_a;
        }
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}