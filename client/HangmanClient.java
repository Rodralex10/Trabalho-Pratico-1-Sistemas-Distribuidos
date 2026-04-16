package client;

import java.io.*;
import java.net.*;
import java.util.*;

public class HangmanClient {

    private static final String DEFAULT_HOST = "localhost";
    private static final int    DEFAULT_PORT = 12345;
    private static final int    MAX_ATTEMPTS = 6;

    // Hangman ASCII-art stages (index = number of wrong guesses, 0..6)
    private static final String[][] HANGMAN = {
        {"  +---+", "  |   |", "      |", "      |", "      |", "      |", "========="},
        {"  +---+", "  |   |", "  O   |", "      |", "      |", "      |", "========="},
        {"  +---+", "  |   |", "  O   |", "  |   |", "      |", "      |", "========="},
        {"  +---+", "  |   |", "  O   |", " /|   |", "      |", "      |", "========="},
        {"  +---+", "  |   |", "  O   |", " /|\\  |", "      |", "      |", "========="},
        {"  +---+", "  |   |", "  O   |", " /|\\  |", " /    |", "      |", "========="},
        {"  +---+", "  |   |", "  O   |", " /|\\  |", " / \\  |", "      |", "========="},
    };

    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;

    private volatile boolean gameOver      = false;
    private volatile boolean awaitingInput = false;
    private int playerId;

    // ── Main ─────────────────────────────────────────────────────────────
    public static void main(String[] args) {
        String host = DEFAULT_HOST;
        int    port = DEFAULT_PORT;
        if (args.length >= 1) host = args[0];
        if (args.length >= 2) {
            try { port = Integer.parseInt(args[1]); }
            catch (NumberFormatException e) {
                System.err.println("Invalid port. Using default: " + DEFAULT_PORT);
            }
        }

        HangmanClient client = new HangmanClient();
        try {
            System.out.println("Connecting to " + host + ":" + port + "...");
            client.connect(host, port);
            client.run();
        } catch (ConnectException e) {
            System.err.println("Could not connect — is the server running?");
        } catch (IOException e) {
            System.err.println("Connection error: " + e.getMessage());
        }
    }

    // ── Connection ───────────────────────────────────────────────────────
    private void connect(String host, int port) throws IOException {
        socket = new Socket(host, port);
        in     = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        out    = new PrintWriter(socket.getOutputStream(), true);
    }

    // ── Run ──────────────────────────────────────────────────────────────
    private void run() {
        // Reader thread — handles all server messages
        Thread reader = new Thread(() -> {
            try {
                String line;
                while ((line = in.readLine()) != null) {
                    handleMessage(line);
                    if (gameOver) break;
                }
            } catch (IOException e) {
                if (!gameOver) System.out.println("\nConnection lost.");
            }
            if (!gameOver) {
                gameOver = true;
            }
            // Give time for final output, then exit
            try { Thread.sleep(200); } catch (InterruptedException ignored) {}
            System.exit(0);
        }, "ReaderThread");
        reader.setDaemon(true);
        reader.start();

        // Input loop on main thread
        Scanner scanner = new Scanner(System.in);
        while (!gameOver) {
            try {
                String input = scanner.nextLine().trim();
                if (awaitingInput && !input.isEmpty()) {
                    out.println("GUESS " + input);
                    awaitingInput = false;
                    System.out.println("Guess sent! Waiting for other players...\n");
                } else if (!awaitingInput && !input.isEmpty()) {
                    System.out.println("(wait for your turn)");
                }
            } catch (NoSuchElementException e) {
                break; // stdin closed
            }
        }
    }

    // ── Message dispatcher ───────────────────────────────────────────────
    private void handleMessage(String msg) {
        if      (msg.startsWith("WELCOME ")) handleWelcome(msg);
        else if (msg.startsWith("START "))   handleStart(msg);
        else if (msg.startsWith("ROUND "))   handleRound(msg);
        else if (msg.startsWith("STATE "))   handleState(msg);
        else if (msg.startsWith("END "))     handleEnd(msg);
        else if (msg.equals("FULL"))         handleFull();
    }

    // ── Handlers ─────────────────────────────────────────────────────────
    private void handleWelcome(String msg) {
        String[] parts = msg.split(" ");
        playerId = Integer.parseInt(parts[1]);
        int total = Integer.parseInt(parts[2]);
        System.out.println();
        System.out.println("╔══════════════════════════════╗");
        System.out.println("║     HANGMAN MULTIPLAYER      ║");
        System.out.println("╚══════════════════════════════╝");
        System.out.println("  You are Player " + playerId);
        System.out.println("  Players in lobby: " + total);
        System.out.println("  Waiting for game to start...");
        System.out.println();
    }

    private void handleStart(String msg) {
        String[] parts = msg.split(" ");
        String mask    = parts[1];
        int attempts   = Integer.parseInt(parts[2]);
        long timeout   = Long.parseLong(parts[3]);
        System.out.println("*** GAME STARTED! ***");
        System.out.println("  Word length : " + mask.length() + " letters");
        System.out.println("  Attempts    : " + attempts);
        System.out.println("  Round timeout: " + (timeout / 1000) + "s");
        System.out.println();
    }

    private void handleRound(String msg) {
        String[] parts = msg.split(" ");
        int round      = Integer.parseInt(parts[1]);
        String mask    = parts[2];
        int attempts   = Integer.parseInt(parts[3]);
        String used    = parts[4];

        System.out.println("────── Round " + round + " ──────");
        printHangman(MAX_ATTEMPTS - attempts);
        printMask(mask);
        System.out.println("  Attempts left : " + attempts);
        System.out.println("  Used letters  : " + formatUsed(used));
        System.out.println();
        System.out.print("  Your guess (letter or word): ");
        awaitingInput = true;
    }

    private void handleState(String msg) {
        String[] parts = msg.split(" ");
        String mask    = parts[1];
        int attempts   = Integer.parseInt(parts[2]);
        String used    = parts[3];

        System.out.println("── Round result ──");
        printMask(mask);
        System.out.println("  Attempts left : " + attempts);
        System.out.println("  Used letters  : " + formatUsed(used));
        System.out.println();
    }

    private void handleEnd(String msg) {
        String[] parts = msg.split(" ");
        String result  = parts[1];

        System.out.println();
        System.out.println("╔══════════════════════════════╗");

        if (result.equals("WIN")) {
            String winners = parts[2];
            String word    = parts[3];
            boolean iWon   = Arrays.asList(winners.split(","))
                                   .contains(String.valueOf(playerId));

            printHangman(0);
            if (iWon) {
                System.out.println("║        YOU WON! :D          ║");
            } else {
                System.out.println("║        YOU LOST :(          ║");
            }
            System.out.println("╚══════════════════════════════╝");
            System.out.println("  Word      : " + word);
            System.out.println("  Winner(s) : Player " + winners.replace(",", ", Player "));
        } else {
            printHangman(MAX_ATTEMPTS);
            System.out.println("║      EVERYONE LOST :(        ║");
            System.out.println("╚══════════════════════════════╝");
            System.out.println("  The word was: " + parts[2]);
        }
        System.out.println();
        gameOver = true;
    }

    private void handleFull() {
        System.out.println("Server is full. Try again later.");
        gameOver = true;
    }

    // ── Display helpers ──────────────────────────────────────────────────
    private void printHangman(int wrong) {
        if (wrong < 0) wrong = 0;
        if (wrong > 6) wrong = 6;
        for (String line : HANGMAN[wrong]) {
            System.out.println("  " + line);
        }
    }

    private void printMask(String mask) {
        StringBuilder sb = new StringBuilder("  Word: ");
        for (int i = 0; i < mask.length(); i++) {
            if (i > 0) sb.append(' ');
            sb.append(mask.charAt(i));
        }
        System.out.println(sb);
    }

    private String formatUsed(String used) {
        return used.equals("-") ? "(none)" : used.replace(",", ", ");
    }
}
