package com.roniel.escrevemais;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;

public final class GeminiClient {
    private GeminiClient() {}

    /**
     * MODO GRATUITO PROTEGIDO
     *
     * O modelo não pode ser alterado pelo usuário dentro do app. O Escreve+ usa apenas
     * o Gemini 3.5 Flash-Lite, que possui Free Tier segundo a tabela oficial do Google.
     *
     * IMPORTANTE: o app não consegue consultar nem desativar o faturamento da conta Google.
     * Para risco zero de cobrança, use uma chave de um projeto que permaneça no Free Tier
     * e NÃO tenha faturamento pago ativado.
     */
    public static final String FIXED_MODEL = "gemini-3.5-flash-lite";
    public static final int DAILY_REQUEST_LIMIT = 50;
    public static final int MONTHLY_REQUEST_LIMIT = 500;
    public static final int MAX_INPUT_CHARS = 4000;
    private static final int MAX_OUTPUT_TOKENS = 300;

    private static final String KEY_DAY = "free_usage_day";
    private static final String KEY_DAY_COUNT = "free_usage_day_count";
    private static final String KEY_MONTH = "free_usage_month";
    private static final String KEY_MONTH_COUNT = "free_usage_month_count";

    public interface Callback {
        void onSuccess(String text);
        void onError(String message);
    }

    public static void rewrite(Context context, String original, String mode, Callback callback) {
        SharedPreferences sp = context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
        String key = sp.getString(MainActivity.KEY_API, "").trim();
        String userStyle = sp.getString(MainActivity.KEY_STYLE,
                "Natural, educado, claro e sem parecer texto de robô.").trim();

        if (key.isEmpty()) {
            callback.onError("Abra o Escreve+ e informe sua chave gratuita da API Gemini.");
            return;
        }

        if (original == null || original.trim().isEmpty()) {
            callback.onError("Digite uma mensagem primeiro.");
            return;
        }

        if (original.length() > MAX_INPUT_CHARS) {
            callback.onError("Para proteger o modo gratuito, o Escreve+ aceita até " +
                    MAX_INPUT_CHARS + " caracteres por vez.");
            return;
        }

        String usageError = validateUsageLimit(sp);
        if (usageError != null) {
            callback.onError(usageError);
            return;
        }

        // Conta a tentativa antes da chamada para evitar loops/repetições abusivas.
        registerRequest(sp);

        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                String endpoint = "https://generativelanguage.googleapis.com/v1beta/models/" +
                        FIXED_MODEL + ":generateContent?key=" + key;
                conn = (HttpURLConnection) new URL(endpoint).openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");

                String instruction = buildInstruction(mode, userStyle, original);
                JSONObject body = new JSONObject();
                JSONArray contents = new JSONArray();
                JSONObject content = new JSONObject();
                JSONArray parts = new JSONArray();
                parts.put(new JSONObject().put("text", instruction));
                content.put("parts", parts);
                contents.put(content);
                body.put("contents", contents);
                body.put("generationConfig", new JSONObject()
                        .put("temperature", 0.35)
                        .put("maxOutputTokens", MAX_OUTPUT_TOKENS));

                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(bytes);
                }

                int code = conn.getResponseCode();
                InputStream stream = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
                String response = readAll(stream);
                if (code < 200 || code >= 300) {
                    callback.onError(friendlyApiError(code, response));
                    return;
                }

                JSONObject json = new JSONObject(response);
                JSONArray candidates = json.optJSONArray("candidates");
                if (candidates == null || candidates.length() == 0) {
                    callback.onError("A IA não retornou uma sugestão.");
                    return;
                }
                JSONObject c0 = candidates.getJSONObject(0).getJSONObject("content");
                JSONArray outParts = c0.getJSONArray("parts");
                String result = outParts.getJSONObject(0).optString("text", "").trim();
                if (result.isEmpty()) {
                    callback.onError("A IA retornou uma resposta vazia.");
                } else {
                    callback.onSuccess(clean(result));
                }
            } catch (Exception e) {
                callback.onError("Não foi possível conectar à IA. Verifique sua internet e tente novamente.");
            } finally {
                if (conn != null) conn.disconnect();
            }
        }).start();
    }

    public static int getTodayUsage(Context context) {
        SharedPreferences sp = context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
        resetCountersIfNeeded(sp);
        return sp.getInt(KEY_DAY_COUNT, 0);
    }

    public static int getMonthUsage(Context context) {
        SharedPreferences sp = context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
        resetCountersIfNeeded(sp);
        return sp.getInt(KEY_MONTH_COUNT, 0);
    }

    private static String validateUsageLimit(SharedPreferences sp) {
        resetCountersIfNeeded(sp);
        int dayCount = sp.getInt(KEY_DAY_COUNT, 0);
        int monthCount = sp.getInt(KEY_MONTH_COUNT, 0);

        if (dayCount >= DAILY_REQUEST_LIMIT) {
            return "Limite de segurança do modo gratuito atingido hoje (" + DAILY_REQUEST_LIMIT +
                    " usos). Tente novamente amanhã.";
        }
        if (monthCount >= MONTHLY_REQUEST_LIMIT) {
            return "Limite mensal de segurança do modo gratuito atingido (" + MONTHLY_REQUEST_LIMIT +
                    " usos). Aguarde o próximo mês.";
        }
        return null;
    }

    private static synchronized void registerRequest(SharedPreferences sp) {
        resetCountersIfNeeded(sp);
        sp.edit()
                .putInt(KEY_DAY_COUNT, sp.getInt(KEY_DAY_COUNT, 0) + 1)
                .putInt(KEY_MONTH_COUNT, sp.getInt(KEY_MONTH_COUNT, 0) + 1)
                .apply();
    }

    private static void resetCountersIfNeeded(SharedPreferences sp) {
        String today = LocalDate.now().toString();
        String month = YearMonth.now().toString();

        SharedPreferences.Editor edit = null;
        if (!today.equals(sp.getString(KEY_DAY, ""))) {
            edit = sp.edit().putString(KEY_DAY, today).putInt(KEY_DAY_COUNT, 0);
        }
        if (!month.equals(sp.getString(KEY_MONTH, ""))) {
            if (edit == null) edit = sp.edit();
            edit.putString(KEY_MONTH, month).putInt(KEY_MONTH_COUNT, 0);
        }
        if (edit != null) edit.apply();
    }

    private static String buildInstruction(String mode, String style, String original) {
        String goal;
        switch (mode) {
            case "Corrigir":
                goal = "Corrija ortografia, acentuação, pontuação e concordância. Faça o mínimo de mudanças necessário.";
                break;
            case "Profissional":
                goal = "Reescreva de forma profissional, clara e educada, sem ficar excessivamente formal.";
                break;
            case "Natural":
                goal = "Reescreva de forma natural, humana e agradável, como uma boa mensagem de conversa.";
                break;
            case "Curta":
                goal = "Deixe a mensagem mais curta e objetiva, sem perder nenhuma informação importante.";
                break;
            default:
                goal = "Melhore clareza, fluidez, ortografia e pontuação, mantendo um tom natural.";
        }

        return "Você é um assistente de escrita em português do Brasil.\n" +
                "Tarefa: " + goal + "\n" +
                "Estilo preferido do usuário: " + style + "\n" +
                "REGRAS OBRIGATÓRIAS:\n" +
                "- Preserve rigorosamente o sentido, intenção, nomes, fatos, números e datas.\n" +
                "- Não invente informação nem mude a posição/opinião do autor.\n" +
                "- Não acrescente explicações, comentários, aspas, títulos ou alternativas.\n" +
                "- Retorne SOMENTE a mensagem final pronta para enviar.\n\n" +
                "Mensagem original:\n" + original;
    }

    private static String readAll(InputStream input) throws Exception {
        if (input == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }

    private static String clean(String s) {
        s = s.trim();
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() > 1) {
            s = s.substring(1, s.length() - 1).trim();
        }
        return s;
    }

    private static String friendlyApiError(int code, String response) {
        String detail = summarizeError(response);
        String lower = detail.toLowerCase();

        if (code == 429 || lower.contains("quota") || lower.contains("resource_exhausted")) {
            return "A cota gratuita da Gemini foi atingida ou está temporariamente limitada. " +
                    "O Escreve+ não tentará trocar para um modelo pago. Tente novamente mais tarde.";
        }
        if (code == 403 && (lower.contains("billing") || lower.contains("payment"))) {
            return "A API pediu configuração de faturamento. O modo gratuito do Escreve+ não continua nesse caso. " +
                    "Use uma chave de um projeto que permaneça no Free Tier.";
        }
        if (code == 400 && lower.contains("api key")) {
            return "Chave da API inválida. Confira a chave gratuita no Google AI Studio.";
        }
        return "Falha da IA (HTTP " + code + "). " + detail;
    }

    private static String summarizeError(String response) {
        try {
            JSONObject o = new JSONObject(response);
            JSONObject error = o.optJSONObject("error");
            if (error != null) return error.optString("message", "Erro desconhecido da API.");
        } catch (Exception ignored) {}
        return "Verifique sua chave e sua conexão.";
    }
}
