package de.flolang.matchyourgame.manager.gameapi;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import de.flolang.matchyourgame.Main;
import de.flolang.matchyourgame.config.ConfigManager;
import de.flolang.matchyourgame.database.user.UserController;
import de.flolang.matchyourgame.database.user.UserObject;
import de.flolang.matchyourgame.gameapi.GameApiException;
import de.flolang.matchyourgame.language.LanguageManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;

public final class GameApiOAuthServer {
    private static final Logger LOGGER = LoggerFactory.getLogger(GameApiOAuthServer.class);
    private final GameApiService service;
    private HttpServer server;

    public GameApiOAuthServer(GameApiService service) {
        this.service = service;
    }

    public boolean start() {
        String redirectUri = ConfigManager.getString("GameAPIs.OAuth.RedirectUri", "");
        int port = ConfigManager.getInt("GameAPIs.OAuth.CallbackPort", 0);
        if (redirectUri.isBlank() || port <= 0) {
            LOGGER.info("Game API OAuth callback is disabled (RedirectUri/CallbackPort not configured)");
            return false;
        }
        try {
            URI redirect = URI.create(redirectUri);
            String path = redirect.getPath() == null || redirect.getPath().isBlank()
                    ? "/oauth/game-api" : redirect.getPath();
            String bindAddress = ConfigManager.getString("GameAPIs.OAuth.BindAddress", "127.0.0.1");
            server = HttpServer.create(new InetSocketAddress(bindAddress, port), 0);
            server.createContext(path, this::handleCallback);
            server.setExecutor(Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "game-api-oauth-callback");
                thread.setDaemon(true);
                return thread;
            }));
            server.start();
            LOGGER.info("Game API OAuth callback listening on {}:{}{}", bindAddress, port, path);
            return true;
        } catch (IOException | IllegalArgumentException exception) {
            LOGGER.error("Could not start Game API OAuth callback", exception);
            return false;
        }
    }

    private void handleCallback(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            send(exchange, 405, page("Login nicht möglich", "Diese Anfrage wird nicht unterstützt."));
            return;
        }
        Map<String, String> query = query(exchange.getRequestURI().getRawQuery());
        try {
            if (query.containsKey("error"))
                throw new GameApiException("Der Login wurde abgebrochen oder von Riot abgelehnt.");
            GameApiService.CompletedAccountLogin result = service.completeAccountLogin(
                    query.get("state"), query.get("code"));
            notifyUser(result);
            send(exchange, 200, page("Account erfolgreich verknüpft",
                    escape(result.accountName()) + " wurde mit deinem MatchYourGame-Spielprofil verknüpft. "
                            + "Du kannst dieses Fenster jetzt schließen."));
        } catch (GameApiException exception) {
            send(exchange, 400, page("Account konnte nicht verknüpft werden", escape(exception.getMessage())));
        } catch (RuntimeException exception) {
            LOGGER.error("Unexpected OAuth callback error", exception);
            send(exchange, 500, page("Interner Fehler",
                    "Der Account konnte nicht verknüpft werden. Bitte starte den Login im Bot erneut."));
        }
    }

    private static void notifyUser(GameApiService.CompletedAccountLogin result) {
        UserObject user = UserController.get(result.userId());
        if (user == null || Main.jda == null) return;
        String rank = result.rankValue() == null ? "-" : String.valueOf(result.rankValue());
        String key = result.rankSyncError() == null
                ? "GameProfile.API.Linked" : "GameProfile.API.LinkedWithoutGameData";
        String message = LanguageManager.getMessageForUser(key, user.getId(), Map.of(
                "%provider%", result.providerName(), "%account%", result.accountName(), "%rank%", rank,
                "%error%", result.rankSyncError() == null ? "" : result.rankSyncError()));
        Main.jda.retrieveUserById(user.getDiscordID()).queue(discordUser ->
                discordUser.openPrivateChannel().queue(channel -> channel.sendMessage(message).queue(),
                        ignored -> {}), ignored -> {});
    }

    private static Map<String, String> query(String rawQuery) {
        Map<String, String> result = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) return result;
        for (String pair : rawQuery.split("&")) {
            String[] parts = pair.split("=", 2);
            String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            String value = parts.length == 2 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
            result.put(key, value);
        }
        return result;
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }

    private static String page(String title, String message) {
        return "<!doctype html><html lang=\"de\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>" + escape(title) + "</title></head>"
                + "<body style=\"font-family:system-ui;max-width:640px;margin:10vh auto;padding:24px;"
                + "background:#111827;color:#f9fafb\"><h1>" + escape(title) + "</h1><p>"
                + message + "</p></body></html>";
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }
}
