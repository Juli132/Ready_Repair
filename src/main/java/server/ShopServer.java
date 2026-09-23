package server;

import static spark.Spark.*;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.time.Duration;
import javax.servlet.MultipartConfigElement;

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

    private static Path getDataDirectory() {
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

    private static void seedStorageIfMissing(Gson gson, Path dataDir) {
        Path repairsFile = dataDir.resolve("repairs.json");
        Path diagnosticsFile = dataDir.resolve("diagnostics.json");

        try {
            if (!Files.exists(repairsFile)) {
                Files.writeString(repairsFile, "{}");
                System.out.println("Created empty repairs.json at " + repairsFile.toAbsolutePath());
            }

            if (!Files.exists(diagnosticsFile)) {
                Files.writeString(diagnosticsFile, "{}");
                System.out.println("Created empty diagnostics.json at " + diagnosticsFile.toAbsolutePath());
            }
        } catch (IOException e) {
            System.err.println("Failed to create initial data files: " + e.getMessage());
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

        get("/api/diagnostics", (req, res) -> {
            res.type("application/json");
            Path diagFile = dataDir.resolve("diagnostics.json");
            if (!Files.exists(diagFile)) return "{}";
            return Files.readString(diagFile);
        });

        // PREVIEW: Reads headers and returns them along with any saved column config
        post("/api/preview", (req, res) -> {
            res.type("application/json");
            ApiResponse out = new ApiResponse();
            req.attribute("org.eclipse.jetty.multipartConfig", new MultipartConfigElement("/temp"));

            try (InputStream is = req.raw().getPart("file").getInputStream()) {
                Path tempFile = Files.createTempFile("preview_", ".xlsx");
                Files.copy(is, tempFile, StandardCopyOption.REPLACE_EXISTING);

                Path configFile = dataDir.resolve("header_config.json");
                JsonObject savedConfig = Files.exists(configFile)
                        ? gson.fromJson(Files.readString(configFile), JsonObject.class)
                        : new JsonObject();

                JsonObject responsePayload = new JsonObject();
                responsePayload.add("headers", gson.toJsonTree(ExcelImporter.extractHeaders(tempFile.toFile())));
                responsePayload.add("config", savedConfig);

                out.success = true;
                out.data = gson.toJson(responsePayload);
                Files.deleteIfExists(tempFile);
            } catch (Exception e) {
                out.success = false;
                out.error = e.getMessage();
            }
            return gson.toJson(out);
        });

        // IMPORT: Reads config, saves the sheet, imports. Stores a copy as last_import.xlsx.
        post("/api/import", (req, res) -> {
            res.type("application/json");
            ApiResponse out = new ApiResponse();
            req.attribute("org.eclipse.jetty.multipartConfig", new MultipartConfigElement("/temp"));

            try {
                InputStream configStream = req.raw().getPart("config").getInputStream();
                String configJsonStr = new String(
                        configStream.readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8
                );
                JsonObject mappingConfig = gson.fromJson(configJsonStr, JsonObject.class);

                Path configFile = dataDir.resolve("header_config.json");
                Files.writeString(configFile, gson.toJson(mappingConfig));

                try (InputStream fileStream = req.raw().getPart("file").getInputStream()) {
                    Path tempFile = Files.createTempFile("import_", ".xlsx");
                    Files.copy(fileStream, tempFile, StandardCopyOption.REPLACE_EXISTING);

                    // Persist the sheet so the refresh button can find it later.
                    Path storedFile = dataDir.resolve("last_import.xlsx");
                    Files.copy(tempFile, storedFile, StandardCopyOption.REPLACE_EXISTING);

                    String summary = ExcelImporter.importShopData(tempFile.toFile(), dataDir, gson, mappingConfig);
                    Files.deleteIfExists(tempFile);

                    out.success = true;
                    out.data = summary;
                }
            } catch (Exception e) {
                out.success = false;
                out.error = e.getMessage();
            }
            return gson.toJson(out);
        });

        // REIMPORT: re-runs the stored Excel file with the saved config.
        post("/api/reimport", (req, res) -> {
            res.type("application/json");
            ApiResponse out = new ApiResponse();
            try {
                Path storedFile = dataDir.resolve("last_import.xlsx");
                if (!Files.exists(storedFile)) {
                    out.success = false;
                    out.error = "No previously imported file found.";
                    return gson.toJson(out);
                }

                Path configFile = dataDir.resolve("header_config.json");
                if (!Files.exists(configFile)) {
                    out.success = false;
                    out.error = "No column config saved. Import with the picker first.";
                    return gson.toJson(out);
                }

                JsonObject mappingConfig = gson.fromJson(
                        Files.readString(configFile), JsonObject.class);

                String summary = ExcelImporter.importShopData(
                        storedFile.toFile(), dataDir, gson, mappingConfig);

                out.success = true;
                out.data = summary;
            } catch (Exception e) {
                out.success = false;
                out.error = e.getMessage();
            }
            return gson.toJson(out);
        });

        // Reports whether a stored sheet exists so the frontend can enable the refresh button.
        get("/api/has-stored-file", (req, res) -> {
            res.type("application/json");
            Path storedFile = dataDir.resolve("last_import.xlsx");
            JsonObject payload = new JsonObject();
            payload.addProperty("exists", Files.exists(storedFile));
            if (Files.exists(storedFile)) {
                payload.addProperty("modified", Files.getLastModifiedTime(storedFile).toString());
            }
            return gson.toJson(payload);
        });

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

                JsonObject systemMsg = new JsonObject();
                systemMsg.addProperty("role", "system");
                systemMsg.addProperty("content", SYSTEM_PROMPT);
                messagesArray.add(systemMsg);

                if (input.chat_history != null) {
                    for (JsonElement el : input.chat_history) {
                        messagesArray.add(el);
                    }
                }

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

        post("/api/save-config", (req, res) -> {
            res.type("application/json");
            ApiResponse out = new ApiResponse();
            try {
                JsonObject incoming = gson.fromJson(req.body(), JsonObject.class);
                Path configFile = dataDir.resolve("header_config.json");
                Files.writeString(configFile, gson.toJson(incoming));
                out.success = true;
                out.data = "Config saved.";
            } catch (Exception e) {
                out.success = false;
                out.error = e.getMessage();
            }
            return gson.toJson(out);
        });

        post("/api/clear-all", (req, res) -> {
            res.type("application/json");
            ApiResponse out = new ApiResponse();
            try {
                Files.writeString(dataDir.resolve("repairs.json"), "{}");
                Files.writeString(dataDir.resolve("diagnostics.json"), "{}");
                out.success = true;
                out.data = "All jobs cleared.";
            } catch (Exception e) {
                out.success = false;
                out.error = e.getMessage();
            }
            return gson.toJson(out);
        });

        post("/api/delete-job", (req, res) -> {
            res.type("application/json");
            ApiResponse out = new ApiResponse();
            try {
                JsonObject incoming = gson.fromJson(req.body(), JsonObject.class);
                String targetId = incoming.get("id").getAsString();

                Path diagFile = dataDir.resolve("diagnostics.json");
                Path repairsFile = dataDir.resolve("repairs.json");

                JsonObject diagData = Files.exists(diagFile)
                        ? gson.fromJson(Files.readString(diagFile), JsonObject.class)
                        : new JsonObject();
                JsonObject repairsData = Files.exists(repairsFile)
                        ? gson.fromJson(Files.readString(repairsFile), JsonObject.class)
                        : new JsonObject();

                diagData.remove(targetId);
                repairsData.remove(targetId);

                Files.writeString(diagFile, gson.toJson(diagData));
                Files.writeString(repairsFile, gson.toJson(repairsData));

                out.success = true;
                out.data = "Job " + targetId + " deleted.";
            } catch (Exception e) {
                out.success = false;
                out.error = e.getMessage();
            }
            return gson.toJson(out);
        });

        get("/api/repairs", (req, res) -> {
            res.type("application/json");
            Path repairsFile = dataDir.resolve("repairs.json");
            if (!Files.exists(repairsFile)) return "{}";
            return Files.readString(repairsFile);
        });

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