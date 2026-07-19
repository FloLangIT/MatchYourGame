package de.flolang.matchyourgame.database.lobby;

import de.flolang.matchyourgame.database.Database;
import de.flolang.matchyourgame.database.profile.CommunicationLanguage;
import de.flolang.matchyourgame.database.profile.CommunicationLanguageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public final class LobbyLanguageRepository {
    private static final Logger LOGGER = LoggerFactory.getLogger(LobbyLanguageRepository.class);
    private LobbyLanguageRepository() {}

    public static void set(int lobbyId, Collection<String> languages) {
        try (Connection conn = Database.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement delete = conn.prepareStatement("DELETE FROM lobby_communication_language WHERE lobby_id=?");
                 PreparedStatement insert = conn.prepareStatement(
                         "INSERT INTO lobby_communication_language (lobby_id,language_code) VALUES (?,?)")) {
                delete.setInt(1, lobbyId); delete.executeUpdate();
                for (String language : languages) {
                    insert.setInt(1, lobbyId); insert.setString(2, language.toUpperCase()); insert.addBatch();
                }
                insert.executeBatch(); conn.commit();
            } catch (SQLException e) { conn.rollback(); throw e; }
            finally { conn.setAutoCommit(true); }
        } catch (SQLException e) { LOGGER.error("Could not save lobby languages", e); }
    }

    public static List<String> get(int lobbyId) {
        List<String> result = new ArrayList<>();
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT language_code FROM lobby_communication_language WHERE lobby_id=? ORDER BY language_code")) {
            ps.setInt(1, lobbyId); try (ResultSet rs = ps.executeQuery()) { while (rs.next()) result.add(rs.getString(1)); }
        } catch (SQLException e) { LOGGER.error("Could not load lobby languages", e); }
        return result;
    }

    public static List<String> intersectionForUsers(Collection<String> current, Collection<Integer> userIds) {
        List<String> intersection = new ArrayList<>(current);
        for (int userId : userIds) {
            List<String> userLanguages = CommunicationLanguageRepository.getForUser(userId).stream()
                    .map(CommunicationLanguage::code).toList();
            intersection.removeIf(language -> userLanguages.stream().noneMatch(language::equalsIgnoreCase));
        }
        return intersection;
    }

    public static int bestPriority(int userId, Collection<String> lobbyLanguages) {
        return CommunicationLanguageRepository.getForUser(userId).stream()
                .filter(language -> lobbyLanguages.stream().anyMatch(language.code()::equalsIgnoreCase))
                .mapToInt(CommunicationLanguage::priority).min().orElse(Integer.MAX_VALUE);
    }
}
