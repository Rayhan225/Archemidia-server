package com.archemidia.service;

import com.archemidia.model.PlayerState;
import com.archemidia.model.WorldObject;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class PersistenceService {

    private final ObjectMapper mapper = new ObjectMapper();
    private final String DATA_FILE = "world_data.json";

    public void saveData(Map<String, WorldObject> objects, Map<String, PlayerState> players) {
        try {
            SaveData data = new SaveData();
            data.objects = objects;
            data.players = players;
            mapper.writeValue(new File(DATA_FILE), data);
            System.out.println(" [Persistence] World saved (" + objects.size() + " objects, " + players.size() + " players).");
        } catch (IOException e) {
            System.err.println(" [Persistence] Failed to save world: " + e.getMessage());
        }
    }

    public SaveData loadData() {
        File f = new File(DATA_FILE);
        if (!f.exists()) return null;
        try {
            return mapper.readValue(f, new TypeReference<SaveData>() {});
        } catch (IOException e) {
            System.err.println(" [Persistence] Failed to load world: " + e.getMessage());
            return null;
        }
    }

    public static class SaveData {
        public Map<String, WorldObject> objects = new ConcurrentHashMap<>();
        public Map<String, PlayerState> players = new ConcurrentHashMap<>();
    }
}