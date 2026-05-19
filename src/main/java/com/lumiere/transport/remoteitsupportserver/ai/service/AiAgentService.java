package com.lumiere.transport.remoteitsupportserver.ai.service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import com.lumiere.transport.remoteitsupportserver.ai.dto.AiAction;
import com.lumiere.transport.remoteitsupportserver.ai.dto.AiActionEnvelope;
import com.lumiere.transport.remoteitsupportserver.ai.dto.AiFrameRequest;
import com.lumiere.transport.remoteitsupportserver.ai.entity.AiSession;
import com.lumiere.transport.remoteitsupportserver.ai.repository.AiSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Service IA — orchestre l'appel a Gemini 2.5 Flash en mode vision et persiste
 * chaque round-trip dans {@code ai_sessions} pour audit.
 *
 * Pipeline :
 *   1. Build payload Gemini (system prompt + image inline_data base64 + commande).
 *   2. POST sur {@code generativelanguage.googleapis.com/.../generateContent}.
 *   3. Extract le premier candidate.parts[0].text — c'est du JSON pur (on a force
 *      {@code response_mime_type: application/json} dans la config).
 *   4. Parse / valide / clamp les coordonnees, retourne l'envelope.
 *
 * Gestion d'erreur :
 *   - timeout HTTP        → status="error", message lisible
 *   - HTTP 4xx/5xx Gemini → status="error" + extrait du body
 *   - JSON malforme       → status="error"
 *   - tout est loggue en DB (ok ou error) avec latence + extrait commande
 */
@Service
public class AiAgentService {

    private static final Logger log = LoggerFactory.getLogger(AiAgentService.class);

    private static final String GEMINI_BASE_URL =
            "https://generativelanguage.googleapis.com/v1beta/models";
    private static final String GEMINI_MODEL = "gemini-2.5-flash";

    private static final Duration HTTP_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration HTTP_READ_TIMEOUT = Duration.ofSeconds(25);
    private static final int MAX_ACTIONS = 32;
// Le system prompt est la piece centrale de ce service : il explique en detail a
    private static final String SYSTEM_PROMPT = """
            You are an OS automation agent embedded in a remote-support tool.
            You receive (1) a JPEG screenshot of a Windows desktop and (2) a
            natural-language instruction from a human IT technician.
            Your job: output a short, ordered list of atomic UI/shell actions
            that, executed in order, accomplish the instruction.

            STRICT OUTPUT FORMAT — return ONLY a single JSON object, no prose,
            no markdown fences, no comments. Shape:
            {
              "rationale": "<one short sentence (<=120 chars) explaining the plan>",
              "actions": [ <action>, <action>, ... ]   // 1..32 items
            }

            Each <action> is exactly one of:
              { "type": "click",        "x": <0..1>, "y": <0..1>, "button": "left"|"right"|"middle" }
              { "type": "double_click", "x": <0..1>, "y": <0..1> }
              { "type": "move",         "x": <0..1>, "y": <0..1> }
              { "type": "type_text",    "text": "<string to type>" }
              { "type": "key",          "key": "<see SUPPORTED KEY NAMES below>", "modifiers": ["ctrl"|"alt"|"shift"|"meta"] }
              { "type": "shell",        "cmd": "<full shell command>", "shell": "cmd"|"powershell"|"bash" }
              { "type": "scroll",       "x": <0..1>, "y": <0..1>, "dy": <int -30..30>, "dx": <int -30..30> }
                  // dy/dx in mouse-wheel "clicks". Positive dy = scroll DOWN, negative = UP.
                  // x/y optional — if present, cursor moves there first.
              { "type": "drag",         "x": <0..1>, "y": <0..1>, "destX": <0..1>, "destY": <0..1>, "button": "left" }
                  // x/y = drag origin, destX/destY = drag destination. For sliders,
                  // drag-and-drop, text selection, window resize.
              { "type": "wait",         "ms": <50..10000> }
              { "type": "screenshot" }

            SUPPORTED KEY NAMES (case-insensitive):
              • Navigation : Enter, Tab, Escape, Backspace, Delete, Insert, Home, End,
                              PageUp, PageDown, ArrowLeft/Right/Up/Down, Space, CapsLock
              • Modifiers  : Shift, Control, Alt, Meta (= Windows key)
              • Function   : F1..F12
              • Characters : single letter/digit (a-z, 0-9)
              • Media      : VolumeUp, VolumeDown, VolumeMute, MediaPlay, MediaNext,
                              MediaPrev, MediaStop  ← USE THESE for audio/playback tasks

            COMMON TASK RECIPES (prefer these, they're rock-solid):

              Volume / audio:
                Decrease:  key "VolumeDown" repeated N times (each press ~2% on Windows).
                Increase:  key "VolumeUp" repeated N times.
                Mute:      Single key "VolumeMute".
                ❌ Never click the Quick Settings volume slider — drag isn't precise enough.
                For an exact level, use shell:
                  shell powershell: (New-Object -ComObject WScript.Shell).SendKeys(...)
                  or better: nircmd setsysvolume <0..65535>

              Brightness:
                shell powershell: (Get-CimInstance Win32_LogicalDisk -Namespace root/wmi).Brightness
                  or: (Get-WmiObject -Namespace root/WMI -Class WmiMonitorBrightnessMethods).WmiSetBrightness(1, <0..100>)

              Media playback (YouTube / Spotify / VLC):
                Use key "MediaPlayPause", "MediaNext", "MediaPrev" — works across all apps
                without needing to focus the player window.

              Open an app (most reliable, works for any installed app):
                key "Meta" → wait 400ms → type_text "<app name>" → wait 600ms → key "Enter"
                → wait 1500ms → screenshot
                Always finish with a screenshot so the technician verifies.

              Open a URL in browser:
                If browser is closed: open it first (recipe above), wait 2000ms.
                Then: key "l" with modifiers ["ctrl"] (Ctrl+L to focus address bar)
                → type_text the URL → key "Enter".

              Search the web:
                Open browser → Ctrl+L → type "https://google.com/search?q=<query>" → Enter.

              Find a file / search in Start:
                key "Meta" → type_text "<filename>" → wait 800ms → screenshot to see
                results → key "Enter" or click the relevant result.

              File Explorer:
                key "e" with modifiers ["meta"] (Win+E).
                Navigate via address bar: Ctrl+L → type path → Enter.
                New folder in current view: key "n" with modifiers ["ctrl", "shift"].
                Refresh:                    key "F5".

              Task manager / process kill:
                Open: key "Escape" with modifiers ["ctrl", "shift"] (Ctrl+Shift+Esc).
                Kill process by name (no UI): shell powershell: Stop-Process -Name <name> -Force
                List top CPU: shell powershell: Get-Process | Sort-Object CPU -Descending | Select -First 10

              Lock screen / sign out / restart:
                Lock:    key "l" with modifiers ["meta"] (Win+L).
                Restart: shell powershell: Restart-Computer -Force -Timeout 5
                Sleep:   shell powershell: rundll32.exe powrprof.dll,SetSuspendState 0,1,0

              Window management:
                Minimize all:       key "d" with modifiers ["meta"] (Win+D).
                Maximize current:   key "ArrowUp" with modifiers ["meta"].
                Close window:       key "F4" with modifiers ["alt"] (Alt+F4).
                Switch app:         key "Tab" with modifiers ["alt"].
                Snap to left half:  key "ArrowLeft" with modifiers ["meta"].
                Snap to right half: key "ArrowRight" with modifiers ["meta"].

              Take a screenshot to the user's clipboard:
                key "PrintScreen" — OR Snipping Tool via Win+Shift+S:
                key "s" with modifiers ["meta", "shift"].

              Scroll a long page / list:
                action "scroll" with dy=5 (down) or dy=-5 (up). Repeat as needed.
                For a Settings page or doc: dy=10 typically scrolls about half a screen.

              Slider control (when media keys don't apply):
                action "drag" from (slider current position) to (target position).
                Estimate target x/y based on the slider's visible range in the screenshot.
                Example for a volume slider going horizontally from x=0.3 to x=0.9 :
                  to go to 50%, drag to x = 0.3 + 0.5*(0.9-0.3) = 0.6.

              Send a chat message (WhatsApp Web, Discord, etc.):
                Click the message input box (use coords from the screenshot).
                Then: type_text "<message>" → key "Enter".

              Type with special characters:
                For accented chars (é, à, ü, etc.), type_text handles Unicode directly.
                For symbols like @ or # on AZERTY keyboards, just type_text "@" —
                the agent uses Windows scancode injection that respects the layout.

              System info queries (return result in shell stdout, technician reads):
                IP address:     shell cmd: ipconfig | findstr IPv4
                Disk free:      shell powershell: Get-PSDrive C | Select Used,Free
                CPU usage:      shell powershell: Get-Counter '\\Processor(_Total)\\% Processor Time'
                Windows version: shell cmd: winver  (opens a dialog, then screenshot)
                List users:     shell cmd: query user

              Install / uninstall via winget (Windows 11):
                Install Chrome: shell powershell: winget install -e --id Google.Chrome
                Update all:     shell powershell: winget upgrade --all
                Uninstall:      shell powershell: winget uninstall <name>
                ⚠️ Long-running — wrap with a 20-30s wait + screenshot to verify.

              Multi-step plans:
                For complex tasks, decompose AGGRESSIVELY into small steps with waits
                and screenshots between each major UI transition. Better 15 small
                actions than 5 ambitious ones — small steps are easier to recover from.

            GENERAL RULES:
              * Coordinates x/y are NORMALISED in [0, 1] relative to the screenshot.
              * Always finish a plan with a "screenshot" action so the technician
                can verify the result.
              * For multi-step UI flows (open Start menu, type, press Enter, …),
                insert a "wait" of 300-2000 ms between UI transitions.
              * Prefer keyboard shortcuts and media keys over clicks when possible
                (Win+R, Ctrl+L, VolumeDown × N, …) — way more reliable than coordinates.
              * For tasks involving SLIDERS (volume slider, brightness slider,
                progress bar) : use the corresponding media key if it exists, OR
                approximate via repeated arrow keys after focusing the slider.
                NEVER try to click+drag (not supported).
              * NEVER invent destructive shell commands (format, rm -rf /, del /F /Q
                C:\\Windows, registry wipes, etc.). If the request is ambiguous or
                dangerous, return:
                  { "rationale": "Refusing: <reason>", "actions": [] }
              * Output MUST be parseable JSON. No trailing commas.
            """;

    private final RestClient httpClient;
    private final ObjectMapper objectMapper;
    private final AiSessionRepository aiSessionRepository;
    private final String geminiApiKey;

    public AiAgentService(ObjectMapper objectMapper,
                          AiSessionRepository aiSessionRepository,
                          @Value("${gemini.api.key:}") String geminiApiKey) {
        this.objectMapper = objectMapper;
        this.aiSessionRepository = aiSessionRepository;
        this.geminiApiKey = geminiApiKey == null ? "" : geminiApiKey.trim();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) HTTP_CONNECT_TIMEOUT.toMillis());
        factory.setReadTimeout((int) HTTP_READ_TIMEOUT.toMillis());

        this.httpClient = RestClient.builder()
                .baseUrl(GEMINI_BASE_URL)
                .requestFactory(factory)
                .build();

        log.info("[ai-service] initialised — model={}, httpConnect={}s, httpRead={}s, apiKey={}",
                GEMINI_MODEL,
                HTTP_CONNECT_TIMEOUT.toSeconds(),
                HTTP_READ_TIMEOUT.toSeconds(),
                this.geminiApiKey.isEmpty() ? "MISSING" : "set");
    }

    /**
     * Point d'entree synchrone — appele depuis le @MessageMapping STOMP.
     * Renvoie toujours une envelope (jamais null) et persiste systematiquement
     * une ligne dans ai_sessions, meme en erreur.
     */
    public AiActionEnvelope analyse(AiFrameRequest req) {
        long t0 = System.currentTimeMillis();

        // Validation entree
        String validationError = validate(req);
        if (validationError != null) {
            return persistAndReturn(req, null, "error", validationError, t0);
        }

        if (geminiApiKey.isEmpty()) {
            return persistAndReturn(req, null, "error",
                    "Gemini API key not configured (gemini.api.key)", t0);
        }

        String body;
        try {
            body = buildGeminiPayload(req);
        } catch (Exception ex) {
            return persistAndReturn(req, null, "error",
                    "Failed to build Gemini payload: " + ex.getMessage(), t0);
        }

        String rawResponseText = null;
        String lastError = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            long tHttpStart = System.currentTimeMillis();
            log.info("[ai-service] → POST Gemini (attempt {}, screenshot ~{} KB)",
                    attempt, req.screenshot().length() / 1024);
            try {
                String response = httpClient.post()
                        .uri(uriBuilder -> uriBuilder
                                .path("/{model}:generateContent")
                                .queryParam("key", geminiApiKey)
                                .build(GEMINI_MODEL))
                        .header("Content-Type", "application/json")
                        .body(body)
                        .retrieve()
                        .body(String.class);
                if (response == null) {
                    lastError = "Empty response from Gemini";
                    break;
                }
                rawResponseText = extractText(response);
                lastError = null;
                log.info("[ai-service] ◀ Gemini OK in {} ms (response {} chars, parsed text {} chars)",
                        System.currentTimeMillis() - tHttpStart,
                        response.length(),
                        rawResponseText == null ? 0 : rawResponseText.length());
                break;
            } catch (HttpStatusCodeException ex) {
                int code = ex.getStatusCode().value();
                String bodyExcerpt = truncate(ex.getResponseBodyAsString(), 400);

                if (code == 429 && attempt == 1) {
                    // Rate-limit transitoire — extrait le retry-after suggere par
                    // l'API si present, sinon fallback 4s.
                    long backoffMs = parseGeminiRetryDelayMs(ex.getResponseBodyAsString())
                            .orElse(4_000L);
                    backoffMs = Math.min(backoffMs, 8_000L); // cap a 8s, on ne veut pas
                                                              // bloquer le pool indefiniment
                    log.info("Gemini 429 received, retrying after {}ms (attempt {})", backoffMs, attempt);
                    try {
                        Thread.sleep(backoffMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        lastError = "Interrupted during 429 backoff";
                        break;
                    }
                    continue;
                }

                lastError = formatHttpError(code, bodyExcerpt);
                log.warn("Gemini HTTP {} (attempt {}): {}", code, attempt, bodyExcerpt);
                break;
            } catch (ResourceAccessException ex) {
                log.warn("Gemini timeout / network error: {}", ex.getMessage());
                lastError = "Gemini timeout/network: " + ex.getMessage();
                break;
            } catch (Exception ex) {
                log.warn("Unexpected error during Gemini call", ex);
                lastError = "Unexpected: " + ex.getClass().getSimpleName() + " — " + ex.getMessage();
                break;
            }
        }

        if (rawResponseText == null) {
            return persistAndReturn(req, null, "error",
                    lastError == null ? "Unknown Gemini error" : lastError, t0);
        }

        // Parse JSON de Gemini
        ParsedPlan plan;
        try {
            plan = parsePlan(rawResponseText);
        } catch (Exception ex) {
            log.warn("Failed to parse Gemini JSON. Raw text: {}", truncate(rawResponseText, 500));
            return persistAndReturn(req, null, "error",
                    "Invalid JSON from Gemini: " + ex.getMessage(), t0);
        }

        if (plan.actions.isEmpty()) {
            // Modele a refuse — on remonte le rationale sans erreur (status ok mais
            // pas d'actions). Pour le front c'est un "refus" lisible.
            String actionsJson = "[]";
            return persistOkAndReturn(req, actionsJson, plan.rationale, List.of(), t0);
        }

        // OK
        String actionsJson;
        try {
            actionsJson = objectMapper.writeValueAsString(plan.actions);
        } catch (JacksonException e) {
            actionsJson = "[]";
        }
        return persistOkAndReturn(req, actionsJson, plan.rationale, plan.actions, t0);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String validate(AiFrameRequest req) {
        if (req == null) return "Missing payload";
        if (req.sessionId() == null || req.sessionId().isBlank()) return "sessionId is required";
        if (req.command() == null || req.command().isBlank()) return "command is required";
        if (req.screenshot() == null || req.screenshot().isBlank()) return "screenshot is required";
        if (req.screenshot().length() > 6_000_000) {
            // ~4.5 MB de JPEG decode — protege contre upload accidentel d'images enormes.
            return "screenshot too large (>6MB base64)";
        }
        return null;
    }

    private String buildGeminiPayload(AiFrameRequest req) {
        ObjectNode root = objectMapper.createObjectNode();

        // generation_config : on FORCE le mime-type JSON pour eviter les markdown fences.
        ObjectNode genConfig = root.putObject("generationConfig");
        genConfig.put("temperature", 0.2);
        genConfig.put("topK", 32);
        genConfig.put("topP", 0.9);
        genConfig.put("maxOutputTokens", 2048);
        genConfig.put("responseMimeType", "application/json");

        // system_instruction
        ObjectNode sysInstr = root.putObject("systemInstruction");
        ArrayNode sysParts = sysInstr.putArray("parts");
        sysParts.addObject().put("text", SYSTEM_PROMPT);

        // contents
        ArrayNode contents = root.putArray("contents");
        ObjectNode userTurn = contents.addObject();
        userTurn.put("role", "user");
        ArrayNode userParts = userTurn.putArray("parts");

        // Image inline
        ObjectNode imagePart = userParts.addObject();
        ObjectNode inlineData = imagePart.putObject("inlineData");
        inlineData.put("mimeType", "image/jpeg");
        inlineData.put("data", req.screenshot());

        // Commande textuelle
        String userText = """
                Technician instruction (French or English):
                "%s"

                Screenshot resolution: %dx%d px
                Return ONLY the JSON object described in the system prompt.
                """.formatted(
                req.command().replace("\"", "\\\""),
                req.frameWidth() == null ? 0 : req.frameWidth(),
                req.frameHeight() == null ? 0 : req.frameHeight()
        );
        userParts.addObject().put("text", userText);

        return objectMapper.writeValueAsString(root);
    }

    private String extractText(String responseBody) {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode candidates = root.path("candidates");
        if (!candidates.isArray() || candidates.isEmpty()) {
            // Gemini renvoie souvent {"promptFeedback":{"blockReason":"..."}}
            JsonNode feedback = root.path("promptFeedback");
            if (!feedback.isMissingNode()) {
                throw new IllegalStateException("Gemini refused: " + feedback.toString());
            }
            throw new IllegalStateException("No candidates in Gemini response: "
                    + truncate(responseBody, 300));
        }
        JsonNode parts = candidates.get(0).path("content").path("parts");
        if (!parts.isArray() || parts.isEmpty()) {
            throw new IllegalStateException("No parts in Gemini candidate");
        }
        return parts.get(0).path("text").asText("");
    }

    private record ParsedPlan(String rationale, List<AiAction> actions) {}

    private ParsedPlan parsePlan(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            throw new IllegalArgumentException("Empty plan text");
        }
        String cleaned = stripMarkdownFences(rawJson);
        JsonNode root = objectMapper.readTree(cleaned);

        String rationale = root.path("rationale").asString(null);
        JsonNode actionsNode = root.path("actions");
        List<AiAction> actions = new ArrayList<>();
        if (actionsNode.isArray()) {
            int i = 0;
            for (JsonNode n : actionsNode) {
                if (i++ >= MAX_ACTIONS) break;
                AiAction a = mapAction(n);
                if (a != null) actions.add(a);
            }
        }
        return new ParsedPlan(rationale, actions);
    }


    private String stripMarkdownFences(String s) {
        String t = s.trim();
        if (t.startsWith("```")) {
            int firstNewline = t.indexOf('\n');
            if (firstNewline > 0) t = t.substring(firstNewline + 1);
            if (t.endsWith("```")) t = t.substring(0, t.length() - 3);
        }
        return t.trim();
    }

   
    private AiAction mapAction(JsonNode n) {
        String type = n.path("type").asText("").toLowerCase();
        return switch (type) {
            case "click", "double_click", "move" -> new AiAction(
                    type,
                    clamp01(n.path("x").asDouble(0)),
                    clamp01(n.path("y").asDouble(0)),
                    "click".equals(type) ? normaliseButton(n.path("button").asText("left")) : null,
                    null, null, null, null, null, null,
                    null, null, null, null
            );
            case "type_text" -> {
                String text = n.path("text").asText("");
                if (text.isEmpty()) yield null;
                if (text.length() > 4000) text = text.substring(0, 4000);
                yield new AiAction("type_text", null, null, null, text, null, null, null, null, null,
                        null, null, null, null);
            }
            case "key" -> {
                String key = n.path("key").asText("").trim();
                if (key.isEmpty()) yield null;
                List<String> mods = new ArrayList<>();
                JsonNode modsNode = n.path("modifiers");
                if (modsNode.isArray()) {
                    for (JsonNode m : modsNode) {
                        String mod = m.asText("").toLowerCase().trim();
                        if (mod.equals("ctrl") || mod.equals("alt") || mod.equals("shift") || mod.equals("meta")) {
                            mods.add(mod);
                        }
                    }
                }
                yield new AiAction("key", null, null, null, null, key, mods, null, null, null,
                        null, null, null, null);
            }
            case "shell" -> {
                String cmd = n.path("cmd").asText("").trim();
                if (cmd.isEmpty()) yield null;
                if (looksDestructive(cmd)) {
                    log.warn("Refusing destructive shell command from Gemini: {}", truncate(cmd, 200));
                    yield null;
                }
                String shell = n.path("shell").asText("powershell").toLowerCase();
                if (!shell.equals("cmd") && !shell.equals("powershell") && !shell.equals("bash")) {
                    shell = "powershell";
                }
                yield new AiAction("shell", null, null, null, null, null, null, cmd, shell, null,
                        null, null, null, null);
            }
            case "wait" -> {
                int ms = n.path("ms").asInt(500);
                ms = Math.max(50, Math.min(10_000, ms));
                yield new AiAction("wait", null, null, null, null, null, null, null, null, ms,
                        null, null, null, null);
            }
            case "screenshot" -> new AiAction("screenshot",
                    null, null, null, null, null, null, null, null, null,
                    null, null, null, null);

            case "scroll" -> {
                int dy = clampScroll(n.path("dy").asInt(0));
                int dx = clampScroll(n.path("dx").asInt(0));
                if (dy == 0 && dx == 0) yield null; // no-op
                Double sx = n.has("x") ? clamp01(n.path("x").asDouble(0)) : null;
                Double sy = n.has("y") ? clamp01(n.path("y").asDouble(0)) : null;
                yield new AiAction("scroll", sx, sy, null, null, null, null, null, null, null,
                        dy, dx, null, null);
            }

            case "drag" -> {
                double fromX = clamp01(n.path("x").asDouble(0));
                double fromY = clamp01(n.path("y").asDouble(0));
                double toX = clamp01(n.path("destX").asDouble(0));
                double toY = clamp01(n.path("destY").asDouble(0));
                String btn = normaliseButton(n.path("button").asText("left"));
                yield new AiAction("drag", fromX, fromY, btn, null, null, null, null, null, null,
                        null, null, toX, toY);
            }
            default -> null;
        };
    }

    private static int clampScroll(int v) {
        if (v > 30) return 30;
        if (v < -30) return -30;
        return v;
    }

    private static String normaliseButton(String b) {
        String x = b == null ? "left" : b.toLowerCase().trim();
        return switch (x) {
            case "left", "right", "middle" -> x;
            default -> "left";
        };
    }

    private static double clamp01(double v) {
        if (Double.isNaN(v)) return 0.0;
        if (v < 0) return 0.0;
        if (v > 1) return 1.0;
        return v;
    }

    private static boolean looksDestructive(String cmd) {
        String l = cmd.toLowerCase();
        return l.contains("format ") || l.contains("rm -rf /") || l.contains("rm -rf ~")
                || l.contains("del /f /s /q c:\\windows") || l.contains("del /f /s /q c:\\")
                || l.contains("rd /s /q c:\\") || l.contains("mkfs") || l.contains("dd if=")
                || l.contains("> /dev/sda") || l.contains("reg delete hklm")
                || l.contains("shutdown /r /f /t 0") || l.contains("net user administrator")
                || l.contains("cipher /w:");
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        if (s.length() <= max) return s;
        return s.substring(0, max) + "…";
    }

  
    private static String formatHttpError(int code, String bodyExcerpt) {
        if (code == 429) {
            // Cas le plus frequent en free-tier : "Quota IA depasse, attends ~30s."
            return "Quota IA depasse (HTTP 429). Free-tier Gemini = ~10 req/min. "
                    + "Attends 30-60s et reessaie, ou passe en tier paye.";
        }
        if (code == 401 || code == 403) {
            return "Cle Gemini invalide ou desactivee (HTTP " + code + "). Verifie gemini.api.key dans application.properties.";
        }
        if (code == 400) {
            return "Requete IA mal formee (HTTP 400) : " + bodyExcerpt;
        }
        if (code >= 500) {
            return "Gemini indisponible (HTTP " + code + "). Reessaie dans quelques secondes.";
        }
        HttpStatus status = HttpStatus.resolve(code);
        return "Gemini HTTP " + code
                + (status != null ? " (" + status.getReasonPhrase() + ")" : "")
                + ": " + bodyExcerpt;
    }


    private java.util.Optional<Long> parseGeminiRetryDelayMs(String body) {
        if (body == null || body.isBlank()) return java.util.Optional.empty();
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode details = root.path("error").path("details");
            if (!details.isArray()) return java.util.Optional.empty();
            for (JsonNode d : details) {
                String delay = d.path("retryDelay").asString("");
                if (!delay.isBlank()) {
                    // Format "30s" / "1.5s" — on extrait les chiffres + .
                    String num = delay.replaceAll("[^0-9.]", "");
                    if (!num.isBlank()) {
                        double seconds = Double.parseDouble(num);
                        return java.util.Optional.of((long) (seconds * 1000));
                    }
                }
            }
        } catch (Exception ignored) {
            // best-effort
        }
        return java.util.Optional.empty();
    }

    // ── Persistance ──────────────────────────────────────────────────────────

    private AiActionEnvelope persistAndReturn(AiFrameRequest req, String actionsJson,
                                              String status, String error, long t0) {
        try {
            AiSession row = new AiSession();
            row.setSessionId(req == null ? "?" : safe(req.sessionId(), 64));
            row.setAdminUser(req == null ? null : safe(req.technicianUsername(), 128));
            row.setCommand(req == null ? "?" : safe(req.command(), 2000));
            row.setActionsJson(actionsJson);
            row.setStatus(status);
            row.setErrorMessage(truncate(error, 1000));
            row.setLatencyMs(System.currentTimeMillis() - t0);
            aiSessionRepository.save(row);
        } catch (Exception ex) {
            log.warn("Failed to persist ai_sessions row (status={}): {}", status, ex.getMessage());
        }
        return AiActionEnvelope.error(req == null ? null : req.sessionId(),
                req == null ? null : req.command(), error);
    }

    private AiActionEnvelope persistOkAndReturn(AiFrameRequest req, String actionsJson,
                                                String rationale, List<AiAction> actions, long t0) {
        try {
            AiSession row = new AiSession();
            row.setSessionId(safe(req.sessionId(), 64));
            row.setAdminUser(safe(req.technicianUsername(), 128));
            row.setCommand(safe(req.command(), 2000));
            row.setActionsJson(actionsJson);
            row.setStatus("ok");
            row.setLatencyMs(System.currentTimeMillis() - t0);
            aiSessionRepository.save(row);
        } catch (Exception ex) {
            log.warn("Failed to persist ai_sessions row (ok): {}", ex.getMessage());
        }
        return AiActionEnvelope.ok(req.sessionId(), req.command(), rationale, actions);
    }

    private static String safe(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    // Reserve pour les tests / accees direct si besoin
    public Map<String, Object> debugInfo() {
        return Map.of(
                "model", GEMINI_MODEL,
                "apiKeyConfigured", !geminiApiKey.isEmpty(),
                "httpConnectTimeoutSeconds", HTTP_CONNECT_TIMEOUT.toSeconds(),
                "httpReadTimeoutSeconds", HTTP_READ_TIMEOUT.toSeconds()
        );
    }
}
