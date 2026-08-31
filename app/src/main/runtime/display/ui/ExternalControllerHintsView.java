package com.winlator.cmod.runtime.display.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import com.winlator.cmod.R;
import com.winlator.cmod.runtime.input.controls.Binding;
import com.winlator.cmod.runtime.input.controls.ControlsProfile;
import com.winlator.cmod.runtime.input.controls.ExternalController;
import com.winlator.cmod.runtime.input.controls.ExternalControllerBinding;

import java.util.ArrayList;
import java.util.HashSet;

/**
 * Floating overlay (similar to {@link FrameRating}) that shows the controller mappings
 * of the current shortcut's {@link ControlsProfile} as "gamepad icon + description" rows.
 *
 * <p>Interactions (same as {@code FrameRating}):
 * <ul>
 *   <li><b>Tap</b> — toggle between horizontal and vertical layout.</li>
 *   <li><b>Drag</b> — move to any position on screen.</li>
 *   <li><b>Long-press</b> — open a 9-grid anchor menu to snap the view into a corner/edge.</li>
 * </ul>
 *
 * <p>Position and anchor are persisted via {@link android.content.SharedPreferences}
 * (keys {@code hints_anchor}, {@code hints_pos_x}, {@code hints_pos_y}, {@code hints_has_position}).
 */
public class ExternalControllerHintsView extends LinearLayout {
    public static final int DIRECTION_VERTICAL = LinearLayout.VERTICAL;
    public static final int DIRECTION_HORIZONTAL = LinearLayout.HORIZONTAL;

    public static final String PREF_HINTS_ANCHOR = "hints_anchor";
    public static final String PREF_HINTS_POS_X = "hints_pos_x";
    public static final String PREF_HINTS_POS_Y = "hints_pos_y";
    public static final String PREF_HINTS_HAS_POSITION = "hints_has_position";

    private static final int ANCHOR_NONE = -1;
    private static final int ANCHOR_TOP_LEFT = 0;
    private static final int ANCHOR_TOP_CENTER = 1;
    private static final int ANCHOR_TOP_RIGHT = 2;
    private static final int ANCHOR_BOTTOM_LEFT = 3;
    private static final int ANCHOR_BOTTOM_CENTER = 4;
    private static final int ANCHOR_BOTTOM_RIGHT = 5;
    private static final int ANCHOR_LEFT_CENTER = 6;
    private static final int ANCHOR_RIGHT_CENTER = 7;

    private final Context context;
    private final SharedPreferences preferences;
    private ControlsProfile lastProfile = null;

    private int currentAnchor = ANCHOR_NONE;
    private PopupWindow positionPopup;
    private ViewTreeObserver.OnGlobalLayoutListener parentLayoutListener;

    private float hudElevation = 1000f;

    public ExternalControllerHintsView(Context context) {
        super(context);
        this.context = context;
        this.preferences = PreferenceManager.getDefaultSharedPreferences(context);

        setOrientation(DIRECTION_VERTICAL);
        GradientDrawable backdrop = new GradientDrawable();
        backdrop.setColor(0xA6000000);
        backdrop.setCornerRadius(dp(8));
        setBackground(backdrop);
        setPadding(dp(10), dp(6), dp(10), dp(6));
        setVisibility(GONE);

        // Explicit WRAP_CONTENT: when added to a FrameLayout without LayoutParams the default
        // is MATCH_PARENT, which would stretch this overlay across the whole screen (a full-screen
        // dark mask) and break anchor/drag math (parent minus self = 0).
        setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        loadPersistedPreferences();
        setupTapAndDragListener();
    }

    private void loadPersistedPreferences() {
        currentAnchor = preferences.getInt(PREF_HINTS_ANCHOR, ANCHOR_LEFT_CENTER);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        post(this::bringToFront);
        setElevation(hudElevation);
        restorePersistedPosition();
        installParentLayoutListener();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        dismissPositionPopup();
        removeParentLayoutListener();
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        if (changed) {
            post(() -> {
                if (currentAnchor != ANCHOR_NONE) {
                    applyAnchor(currentAnchor, false);
                } else {
                    clampToParentBounds(this);
                }
            });
        }
    }

    private void installParentLayoutListener() {
        final View parentView = (View) getParent();
        if (parentView == null) return;
        removeParentLayoutListener();
        this.parentLayoutListener = () -> {
            if (currentAnchor != ANCHOR_NONE) {
                applyAnchor(currentAnchor, false);
            } else {
                clampToParentBounds(this);
            }
        };
        parentView.getViewTreeObserver().addOnGlobalLayoutListener(this.parentLayoutListener);
    }

    private void removeParentLayoutListener() {
        if (this.parentLayoutListener == null) return;
        View parentView = (View) getParent();
        if (parentView != null) {
            parentView.getViewTreeObserver().removeOnGlobalLayoutListener(this.parentLayoutListener);
        }
        this.parentLayoutListener = null;
    }

    private void restorePersistedPosition() {
        if (!preferences.getBoolean(PREF_HINTS_HAS_POSITION, false)) return;
        post(() -> {
            setX(preferences.getFloat(PREF_HINTS_POS_X, getX()));
            setY(preferences.getFloat(PREF_HINTS_POS_Y, getY()));
            clampToParentBounds(this);
        });
    }

    private void persistPosition(float x, float y) {
        preferences.edit()
                .putBoolean(PREF_HINTS_HAS_POSITION, true)
                .putFloat(PREF_HINTS_POS_X, x)
                .putFloat(PREF_HINTS_POS_Y, y)
                .apply();
    }

    public void setHudElevation(float elevation) {
        this.hudElevation = elevation;
        setElevation(elevation);
    }

    public int getDirection() {
        return getOrientation();
    }

    public void setDirection(int direction) {
        if (getOrientation() == direction) return;
        setOrientation(direction);
        if (lastProfile != null) setProfile(lastProfile);
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

    // ── Touch handling: tap toggles orientation, drag moves view, long-press shows 9-grid menu ──
    private void setupTapAndDragListener() {
        setOnTouchListener(new OnTouchListener() {
            private int activePointerId = -1;
            private float dX, dY;
            private float downRawX, downRawY;
            private long downTime;
            private boolean isDragging = false;
            private boolean longPressFired = false;
            private final Handler longPressHandler = new Handler(Looper.getMainLooper());
            private final Runnable longPressRunnable = () -> {
                if (!isDragging && activePointerId != -1) {
                    longPressFired = true;
                    showPositionMenu();
                }
            };
            private static final float TAP_SLOP = 20f;
            private static final long LONG_PRESS_MS = 500L;

            @Override
            public boolean onTouch(View view, MotionEvent event) {
                if (event.getPointerCount() > 1) {
                    activePointerId = -1;
                    longPressHandler.removeCallbacks(longPressRunnable);
                    return false;
                }
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        activePointerId = event.getPointerId(0);
                        dX = view.getX() - event.getRawX();
                        dY = view.getY() - event.getRawY();
                        downRawX = event.getRawX();
                        downRawY = event.getRawY();
                        downTime = SystemClock.elapsedRealtime();
                        isDragging = false;
                        longPressFired = false;
                        view.bringToFront();
                        longPressHandler.removeCallbacks(longPressRunnable);
                        longPressHandler.postDelayed(longPressRunnable, LONG_PRESS_MS);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (activePointerId != -1) {
                            float dx = Math.abs(event.getRawX() - downRawX);
                            float dy = Math.abs(event.getRawY() - downRawY);
                            if (dx > TAP_SLOP || dy > TAP_SLOP) {
                                isDragging = true;
                                longPressHandler.removeCallbacks(longPressRunnable);
                            }
                            if (isDragging && !longPressFired) {
                                view.setX(event.getRawX() + dX);
                                view.setY(event.getRawY() + dY);
                                clampToParentBounds(view);
                            }
                            return true;
                        }
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        longPressHandler.removeCallbacks(longPressRunnable);
                        if (activePointerId != -1) {
                            long elapsed = SystemClock.elapsedRealtime() - downTime;
                            if (longPressFired) {
                                // menu was shown — consume up event
                            } else if (!isDragging && elapsed < 400) {
                                int newDir = (getOrientation() == DIRECTION_VERTICAL)
                                        ? DIRECTION_HORIZONTAL : DIRECTION_VERTICAL;
                                setDirection(newDir);
                            } else if (isDragging) {
                                clampToParentBounds(view);
                                persistPosition(view.getX(), view.getY());
                                currentAnchor = ANCHOR_NONE;
                                preferences.edit().putInt(PREF_HINTS_ANCHOR, ANCHOR_NONE).apply();
                            }
                            activePointerId = -1;
                            return true;
                        }
                        return false;
                }
                return false;
            }
        });
    }

    private void clampToParentBounds(View view) {
        View parentView = (View) view.getParent();
        if (parentView == null) return;
        if (parentView.getWidth() <= 0 || parentView.getHeight() <= 0
                || view.getWidth() <= 0 || view.getHeight() <= 0) return;
        float maxX = Math.max(0f, parentView.getWidth() - view.getWidth());
        float maxY = Math.max(0f, parentView.getHeight() - view.getHeight());
        view.setX(Math.max(0f, Math.min(view.getX(), maxX)));
        view.setY(Math.max(0f, Math.min(view.getY(), maxY)));
    }

    // ── Long-press 9-grid position menu ────────────────────────────
    private int dp(float v) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private void showPositionMenu() {
        if (!isAttachedToWindow()) return;
        dismissPositionPopup();

        int surface = ContextCompat.getColor(context, R.color.settings_popup_surface);
        int edge = ContextCompat.getColor(context, R.color.settings_popup_surface_edge);
        int textColor = ContextCompat.getColor(context, R.color.settings_text_primary);
        int rippleColor = 0x33A0C8FF;

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(surface);
        bg.setCornerRadius(dp(10));
        bg.setStroke(dp(1), edge);

        LinearLayout menuLayout = new LinearLayout(context);
        menuLayout.setOrientation(LinearLayout.VERTICAL);
        menuLayout.setBackground(bg);
        menuLayout.setPadding(dp(5), dp(5), dp(5), dp(5));
        menuLayout.setElevation(dp(8));

        TextView header = new TextView(context);
        header.setText(R.string.hud_position_menu_title);
        header.setTextColor(textColor);
        header.setAlpha(0.7f);
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        header.setGravity(Gravity.CENTER_HORIZONTAL);
        header.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        header.setPadding(dp(4), dp(1), dp(4), dp(4));
        menuLayout.addView(header);

        GridLayout grid = new GridLayout(context);
        grid.setColumnCount(3);
        grid.setRowCount(3);

        final int cellSize = dp(36);
        final int cellMargin = dp(1);
        final int iconPadding = dp(7);

        int[][] cells = {
                {ANCHOR_TOP_LEFT, R.drawable.ic_hud_arrow_north_west},
                {ANCHOR_TOP_CENTER, R.drawable.ic_hud_arrow_north},
                {ANCHOR_TOP_RIGHT, R.drawable.ic_hud_arrow_north_east},
                {ANCHOR_LEFT_CENTER, R.drawable.ic_hud_arrow_west},
                {-1, 0},
                {ANCHOR_RIGHT_CENTER, R.drawable.ic_hud_arrow_east},
                {ANCHOR_BOTTOM_LEFT, R.drawable.ic_hud_arrow_south_west},
                {ANCHOR_BOTTOM_CENTER, R.drawable.ic_hud_arrow_south},
                {ANCHOR_BOTTOM_RIGHT, R.drawable.ic_hud_arrow_south_east},
        };

        for (int[] cell : cells) {
            final int anchor = cell[0];
            final int iconRes = cell[1];

            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = cellSize;
            lp.height = cellSize;
            lp.setMargins(cellMargin, cellMargin, cellMargin, cellMargin);

            if (anchor == -1) {
                View placeholder = new View(context);
                placeholder.setLayoutParams(lp);
                grid.addView(placeholder);
                continue;
            }

            ImageView item = new ImageView(context);
            item.setLayoutParams(lp);
            item.setImageResource(iconRes);
            item.setScaleType(ImageView.ScaleType.FIT_CENTER);
            item.setPadding(iconPadding, iconPadding, iconPadding, iconPadding);
            item.setBackground(buildItemRipple(rippleColor));
            item.setClickable(true);
            item.setFocusable(true);
            item.setContentDescription(getResources().getString(labelForAnchor(anchor)));
            item.setOnClickListener(v -> {
                applyAnchor(anchor, true);
                dismissPositionPopup();
            });
            grid.addView(item);
        }

        menuLayout.addView(grid);

        PopupWindow popup = new PopupWindow(menuLayout,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                true);
        popup.setOutsideTouchable(true);
        popup.setElevation(dp(8));
        popup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
        this.positionPopup = popup;

        menuLayout.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int popupW = menuLayout.getMeasuredWidth();
        int popupH = menuLayout.getMeasuredHeight();

        View parentView = (View) getParent();
        int parentW = parentView != null ? parentView.getWidth() : popupW;
        int parentH = parentView != null ? parentView.getHeight() : popupH;
        int[] parentLoc = new int[2];
        if (parentView != null) parentView.getLocationOnScreen(parentLoc);

        int hudCenterX = (int) (getX() + (getWidth() * getScaleX()) / 2f);
        int hudCenterY = (int) (getY() + (getHeight() * getScaleY()) / 2f);
        int x = Math.max(dp(8), Math.min(hudCenterX - popupW / 2, parentW - popupW - dp(8)));
        int y = Math.max(dp(8), Math.min(hudCenterY - popupH / 2, parentH - popupH - dp(8)));

        popup.showAtLocation(parentView != null ? parentView : this, Gravity.NO_GRAVITY,
                parentLoc[0] + x, parentLoc[1] + y);
    }

    private int labelForAnchor(int anchor) {
        switch (anchor) {
            case ANCHOR_TOP_LEFT: return R.string.hud_position_top_left;
            case ANCHOR_TOP_CENTER: return R.string.hud_position_top_center;
            case ANCHOR_TOP_RIGHT: return R.string.hud_position_top_right;
            case ANCHOR_LEFT_CENTER: return R.string.hud_position_left_center;
            case ANCHOR_RIGHT_CENTER: return R.string.hud_position_right_center;
            case ANCHOR_BOTTOM_LEFT: return R.string.hud_position_bottom_left;
            case ANCHOR_BOTTOM_CENTER: return R.string.hud_position_bottom_center;
            case ANCHOR_BOTTOM_RIGHT: return R.string.hud_position_bottom_right;
            default: return R.string.hud_position_menu_title;
        }
    }

    private Drawable buildItemRipple(int rippleColor) {
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(0xFFFFFFFF);
        mask.setCornerRadius(dp(8));
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), null, mask);
    }

    private void dismissPositionPopup() {
        if (this.positionPopup != null) {
            try { this.positionPopup.dismiss(); } catch (Exception ignored) {}
            this.positionPopup = null;
        }
    }

    private void applyAnchor(int anchor, boolean persist) {
        View parentView = (View) getParent();
        if (parentView == null || parentView.getWidth() <= 0 || parentView.getHeight() <= 0
                || getWidth() <= 0 || getHeight() <= 0) {
            this.currentAnchor = anchor;
            if (persist) preferences.edit().putInt(PREF_HINTS_ANCHOR, anchor).apply();
            post(() -> {
                if (getWidth() > 0 && getHeight() > 0) applyAnchor(anchor, false);
            });
            return;
        }

        float maxX = Math.max(0f, parentView.getWidth() - getWidth());
        float maxY = Math.max(0f, parentView.getHeight() - getHeight());
        float centerX = Math.max(0f, (parentView.getWidth() - getWidth()) / 2f);
        float centerY = Math.max(0f, (parentView.getHeight() - getHeight()) / 2f);

        float targetX, targetY;
        switch (anchor) {
            case ANCHOR_TOP_LEFT:     targetX = 0f;     targetY = 0f;     break;
            case ANCHOR_TOP_CENTER:   targetX = centerX; targetY = 0f;    break;
            case ANCHOR_TOP_RIGHT:    targetX = maxX;   targetY = 0f;     break;
            case ANCHOR_LEFT_CENTER:  targetX = 0f;     targetY = centerY; break;
            case ANCHOR_RIGHT_CENTER: targetX = maxX;   targetY = centerY; break;
            case ANCHOR_BOTTOM_LEFT:  targetX = 0f;     targetY = maxY;   break;
            case ANCHOR_BOTTOM_CENTER:targetX = centerX; targetY = maxY;   break;
            case ANCHOR_BOTTOM_RIGHT: targetX = maxX;   targetY = maxY;   break;
            default: return;
        }

        targetX = Math.max(0f, Math.min(targetX, maxX));
        targetY = Math.max(0f, Math.min(targetY, maxY));
        setX(targetX);
        setY(targetY);
        this.currentAnchor = anchor;
        persistPosition(targetX, targetY);
        if (persist) preferences.edit().putInt(PREF_HINTS_ANCHOR, anchor).apply();
    }

    // ── Xbox controller key → drawable mapping ─────────────────────
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
}