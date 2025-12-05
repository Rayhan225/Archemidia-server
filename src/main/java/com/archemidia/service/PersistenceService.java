package com.archemidia.service;

import com.archemidia.model.Monster;
import com.archemidia.model.PlayerState;
import com.archemidia.model.WorldObject;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class PersistenceService {

    private final ObjectMapper mapper = new ObjectMapper();
    private final String DATA_FILE = "world_data.json";
    private final String TEMP_FILE = "world_data.tmp";

    public PersistenceService() {
        // Prevent crashing if save file schema changes slightly
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public void saveData(Map<String, WorldObject> objects, Map<String, PlayerState> players, Map<String, Monster> monsters) {
        try {
            SaveData data = new SaveData();
            data.objects = objects;
            data.players = players;
            data.monsters = monsters;

            // 1. Write to a temporary file first (Atomic Write pattern)
            File tempFile = new File(TEMP_FILE);
            mapper.writeValue(tempFile, data);

            // 2. If write succeeded, replace the real file
            File realFile = new File(DATA_FILE);
            Files.move(tempFile.toPath(), realFile.toPath(), StandardCopyOption.REPLACE_EXISTING);

            System.out.println(" [Persistence] World saved safely (" + objects.size() + " objects, " + players.size() + " players).");
        } catch (IOException e) {
            System.err.println(" [Persistence] Failed to save world: " + e.getMessage());
        }
    }

    public SaveData loadData() {
        File f = new File(DATA_FILE);
        if (!f.exists() || f.length() == 0) return null;

        try {
            return mapper.readValue(f, new TypeReference<SaveData>() {});
        } catch (IOException e) {
            System.err.println(" [Persistence] Save file is corrupted: " + e.getMessage());

            // Auto-fix: Rename corrupted file so the server can start fresh
            File backup = new File(DATA_FILE + ".corrupted_" + System.currentTimeMillis());
            if (f.renameTo(backup)) {
                System.out.println(" [Persistence] Renamed corrupted file to: " + backup.getName());
                System.out.println(" [Persistence] Generating new world...");
            }
            return null; // Return null triggers fresh world gen in GameService
        }
    }

    public static class SaveData {
        public Map<String, WorldObject> objects = new ConcurrentHashMap<>();
        public Map<String, PlayerState> players = new ConcurrentHashMap<>();
        public Map<String, Monster> monsters = new ConcurrentHashMap<>();
    }
}