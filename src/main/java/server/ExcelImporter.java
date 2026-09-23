package server;

import org.apache.poi.ss.usermodel.*;
import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

public class ExcelImporter {

    public static List<String> extractHeaders(File excelFile) throws Exception {
        List<String> headers = new ArrayList<>();
        try (FileInputStream fis = new FileInputStream(excelFile);
             Workbook workbook = WorkbookFactory.create(fis)) {

            Sheet sheet = workbook.getSheetAt(0);
            Row headerRow = sheet.getRow(0);
            if (headerRow == null) throw new Exception("Excel sheet is empty.");

            for (Cell cell : headerRow) {
                String val = new DataFormatter().formatCellValue(cell).trim();
                if (!val.isEmpty()) headers.add(val);
            }
        }
        return headers;
    }

    public static String importShopData(File excelFile, Path dataDir, Gson gson,
                                        JsonObject mappingConfig) throws Exception {
        Path repairsFile = dataDir.resolve("repairs.json");
        Path diagnosticsFile = dataDir.resolve("diagnostics.json");

        JsonObject repairsDb = Files.exists(repairsFile)
                ? gson.fromJson(Files.readString(repairsFile), JsonObject.class)
                : new JsonObject();
        JsonObject diagDb = Files.exists(diagnosticsFile)
                ? gson.fromJson(Files.readString(diagnosticsFile), JsonObject.class)
                : new JsonObject();

        // Reverse map: display_id -> internal JL-x key.
        Map<String, String> displayIdToKey = new HashMap<>();
        for (String key : repairsDb.keySet()) {
            JsonObject rec = repairsDb.getAsJsonObject(key);
            if (rec.has("display_id")) {
                displayIdToKey.put(rec.get("display_id").getAsString(), key);
            }
        }

        int added = 0;
        int updated = 0;

        try (FileInputStream fis = new FileInputStream(excelFile);
             Workbook workbook = WorkbookFactory.create(fis)) {

            Sheet sheet = workbook.getSheetAt(0);
            Row headerRow = sheet.getRow(0);
            if (headerRow == null) throw new Exception("Excel sheet is empty.");

            Map<String, Integer> headerMap = new HashMap<>();
            List<String> headerOrder = new ArrayList<>();
            for (Cell cell : headerRow) {
                String name = new DataFormatter().formatCellValue(cell).trim();
                if (!name.isEmpty() && !headerMap.containsKey(name)) {
                    headerMap.put(name, cell.getColumnIndex());
                    headerOrder.add(name);
                }
            }

            String firstColumn = headerOrder.isEmpty() ? null : headerOrder.get(0);

            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;

                StringBuilder symptomsData = new StringBuilder();
                StringBuilder hardwareData = new StringBuilder();
                List<String> idParts = new ArrayList<>();
                String fallbackId = "";

                for (String header : headerOrder) {
                    String value = getCellValue(row, headerMap, header);
                    if (value.isEmpty()) continue;

                    if (header.equals(firstColumn) && fallbackId.isEmpty()) {
                        fallbackId = value;
                    }

                    String role = mappingConfig.has(header)
                            ? mappingConfig.get(header).getAsString()
                            : "private";

                    switch (role.toLowerCase()) {
                        case "id":
                            idParts.add(value);
                            break;
                        case "symptoms":
                            if (symptomsData.length() > 0) symptomsData.append(" | ");
                            symptomsData.append(header).append(": ").append(value);
                            break;
                        case "hardware":
                            if (hardwareData.length() > 0) hardwareData.append(" | ");
                            hardwareData.append(header).append(": ").append(value);
                            break;
                        default:
                            break;
                    }
                }

                if (symptomsData.length() == 0 && hardwareData.length() == 0 && idParts.isEmpty()) continue;

                String displayId = idParts.isEmpty()
                        ? fallbackId
                        : String.join(" \u00b7 ", idParts);

                String diagnosticRef;
                boolean isNew = true;
                if (!displayId.isEmpty() && displayIdToKey.containsKey(displayId)) {
                    diagnosticRef = displayIdToKey.get(displayId);
                    isNew = false;
                } else {
                    diagnosticRef = nextId(diagDb);
                    displayIdToKey.put(displayId, diagnosticRef);
                }

                JsonObject repairRecord = new JsonObject();
                repairRecord.addProperty("diagnostic_ref", diagnosticRef);
                repairRecord.addProperty("display_id",
                        displayId.isEmpty() ? diagnosticRef : displayId);
                repairsDb.add(diagnosticRef, repairRecord);

                // Preserve notes and chat history from the existing record, if any.
                JsonObject existing = diagDb.has(diagnosticRef)
                        ? diagDb.getAsJsonObject(diagnosticRef)
                        : new JsonObject();
                String existingNotes = existing.has("notes_so_far")
                        ? existing.get("notes_so_far").getAsString()
                        : "";

                JsonObject diagnosticRecord = new JsonObject();
                diagnosticRecord.addProperty("hardware", hardwareData.toString());
                diagnosticRecord.addProperty("symptoms", symptomsData.toString());
                diagnosticRecord.addProperty("notes_so_far", existingNotes);

                if (existing.has("chat_history")) {
                    diagnosticRecord.add("chat_history", existing.get("chat_history"));
                }

                diagDb.add(diagnosticRef, diagnosticRecord);

                if (isNew) added++; else updated++;
            }
        }

        Files.writeString(repairsFile, gson.toJson(repairsDb));
        Files.writeString(diagnosticsFile, gson.toJson(diagDb));

        return added + " added, " + updated + " updated";
    }

    private static String nextId(JsonObject existing) {
        int n = existing.size() + 1;
        String candidate = "JL-" + n;
        while (existing.has(candidate)) {
            n++;
            candidate = "JL-" + n;
        }
        return candidate;
    }

    private static String getCellValue(Row row, Map<String, Integer> headerMap, String header) {
        if (header == null || header.isEmpty()) return "";
        Integer colIdx = headerMap.get(header);
        if (colIdx == null) return "";
        Cell cell = row.getCell(colIdx);
        if (cell == null) return "";
        return new DataFormatter().formatCellValue(cell).trim();
    }
}