package com.roniel.escrevemais;

import android.accessibilityservice.AccessibilityService;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.DisplayMetrics;
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
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WritingAssistantService extends AccessibilityService {
    private static final String[][] MODE_ROWS = {
            {"Corrigir", "Melhorar", "Natural"},
            {"Profissional", "Curta", "Educado"},
            {"Firme", "Desculpa", "Responder"},
            {"Traduzir", "Resumir", "Lista"},
            {"Emojis", "Sem emoji", "3 versões"},
            {"Ideia"}
    };

    private WindowManager wm;
    private View bubble;
    private View panel;
    private View undoBar;
    private final Handler main = new Handler(Looper.getMainLooper());
    private String currentOriginal = "";
    private String currentSuggestion = "";
    private String screenContext = "";
    private String undoText = "";
    private TextView originalText;
    private TextView suggestionText;
    private LinearLayout versionsRow;
    private LinearLayout historyBox;
    private ProgressBar progress;
    private Button useButton;
    private Button copyButton;
    private final Runnable hideUndo = this::hideUndoBar;

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
        main.removeCallbacks(hideUndo);
        removeViewSafe(undoBar);
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
        hideUndoBar();
        AccessibilityNodeInfo node = findEditableNode();
        if (node != null && isPassword(node)) {
            node.recycle();
            Toast.makeText(this, "Por segurança, o Escreve+ não processa campos de senha.", Toast.LENGTH_LONG).show();
            return;
        }
        CharSequence value = node == null ? null : node.getText();
        currentOriginal = value == null ? "" : value.toString().trim();
        if (node != null) node.recycle();
        screenContext = collectScreenContext();
        if (currentOriginal.isEmpty() && screenContext.isEmpty()) {
            Toast.makeText(this, "Toque no campo, digite algo, ou abra uma conversa primeiro.", Toast.LENGTH_LONG).show();
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
        bg.setCornerRadii(new float[]{dp(24), dp(24), dp(24), dp(24), 0, 0, 0, 0});
        bg.setStroke(dp(1), Color.rgb(220, 215, 230));
        root.setBackground(bg);
        root.setElevation(dp(16));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = label("✦  Escreve+", 19, true);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button history = smallButton("Recentes");
        history.setTextSize(12);
        history.setOnClickListener(v -> toggleHistory());
        header.addView(history, new LinearLayout.LayoutParams(dp(92), dp(40)));
        Button close = smallButton("×");
        close.setOnClickListener(v -> removeViewSafe(panel));
        header.addView(close, new LinearLayout.LayoutParams(dp(46), dp(40)));
        root.addView(header);

        historyBox = new LinearLayout(this);
        historyBox.setOrientation(LinearLayout.VERTICAL);
        historyBox.setVisibility(View.GONE);
        historyBox.setPadding(0, dp(8), 0, dp(4));
        root.addView(historyBox);

        TextView originalLabel = label("Seu texto", 12, true);
        originalLabel.setTextColor(Color.GRAY);
        originalLabel.setPadding(0, dp(10), 0, dp(4));
        root.addView(originalLabel);
        originalText = label(currentOriginal.isEmpty()
                ? "Nada digitado. Use Responder, Desculpa ou Ideia com a conversa da tela."
                : currentOriginal, 15, false);
        originalText.setMaxLines(3);
        root.addView(originalText);

        TextView actionsLabel = label("Escolha o que fazer", 12, true);
        actionsLabel.setTextColor(Color.GRAY);
        actionsLabel.setPadding(0, dp(12), 0, dp(6));
        root.addView(actionsLabel);

        for (String[] rowModes : MODE_ROWS) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (String mode : rowModes) addModeButton(row, mode);
            if (rowModes.length == 1) {
                View spacer = new View(this);
                row.addView(spacer, weighted());
                row.addView(new View(this), weighted());
            }
            root.addView(row);
        }

        progress = new ProgressBar(this);
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(dp(34), dp(34));
        pp.gravity = Gravity.CENTER_HORIZONTAL;
        pp.topMargin = dp(10);
        root.addView(progress, pp);

        suggestionText = label("Escolha uma opção acima para gerar a nova mensagem.", 16, false);
        suggestionText.setPadding(0, dp(10), 0, dp(8));
        root.addView(suggestionText);

        versionsRow = new LinearLayout(this);
        versionsRow.setOrientation(LinearLayout.HORIZONTAL);
        versionsRow.setVisibility(View.GONE);
        root.addView(versionsRow);

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        copyButton = actionButton("Copiar", false);
        useButton = actionButton("Usar esta", true);
        copyButton.setEnabled(false);
        useButton.setEnabled(false);
        copyButton.setOnClickListener(v -> copySuggestion());
        useButton.setOnClickListener(v -> applySuggestion(currentSuggestion, true));
        bottom.addView(copyButton, weighted());
        bottom.addView(useButton, weighted());
        root.addView(bottom);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        panel = scroll;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                Math.min((int) (dm.heightPixels * 0.78f), dp(640)),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM;
        wm.addView(panel, lp);
    }

    private void addModeButton(LinearLayout row, String mode) {
        Button b = smallButton(mode);
        b.setTextSize(12);
        b.setOnClickListener(v -> generate(mode));
        row.addView(b, weighted());
    }

    private void generate(String mode) {
        if (progress != null) progress.setVisibility(View.VISIBLE);
        suggestionText.setText("Falando com a IA…");
        versionsRow.setVisibility(View.GONE);
        versionsRow.removeAllViews();
        useButton.setEnabled(false);
        copyButton.setEnabled(false);
        currentSuggestion = "";

        String ctx = GeminiClient.usesScreenContext(mode) ? screenContext : "";
        GeminiClient.rewrite(this, currentOriginal, mode, ctx, new GeminiClient.Callback() {
            @Override
            public void onSuccess(String text) {
                main.post(() -> {
                    if (panel == null) return;
                    progress.setVisibility(View.GONE);
                    if ("3 versões".equals(mode)) {
                        showVersions(text);
                    } else {
                        currentSuggestion = text;
                        suggestionText.setText(text);
                        useButton.setEnabled(true);
                        copyButton.setEnabled(true);
                    }
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

    private void showVersions(String text) {
        List<String> versions = parseVersions(text);
        if (versions.size() < 2) {
            currentSuggestion = text;
            suggestionText.setText(text);
            useButton.setEnabled(true);
            copyButton.setEnabled(true);
            return;
        }
        currentSuggestion = versions.get(0);
        suggestionText.setText("Versão 1:\n" + currentSuggestion);
        useButton.setEnabled(true);
        copyButton.setEnabled(true);
        versionsRow.setVisibility(View.VISIBLE);
        versionsRow.removeAllViews();
        for (int i = 0; i < versions.size(); i++) {
            final int idx = i;
            final String value = versions.get(i);
            Button b = smallButton("Versão " + (i + 1));
            b.setTextSize(12);
            b.setOnClickListener(v -> {
                currentSuggestion = value;
                suggestionText.setText("Versão " + (idx + 1) + ":\n" + value);
                useButton.setEnabled(true);
                copyButton.setEnabled(true);
            });
            versionsRow.addView(b, weighted());
        }
    }

    private List<String> parseVersions(String text) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("(?m)^\\s*(?:\\d+\\s*[\\).\\-]\\s*|[-•]\\s+)(.+)$").matcher(text);
        while (m.find()) {
            String line = m.group(1).trim();
            if (!line.isEmpty()) out.add(line);
        }
        return out;
    }

    private void toggleHistory() {
        if (historyBox.getVisibility() == View.VISIBLE) {
            historyBox.setVisibility(View.GONE);
            historyBox.removeAllViews();
            return;
        }
        historyBox.removeAllViews();
        List<GeminiClient.HistoryItem> items = GeminiClient.getHistory(this);
        if (items.isEmpty()) {
            TextView empty = label("Ainda não há sugestões recentes.", 13, false);
            empty.setTextColor(Color.GRAY);
            historyBox.addView(empty);
        } else {
            int shown = Math.min(8, items.size());
            for (int i = 0; i < shown; i++) {
                GeminiClient.HistoryItem item = items.get(i);
                Button b = smallButton((item.mode == null ? "Recente" : item.mode) + ": " + preview(item.suggestion));
                b.setTextSize(12);
                b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                b.setOnClickListener(v -> {
                    currentSuggestion = item.suggestion;
                    suggestionText.setText(item.suggestion);
                    useButton.setEnabled(true);
                    copyButton.setEnabled(true);
                    historyBox.setVisibility(View.GONE);
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(42));
                lp.topMargin = dp(4);
                historyBox.addView(b, lp);
            }
        }
        historyBox.setVisibility(View.VISIBLE);
    }

    private String preview(String text) {
        if (text == null) return "";
        String one = text.replace('\n', ' ').trim();
        return one.length() > 42 ? one.substring(0, 42) + "…" : one;
    }

    private void applySuggestion(String text, boolean canUndo) {
        if (text == null || text.isEmpty()) return;
        AccessibilityNodeInfo node = findEditableNode();
        if (node == null) {
            copyText(text);
            Toast.makeText(this, "Não encontrei o campo. O texto foi copiado.", Toast.LENGTH_LONG).show();
            return;
        }
        String previous = currentOriginal;
        CharSequence existing = node.getText();
        if (existing != null && !existing.toString().trim().isEmpty()) {
            previous = existing.toString();
        }
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        boolean ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        node.recycle();
        if (ok) {
            if (canUndo) {
                undoText = previous == null ? "" : previous;
                showUndoBar();
            }
            Toast.makeText(this, canUndo ? "Mensagem substituída." : "Texto desfeito.", Toast.LENGTH_SHORT).show();
            removeViewSafe(panel);
        } else {
            copyText(text);
            Toast.makeText(this, "Este app não permitiu substituir. O texto foi copiado.", Toast.LENGTH_LONG).show();
        }
    }

    private void copySuggestion() {
        copyText(currentSuggestion);
    }

    private void copyText(String text) {
        if (text == null || text.isEmpty()) return;
        ClipboardManager cb = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cb.setPrimaryClip(ClipData.newPlainText("Escreve+", text));
        Toast.makeText(this, "Texto copiado.", Toast.LENGTH_SHORT).show();
    }

    private void showUndoBar() {
        hideUndoBar();
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(16), dp(10), dp(12), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(40, 32, 58));
        bg.setCornerRadius(dp(18));
        bar.setBackground(bg);

        TextView msg = new TextView(this);
        msg.setText("Mensagem aplicada");
        msg.setTextColor(Color.WHITE);
        msg.setTextSize(14);
        bar.addView(msg, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        Button undo = smallButton("Desfazer");
        undo.setTextSize(13);
        undo.setOnClickListener(v -> {
            applySuggestion(undoText, false);
            hideUndoBar();
        });
        bar.addView(undo, new LinearLayout.LayoutParams(dp(110), dp(40)));

        undoBar = bar;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM;
        lp.x = 0;
        lp.y = dp(18);
        lp.width = WindowManager.LayoutParams.MATCH_PARENT;
        try {
            wm.addView(undoBar, lp);
            main.postDelayed(hideUndo, 12000);
        } catch (Exception ignored) {
            undoBar = null;
        }
    }

    private void hideUndoBar() {
        main.removeCallbacks(hideUndo);
        removeViewSafe(undoBar);
        undoBar = null;
    }

    private String collectScreenContext() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return "";
        List<String> lines = new ArrayList<>();
        collectText(root, lines, 0);
        root.recycle();
        if (lines.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        int start = Math.max(0, lines.size() - 18);
        for (int i = start; i < lines.size(); i++) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(lines.get(i));
            if (sb.length() > 1800) break;
        }
        return sb.toString().trim();
    }

    private void collectText(AccessibilityNodeInfo node, List<String> lines, int depth) {
        if (node == null || depth > 24 || lines.size() > 40) return;
        if (node.isPassword()) return;
        CharSequence cs = node.getText();
        if (cs != null) {
            String t = cs.toString().trim();
            if (t.length() >= 2 && t.length() <= 400
                    && !t.equals(currentOriginal)
                    && !looksLikeUiChrome(t)) {
                lines.add(t);
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            collectText(child, lines, depth + 1);
            child.recycle();
        }
    }

    private boolean looksLikeUiChrome(String t) {
        String lower = t.toLowerCase();
        return lower.equals("enviar") || lower.equals("send") || lower.equals("anexo")
                || lower.equals("emoji") || lower.equals("pesquisar") || lower.equals("search")
                || lower.equals("voltar") || lower.equals("back") || lower.equals("camera")
                || lower.equals("câmera") || t.length() <= 1;
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
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(42), 1);
        lp.setMargins(dp(3), dp(3), dp(3), dp(3));
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
        b.setPadding(dp(4), dp(2), dp(4), dp(2));
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
        if (v == undoBar) undoBar = null;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
