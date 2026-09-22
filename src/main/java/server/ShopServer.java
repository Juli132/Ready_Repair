package server;

import static spark.Spark.*;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.time.Duration;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

public class ShopServer {

    private static final int DEFAULT_PORT = 4568;
    private static final String GROQ_API_URL = "https://api.groq.com/openai/v1/chat/completions";

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
            return p;
        }

        // 2. Portable mode: a 'data' folder sitting next to the running JAR
        try {
            Path jarDir = Paths.get(
                    ShopServer.class.getProtectionDomain()
                            .getCodeSource().getLocation().toURI()
            ).getParent();

            if (jarDir != null) {
                Path portable = jarDir.resolve("data");
                if (Files.isDirectory(portable)) {
                    return portable;
                }
            }
        } catch (Exception e) {
            // Running from IDE / unusual classloader; fall through to default.
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

    // Reads directly from the obscure file, falling back to a dummy key if missing
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

        // Query Groq API with sanitized payload
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

                String prompt = String.format(
                    "You are an expert industrial PC repair technician. Provide a concise, step-by-step diagnostic checklist.\n" +
                    "Hardware: %s\nSymptoms: %s\nTechnician Notes so far: %s",
                    input.hardware, input.symptoms, input.notes == null ? "None" : input.notes
                );

                JsonObject message = new JsonObject();
                message.addProperty("role", "user");
                message.addProperty("content", prompt);

                com.google.gson.JsonArray messagesArray = new com.google.gson.JsonArray();
                messagesArray.add(message);

                JsonObject payload = new JsonObject();
                payload.addProperty("model", "openai/gpt-oss-120b");
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

                    // Save manual notes
                    if (incoming.has("notes")) {
                        targetPc.addProperty("notes_so_far", incoming.get("notes").getAsString());
                    }
                    // Save the AI conversation history array
                    if (incoming.has("chat_history")) {
                        targetPc.add("chat_history", incoming.get("chat_history").getAsJsonArray());
                    }

                    // Back up the current file before we touch it. Cheap insurance
                    // for FAT32/exFAT sticks where ATOMIC_MOVE may not be honored.
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