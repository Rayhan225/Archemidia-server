package com.archemidia.model.minigames;

import java.util.Arrays;
import java.util.Random;

public class CodeBreakerGame {
    private final int[] secretCode; // Encapsulated! No getter.
    public boolean gameOver = false;
    public String message = "Guess the 4-color code (0-5)!";
    public int turnsLeft = 10;

    public CodeBreakerGame() {
        this.secretCode = new Random().ints(4, 0, 6).toArray();
    }

    public Result checkGuess(int[] guess) {
        if (guess.length != 4) return new Result(0, 0, "Invalid Length");

        int exact = 0;
        int partial = 0;
        boolean[] codeUsed = new boolean[4];
        boolean[] guessUsed = new boolean[4];

        // 1. Check Exact Matches
        for (int i = 0; i < 4; i++) {
            if (guess[i] == secretCode[i]) {
                exact++;
                codeUsed[i] = true;
                guessUsed[i] = true;
            }
        }

        // 2. Check Partial Matches
        for (int i = 0; i < 4; i++) {
            if (!guessUsed[i]) {
                for (int j = 0; j < 4; j++) {
                    if (!codeUsed[j] && guess[i] == secretCode[j]) {
                        partial++;
                        codeUsed[j] = true;
                        break;
                    }
                }
            }
        }

        turnsLeft--;
        if (exact == 4) {
            gameOver = true;
            message = "VICTORY! Code broken.";
        } else if (turnsLeft <= 0) {
            gameOver = true;
            message = "DEFEAT! Code was: " + Arrays.toString(secretCode);
        } else {
            message = "Exact: " + exact + ", Partial: " + partial;
        }

        return new Result(exact, partial, message);
    }

    // DTO for response
    public static class Result {
        public int exact;
        public int partial;
        public String msg;
        public Result(int e, int p, String m) { exact = e; partial = p; msg = m; }
    }
}