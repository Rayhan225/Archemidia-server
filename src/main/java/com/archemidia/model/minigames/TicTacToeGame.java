package com.archemidia.model.minigames;

public class TicTacToeGame {
    // 0 = Empty, 1 = Player (X), 2 = AI (O)
    private int[] board = new int[9];
    public boolean gameOver = false;
    public String winner = null; // "Player", "AI", or "Draw"

    public int[] getBoard() { return board; }

    public boolean playerMove(int index) {
        if (gameOver || index < 0 || index >= 9 || board[index] != 0) return false;

        board[index] = 1; // Player is 1
        checkGameState();

        if (!gameOver) {
            makeAIMove();
        }
        return true;
    }

    private void makeAIMove() {
        int bestScore = Integer.MIN_VALUE;
        int move = -1;

        for (int i = 0; i < 9; i++) {
            if (board[i] == 0) {
                board[i] = 2; // AI Try
                int score = minimax(board, 0, false);
                board[i] = 0; // Undo
                if (score > bestScore) {
                    bestScore = score;
                    move = i;
                }
            }
        }

        if (move != -1) {
            board[move] = 2;
            checkGameState();
        }
    }

    // The Brain (Minimax Algorithm)
    private int minimax(int[] b, int depth, boolean isMaximizing) {
        int result = checkWinRaw(b);
        if (result != -1) return result; // 10 if AI wins, -10 if Player wins, 0 if Draw

        if (isMaximizing) {
            int bestScore = Integer.MIN_VALUE;
            for (int i = 0; i < 9; i++) {
                if (b[i] == 0) {
                    b[i] = 2;
                    int score = minimax(b, depth + 1, false);
                    b[i] = 0;
                    bestScore = Math.max(score, bestScore);
                }
            }
            return bestScore;
        } else {
            int bestScore = Integer.MAX_VALUE;
            for (int i = 0; i < 9; i++) {
                if (b[i] == 0) {
                    b[i] = 1;
                    int score = minimax(b, depth + 1, true);
                    b[i] = 0;
                    bestScore = Math.min(score, bestScore);
                }
            }
            return bestScore;
        }
    }

    private int checkWinRaw(int[] b) {
        int[][] wins = {{0,1,2}, {3,4,5}, {6,7,8}, {0,3,6}, {1,4,7}, {2,5,8}, {0,4,8}, {2,4,6}};
        for (int[] w : wins) {
            if (b[w[0]] != 0 && b[w[0]] == b[w[1]] && b[w[1]] == b[w[2]]) {
                if (b[w[0]] == 2) return 10; // AI Win
                else return -10; // Player Win
            }
        }
        boolean full = true;
        for (int i : b) if (i == 0) full = false;
        if (full) return 0; // Draw
        return -1; // Keep playing
    }

    private void checkGameState() {
        int res = checkWinRaw(board);
        if (res == 10) { gameOver = true; winner = "AI"; }
        else if (res == -10) { gameOver = true; winner = "Player"; }
        else if (res == 0) { gameOver = true; winner = "Draw"; }
    }

    // In TicTacToeGame.java
    public void reset() {
        this.board = new int[9]; // Clear the array
        this.gameOver = false;   // Reset flag
        this.winner = null;      // Reset winner
    }
}