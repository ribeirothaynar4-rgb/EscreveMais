package com.roniel.escrevemais;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    public static final String PREFS = "escreve_mais_prefs";
    public static final String KEY_API = "gemini_api_key";
    public static final String KEY_STYLE = "writing_style";

    private TextView status;
    private TextView usage;
    private EditText apiKey;
    private EditText style;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private View buildUi() {
        int pad = dp(20);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(247, 245, 255));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, dp(34), pad, dp(34));
        scroll.addView(root, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView brand = text("Escreve+", 34, true);
        root.addView(brand);
        TextView subtitle = text("Seu assistente pessoal de escrita, em qualquer aplicativo.", 16, false);
        subtitle.setTextColor(Color.rgb(90, 85, 100));
        subtitle.setPadding(0, dp(6), 0, dp(24));
        root.addView(subtitle);

        LinearLayout freeCard = card();
        TextView freeTitle = text("✓ Modo gratuito protegido", 18, true);
        freeTitle.setTextColor(Color.rgb(30, 130, 80));
        freeCard.addView(freeTitle);
        TextView freeText = text(
                "Modelo travado em Gemini 3.5 Flash-Lite. O aplicativo não permite trocar para outro modelo, " +
                        "não usa Pesquisa Google/Maps e limita o tamanho e a quantidade de solicitações.",
                14, false);
        freeText.setTextColor(Color.DKGRAY);
        freeText.setPadding(0, dp(8), 0, dp(6));
        freeCard.addView(freeText);
        usage = text("Uso: carregando…", 13, true);
        freeCard.addView(usage);
        root.addView(freeCard);

        LinearLayout card = card();
        status = text("Verificando…", 16, true);
        card.addView(status);
        TextView statusHelp = text("Ative o serviço uma única vez. Depois, a bolha ficará disponível sobre WhatsApp, Instagram, Telegram e outros apps.", 14, false);
        statusHelp.setTextColor(Color.DKGRAY);
        statusHelp.setPadding(0, dp(8), 0, dp(14));
        card.addView(statusHelp);
        Button open = button("Ativar assistente");
        open.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        card.addView(open);
        LinearLayout.LayoutParams serviceParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        serviceParams.topMargin = dp(16);
        root.addView(card, serviceParams);

        TextView aiTitle = text("Inteligência artificial", 20, true);
        aiTitle.setPadding(0, dp(26), 0, dp(10));
        root.addView(aiTitle);

        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        apiKey = field("Chave gratuita da API Gemini");
        apiKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        apiKey.setText(sp.getString(KEY_API, ""));
        root.addView(apiKey);

        TextView modelLocked = text("Modelo: " + GeminiClient.FIXED_MODEL + "  🔒", 14, true);
        modelLocked.setTextColor(Color.rgb(65, 48, 110));
        modelLocked.setPadding(dp(4), dp(2), 0, dp(14));
        root.addView(modelLocked);

        style = field("Meu estilo de escrita");
        style.setMinLines(3);
        style.setGravity(Gravity.TOP);
        style.setText(sp.getString(KEY_STYLE, "Natural, educado, claro e sem parecer texto de robô. Preserve meu jeito de falar."));
        root.addView(style);

        Button save = button("Salvar configurações");
        save.setOnClickListener(v -> {
            sp.edit()
                    .putString(KEY_API, apiKey.getText().toString().trim())
                    .putString(KEY_STYLE, style.getText().toString().trim())
                    .apply();
            Toast.makeText(this, "Configurações salvas no modo gratuito.", Toast.LENGTH_SHORT).show();
            refreshStatus();
        });
        root.addView(save);

        LinearLayout warning = card();
        TextView w1 = text("Proteção contra cobrança", 18, true);
        warning.addView(w1);
        TextView w2 = text(
                "O Escreve+ nunca ativa faturamento e nunca troca automaticamente para um modelo pago. " +
                        "Para impedir qualquer cobrança pela conta Google, use uma chave criada em um projeto que continue no Free Tier e não ative faturamento pago nesse projeto.",
                14, false);
        w2.setTextColor(Color.DKGRAY);
        w2.setPadding(0, dp(8), 0, 0);
        warning.addView(w2);
        LinearLayout.LayoutParams warningParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        warningParams.topMargin = dp(24);
        root.addView(warning, warningParams);

        LinearLayout privacy = card();
        TextView p1 = text("Privacidade", 18, true);
        privacy.addView(p1);
        TextView p2 = text("O Escreve+ não fica copiando suas conversas. Ele procura o campo de texto atual somente quando você toca na bolha e escolhe uma ação. Campos de senha são bloqueados.", 14, false);
        p2.setTextColor(Color.DKGRAY);
        p2.setPadding(0, dp(8), 0, 0);
        privacy.addView(p2);
        LinearLayout.LayoutParams privacyParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        privacyParams.topMargin = dp(16);
        root.addView(privacy, privacyParams);

        return scroll;
    }

    private void refreshStatus() {
        boolean enabled = isServiceEnabled(this, WritingAssistantService.class);
        if (status != null) {
            status.setText(enabled ? "● Assistente ativo" : "○ Assistente desativado");
            status.setTextColor(enabled ? Color.rgb(30, 130, 80) : Color.rgb(170, 65, 65));
        }
        if (usage != null) {
            usage.setText("Uso local: " + GeminiClient.getTodayUsage(this) + "/" + GeminiClient.DAILY_REQUEST_LIMIT +
                    " hoje • " + GeminiClient.getMonthUsage(this) + "/" + GeminiClient.MONTHLY_REQUEST_LIMIT + " neste mês");
        }
    }

    public static boolean isServiceEnabled(Context context, Class<?> service) {
        String expected = new ComponentName(context, service).flattenToString();
        String enabled = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        for (String item : enabled.split(":")) {
            if (expected.equalsIgnoreCase(item)) return true;
        }
        return false;
    }

    private LinearLayout card() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(18), dp(18), dp(18), dp(18));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(20));
        bg.setStroke(dp(1), Color.rgb(225, 221, 235));
        l.setBackground(bg);
        return l;
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(Color.rgb(29, 27, 32));
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private EditText field(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setTextSize(15);
        e.setTextColor(Color.rgb(29, 27, 32));
        e.setHintTextColor(Color.rgb(120, 115, 128));
        e.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), Color.rgb(205, 200, 215));
        e.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        e.setLayoutParams(lp);
        return e;
    }

    private Button button(String value) {
        Button b = new Button(this);
        b.setText(value);
        b.setTextSize(15);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(103, 80, 164));
        bg.setCornerRadius(dp(14));
        b.setBackground(bg);
        b.setPadding(dp(12), dp(12), dp(12), dp(12));
        return b;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
