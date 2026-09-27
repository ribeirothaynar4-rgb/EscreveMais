package com.roniel.escrevemais;

import android.accessibilityservice.AccessibilityService;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayDeque;
import java.util.Deque;

public class WritingAssistantService extends AccessibilityService {
    private WindowManager wm;
    private View bubble;
    private View panel;
    private final Handler main = new Handler(Looper.getMainLooper());
    private String currentOriginal = "";
    private String currentSuggestion = "";
    private TextView originalText;
    private TextView suggestionText;
    private ProgressBar progress;
    private Button useButton;
    private Button copyButton;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        showBubble();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Deliberately does not store typed text. Content is read only when the user taps the bubble.
    }

    @Override
    public void onInterrupt() { }

    @Override
    public void onDestroy() {
        removeViewSafe(panel);
        removeViewSafe(bubble);
        super.onDestroy();
    }

    private void showBubble() {
        if (bubble != null) return;
        TextView b = new TextView(this);
        b.setText("✦");
        b.setTextSize(27);
        b.setTextColor(Color.WHITE);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(103, 80, 164));
        bg.setShape(GradientDrawable.OVAL);
        bg.setStroke(dp(2), Color.WHITE);
        b.setBackground(bg);
        b.setElevation(dp(10));
        bubble = b;

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                dp(58), dp(58),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.x = dp(14);
        lp.y = dp(240);

        b.setOnClickListener(v -> openAssistant());
        attachDrag(b, lp);
        wm.addView(b, lp);
    }

    private void attachDrag(View view, WindowManager.LayoutParams lp) {
        final float[] downRawX = new float[1];
        final float[] downRawY = new float[1];
        final int[] downX = new int[1];
        final int[] downY = new int[1];
        final boolean[] moved = new boolean[1];
        view.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downRawX[0] = e.getRawX();
                    downRawY[0] = e.getRawY();
                    downX[0] = lp.x;
                    downY[0] = lp.y;
                    moved[0] = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = e.getRawX() - downRawX[0];
                    float dy = e.getRawY() - downRawY[0];
                    if (Math.abs(dx) > dp(4) || Math.abs(dy) > dp(4)) moved[0] = true;
                    // gravity END means horizontal direction is visually reversed.
                    lp.x = Math.max(0, downX[0] - Math.round(dx));
                    lp.y = Math.max(0, downY[0] + Math.round(dy));
                    try { wm.updateViewLayout(view, lp); } catch (Exception ignored) {}
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!moved[0]) v.performClick();
                    return true;
                default:
                    return false;
            }
        });
    }

    private void openAssistant() {
        AccessibilityNodeInfo node = findEditableNode();
        if (node == null) {
            Toast.makeText(this, "Toque primeiro no campo onde você está escrevendo.", Toast.LENGTH_LONG).show();
            return;
        }
        if (isPassword(node)) {
            Toast.makeText(this, "Por segurança, o Escreve+ não processa campos de senha.", Toast.LENGTH_LONG).show();
            return;
        }
        CharSequence value = node.getText();
        currentOriginal = value == null ? "" : value.toString().trim();
        node.recycle();
        if (currentOriginal.isEmpty()) {
            Toast.makeText(this, "Digite uma mensagem primeiro.", Toast.LENGTH_SHORT).show();
            return;
        }
        showPanel();
    }

    private void showPanel() {
        removeViewSafe(panel);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(16), dp(18), dp(18));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(252, 250, 255));
        bg.setCornerRadii(new float[]{dp(24),dp(24),dp(24),dp(24),0,0,0,0});
        bg.setStroke(dp(1), Color.rgb(220, 215, 230));
        root.setBackground(bg);
        root.setElevation(dp(16));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = label("✦  Melhorar mensagem", 19, true);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button close = smallButton("×");
        close.setOnClickListener(v -> removeViewSafe(panel));
        header.addView(close, new LinearLayout.LayoutParams(dp(50), dp(46)));
        root.addView(header);

        TextView originalLabel = label("Seu texto", 12, true);
        originalLabel.setTextColor(Color.GRAY);
        originalLabel.setPadding(0, dp(10), 0, dp(4));
        root.addView(originalLabel);
        originalText = label(currentOriginal, 15, false);
        originalText.setMaxLines(4);
        root.addView(originalText);

        TextView actionsLabel = label("Escolha o que fazer", 12, true);
        actionsLabel.setTextColor(Color.GRAY);
        actionsLabel.setPadding(0, dp(14), 0, dp(6));
        root.addView(actionsLabel);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        addModeButton(row1, "Corrigir");
        addModeButton(row1, "Melhorar");
        addModeButton(row1, "Natural");
        root.addView(row1);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        addModeButton(row2, "Profissional");
        addModeButton(row2, "Curta");
        root.addView(row2);

        progress = new ProgressBar(this);
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(dp(34), dp(34));
        pp.gravity = Gravity.CENTER_HORIZONTAL;
        pp.topMargin = dp(12);
        root.addView(progress, pp);

        suggestionText = label("Escolha uma opção acima para gerar a nova mensagem.", 16, false);
        suggestionText.setPadding(0, dp(14), 0, dp(14));
        root.addView(suggestionText);

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        copyButton = actionButton("Copiar", false);
        useButton = actionButton("Usar esta", true);
        copyButton.setEnabled(false);
        useButton.setEnabled(false);
        copyButton.setOnClickListener(v -> copySuggestion());
        useButton.setOnClickListener(v -> applySuggestion());
        bottom.addView(copyButton, weighted());
        bottom.addView(useButton, weighted());
        root.addView(bottom);

        panel = root;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM;
        wm.addView(panel, lp);
    }

    private void addModeButton(LinearLayout row, String mode) {
        Button b = smallButton(mode);
        b.setTextSize(13);
        b.setOnClickListener(v -> generate(mode));
        row.addView(b, weighted());
    }

    private void generate(String mode) {
        if (progress != null) progress.setVisibility(View.VISIBLE);
        suggestionText.setText("Preparando uma versão melhor…");
        useButton.setEnabled(false);
        copyButton.setEnabled(false);
        currentSuggestion = "";

        GeminiClient.rewrite(this, currentOriginal, mode, new GeminiClient.Callback() {
            @Override
            public void onSuccess(String text) {
                main.post(() -> {
                    if (panel == null) return;
                    progress.setVisibility(View.GONE);
                    currentSuggestion = text;
                    suggestionText.setText(text);
                    useButton.setEnabled(true);
                    copyButton.setEnabled(true);
                });
            }

            @Override
            public void onError(String message) {
                main.post(() -> {
                    if (panel == null) return;
                    progress.setVisibility(View.GONE);
                    suggestionText.setText(message);
                    Toast.makeText(WritingAssistantService.this, message, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void applySuggestion() {
        if (currentSuggestion.isEmpty()) return;
        AccessibilityNodeInfo node = findEditableNode();
        if (node == null) {
            Toast.makeText(this, "Não encontrei mais o campo de texto. Feche o painel, toque no campo e tente novamente.", Toast.LENGTH_LONG).show();
            return;
        }
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, currentSuggestion);
        boolean ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        node.recycle();
        if (ok) {
            Toast.makeText(this, "Mensagem substituída.", Toast.LENGTH_SHORT).show();
            removeViewSafe(panel);
        } else {
            copySuggestion();
            Toast.makeText(this, "Este app não permitiu substituir automaticamente. O texto foi copiado.", Toast.LENGTH_LONG).show();
        }
    }

    private void copySuggestion() {
        if (currentSuggestion.isEmpty()) return;
        ClipboardManager cb = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cb.setPrimaryClip(ClipData.newPlainText("Escreve+", currentSuggestion));
        Toast.makeText(this, "Texto copiado.", Toast.LENGTH_SHORT).show();
    }

    private AccessibilityNodeInfo findEditableNode() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return null;

        AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (focused != null && focused.isEditable()) {
            root.recycle();
            return focused;
        }
        if (focused != null) focused.recycle();

        Deque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(root);
        AccessibilityNodeInfo candidate = null;
        while (!q.isEmpty()) {
            AccessibilityNodeInfo n = q.removeFirst();
            if (n.isEditable() && n.isFocused()) {
                candidate = AccessibilityNodeInfo.obtain(n);
                n.recycle();
                while (!q.isEmpty()) q.removeFirst().recycle();
                break;
            }
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) q.addLast(c);
            }
            n.recycle();
        }
        return candidate;
    }

    private boolean isPassword(AccessibilityNodeInfo n) {
        if (n.isPassword()) return true;
        int t = n.getInputType();
        int variation = t & android.text.InputType.TYPE_MASK_VARIATION;
        return variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD;
    }

    private LinearLayout.LayoutParams weighted() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(46), 1);
        lp.setMargins(dp(4), dp(4), dp(4), dp(4));
        return lp;
    }

    private TextView label(String value, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(Color.rgb(29, 27, 32));
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private Button smallButton(String value) {
        Button b = new Button(this);
        b.setText(value);
        b.setAllCaps(false);
        b.setTextColor(Color.rgb(65, 48, 110));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(239, 233, 252));
        bg.setCornerRadius(dp(13));
        b.setBackground(bg);
        return b;
    }

    private Button actionButton(String value, boolean primary) {
        Button b = new Button(this);
        b.setText(value);
        b.setAllCaps(false);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        if (primary) {
            bg.setColor(Color.rgb(103, 80, 164));
            b.setTextColor(Color.WHITE);
        } else {
            bg.setColor(Color.rgb(236, 231, 242));
            b.setTextColor(Color.rgb(55, 45, 70));
        }
        b.setBackground(bg);
        return b;
    }

    private void removeViewSafe(View v) {
        if (v == null || wm == null) return;
        try { wm.removeView(v); } catch (Exception ignored) {}
        if (v == panel) panel = null;
        if (v == bubble) bubble = null;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
