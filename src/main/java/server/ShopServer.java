package server;

import static spark.Spark.*;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.time.Duration;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public class ShopServer {

    private static final int DEFAULT_PORT = 4568;
    private static final String GROQ_API_URL = "https://api.groq.com/openai/v1/chat/completions";
    private static final String MODEL = "openai/gpt-oss-120b";

    private static final String SYSTEM_PROMPT =
    "You are an experienced industrial PC repair technician helping a colleague on the bench.\n\n" +
    "Respond to ONLY the technician's latest note. Do not re-summarize or re-diagnose earlier problems.\n\n" +
    "Match the response format to the note's intent:\n" +
    "- If the note is a STATUS UPDATE (symptom changed, observation added), give focused next steps for just that change.\n" +
    "- If the note is a QUESTION (contains '?', 'should I', 'is it worth', 'can I'), answer it directly in 1-3 sentences. Do NOT give a checklist unless specifically asked.\n" +
    "- If the note says the problem is FIXED, give 2-3 short verification or monitoring points. Do NOT give a checklist.\n" +
    "- If the note is a NEW SYMPTOM, give a focused diagnostic list of no more than 5 steps.\n\n" +
    "Hard rules:\n" +
    "- Never include a step the technician has already done or mentioned. Check the previous notes carefully.\n" +
    "- Never include more than 5 numbered steps. Use fewer if the situation doesn't warrant 5.\n" +
    "- Never start with 'Power down, unplug' unless the technician explicitly needs to open the case.\n" +
    "- No preamble, no 'here is a checklist', no closing summary.";
    /**
     * Resolves the data directory using a precedence chain:
     *   1. -Dshop.data=/path/to/dir  (explicit override, mostly for testing)
     *   2. a 'data' folder next to the running JAR (portable / USB mode)
     *   3. the OS-appropriate per-user application data directory (default)
     */
    private static Path getDataDirectory() {
        // 1. Explicit override
        String override = System.getProperty("shop.data");
        if (override != null && !override.isBlank()) {
            Path p = Paths.get(override);
            try {
                Files.createDirectories(p);
            } catch (IOException e) {
                System.out.println("WARNING: Could not create override data dir " + p + ": " + e.getMessage());
            }
            System.out.println("Using override data directory: " + p.toAbsolutePath());
            return p;
        }

        // 2. Portable mode: a 'data' folder sitting next to the running JAR
        Path jarDir = getJarDirectory();
        if (jarDir != null) {
            Path portable = jarDir.resolve("data");
            if (Files.isDirectory(portable)) {
                System.out.println("Portable mode: found data folder at " + portable);
                return portable;
            } else {
                System.out.println("Checked for portable data folder at " + portable + " - not found");
            }
        } else {
            System.out.println("Could not determine JAR directory; skipping portable mode check");
        }

        // 3. Default: per-user application data directory
        String userHome = System.getProperty("user.home");
        String os = System.getProperty("os.name", "").toLowerCase();
        Path dataDir;

        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            dataDir = Paths.get(appData != null ? appData : userHome, "ShopDiagnostics");
        } else if (os.contains("mac")) {
            dataDir = Paths.get(userHome, "Library", "Application Support", "ShopDiagnostics");
        } else {
            dataDir = Paths.get(userHome, ".local", "share", "shopdiagnostics");
        }

        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            System.out.println("WARNING: Failed to create data directory " + dataDir);
        }
        return dataDir;
    }

    /**
     * Determines the directory containing the running JAR. Uses java.class.path,
     * which the JVM sets reliably when launched via 'java -jar'. Returns null
     * if we can't determine it (e.g. running from an IDE).
     */
    private static Path getJarDirectory() {
        String classpath = System.getProperty("java.class.path", "");
        if (classpath.isEmpty()) return null;

        for (String entry : classpath.split(File.pathSeparator)) {
            if (entry.toLowerCase().endsWith(".jar")) {
                Path p = Paths.get(entry).toAbsolutePath();
                if (Files.isRegularFile(p)) {
                    return p.getParent();
                }
            }
        }
        return null;
    }

    // Reads the API key from a file literally named A_oi_t in the working directory
    private static String resolveApiKey() {
        Path keyFile = Paths.get("A_oi_t");

        if (Files.exists(keyFile)) {
            try {
                String key = Files.readString(keyFile).trim();
                if (!key.isEmpty()) {
                    return key;
                }
            } catch (IOException e) {
                System.out.println("WARNING: Could not read " + keyFile);
            }
        }

        System.out.println("NOTE: No A_oi_t file found. Using dummy key for testing.");
        return "dummy_test_key";
    }

    // Seeds default JSON files on launch if missing
    private static void seedStorageIfMissing(Gson gson, Path dataDir) {
        Path repairsFile = dataDir.resolve("repairs.json");
        Path diagnosticsFile = dataDir.resolve("diagnostics.json");

        try {
            if (!Files.exists(repairsFile)) {
                JsonObject repairsSeed = new JsonObject();

                JsonObject jl1 = new JsonObject();
                jl1.addProperty("rma_number", "RMA-84213");
                jl1.addProperty("customer", "Apex Controls");
                jl1.addProperty("asset_tag", "AC-9901");
                jl1.addProperty("received_date", "2026-09-21");
                jl1.addProperty("diagnostic_ref", "JL-1");
                repairsSeed.add("JL-1", jl1);

                JsonObject jl2 = new JsonObject();
                jl2.addProperty("rma_number", "RMA-84214");
                jl2.addProperty("customer", "Vanguard Logistics");
                jl2.addProperty("asset_tag", "VL-4412");
                jl2.addProperty("received_date", "2026-09-21");
                jl2.addProperty("diagnostic_ref", "JL-2");
                repairsSeed.add("JL-2", jl2);

                Files.writeString(repairsFile, gson.toJson(repairsSeed));
                System.out.println("Created default repairs.json at " + repairsFile.toAbsolutePath());
            }

            if (!Files.exists(diagnosticsFile)) {
                JsonObject diagSeed = new JsonObject();

                JsonObject jl1 = new JsonObject();
                jl1.addProperty("symptoms", "PC won't POST, fans spin, no display output");
                jl1.addProperty("hardware", "Dell OptiPlex 7090, i5-11500, 16GB RAM");
                jl1.addProperty("notes_so_far", "Reseated RAM, no change");
                diagSeed.add("JL-1", jl1);

                JsonObject jl2 = new JsonObject();
                jl2.addProperty("symptoms", "Blue screen on boot: INACCESSIBLE_BOOT_DEVICE");
                jl2.addProperty("hardware", "Lenovo ThinkStation P340, NVMe SSD");
                jl2.addProperty("notes_so_far", "");
                diagSeed.add("JL-2", jl2);

                Files.writeString(diagnosticsFile, gson.toJson(diagSeed));
                System.out.println("Created default diagnostics.json at " + diagnosticsFile.toAbsolutePath());
            }
        } catch (IOException e) {
            System.err.println("Failed to seed initial data files: " + e.getMessage());
        }
    }

    static class GroqRequest {
        public String id;
        public String hardware;
        public String symptoms;
        public String notes;
        public JsonArray chat_history;
    }

    static class ApiResponse {
        public boolean success;
        public String data;
        public String error;
    }

    public static void main(String[] args) {
        Path dataDir = getDataDirectory();
        System.out.println("Data directory: " + dataDir.toAbsolutePath());

        String groqKey = resolveApiKey();

        port(DEFAULT_PORT);
        staticFiles.externalLocation("webpage");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            stop();
            awaitStop();
        }));

        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        seedStorageIfMissing(gson, dataDir);

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        System.out.println("Shop Diagnostics Server running on http://localhost:" + DEFAULT_PORT);

        // Serve decoupled diagnostics.json to the frontend
        get("/api/diagnostics", (req, res) -> {
            res.type("application/json");
            Path diagFile = dataDir.resolve("diagnostics.json");
            if (!Files.exists(diagFile)) return "{}";
            return Files.readString(diagFile);
        });

        // Query Groq API with sanitized payload and multi-turn conversation history
        post("/api/groq", (req, res) -> {
            res.type("application/json");
            ApiResponse out = new ApiResponse();

            if (groqKey.equals("dummy_test_key")) {
                out.success = false;
                out.error = "Testing mode: No valid Groq API key configured in A_oi_t.";
                return gson.toJson(out);
            }

            try {
                GroqRequest input = gson.fromJson(req.body(), GroqRequest.class);

                JsonArray messagesArray = new JsonArray();

                // System message: sets persona and rules once per conversation
                JsonObject systemMsg = new JsonObject();
                systemMsg.addProperty("role", "system");
                systemMsg.addProperty("content", SYSTEM_PROMPT);
                messagesArray.add(systemMsg);

                // Replay prior turns so the model has context
                if (input.chat_history != null) {
                    for (JsonElement el : input.chat_history) {
                        messagesArray.add(el);
                    }
                }

                // Current turn: the technician's latest note, framed as the actual question
                JsonObject userMsg = new JsonObject();
                userMsg.addProperty("role", "user");
                userMsg.addProperty("content", String.format(
                    "Machine: %s\nOriginal symptoms: %s\nTechnician's latest note: %s\n\n" +
                    "Respond only to the latest note. Do not repeat steps already tried.",
                    input.hardware,
                    input.symptoms,
                    (input.notes == null || input.notes.isBlank()) ? "(none yet)" : input.notes
                ));
                messagesArray.add(userMsg);

                JsonObject payload = new JsonObject();
                payload.addProperty("model", MODEL);
                payload.addProperty("max_tokens", 800);
                payload.add("messages", messagesArray);

                HttpRequest groqReq = HttpRequest.newBuilder()
                        .uri(URI.create(GROQ_API_URL))
                        .header("Authorization", "Bearer " + groqKey)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload)))
                        .build();

                HttpResponse<String> groqRes = httpClient.send(groqReq, HttpResponse.BodyHandlers.ofString());

                if (groqRes.statusCode() == 200) {
                    JsonObject jsonResponse = gson.fromJson(groqRes.body(), JsonObject.class);
                    String generatedText = jsonResponse.getAsJsonArray("choices")
                            .get(0).getAsJsonObject()
                            .getAsJsonObject("message")
                            .get("content").getAsString();

                    out.success = true;
                    out.data = generatedText;
                } else {
                    out.success = false;
                    out.error = "Groq API Error: " + groqRes.statusCode() + " - " + groqRes.body();
                }
            } catch (Exception e) {
                out.success = false;
                out.error = e.getMessage();
            }

            return gson.toJson(out);
        });

        // Save technician notes AND chat history atomically back to diagnostics.json
        post("/api/notes", (req, res) -> {
            res.type("application/json");
            ApiResponse out = new ApiResponse();

            try {
                JsonObject incoming = gson.fromJson(req.body(), JsonObject.class);
                String targetId = incoming.get("id").getAsString();

                Path diagFile = dataDir.resolve("diagnostics.json");
                JsonObject diagData = Files.exists(diagFile)
                        ? gson.fromJson(Files.readString(diagFile), JsonObject.class)
                        : new JsonObject();

                if (diagData.has(targetId)) {
                    JsonObject targetPc = diagData.getAsJsonObject(targetId);

                    if (incoming.has("notes")) {
                        targetPc.addProperty("notes_so_far", incoming.get("notes").getAsString());
                    }
                    if (incoming.has("chat_history")) {
                        targetPc.add("chat_history", incoming.get("chat_history").getAsJsonArray());
                    }

                    // Back up the current file before we touch it.
                    if (Files.exists(diagFile)) {
                        Path backupFile = diagFile.resolveSibling("diagnostics.bak");
                        try {
                            Files.copy(diagFile, backupFile, StandardCopyOption.REPLACE_EXISTING);
                        } catch (IOException e) {
                            System.out.println("WARNING: Could not write backup: " + e.getMessage());
                        }
                    }

                    Path tempFile = diagFile.resolveSibling("diagnostics.tmp");
                    Files.writeString(tempFile, gson.toJson(diagData));

                    try {
                        Files.move(tempFile, diagFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    } catch (AtomicMoveNotSupportedException e) {
                        Files.move(tempFile, diagFile, StandardCopyOption.REPLACE_EXISTING);
                    }

                    out.success = true;
                    out.data = "Notes and history successfully saved.";
                } else {
                    out.success = false;
                    out.error = "Unit ID " + targetId + " not found.";
                }
            } catch (Exception e) {
                out.success = false;
                out.error = e.getMessage();
            }

            return gson.toJson(out);
        });
    }
}