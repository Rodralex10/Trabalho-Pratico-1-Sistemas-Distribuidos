package server;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class HangmanServer {

    // ── Constants ────────────────────────────────────────────────────────
    private static final int DEFAULT_PORT       = 12345;
    private static final int MIN_PLAYERS        = 2;
    private static final int MAX_PLAYERS        = 4;
    private static final int MAX_ATTEMPTS       = 6;
    private static final int LOBBY_TIMEOUT_SEC  = 20;
    private static final long ROUND_TIMEOUT_MS  = 30_000;

    private static final String[] WORDS = {
        "abacate",     "ananás",      "academia",    "alimento",    "amizade",
        "animal",      "arquivo",     "aventura",    "sanita",      "basquetebol",
        "batata",      "biologia",    "borboleta",   "borracha",    "caderno",
        "campeonato",  "caneta",      "castelo",     "cavaleiro",   "celula",
        "cenoura",     "cerebro",     "cidade",      "ciencia",     "cobertor",
        "computador",  "coragem",     "corrida",     "cortina",     "costela",
        "cotovelo",    "cozinha",     "digital",     "diploma",     "dragao",
        "empresa",     "energia",     "escada",      "escola",      "escudo",
        "espada",      "espaco",      "estomago",    "estrada",     "estrela",
        "estudante",   "exercicio",   "floresta",    "futebol",     "galaxia",
        "galinha",     "frigorifico", "ginasio",     "ferreiro",    "governo",
        "hospital",    "internet",    "jardim",      "joelho",      "laranja",
        "legumes",     "medalha",     "memoria",     "mercado",     "mochila",
        "monitor",     "montanha",    "museu",       "natacao",     "natureza",
        "oceano",      "ombro",       "padaria",     "passaro",     "peixe",
        "planeta",     "ponte",       "predio",      "princesa",    "professor",
        "programa",    "prova",       "pulmao",      "quarto",      "quimica",
        "servidor",    "sistema",     "teclado",     "telhado",     "tenis",
        "tesouro",     "tomate",      "tornozelo",   "trabalho",    "almofada",
        "varanda",     "voleibol",    "braco",       "cabeca",      "protocolo"
    };

    // ── Shared state ─────────────────────────────────────────────────────
    private final Object lock = new Object();
    private volatile boolean gameStarted      = false;
    private volatile boolean gameOver         = false;
    private volatile boolean acceptingPlayers = true;

    // Protected by synchronized(lock)
    private String         word;
    private char[]         mask;
    private int            attemptsLeft;
    private final Set<Character>  usedLetters = new LinkedHashSet<>();
    private final List<Integer>   winnerIds   = new ArrayList<>();

    // Per-round guess collection
    private final ConcurrentHashMap<Integer, String> roundGuesses = new ConcurrentHashMap<>();

    // Player management
    private final List<PlayerHandler> players = new ArrayList<>();
    private int nextPlayerId = 1;
    private final int port;
    private ServerSocket serverSocket;

    // ── PlayerHandler ────────────────────────────────────────────────────
    private class PlayerHandler extends Thread {
        final int id;
        final Socket socket;
        final BufferedReader in;
        final PrintWriter out;
        volatile boolean connected = true;

        PlayerHandler(Socket socket, int id) throws IOException {
            this.socket = socket;
            this.id     = id;
            this.in     = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            this.out    = new PrintWriter(socket.getOutputStream(), true);
            setDaemon(true);
            setName("Player-" + id);
        }

        void send(String msg) {
            out.println(msg);
        }

        @Override
        public void run() {
            try {
                String line;
                while ((line = in.readLine()) != null && !gameOver) {
                    if (line.startsWith("GUESS ")) {
                        String guess = line.substring(6).trim();
                        if (!guess.isEmpty()) {
                            roundGuesses.put(id, guess);
                            synchronized (lock) { lock.notifyAll(); }
                        }
                    }
                }
            } catch (IOException ignored) {
            } finally {
                connected = false;
                synchronized (lock) { lock.notifyAll(); }
                log("Player " + id + " disconnected.");
            }
        }
    }

    // ── Constructor & main ───────────────────────────────────────────────
    public HangmanServer(int port) {
        this.port = port;
    }

    public static void main(String[] args) {
        int port = DEFAULT_PORT;
        if (args.length > 0) {
            try { port = Integer.parseInt(args[0]); }
            catch (NumberFormatException e) {
                System.err.println("Invalid port. Using default: " + DEFAULT_PORT);
            }
        }
        try {
            new HangmanServer(port).start();
        } catch (IOException e) {
            System.err.println("Server error: " + e.getMessage());
        }
    }

    // ── Server lifecycle ─────────────────────────────────────────────────
    public void start() throws IOException {
        serverSocket = new ServerSocket(port);
        log("Listening on port " + port);

        Thread acceptThread = new Thread(this::acceptLoop, "AcceptThread");
        acceptThread.setDaemon(true);
        acceptThread.start();

        lobby();

        if (connectedCount() < MIN_PLAYERS) {
            log("Not enough players (" + connectedCount() + "/" + MIN_PLAYERS + "). Shutting down.");
            shutdown();
            return;
        }

        acceptingPlayers = false;
        gameStarted = true;
        gameLoop();
        shutdown();
    }

    // ── Accept loop (runs on daemon thread) ──────────────────────────────
    private void acceptLoop() {
        try {
            while (!gameOver) {
                Socket socket = serverSocket.accept();
                synchronized (lock) {
                    if (!acceptingPlayers || players.size() >= MAX_PLAYERS) {
                        try (PrintWriter pw = new PrintWriter(socket.getOutputStream(), true)) {
                            pw.println("FULL");
                        }
                        socket.close();
                        continue;
                    }
                    PlayerHandler handler = new PlayerHandler(socket, nextPlayerId++);
                    players.add(handler);
                    handler.start();
                    handler.send("WELCOME " + handler.id + " " + players.size());
                    log("Player " + handler.id + " joined (" + players.size() + "/" + MAX_PLAYERS + ")");

                    if (players.size() >= MAX_PLAYERS) {
                        acceptingPlayers = false;
                    }
                    lock.notifyAll();
                }
            }
        } catch (IOException e) {
            if (!gameOver) log("Accept error: " + e.getMessage());
        }
    }

    // ── Lobby ────────────────────────────────────────────────────────────
    private void lobby() {
        long deadline = System.currentTimeMillis() + LOBBY_TIMEOUT_SEC * 1000L;
        log("Lobby open (" + LOBBY_TIMEOUT_SEC + "s). Waiting for "
            + MIN_PLAYERS + "-" + MAX_PLAYERS + " players...");

        synchronized (lock) {
            while (System.currentTimeMillis() < deadline && players.size() < MAX_PLAYERS) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) break;
                try { lock.wait(remaining); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
        }
        acceptingPlayers = false;
        log("Lobby closed. " + connectedCount() + " player(s) ready.");
    }

    // ── Game loop ────────────────────────────────────────────────────────
    private void gameLoop() {
        word = WORDS[new Random().nextInt(WORDS.length)];
        mask = new char[word.length()];
        Arrays.fill(mask, '_');
        attemptsLeft = MAX_ATTEMPTS;
        usedLetters.clear();
        winnerIds.clear();

        log("Game started! Word: " + word + " (" + word.length() + " letters)");
        broadcast("START " + new String(mask) + " " + attemptsLeft + " " + ROUND_TIMEOUT_MS);

        int round = 1;
        while (!gameOver) {
            if (connectedCount() == 0) {
                log("All players disconnected.");
                gameOver = true;
                break;
            }

            roundGuesses.clear();
            String roundMsg = "ROUND " + round + " " + new String(mask) + " "
                            + attemptsLeft + " " + formatUsedLetters();
            broadcast(roundMsg);
            log("Round " + round + " — waiting for guesses...");

            // Wait for all connected players to guess (or timeout)
            long deadline = System.currentTimeMillis() + ROUND_TIMEOUT_MS;
            synchronized (lock) {
                while (roundGuesses.size() < connectedCount()
                       && System.currentTimeMillis() < deadline
                       && !gameOver) {
                    long remaining = deadline - System.currentTimeMillis();
                    if (remaining <= 0) break;
                    try { lock.wait(Math.min(remaining, 500)); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                }
            }

            // Process guesses atomically
            synchronized (lock) {
                boolean anyWrong = false;
                for (PlayerHandler p : players) {
                    if (!p.connected) continue;
                    String guess = roundGuesses.get(p.id);
                    log("Player " + p.id + ": " + (guess != null ? "\"" + guess + "\"" : "(timeout)"));
                    if (processGuess(p.id, guess)) anyWrong = true;
                }
                if (anyWrong) attemptsLeft--;  // at most one attempt lost per round

                // Mask fully revealed through letters → all connected players win
                if (new String(mask).equals(word)) {
                    for (PlayerHandler p : players) {
                        if (p.connected && !winnerIds.contains(p.id)) {
                            winnerIds.add(p.id);
                        }
                    }
                }

                if (!winnerIds.isEmpty()) {
                    gameOver = true;
                } else if (attemptsLeft <= 0) {
                    gameOver = true;
                }
            }

            if (gameOver) {
                if (!winnerIds.isEmpty()) {
                    String winners = joinInts(winnerIds);
                    broadcast("END WIN " + winners + " " + word);
                    log("WIN! Winners: " + winners + " | Word: " + word);
                } else {
                    broadcast("END LOSE " + word);
                    log("LOSE! Word was: " + word);
                }
            } else {
                broadcast("STATE " + new String(mask) + " " + attemptsLeft + " " + formatUsedLetters());
                round++;
            }
        }
    }

    // ── Guess processing ─────────────────────────────────────────────────
    // Returns true if the guess was wrong (so the caller can track whether
    // to decrement attempts once per round, not once per player).
    private boolean processGuess(int playerId, String guess) {
        if (guess == null || guess.isEmpty()) {
            return true;
        }
        guess = guess.toLowerCase();

        if (guess.length() == 1) {
            char c = guess.charAt(0);
            if (usedLetters.contains(c)) return false;   // already used → skip, no penalty
            usedLetters.add(c);

            boolean found = false;
            for (int i = 0; i < word.length(); i++) {
                if (word.charAt(i) == c) {
                    mask[i] = c;
                    found = true;
                }
            }
            return !found;
        } else {
            // Full word guess
            if (guess.equals(word)) {
                winnerIds.add(playerId);
                return false;
            } else {
                return true;
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────
    private void broadcast(String msg) {
        for (PlayerHandler p : players) {
            if (p.connected) p.send(msg);
        }
    }

    private int connectedCount() {
        int n = 0;
        for (PlayerHandler p : players) {
            if (p.connected) n++;
        }
        return n;
    }

    private String formatUsedLetters() {
        if (usedLetters.isEmpty()) return "-";
        StringBuilder sb = new StringBuilder();
        for (char c : usedLetters) {
            if (sb.length() > 0) sb.append(",");
            sb.append(c);
        }
        return sb.toString();
    }

    private static String joinInts(List<Integer> ids) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(ids.get(i));
        }
        return sb.toString();
    }

    private void shutdown() {
        gameOver = true;
        try { if (serverSocket != null && !serverSocket.isClosed()) serverSocket.close(); }
        catch (IOException ignored) {}
        for (PlayerHandler p : players) {
            try { p.socket.close(); } catch (IOException ignored) {}
        }
        log("Server shut down.");
    }

    private static void log(String msg) {
        System.out.println("[Server] " + msg);
    }
}
