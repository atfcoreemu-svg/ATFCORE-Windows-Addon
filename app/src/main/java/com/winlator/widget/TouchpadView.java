package com.winlator.widget;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.winlator.core.AppUtils;
import com.winlator.math.Mathf;
import com.winlator.math.XForm;
import com.winlator.renderer.ViewTransformation;
import com.winlator.winhandler.MouseEventFlags;
import com.winlator.winhandler.WinHandler;
import com.winlator.xserver.Pointer;
import com.winlator.xserver.XServer;

public class TouchpadView extends View implements View.OnCapturedPointerListener {
    private static final byte MAX_FINGERS = 4;
    public static final byte MAX_TAP_TRAVEL_DISTANCE = 10;
    public static final short MAX_TAP_MILLISECONDS = 200;
    public static final float CURSOR_ACCELERATION = 1.5f;
    public static final byte CURSOR_ACCELERATION_THRESHOLD = 6;
    private final Finger[] fingers = new Finger[MAX_FINGERS];
    private byte numFingers = 0;
    private float sensitivity = 1.0f;
    private Finger mouseMoveFinger = null;
    private boolean pointerButtonLeftEnabled = true;
    private boolean pointerButtonRightEnabled = true;
    private boolean moveCursorToTouchpoint = false;
    private Pointer.Button heldTouchButton;
    private final XServer xServer;
    private Runnable fourFingersTapCallback;
    private final float[] xform = XForm.getInstance();

    public TouchpadView(Context context, XServer xServer, boolean capturePointerOnExternalMouse) {
        super(context);
        this.xServer = xServer;
        setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setBackground(createTransparentBackground());
        setClickable(true);
        setFocusable(true);
        setFocusableInTouchMode(capturePointerOnExternalMouse);
        updateXform(AppUtils.getScreenWidth(), AppUtils.getScreenHeight(), xServer.screenInfo.width, xServer.screenInfo.height);

        if (capturePointerOnExternalMouse) {
            setOnCapturedPointerListener(this);
            setOnClickListener(view -> {
                if (requestFocus()) requestPointerCapture();
            });
        }
    }

    private static StateListDrawable createTransparentBackground() {
        StateListDrawable stateListDrawable = new StateListDrawable();
        ColorDrawable focusedDrawable = new ColorDrawable(Color.TRANSPARENT);
        ColorDrawable defaultDrawable = new ColorDrawable(Color.TRANSPARENT);

        stateListDrawable.addState(new int[]{android.R.attr.state_focused}, focusedDrawable);
        stateListDrawable.addState(new int[0], defaultDrawable);
        return stateListDrawable;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateXform(w, h, xServer.screenInfo.width, xServer.screenInfo.height);
    }

    private void updateXform(int outerWidth, int outerHeight, int innerWidth, int innerHeight) {
        ViewTransformation viewTransformation = new ViewTransformation();
        viewTransformation.update(outerWidth, outerHeight, innerWidth, innerHeight);

        float invAspect = 1.0f / viewTransformation.aspect;
        if (!xServer.getRenderer().isFullscreen()) {
            XForm.makeTranslation(xform, -viewTransformation.viewOffsetX, -viewTransformation.viewOffsetY);
            XForm.scale(xform, invAspect, invAspect);
        }
        else XForm.makeScale(xform, invAspect, invAspect);
    }

    private class Finger {
        private int x;
        private int y;
        private final int startX;
        private final int startY;
        private int lastX;
        private int lastY;
        private final long touchTime;

        public Finger(float x, float y) {
            float[] transformedPoint = XForm.transformPoint(xform, x, y);
            this.x = this.startX = this.lastX = (int)transformedPoint[0];
            this.y = this.startY = this.lastY = (int)transformedPoint[1];
            touchTime = System.currentTimeMillis();
        }

        public void update(float x, float y) {
            lastX = this.x;
            lastY = this.y;
            float[] transformedPoint = XForm.transformPoint(xform, x, y);
            this.x = (int)transformedPoint[0];
            this.y = (int)transformedPoint[1];
        }

        private int deltaX() {
            float dx = (x - lastX) * sensitivity;
            if (Math.abs(dx) > CURSOR_ACCELERATION_THRESHOLD) {
                dx *= CURSOR_ACCELERATION;
            }
            return Mathf.roundPoint(dx);
        }

        private int deltaY() {
            float dy = (y - lastY) * sensitivity;
            if (Math.abs(dy) > CURSOR_ACCELERATION_THRESHOLD) {
                dy *= CURSOR_ACCELERATION;
            }
            return Mathf.roundPoint(dy);
        }

        private boolean isTap() {
            return (System.currentTimeMillis() - touchTime) < MAX_TAP_MILLISECONDS && travelDistance() < MAX_TAP_TRAVEL_DISTANCE;
        }

        private float travelDistance() {
            return (float)Math.hypot(x - startX, y - startY);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int actionIndex = event.getActionIndex();
        int pointerId = event.getPointerId(actionIndex);
        int actionMasked = event.getActionMasked();
        if (pointerId >= MAX_FINGERS) return true;

        switch (actionMasked) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                if (event.isFromSource(InputDevice.SOURCE_MOUSE) || fingers[pointerId] != null) return true;
                Finger finger = new Finger(event.getX(actionIndex), event.getY(actionIndex));
                fingers[pointerId] = finger;
                numFingers++;
                releaseTouchButton();
                if (numFingers == 1 && pointerButtonLeftEnabled) {
                    if (moveCursorToTouchpoint && isEnabled()) xServer.injectPointerMove(finger.x, finger.y);
                    pressTouchButton(Pointer.Button.BUTTON_LEFT);
                } else if (numFingers == 2 && pointerButtonRightEnabled) {
                    pressTouchButton(Pointer.Button.BUTTON_RIGHT);
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (event.isFromSource(InputDevice.SOURCE_MOUSE)) {
                    float[] transformedPoint = XForm.transformPoint(xform, event.getX(), event.getY());
                    if (isEnabled()) xServer.injectPointerMove((int)transformedPoint[0], (int)transformedPoint[1]);
                    break;
                }
                Finger movingFinger = null;
                for (int i = 0; i < MAX_FINGERS; i++) {
                    if (fingers[i] == null) continue;
                    int pointerIndex = event.findPointerIndex(i);
                    if (pointerIndex < 0) {
                        removeFinger(i);
                        continue;
                    }
                    fingers[i].update(event.getX(pointerIndex), event.getY(pointerIndex));
                    if (movingFinger == null) movingFinger = fingers[i];
                }
                if (movingFinger != null) handleFingerMove(movingFinger);
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                if (fingers[pointerId] != null) {
                    fingers[pointerId].update(event.getX(actionIndex), event.getY(actionIndex));
                    boolean openMenu = numFingers == MAX_FINGERS && fourFingersTapCallback != null;
                    if (openMenu) {
                        for (Finger touch : fingers) {
                            if (touch != null && !touch.isTap()) openMenu = false;
                        }
                    }
                    removeFinger(pointerId);
                    if (openMenu) fourFingersTapCallback.run();
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                resetTouch();
                break;
        }

        return true;
    }

    private void removeFinger(int pointerId) {
        releaseTouchButton();
        fingers[pointerId] = null;
        numFingers--;
    }

    private void resetTouch() {
        releaseTouchButton();
        for (int i = 0; i < MAX_FINGERS; i++) fingers[i] = null;
        numFingers = 0;
    }

    @Override
    protected void onDetachedFromWindow() {
        resetTouch();
        super.onDetachedFromWindow();
    }

    private void pressTouchButton(Pointer.Button button) {
        if (isEnabled() && !xServer.pointer.isButtonPressed(button)) {
            xServer.injectPointerButtonPress(button);
            heldTouchButton = button;
        }
    }

    private void releaseTouchButton() {
        if (heldTouchButton != null) {
            xServer.injectPointerButtonRelease(heldTouchButton);
            heldTouchButton = null;
        }
    }

    private void handleFingerMove(Finger finger) {
        if (!isEnabled() || numFingers > 2) return;
        if (moveCursorToTouchpoint && numFingers == 1) {
            xServer.injectPointerMove(finger.x, finger.y);
        } else {
            int dx = finger.deltaX();
            int dy = finger.deltaY();
            WinHandler winHandler = xServer.getWinHandler();
            if (xServer.isRelativeMouseMovement()) {
                winHandler.mouseEvent(MouseEventFlags.MOVE, dx, dy, 0);
            } else {
                xServer.injectPointerMoveDelta(dx, dy);
            }
        }
    }

    public void mouseMove(float x, float y, int action) {
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                mouseMoveFinger = new Finger(x, y);
                break;
            case MotionEvent.ACTION_MOVE:
                if (mouseMoveFinger != null) {
                    mouseMoveFinger.update(x, y);
                    handleFingerMove(mouseMoveFinger);
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mouseMoveFinger = null;
                break;
        }
    }

    public void setSensitivity(float sensitivity) {
        this.sensitivity = sensitivity;
    }

    public boolean isPointerButtonLeftEnabled() {
        return pointerButtonLeftEnabled;
    }

    public void setPointerButtonLeftEnabled(boolean pointerButtonLeftEnabled) {
        this.pointerButtonLeftEnabled = pointerButtonLeftEnabled;
    }

    public boolean isPointerButtonRightEnabled() {
        return pointerButtonRightEnabled;
    }

    public void setPointerButtonRightEnabled(boolean pointerButtonRightEnabled) {
        this.pointerButtonRightEnabled = pointerButtonRightEnabled;
    }

    public void setFourFingersTapCallback(Runnable fourFingersTapCallback) {
        this.fourFingersTapCallback = fourFingersTapCallback;
    }

    public boolean isMoveCursorToTouchpoint() {
        return moveCursorToTouchpoint;
    }

    public void setMoveCursorToTouchpoint(boolean moveCursorToTouchpoint) {
        this.moveCursorToTouchpoint = moveCursorToTouchpoint;
    }

    public boolean onExternalMouseEvent(MotionEvent event) {
        boolean handled = false;
        if (isEnabled() && event.isFromSource(InputDevice.SOURCE_MOUSE)) {
            int actionButton = event.getActionButton();
            switch (event.getAction()) {
                case MotionEvent.ACTION_BUTTON_PRESS:
                    if (actionButton == MotionEvent.BUTTON_PRIMARY) {
                        xServer.injectPointerButtonPress(Pointer.Button.BUTTON_LEFT);
                    }
                    else if (actionButton == MotionEvent.BUTTON_SECONDARY) {
                        xServer.injectPointerButtonPress(Pointer.Button.BUTTON_RIGHT);
                    }
                    handled = true;
                    break;
                case MotionEvent.ACTION_BUTTON_RELEASE:
                    if (actionButton == MotionEvent.BUTTON_PRIMARY) {
                        xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT);
                    }
                    else if (actionButton == MotionEvent.BUTTON_SECONDARY) {
                        xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_RIGHT);
                    }
                    handled = true;
                    break;
                case MotionEvent.ACTION_HOVER_MOVE:
                    float[] transformedPoint = XForm.transformPoint(xform, event.getX(), event.getY());
                    xServer.injectPointerMove((int)transformedPoint[0], (int)transformedPoint[1]);
                    handled = true;
                    break;
                case MotionEvent.ACTION_SCROLL:
                    float scrollY = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
                    if (scrollY <= -1.0f) {
                        xServer.injectPointerButtonPress(Pointer.Button.BUTTON_SCROLL_DOWN);
                        xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_SCROLL_DOWN);
                    }
                    else if (scrollY >= 1.0f) {
                        xServer.injectPointerButtonPress(Pointer.Button.BUTTON_SCROLL_UP);
                        xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_SCROLL_UP);
                    }
                    handled = true;
                    break;
            }
        }
        return handled;
    }

    public float[] computeDeltaPoint(float lastX, float lastY, float x, float y) {
        final float[] result = {0, 0};

        XForm.transformPoint(xform, lastX, lastY, result);
        lastX = result[0];
        lastY = result[1];

        XForm.transformPoint(xform, x, y, result);
        x = result[0];
        y = result[1];

        result[0] = x - lastX;
        result[1] = y - lastY;
        return result;
    }

    @Override
    public boolean onCapturedPointer(View view, MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_MOVE) {
            float dx = event.getX() * sensitivity;
            if (Math.abs(dx) > CURSOR_ACCELERATION_THRESHOLD) dx *= CURSOR_ACCELERATION;

            float dy = event.getY() * sensitivity;
            if (Math.abs(dy) > CURSOR_ACCELERATION_THRESHOLD) dy *= CURSOR_ACCELERATION;

            xServer.injectPointerMoveDelta(Mathf.roundPoint(dx), Mathf.roundPoint(dy));
            return true;
        }
        else {
            event.setSource(event.getSource() | InputDevice.SOURCE_MOUSE);
            return onExternalMouseEvent(event);
        }
    }
}
