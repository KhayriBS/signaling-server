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
 * Service IA — orchestre l'appel a Groq (Llama 4 Scout en mode vision) 
 *
 * Pipeline :
 *   1. Build payload OpenAI-style (messages: system + user multimodal avec image).
 *   2. POST sur {@code api.groq.com/openai/v1/chat/completions}.
 *   3. Extract {@code choices[0].message.content} — c'est du JSON pur (on a
 *      force {@code response_format.type=json_object}).
 *   4. Parse / valide / clamp les coordonnees, retourne l'envelope.
 *
 * Gestion d'erreur :
 *   - timeout HTTP     → status="error", message lisible
 *   - HTTP 4xx/5xx Groq → status="error" + extrait du body
 *   - JSON malforme    → status="error"
 *   - tout est loggue en DB (ok ou error) avec latence + extrait commande
 */
@Service
public class AiAgentService {

    private static final Logger log = LoggerFactory.getLogger(AiAgentService.class);

    private static final String GROQ_BASE_URL = "https://api.groq.com/openai/v1";

    private static final Duration HTTP_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration HTTP_READ_TIMEOUT = Duration.ofSeconds(25);
    private static final int MAX_ACTIONS = 32;
    private static final String SYSTEM_PROMPT = """
            You are an OS automation agent embedded in a remote-support tool.
            You receive (1) a JPEG screenshot of a Windows desktop and (2) a
            natural-language instruction from a human IT technician.
            Your job: output a short, ordered list of atomic UI/shell actions
            that, executed in order, accomplish the instruction.

            AGENTIC LOOP MODE
            You operate in an iterative loop. After every batch of actions you
            return, the technician's client EXECUTES them, captures a fresh
            screenshot, and sends it back to you along with the previous
            turns' summary. You then decide:
              • Continue: return more actions, with "done": false.
              • Stop:     return "done": true and an empty (or short final)
                           actions list — the loop ends and the technician
                           sees your final rationale as the answer.
            The frontend caps the loop at 5 iterations. Use the history field
            in the request to know what you already did — DO NOT repeat the
            same action twice in a row if it had no effect.

            STRICT OUTPUT FORMAT — return ONLY a single JSON object, no prose,
            no markdown fences, no comments. Shape:
            {
              "rationale": "<one short sentence (<=120 chars) explaining this turn>",
              "done":      <true|false>,                // omit = false
              "actions":   [ <action>, <action>, ... ]  // 0..32 items
            }
            On the FINAL turn (done=true), "rationale" should be the human-
            readable answer to the technician's original question (e.g.
            "The IP is 192.168.1.42" or "Notepad++ is now installed and pinned").

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

              Check if an app is INSTALLED ("vérifier si X est installé"):
                ❌ DO NOT open the app to check — opening it doesn't prove it's
                   installed cleanly, and many apps require admin context.
                ✅ Use a single shell action — the loop will return the stdout
                   to you at the next iteration, then set done=true with the answer.
                Recipes (try in this order, first non-empty result wins):
                  shell powershell: Get-Command <name> -ErrorAction SilentlyContinue
                  shell powershell: winget list --name "<name>" 2>$null
                  shell cmd:        where <name>
                Example for "/ai vérifier si Notepad est installé" — return:
                  { "rationale":"Je vérifie via Get-Command", "done":false,
                    "actions":[{"type":"shell","cmd":"Get-Command notepad -ErrorAction SilentlyContinue | Select-Object Source","shell":"powershell"}] }
                Then at the NEXT turn, you'll see the shell stdout in history.
                If non-empty: { "rationale":"Oui, Notepad est installé : <path>", "done":true, "actions":[] }
                If empty:    next try winget list, then `where`. After all 3 fail:
                             { "rationale":"Non, Notepad ne semble pas installé.", "done":true, "actions":[] }

              Multi-step plans:
                For complex tasks, decompose AGGRESSIVELY into small steps with waits
                and screenshots between each major UI transition. Better 15 small
                actions than 5 ambitious ones — small steps are easier to recover from.

            GENERAL RULES:
              * BE DECISIVE. Don't return rationale-only turns with empty actions
                unless done=true. Every turn with done=false MUST contain at
                least one concrete action — otherwise you waste an iteration.
              * QUERY tasks ("donne / vérifie / liste / quelle est…") almost
                always resolve in 1-2 turns via a single shell action followed
                by a done=true answering turn. Don't open UIs to read data
                that PowerShell/cmd can give you in 200ms.
              * On the FINAL turn (done=true), if the answer comes from a shell
                stdout you just received in `history`, QUOTE the relevant part
                in your rationale. Don't just say "done", give the answer.
              * Coordinates x/y are NORMALISED in [0, 1] relative to the screenshot.
              * Always finish a UI-manipulation plan with a "screenshot" action
                so the next turn (or the technician) can verify the result.
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
    private final String groqApiKey;
    private final String groqModel;

    public AiAgentService(ObjectMapper objectMapper,
                          AiSessionRepository aiSessionRepository,
                          @Value("${groq.api.key:}") String groqApiKey,
                          @Value("${groq.model:meta-llama/llama-4-scout-17b-16e-instruct}") String groqModel) {
        this.objectMapper = objectMapper;
        this.aiSessionRepository = aiSessionRepository;
        this.groqApiKey = groqApiKey == null ? "" : groqApiKey.trim();
        this.groqModel = groqModel == null || groqModel.isBlank()
                ? "meta-llama/llama-4-scout-17b-16e-instruct"
                : groqModel.trim();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) HTTP_CONNECT_TIMEOUT.toMillis());
        factory.setReadTimeout((int) HTTP_READ_TIMEOUT.toMillis());

        this.httpClient = RestClient.builder()
                .baseUrl(GROQ_BASE_URL)
                .requestFactory(factory)
                .build();

        log.info("[ai-service] initialised — provider=Groq, model={}, httpConnect={}s, httpRead={}s, apiKey={}",
                this.groqModel,
                HTTP_CONNECT_TIMEOUT.toSeconds(),
                HTTP_READ_TIMEOUT.toSeconds(),
                this.groqApiKey.isEmpty() ? "MISSING" : "set");
    }


    public AiActionEnvelope analyse(AiFrameRequest req) {
        long t0 = System.currentTimeMillis();

        // Validation entree
        String validationError = validate(req);
        if (validationError != null) {
            return persistAndReturn(req, null, "error", validationError, t0);
        }

        if (groqApiKey.isEmpty()) {
            return persistAndReturn(req, null, "error",
                    "Groq API key not configured (groq.api.key)", t0);
        }

        String body;
        try {
            body = buildGroqPayload(req);
        } catch (Exception ex) {
            return persistAndReturn(req, null, "error",
                    "Failed to build Groq payload: " + ex.getMessage(), t0);
        }

        String rawResponseText = null;
        String lastError = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            long tHttpStart = System.currentTimeMillis();
            log.info("[ai-service] → POST Groq (attempt {}, model={}, screenshot ~{} KB)",
                    attempt, groqModel, req.screenshot().length() / 1024);
            try {
                String response = httpClient.post()
                        .uri("/chat/completions")
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + groqApiKey)
                        .body(body)
                        .retrieve()
                        .body(String.class);
                if (response == null) {
                    lastError = "Empty response from Groq";
                    break;
                }
                rawResponseText = extractText(response);
                lastError = null;
                log.info("[ai-service] ◀ Groq OK in {} ms (response {} chars, parsed text {} chars)",
                        System.currentTimeMillis() - tHttpStart,
                        response.length(),
                        rawResponseText == null ? 0 : rawResponseText.length());
                break;
            } catch (HttpStatusCodeException ex) {
                int code = ex.getStatusCode().value();
                String bodyExcerpt = truncate(ex.getResponseBodyAsString(), 400);

                if (code == 429 && attempt == 1) {
                   
                    long backoffMs = parseGroqRetryDelayMs(ex).orElse(4_000L);
                    backoffMs = Math.min(backoffMs, 8_000L); // cap a 8s
                    log.info("Groq 429 received, retrying after {}ms (attempt {})", backoffMs, attempt);
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
                log.warn("Groq HTTP {} (attempt {}): {}", code, attempt, bodyExcerpt);
                break;
            } catch (ResourceAccessException ex) {
                log.warn("Groq timeout / network error: {}", ex.getMessage());
                lastError = "Groq timeout/network: " + ex.getMessage();
                break;
            } catch (Exception ex) {
                log.warn("Unexpected error during Groq call", ex);
                lastError = "Unexpected: " + ex.getClass().getSimpleName() + " — " + ex.getMessage();
                break;
            }
        }

        if (rawResponseText == null) {
            return persistAndReturn(req, null, "error",
                    lastError == null ? "Unknown Groq error" : lastError, t0);
        }

        // Parse JSON renvoye par Groq dans message.content
        ParsedPlan plan;
        try {
            plan = parsePlan(rawResponseText);
        } catch (Exception ex) {
            log.warn("Failed to parse Groq JSON. Raw text: {}", truncate(rawResponseText, 500));
            return persistAndReturn(req, null, "error",
                    "Invalid JSON from Groq: " + ex.getMessage(), t0);
        }

        if (plan.actions.isEmpty()) {

            String actionsJson = "[]";
            return persistOkAndReturn(req, actionsJson, plan.rationale, List.of(), plan.done, t0);
        }

        // OK
        String actionsJson;
        try {
            actionsJson = objectMapper.writeValueAsString(plan.actions);
        } catch (JacksonException e) {
            actionsJson = "[]";
        }
        return persistOkAndReturn(req, actionsJson, plan.rationale, plan.actions, plan.done, t0);
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

    private String buildGroqPayload(AiFrameRequest req) {
        ObjectNode root = objectMapper.createObjectNode();

        // Modele + parametres de generation. Groq accepte les memes parametres
        // que l'API OpenAI Chat Completions.
        root.put("model", groqModel);
        root.put("temperature", 0.2);
        root.put("top_p", 0.9);
        root.put("max_tokens", 2048);

        // JSON mode : Groq supporte response_format={"type":"json_object"}
        // pour Llama 4 Scout/Maverick. Force une sortie JSON sans markdown.
        ObjectNode responseFormat = root.putObject("response_format");
        responseFormat.put("type", "json_object");

        // Historique des tours precedents (mode agentic). Au tour 0 history=null
        // ou vide → on n'ajoute rien. Sinon on serialise compact pour rester sous
        // budget tokens (chaque step ~ 200-400 chars).
        StringBuilder historyText = new StringBuilder();
        int iteration = req.iteration() == null ? 0 : req.iteration();
        if (req.history() != null && !req.history().isEmpty()) {
            historyText.append("\nPREVIOUS TURNS IN THIS LOOP:\n");
            for (var step : req.history()) {
                historyText.append("  [iter=")
                        .append(step.iteration() == null ? "?" : step.iteration())
                        .append("] rationale=\"")
                        .append(truncate(step.rationale(), 150))
                        .append("\"\n");
                if (step.actionsJson() != null && !step.actionsJson().isBlank()) {
                    historyText.append("    actions=").append(truncate(step.actionsJson(), 400)).append("\n");
                }
                if (step.resultText() != null && !step.resultText().isBlank()) {
                    historyText.append("    results=").append(truncate(step.resultText(), 300)).append("\n");
                }
            }
            historyText.append("Use the history above to avoid repeating ineffective actions.\n");
        }

        // Commande textuelle (insertion JSON-safe dans le system prompt sera
        // assuree par Jackson via .put("text", userText)).
        String userText = """
                Technician instruction (French or English):
                "%s"

                Screenshot resolution: %dx%d px
                Current loop iteration: %d (max 5 — after that the loop ends).
                %s
                Return ONLY the JSON object described in the system prompt.
                Include the "done" field explicitly. Set done=true ONLY when
                the technician's instruction is fully accomplished AND the
                visible state confirms it.
                """.formatted(
                req.command().replace("\"", "\\\""),
                req.frameWidth() == null ? 0 : req.frameWidth(),
                req.frameHeight() == null ? 0 : req.frameHeight(),
                iteration,
                historyText.toString()
        );

        // Messages OpenAI-style : system + user (multimodal text + image_url).
        ArrayNode messages = root.putArray("messages");

        // System prompt
        ObjectNode sysMsg = messages.addObject();
        sysMsg.put("role", "system");
        sysMsg.put("content", SYSTEM_PROMPT);

        // User turn : tableau de parts {type:"text"} + {type:"image_url"}
        ObjectNode userMsg = messages.addObject();
        userMsg.put("role", "user");
        ArrayNode userParts = userMsg.putArray("content");

        // 1. partie texte
        ObjectNode textPart = userParts.addObject();
        textPart.put("type", "text");
        textPart.put("text", userText);

        // 2. partie image — Groq accepte les data URLs base64 directement.
        ObjectNode imagePart = userParts.addObject();
        imagePart.put("type", "image_url");
        ObjectNode imageUrl = imagePart.putObject("image_url");
        imageUrl.put("url", "data:image/jpeg;base64," + req.screenshot());

        return objectMapper.writeValueAsString(root);
    }

    private String extractText(String responseBody) {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            // Groq renvoie {"error": {"message":"...","type":"..."}} sur refus
            // model-level (rare, surtout filtres safety).
            JsonNode error = root.path("error");
            if (!error.isMissingNode()) {
                String msg = error.path("message").asText("");
                throw new IllegalStateException("Groq refused: " + msg);
            }
            throw new IllegalStateException("No choices in Groq response: "
                    + truncate(responseBody, 300));
        }
        JsonNode message = choices.get(0).path("message");
        if (message.isMissingNode()) {
            throw new IllegalStateException("No message in Groq choice");
        }
        // finish_reason="length" indique que max_tokens a ete atteint —
        // le JSON est potentiellement tronque. On warn mais on tente le
        // parse, des fois ca passe (le JSON-mode tend a finir proprement).
        String finishReason = choices.get(0).path("finish_reason").asText("");
        if ("length".equals(finishReason)) {
            log.warn("Groq finish_reason=length, response may be truncated");
        }
        return message.path("content").asText("");
    }

    private record ParsedPlan(String rationale, List<AiAction> actions, boolean done) {}

    private ParsedPlan parsePlan(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            throw new IllegalArgumentException("Empty plan text");
        }
        String cleaned = stripMarkdownFences(rawJson);
        JsonNode root = objectMapper.readTree(cleaned);

        String rationale = root.path("rationale").asString(null);
        // `done` peut etre absent (vieux prompt, mono-shot) → false par defaut.
        boolean done = root.path("done").asBoolean(false);
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
        return new ParsedPlan(rationale, actions, done);
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
            // Cas le plus frequent : free-tier Groq = ~30 req/min sur Llama 4 Scout.
            return "Quota IA depasse (HTTP 429). Free-tier Groq = ~30 req/min sur Llama 4. "
                    + "Attends 30-60s et reessaie, ou passe sur un autre modele moins charge.";
        }
        if (code == 401 || code == 403) {
            return "Cle Groq invalide ou desactivee (HTTP " + code + "). Verifie groq.api.key dans application.properties.";
        }
        if (code == 400) {
            return "Requete IA mal formee (HTTP 400) : " + bodyExcerpt;
        }
        if (code >= 500) {
            return "Groq indisponible (HTTP " + code + "). Reessaie dans quelques secondes.";
        }
        HttpStatus status = HttpStatus.resolve(code);
        return "Groq HTTP " + code
                + (status != null ? " (" + status.getReasonPhrase() + ")" : "")
                + ": " + bodyExcerpt;
    }


    /**
     * Extrait le delai de retry Groq
     */
    private java.util.Optional<Long> parseGroqRetryDelayMs(HttpStatusCodeException ex) {
        // 1. Header retry-after
        try {
            var headers = ex.getResponseHeaders();
            if (headers != null) {
                String retryAfter = headers.getFirst("retry-after");
                if (retryAfter == null) retryAfter = headers.getFirst("Retry-After");
                if (retryAfter != null && !retryAfter.isBlank()) {
                    // Groq renvoie un nombre de secondes (peut etre decimal).
                    String num = retryAfter.replaceAll("[^0-9.]", "");
                    if (!num.isBlank()) {
                        double seconds = Double.parseDouble(num);
                        return java.util.Optional.of((long) (seconds * 1000));
                    }
                }
            }
        } catch (Exception ignored) {
            // best-effort
        }

        // 2. Body JSON — chercher pattern "try again in Xs" dans error.message
        String body = ex.getResponseBodyAsString();
        if (body == null || body.isBlank()) return java.util.Optional.empty();
        try {
            JsonNode root = objectMapper.readTree(body);
            String message = root.path("error").path("message").asText("");
            if (!message.isBlank()) {
                // Regex pour "try again in 1.234s" ou "in 30 seconds"
                var matcher = java.util.regex.Pattern
                        .compile("(?:in|after)\\s+([0-9]+(?:\\.[0-9]+)?)\\s*(s|sec|second)")
                        .matcher(message.toLowerCase());
                if (matcher.find()) {
                    double seconds = Double.parseDouble(matcher.group(1));
                    return java.util.Optional.of((long) (seconds * 1000));
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
                                                String rationale, List<AiAction> actions,
                                                boolean done, long t0) {
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
        return AiActionEnvelope.ok(req.sessionId(), req.command(), rationale, actions, done,
                req.iteration());
    }

    private static String safe(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    // Reserve pour les tests / accees direct si besoin
    public Map<String, Object> debugInfo() {
        return Map.of(
                "provider", "Groq",
                "model", groqModel,
                "apiKeyConfigured", !groqApiKey.isEmpty(),
                "httpConnectTimeoutSeconds", HTTP_CONNECT_TIMEOUT.toSeconds(),
                "httpReadTimeoutSeconds", HTTP_READ_TIMEOUT.toSeconds()
        );
    }
}
