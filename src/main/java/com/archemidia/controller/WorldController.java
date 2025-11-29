package com.archemidia.controller;
import com.archemidia.service.WorldGeneratorService;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
public class WorldController {
    private final WorldGeneratorService worldGen;
    public WorldController(WorldGeneratorService wg) { this.worldGen = wg; }

    @GetMapping("/api/map/chunk")
    public List<List<Integer>> getChunk(@RequestParam int x, @RequestParam int y, @RequestParam(defaultValue = "16") int size) {
        return worldGen.generateChunk(x, y, size, size);
    }
}