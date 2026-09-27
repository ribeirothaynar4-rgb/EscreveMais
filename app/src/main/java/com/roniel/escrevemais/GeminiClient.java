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
import java.util.ArrayList;
import java.util.List;

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
    private static final int MAX_OUTPUT_TOKENS = 400;
    public static final int HISTORY_LIMIT = 20;

    private static final String KEY_DAY = "free_usage_day";
    private static final String KEY_DAY_COUNT = "free_usage_day_count";
    private static final String KEY_MONTH = "free_usage_month";
    private static final String KEY_MONTH_COUNT = "free_usage_month_count";
    private static final String KEY_HISTORY = "suggestion_history";

    public interface Callback {
        void onSuccess(String text);
        void onError(String message);
    }

    public static void rewrite(Context context, String original, String mode, Callback callback) {
        rewrite(context, original, mode, "", callback);
    }

    public static void rewrite(Context context, String original, String mode, String screenContext, Callback callback) {
        SharedPreferences sp = context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
        String key = sp.getString(MainActivity.KEY_API, "").trim();
        String userStyle = sp.getString(MainActivity.KEY_STYLE,
                "Natural, educado, claro e sem parecer texto de robô.").trim();

        if (key.isEmpty()) {
            callback.onError("Abra o Escreve+ e informe sua chave gratuita da API Gemini.");
            return;
        }

        String text = original == null ? "" : original.trim();
        String ctx = screenContext == null ? "" : screenContext.trim();
        if (text.length() > MAX_INPUT_CHARS) {
            callback.onError("Para proteger o modo gratuito, o Escreve+ aceita até " +
                    MAX_INPUT_CHARS + " caracteres por vez.");
            return;
        }
        if (ctx.length() > MAX_INPUT_CHARS) {
            ctx = ctx.substring(ctx.length() - MAX_INPUT_CHARS);
        }

        if (text.isEmpty() && !allowsEmptyInput(mode)) {
            callback.onError("Digite uma mensagem primeiro.");
            return;
        }
        if (text.isEmpty() && ctx.isEmpty() && !"Desculpa".equals(mode)) {
            callback.onError("Digite uma mensagem ou abra uma conversa primeiro.");
            return;
        }

        String usageError = validateUsageLimit(sp);
        if (usageError != null) {
            callback.onError(usageError);
            return;
        }

        registerRequest(sp);
        final String input = text;
        final String contextText = ctx;
        final float temperature = temperatureFor(mode);

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

                String instruction = buildInstruction(mode, userStyle, input, contextText);
                JSONObject body = new JSONObject();
                JSONArray contents = new JSONArray();
                JSONObject content = new JSONObject();
                JSONArray parts = new JSONArray();
                parts.put(new JSONObject().put("text", instruction));
                content.put("parts", parts);
                contents.put(content);
                body.put("contents", contents);
                body.put("generationConfig", new JSONObject()
                        .put("temperature", temperature)
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
                    String cleaned = clean(result);
                    saveHistory(sp, mode, input, cleaned);
                    callback.onSuccess(cleaned);
                }
            } catch (Exception e) {
                callback.onError("Não foi possível conectar à IA. Verifique sua internet e tente novamente.");
            } finally {
                if (conn != null) conn.disconnect();
            }
        }).start();
    }

    public static boolean allowsEmptyInput(String mode) {
        return "Responder".equals(mode) || "Desculpa".equals(mode) || "Ideia".equals(mode);
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

    public static List<HistoryItem> getHistory(Context context) {
        SharedPreferences sp = context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
        List<HistoryItem> items = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(sp.getString(KEY_HISTORY, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                HistoryItem item = new HistoryItem();
                item.mode = o.optString("mode");
                item.original = o.optString("original");
                item.suggestion = o.optString("suggestion");
                items.add(item);
            }
        } catch (Exception ignored) {}
        return items;
    }

    public static final class HistoryItem {
        public String mode;
        public String original;
        public String suggestion;
    }

    private static synchronized void saveHistory(SharedPreferences sp, String mode, String original, String suggestion) {
        try {
            JSONArray arr = new JSONArray(sp.getString(KEY_HISTORY, "[]"));
            JSONObject item = new JSONObject();
            item.put("mode", mode);
            item.put("original", original == null ? "" : original);
            item.put("suggestion", suggestion);
            item.put("ts", System.currentTimeMillis());
            JSONArray next = new JSONArray();
            next.put(item);
            int limit = Math.min(arr.length(), HISTORY_LIMIT - 1);
            for (int i = 0; i < limit; i++) next.put(arr.getJSONObject(i));
            sp.edit().putString(KEY_HISTORY, next.toString()).apply();
        } catch (Exception ignored) {}
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

    private static float temperatureFor(String mode) {
        switch (mode) {
            case "Corrigir":
            case "Sem emoji":
                return 0.15f;
            case "3 versões":
            case "Ideia":
            case "Responder":
                return 0.55f;
            default:
                return 0.35f;
        }
    }

    private static String buildInstruction(String mode, String style, String original, String screenContext) {
        String goal;
        boolean numbered = false;
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
            case "Educado":
                goal = "Deixe a mensagem mais educada e respeitosa, sem mudar o recado nem exagerar em formalidade.";
                break;
            case "Firme":
                goal = "Deixe a mensagem firme, direta e clara, sem grosseria, ironia pesada ou agressividade.";
                break;
            case "Desculpa":
                if (original.isEmpty()) {
                    goal = "Escreva um pedido de desculpas curto, sincero e humano em português do Brasil.";
                } else {
                    goal = "Transforme o texto em um pedido de desculpas sincero, curto e sem drama excessivo.";
                }
                break;
            case "Responder":
                goal = "Escreva uma resposta pronta para enviar, adequada ao contexto da conversa. " +
                        "Se houver um rascunho do usuário, use-o como base e só melhore. " +
                        "Se não houver rascunho, crie uma resposta natural, educada e objetiva.";
                break;
            case "Traduzir":
                goal = "Se o texto estiver em português, traduza para inglês natural. " +
                        "Se estiver em inglês ou outro idioma, traduza para português do Brasil. " +
                        "Mantenha o mesmo tom.";
                break;
            case "Resumir":
                goal = "Resuma a mensagem de forma clara e curta, mantendo os pontos importantes.";
                break;
            case "Lista":
                goal = "Transforme o texto em uma lista objetiva com tópicos curtos, usando hífen. Sem introdução.";
                break;
            case "Emojis":
                goal = "Reescreva a mensagem de forma natural e acrescente poucos emojis adequados ao tom. Não exagere.";
                break;
            case "Sem emoji":
                goal = "Reescreva a mensagem sem emojis, sem gíria excessiva e com pontuação correta, mantendo o sentido.";
                break;
            case "3 versões":
                numbered = true;
                goal = "Crie 3 versões curtas da mensagem: 1) natural, 2) mais educada, 3) mais direta. Cada uma em uma linha.";
                break;
            case "Ideia":
                goal = "Transforme a ideia solta, rascunho ou conversa visível em uma mensagem pronta para enviar, clara e natural.";
                break;
            default:
                goal = "Melhore clareza, fluidez, ortografia e pontuação, mantendo um tom natural.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Você é um assistente de escrita em português do Brasil.\n");
        sb.append("Tarefa: ").append(goal).append("\n");
        sb.append("Estilo preferido do usuário: ").append(style).append("\n");
        sb.append("REGRAS OBRIGATÓRIAS:\n");
        sb.append("- Preserve rigorosamente o sentido, intenção, nomes, fatos, números e datas.\n");
        sb.append("- Não invente informação nem mude a posição/opinião do autor.\n");
        if (numbered) {
            sb.append("- Retorne exatamente 3 linhas no formato:\n1) ...\n2) ...\n3) ...\n");
            sb.append("- Sem títulos, explicações ou texto extra.\n");
        } else {
            sb.append("- Não acrescente explicações, comentários, aspas, títulos ou alternativas.\n");
            sb.append("- Retorne SOMENTE a mensagem final pronta para enviar.\n");
        }
        if (!screenContext.isEmpty()) {
            sb.append("\nConversa visível na tela (use só como contexto):\n").append(screenContext).append("\n");
        }
        if (!original.isEmpty()) {
            sb.append("\nMensagem original:\n").append(original);
        } else {
            sb.append("\nO usuário ainda não digitou um rascunho. Gere a mensagem com base no contexto.");
        }
        return sb.toString();
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
        if (s.startsWith("```") && s.endsWith("```") && s.length() > 6) {
            s = s.substring(3, s.length() - 3).trim();
            if (s.contains("\n")) s = s.substring(s.indexOf('\n') + 1).trim();
        }
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
